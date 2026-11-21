package com.aid.runtime.assistant.replan;

import cn.hutool.crypto.SecureUtil;
import com.aid.assistant.domain.AidAssistantSession;
import com.aid.assistant.mapper.AidAssistantSessionMapper;
import com.aid.common.exception.ServiceException;
import com.aid.runtime.domain.*;
import com.aid.runtime.mapper.*;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.Date;
import java.util.Objects;
import java.util.Set;

/** Exact persisted ticket; uses the existing run lease, never creates a parallel lease. */
@Service
public class RuntimeReplanCancellationAuthorizationService implements RuntimeReplanCancellationAuthorizationPort {
    @Resource private AidAssistantSessionMapper sessions;
    @Resource private AidRuntimeRunMapper runs;
    @Resource private AidRuntimeReplanIntentMapper intents;
    @Resource private AidRuntimeCommandMapper commands;

    @Override
    @Transactional(propagation=Propagation.MANDATORY,rollbackFor=Exception.class)
    public ReplanCancellationIdentity lockAndVerify(ReplanCancellationAuthorizationRequest request) {
        if (request == null || request.runId() == null || request.sessionId() == null || request.userId() == null
                || request.intentId() == null || request.commandId() == null || request.intentStateRevision() == null
                || request.expectedRunFencingToken() == null || request.leaseOwner() == null) invalid();
        AidAssistantSession session=sessions.selectOne(Wrappers.<AidAssistantSession>lambdaQuery()
                .eq(AidAssistantSession::getId,request.sessionId()).eq(AidAssistantSession::getDelFlag,"0").last("FOR UPDATE"));
        AidRuntimeRun run=runs.selectOne(Wrappers.<AidRuntimeRun>lambdaQuery()
                .eq(AidRuntimeRun::getId,request.runId()).eq(AidRuntimeRun::getDelFlag,"0").last("FOR UPDATE"));
        AidRuntimeReplanIntent intent=intents.selectActiveByRunIdForUpdate(request.runId());
        AidRuntimeCommand command=commands.selectById(request.commandId());
        if (session==null || run==null || intent==null || command==null
                || !"ASSISTANT".equals(session.getSessionType()) || !Objects.equals(session.getUserId(),request.userId())
                || !Objects.equals(session.getActiveRuntimeRunId(),run.getId()) || !Objects.equals(run.getSessionId(),session.getId())
                || !Objects.equals(run.getUserId(),request.userId()) || Boolean.TRUE.equals(run.getStopRequested())
                || !"PAUSING".equals(run.getStatus()) || !"USER_REQUEST".equals(run.getPauseReason())
                || !java.util.Arrays.asList("CANCEL_FOR_REPLAN","WAIT_REPLAN_RECONCILIATION").contains(run.getNextAction())
                || !Objects.equals(run.getLeaseOwner(),request.leaseOwner())
                || !Objects.equals(run.getFencingToken(),request.expectedRunFencingToken())
                || run.getLeaseUntil()==null || !run.getLeaseUntil().after(new Date())
                || !Objects.equals(intent.getId(),request.intentId()) || !Objects.equals(intent.getSessionId(),session.getId())
                || !Objects.equals(intent.getActorId(),request.userId()) || !Objects.equals(intent.getCommandId(),command.getId())
                || !Objects.equals(intent.getStateRevision(),request.intentStateRevision())
                || !Set.of("CANCEL_REQUESTED","RECONCILING").contains(intent.getStatus())
                || !Objects.equals(intent.getBaseContextDigest(),run.getContextDigest())
                || !Objects.equals(intent.getBasePlanRevision(),run.getPlanRevision())
                || !Objects.equals(intent.getBaseRequestDigest(),SecureUtil.sha256(run.getRequestJson()))
                || intent.getPendingPayloadJson()==null || !Objects.equals(intent.getPendingPayloadDigest(),SecureUtil.sha256(intent.getPendingPayloadJson()))
                || !Objects.equals(command.getActorId(),request.userId()) || !Objects.equals(command.getSessionId(),session.getId())
                || !Objects.equals(command.getClientRequestId(),intent.getClientRequestId())
                || !Objects.equals(command.getPayloadDigest(),intent.getCommandPayloadDigest())) invalid();
        return new ReplanCancellationIdentity(run.getId(),session.getId(),intent.getId(),intent.getStateRevision(),command.getId(),run.getContextDigest());
    }
    private void invalid(){throw new ServiceException("REPLAN_CANCELLATION_TICKET_STALE");}
}
