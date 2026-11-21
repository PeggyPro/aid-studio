package com.aid.assistant.service;

import cn.hutool.crypto.SecureUtil;
import com.aid.assistant.domain.AidAssistantMessage;
import com.aid.assistant.domain.AidAssistantSession;
import com.aid.assistant.mapper.AidAssistantMessageMapper;
import com.aid.context.ConversationContextMemoryStore;
import com.aid.runtime.domain.AidRuntimeInputRequest;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.mapper.AidRuntimeInputRequestMapper;
import com.aid.common.exception.ServiceException;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Set;

/** Freezes source-backed requirements BEFORE operation approval, for every method Skill. */
@Service
@RequiredArgsConstructor
public class AssistantConversationMemoryContext {
    @org.springframework.beans.factory.annotation.Value("${conversation.context.paid-preparation-enabled:false}")
    private boolean paidPreparationEnabled;
    private final ConversationContextMemoryStore memory;
    private final AidAssistantMessageMapper messages;
    private final AidRuntimeInputRequestMapper inputs;
    @jakarta.annotation.Resource private com.aid.context.ConversationMemoryPreparationService preparedContexts;
    @jakarta.annotation.Resource private com.aid.runtime.mapper.AidRuntimeAgentToolRefMapper preparationToolRefs;
    @jakarta.annotation.Resource private AssistantParentObservationContext parentObservations;

    public JSONObject freeze(AidAssistantSession session, AidRuntimeRun run) {
        if (session == null || run == null || run.getUserId() == null
                || !Objects.equals(session.getUserId(), run.getUserId())
                || !Objects.equals(session.getId(), run.getSessionId())
                || !Objects.equals(session.getActiveRuntimeRunId(), run.getId())
                || !"0".equals(session.getDelFlag()) || !"0".equals(run.getDelFlag()))
            throw new ServiceException("CONVERSATION_MEMORY_SCOPE_INVALID");
        AidAssistantMessage turn = messages.selectOne(new LambdaQueryWrapper<AidAssistantMessage>()
                .eq(AidAssistantMessage::getId, run.getTurnId())
                .eq(AidAssistantMessage::getSessionId, session.getId())
                .eq(AidAssistantMessage::getRuntimeRunId, run.getId())
                .eq(AidAssistantMessage::getRole, "USER")
                .eq(AidAssistantMessage::getStatus, "PERSISTED")
                .eq(AidAssistantMessage::getDelFlag, "0").last("LIMIT 1"));
        if (turn == null || turn.getSeqNo() == null)
            throw new ServiceException("CONVERSATION_MEMORY_NOT_READY");
        var scope = new ConversationContextMemoryStore.Key(run.getUserId(), "ASSISTANT_SESSION", String.valueOf(session.getId()));
        JSONObject prepared = preparationEnabled(run) ? preparedContexts.loadReadyContext(scope, turn.getSeqNo()) : null;
        JSONArray historical = new JSONArray();
        if (prepared == null) {
            var stored = memory.load(scope);
            if (stored == null) throw new ServiceException("CONVERSATION_MEMORY_NOT_READY");
            JSONObject document = JSON.parseObject(stored.memoryJson());
            if (!Objects.equals(document.getLong("sourceRevision"), turn.getSeqNo())
                    || !(document.get("keySources") instanceof JSONArray keySources))
                throw new ServiceException("CONVERSATION_MEMORY_SOURCE_CHANGED");
            for (Object raw : keySources) {
                if (!(raw instanceof JSONObject source) || source.getString("text") == null
                        || !Set.of("USER", "CONFIRMED_INPUT").contains(source.getString("kind"))
                        || !Objects.equals(SecureUtil.sha256(source.getString("text")), source.getString("contentHash")))
                    throw new ServiceException("CONVERSATION_MEMORY_SOURCE_INVALID");
                if (!source.getString("text").isBlank()) historical.add(source.getString("text"));
            }
        } else com.aid.context.PreparedConversationContextContract.require(prepared);
        JSONArray accepted = new JSONArray();
        var rounds = inputs.selectList(new LambdaQueryWrapper<AidRuntimeInputRequest>()
                .eq(AidRuntimeInputRequest::getRunId, run.getId())
                .eq(AidRuntimeInputRequest::getStatus, "SUBMITTED")
                .eq(AidRuntimeInputRequest::getContextDigest, run.getContextDigest())
                .apply("del_flag = {0}", "0")
                .orderByAsc(AidRuntimeInputRequest::getRoundNo).last("LIMIT 51"));
        if (rounds.size() > 50) throw new ServiceException("CONVERSATION_MEMORY_INPUT_LIMIT");
        for (AidRuntimeInputRequest round : rounds) {
            if (round.getQuestionBundleJson() == null || round.getAcceptedAnswersJson() == null
                    || !Objects.equals(SecureUtil.sha256(round.getQuestionBundleJson()), round.getSchemaDigest())
                    || !Objects.equals(SecureUtil.sha256(round.getAcceptedAnswersJson() + ":"
                        + round.getSchemaDigest() + ":" + round.getContextDigest()), round.getAcceptedDigest()))
                throw new ServiceException("CONVERSATION_MEMORY_INPUT_CHANGED");
            JSONObject exact = new JSONObject();
            exact.put("sourceInputRequestId", String.valueOf(round.getId()));
            exact.put("questions", JSON.parse(round.getQuestionBundleJson()));
            exact.put("acceptedAnswers", JSON.parse(round.getAcceptedAnswersJson()));
            accepted.add(exact.toJSONString());
        }
        JSONObject result = new JSONObject();
        result.put("historicalRequests", historical);
        result.put("acceptedInputs", accepted);
        if (prepared != null) result.put("preparedContext", prepared);
        JSONObject observations = parentObservations.freeze(run);
        if (observations != null) result.put("parentObservations", observations);
        return result;
    }
    private boolean preparationEnabled(AidRuntimeRun run) {
        if (!paidPreparationEnabled || run.getVersionId() == null || preparationToolRefs == null || preparedContexts == null) return false;
        var descriptor = com.aid.runtime.tool.ContextPreparationRuntimeToolProvider.descriptor();
        return preparationToolRefs.selectCount(new LambdaQueryWrapper<com.aid.runtime.domain.AidRuntimeAgentToolRef>()
                .eq(com.aid.runtime.domain.AidRuntimeAgentToolRef::getAgentVersionId, run.getVersionId())
                .eq(com.aid.runtime.domain.AidRuntimeAgentToolRef::getToolCode, descriptor.code())
                .eq(com.aid.runtime.domain.AidRuntimeAgentToolRef::getContractVersion, descriptor.contractVersion())
                .eq(com.aid.runtime.domain.AidRuntimeAgentToolRef::getSchemaDigest, descriptor.schemaDigest())) > 0;
    }

}
