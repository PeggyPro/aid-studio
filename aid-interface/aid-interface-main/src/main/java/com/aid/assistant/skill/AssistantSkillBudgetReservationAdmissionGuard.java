package com.aid.assistant.skill;

import com.aid.assistant.domain.AidAssistantSession;
import com.aid.assistant.mapper.AidAssistantSessionMapper;
import com.aid.common.exception.ServiceException;
import com.aid.runtime.domain.AidRuntimeApproval;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.domain.AidRuntimeRunStep;
import com.aid.runtime.mapper.AidRuntimeApprovalMapper;
import com.aid.runtime.mapper.AidRuntimeRunMapper;
import com.aid.runtime.mapper.AidRuntimeRunStepMapper;
import com.aid.skill.domain.AidSkillRun;
import com.aid.skill.domain.AidSkillRunStep;
import com.aid.skill.executor.SkillExecutionContext;
import com.aid.skill.mapper.AidSkillRunMapper;
import com.aid.skill.mapper.AidSkillRunStepMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.Objects;
import java.util.Set;

/** Linearizes an inner Skill reservation against STOP/PAUSE/REPLAN of its outer Assistant run. */
@Component
@RequiredArgsConstructor
public class AssistantSkillBudgetReservationAdmissionGuard {
    private static final String NORMAL = "0";
    private final AidAssistantSessionMapper sessions;
    private final AidRuntimeRunMapper runtimeRuns;
    private final AidRuntimeApprovalMapper approvals;
    private final AidRuntimeRunStepMapper runtimeSteps;
    private final AidSkillRunMapper skillRuns;
    private final AidSkillRunStepMapper skillSteps;

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void assertMayReserve(SkillExecutionContext execution) {
        if (execution == null || execution.getRun() == null || execution.getStep() == null) {
            throw new ServiceException("Skill reservation admission identity is invalid");
        }
        AssistantSkillBudgetEnvelope envelope = execution.getAssistantBudgetEnvelope();
        AssistantSkillBudgetIdentity.validate(envelope);
        AidRuntimeRun discovered = runtimeRuns.selectById(envelope.getAssistantRuntimeRunId());
        if (discovered == null || discovered.getSessionId() == null) {
            throw new ServiceException("Assistant runtime is unavailable");
        }
        AidAssistantSession session = sessions.selectOne(Wrappers.<AidAssistantSession>lambdaQuery()
                .eq(AidAssistantSession::getId, discovered.getSessionId())
                .eq(AidAssistantSession::getDelFlag, NORMAL).last("FOR UPDATE"));
        AidRuntimeRun runtime = runtimeRuns.selectOne(Wrappers.<AidRuntimeRun>lambdaQuery()
                .eq(AidRuntimeRun::getId, envelope.getAssistantRuntimeRunId())
                .eq(AidRuntimeRun::getDelFlag, NORMAL).last("FOR UPDATE"));
        AidRuntimeApproval approval = approvals.selectOne(Wrappers.<AidRuntimeApproval>lambdaQuery()
                .eq(AidRuntimeApproval::getId, envelope.getApprovalId())
                .eq(AidRuntimeApproval::getRunId, envelope.getAssistantRuntimeRunId())
                .eq(AidRuntimeApproval::getDelFlag, NORMAL).last("FOR UPDATE"));
        AidRuntimeRunStep outerStep = runtimeSteps.selectOne(Wrappers.<AidRuntimeRunStep>lambdaQuery()
                .eq(AidRuntimeRunStep::getId, envelope.getAssistantParentStepId())
                .eq(AidRuntimeRunStep::getRunId, envelope.getAssistantRuntimeRunId())
                .eq(AidRuntimeRunStep::getDelFlag, NORMAL).last("FOR UPDATE"));
        AidSkillRun skillRun = skillRuns.selectOne(Wrappers.<AidSkillRun>lambdaQuery()
                .eq(AidSkillRun::getId, execution.getRun().getId())
                .eq(AidSkillRun::getDelFlag, NORMAL).last("FOR UPDATE"));
        AidSkillRunStep skillStep = skillSteps.selectOne(Wrappers.<AidSkillRunStep>lambdaQuery()
                .eq(AidSkillRunStep::getId, execution.getStep().getId())
                .eq(AidSkillRunStep::getRunId, execution.getRun().getId())
                .eq(AidSkillRunStep::getDelFlag, NORMAL).last("FOR UPDATE"));
        Date now = new Date();
        if (session == null || runtime == null || approval == null || outerStep == null
                || skillRun == null || skillStep == null
                || !Objects.equals(session.getActiveRuntimeRunId(), runtime.getId())
                || !Objects.equals(session.getUserId(), runtime.getUserId())
                || Boolean.TRUE.equals(runtime.getStopRequested())
                || Set.of("PAUSING", "PAUSED", "RECONCILING", "STOPPED").contains(runtime.getStatus())
                || runtime.getLeaseOwner() == null || runtime.getLeaseOwner().isBlank()
                || runtime.getLeaseUntil() == null || !runtime.getLeaseUntil().after(now)
                || !Objects.equals(runtime.getFencingToken(), envelope.getAssistantRuntimeRunFencingToken())
                || !Objects.equals(outerStep.getFencingToken(), envelope.getAssistantParentStepFencingToken())
                || !Objects.equals(runtime.getBudgetAuthorizationId(), envelope.getBillingAuthorizationId())
                || !Objects.equals(approval.getBillingAuthorizationId(), envelope.getBillingAuthorizationId())
                || !Objects.equals(approval.getBillingAuthorizationRevision(),
                envelope.getBillingAuthorizationRevision())
                || !"ACCEPTED".equals(approval.getStatus())
                || !Objects.equals(outerStep.getApprovalId(), approval.getId())
                || !Objects.equals(outerStep.getChildRunId(), skillRun.getId())
                || !Set.of("SUBMITTING", "INFLIGHT", "RUNNING").contains(outerStep.getStatus())
                || !"ACCEPTED".equals(outerStep.getProviderAcceptanceState())
                || !Objects.equals(skillRun.getUserId(), envelope.getPayerUserId())
                || !"RUNNING".equals(skillRun.getStatus())
                || !"PLANNED".equals(skillStep.getOrchestrationStatus())
                || !Objects.equals(skillStep.getWorkflowAttempt(), execution.getStep().getWorkflowAttempt())) {
            throw new ServiceException("Skill reservation admission state has changed");
        }
    }
}
