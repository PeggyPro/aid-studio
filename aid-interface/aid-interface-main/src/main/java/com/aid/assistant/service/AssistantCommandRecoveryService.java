package com.aid.assistant.service;

import com.aid.assistant.domain.AidAssistantSession;
import com.aid.assistant.dto.AssistantCommandRecoveryRequests;
import com.aid.assistant.error.AssistantServiceException;
import com.aid.assistant.mapper.AidAssistantSessionMapper;
import com.aid.assistant.vo.AssistantCommandRecoveryViews;
import com.aid.runtime.assistant.correction.AssistantDecisionCorrectionCommandNamespace;
import com.aid.runtime.domain.AidRuntimeCommand;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.mapper.AidRuntimeCommandMapper;
import com.aid.runtime.mapper.AidRuntimeRunMapper;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** A consistent read of command receipts and session revisions, independent of browser storage. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class AssistantCommandRecoveryService
{
    private static final Set<String> PUBLIC_OUTCOME_FIELDS = Set.of(
            "sessionId", "sessionRevision", "runId", "runRevision", "messageId", "messageSeq",
            "requestId", "requestRevision", "draftRevision", "approvalId", "approvalRevision",
            "planRevision", "stepId", "reservationId", "authorizationId", "lastEventSeq");
    private final AidAssistantSessionMapper sessions;
    private final AidRuntimeCommandMapper commands;
    private final AidRuntimeRunMapper runs;

    public AssistantCommandRecoveryViews.Page page(Long actorId, AssistantCommandRecoveryRequests.Page request)
    {
        if (request == null || request.getLimit() == null || request.getLimit() < 1
                || request.getLimit() > 50)
            throw invalid();
        AidAssistantSession session = requireSession(actorId, request.getSessionId());
        Long after = cursor(request.getAfterCommandId());
        Long through = cursor(request.getThroughCommandId());
        if (after != null)
        {
            if (through == null || after > through || request.getExpectedSessionRevision() == null
                    || request.getExpectedLastEventSeq() == null) throw invalid();
            if (!Objects.equals(session.getStateRevision(), request.getExpectedSessionRevision())
                    || !Objects.equals(session.getLastEventSeq(), request.getExpectedLastEventSeq()))
                throw new AssistantServiceException(409, "COMMAND_RECOVERY_SNAPSHOT_CHANGED", "会话已变化，请重新同步", null);
        }
        else
        {
            if (through != null || request.getExpectedSessionRevision() != null || request.getExpectedLastEventSeq() != null)
                throw invalid();
            AidRuntimeCommand last = commands.selectOne(Wrappers.<AidRuntimeCommand>query()
                    .eq("actor_id", actorId).eq("session_id", session.getId()).eq("del_flag", "0")
                    .notLikeRight("client_request_id",
                            AssistantDecisionCorrectionCommandNamespace.PREFIX)
                    .notIn("command_type",
                            AssistantDecisionCorrectionCommandNamespace.SLOT_TYPE,
                            AssistantDecisionCorrectionCommandNamespace.BOUND_TYPE)
                    .orderByDesc("id").last("LIMIT 1"));
            through = last == null ? 0L : last.getId();
        }
        int limit = request.getLimit();
        List<AidRuntimeCommand> found = commands.selectList(Wrappers.<AidRuntimeCommand>query()
                .eq("actor_id", actorId).eq("session_id", session.getId()).eq("del_flag", "0")
                .notLikeRight("client_request_id",
                        AssistantDecisionCorrectionCommandNamespace.PREFIX)
                .notIn("command_type",
                        AssistantDecisionCorrectionCommandNamespace.SLOT_TYPE,
                        AssistantDecisionCorrectionCommandNamespace.BOUND_TYPE)
                .gt(after != null, "id", after).le("id", through)
                .orderByAsc("id").last("LIMIT " + (limit + 1)));
        boolean more = found.size() > limit;
        List<AidRuntimeCommand> visible = more ? found.subList(0, limit) : found;
        AssistantCommandRecoveryViews.Page result = new AssistantCommandRecoveryViews.Page();
        result.setScope(scope(session, actorId));
        result.setItems(visible.stream().map(this::receipt).toList());
        result.setHasMore(more);
        result.setNextCommandId(visible.isEmpty() ? null : String.valueOf(visible.get(visible.size() - 1).getId()));
        result.setThroughCommandId(String.valueOf(through));
        return result;
    }

    public AssistantCommandRecoveryViews.Lookup lookup(Long actorId, AssistantCommandRecoveryRequests.Lookup request)
    {
        if (request == null || request.getClientRequestId() == null
                || !request.getClientRequestId().matches("[A-Za-z0-9._:-]{1,64}")) throw invalid();
        AssistantDecisionCorrectionCommandNamespace.requireExternalClientRequestId(
                request.getClientRequestId());
        AidAssistantSession session = requireSession(actorId, request.getSessionId());
        AidRuntimeCommand found = commands.selectOne(Wrappers.<AidRuntimeCommand>query()
                .eq("actor_id", actorId).eq("session_id", session.getId()).eq("del_flag", "0")
                .eq("client_request_id", request.getClientRequestId()));
        AssistantCommandRecoveryViews.Lookup result = new AssistantCommandRecoveryViews.Lookup();
        result.setScope(scope(session, actorId));
        result.setObservation(found == null ? "NOT_OBSERVED" : "OBSERVED");
        result.setRecoveryDirective(found != null && "COMPLETED".equals(found.getStatus())
                ? "REFRESH_AUTHORITATIVE_STATE" : "UNKNOWN_REPLAY_SAME_KEY_ONLY");
        result.setReplacementKeyAllowed(false);
        result.setReceipt(found == null ? null : receipt(found));
        return result;
    }

    private AidAssistantSession requireSession(Long actorId, Long sessionId)
    {
        if (actorId == null || actorId < 1 || sessionId == null || sessionId < 1) throw invalid();
        AidAssistantSession session = sessions.selectById(sessionId);
        if (session == null || !"0".equals(session.getDelFlag())
                || !"ASSISTANT".equals(session.getSessionType()) || !Objects.equals(actorId, session.getUserId()))
            throw new AssistantServiceException(404, "ASSISTANT_SESSION_NOT_FOUND", "会话不存在", null);
        return session;
    }

    private AssistantCommandRecoveryViews.Scope scope(AidAssistantSession session, Long actorId)
    {
        AssistantCommandRecoveryViews.Scope value = new AssistantCommandRecoveryViews.Scope();
        value.setSessionId(String.valueOf(session.getId()));
        value.setSessionRevision(session.getStateRevision());
        value.setLastEventSeq(session.getLastEventSeq());
        if (session.getActiveRuntimeRunId() != null)
        {
            AidRuntimeRun run = runs.selectById(session.getActiveRuntimeRunId());
            if (run == null || !"0".equals(run.getDelFlag()) || !Objects.equals(actorId, run.getUserId())
                    || !Objects.equals(session.getId(), run.getSessionId()))
                throw new AssistantServiceException(409, "SNAPSHOT_REQUIRED", "请刷新状态", null);
            value.setActiveRunId(String.valueOf(run.getId()));
            value.setActiveRunRevision(run.getStateRevision());
        }
        return value;
    }

    private AssistantCommandRecoveryViews.Receipt receipt(AidRuntimeCommand command)
    {
        AssistantCommandRecoveryViews.Receipt result = new AssistantCommandRecoveryViews.Receipt();
        result.setCommandId(String.valueOf(command.getId()));
        result.setClientRequestId(command.getClientRequestId());
        result.setCommandType(command.getCommandType());
        result.setStatus(command.getStatus());
        String digest = command.getPayloadDigest();
        result.setPayloadDigest(digest != null && digest.matches("[0-9a-fA-F]{64}") ? digest : null);
        String errorCode = command.getErrorCode();
        result.setErrorCode(errorCode != null && errorCode.matches("[A-Za-z0-9_.:-]{1,64}") ? errorCode : null);
        Map<String, String> outcome = new LinkedHashMap<>();
        if (command.getResultJson() != null && command.getResultJson().length() <= 2_000_000)
        {
            try
            {
                JSONObject json = JSON.parseObject(command.getResultJson());
                if (json != null) for (String key : PUBLIC_OUTCOME_FIELDS)
                {
                    Object value = json.get(key);
                    if (value != null && (value instanceof String || value instanceof Number)
                            && value.toString().matches("[0-9]{1,20}")) outcome.put(key, value.toString());
                }
            }
            catch (RuntimeException malformed) { /* Metadata remains readable; corrupt payload cannot leak raw data. */ }
        }
        result.setOutcome(outcome);
        return result;
    }

    private AssistantServiceException invalid()
    {
        return new AssistantServiceException(422, "COMMAND_RECOVERY_REQUEST_INVALID", "恢复参数无效", null);
    }

    private Long cursor(String value)
    {
        if (value == null) return null;
        if (!value.matches("[0-9]{1,19}")) throw invalid();
        try { return Long.parseLong(value); }
        catch (NumberFormatException invalidNumber) { throw invalid(); }
    }
}
