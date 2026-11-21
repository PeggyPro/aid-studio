package com.aid.assistant.service;

import com.aid.aid.domain.media.AidMediaTask;
import com.aid.assistant.skill.AssistantSkillBudgetEnvelope;
import com.aid.assistant.skill.AssistantSkillBudgetIdentity;
import com.aid.billing.domain.AidBillingAuthorizationReservation;
import com.aid.billing.model.BillingSnapshot;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.domain.AidRuntimeRunStep;
import com.aid.skill.domain.AidSkillRun;
import com.aid.skill.domain.AidSkillRunStep;
import com.aid.skill.domain.AidSkillRunTaskLink;
import com.aid.skill.mapper.AidSkillRunMapper;
import com.aid.skill.mapper.AidSkillRunStepMapper;
import com.aid.skill.mapper.AidSkillRunTaskLinkMapper;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Identifies linked Skill calls outside a direct-Assistant subtotal; never verifies an amount. */
@Service
public class AssistantLinkedSkillCostScopeService
{
    private static final int MAX_ROWS = 200;
    private static final int MAX_IDENTITY_JSON_CHARS = 131_072;
    @Resource private AidSkillRunMapper runs;
    @Resource private AidSkillRunStepMapper steps;
    @Resource private AidSkillRunTaskLinkMapper links;

    public Set<Long> identify(Long actorId, AidRuntimeRun parent,
                              List<AidRuntimeRunStep> outerSteps,
                              List<AidBillingAuthorizationReservation> reservations,
                              Map<Long, AidMediaTask> tasks)
    {
        bounded(outerSteps.size()); bounded(reservations.size()); bounded(tasks.size());
        if (parent == null || parent.getId() == null || actorId == null
                || !Objects.equals(actorId, parent.getUserId())) return Set.of();
        Set<Long> roots = new HashSet<>();
        for (AidRuntimeRunStep outer : outerSteps)
            if ("SKILL".equals(outer.getStepKind()) && "ASSISTANT_SKILL".equals(outer.getOperationKind())
                    && outer.getChildRunId() != null) roots.add(outer.getChildRunId());
        if (roots.isEmpty() || reservations.stream().noneMatch(row -> "SKILL_TEXT".equals(row.getOperationKind())))
            return Set.of();
        bounded(roots.size());
        List<AidSkillRun> children = runs.selectList(new LambdaQueryWrapper<AidSkillRun>()
                .select(AidSkillRun::getId, AidSkillRun::getUserId, AidSkillRun::getRootRunId,
                        AidSkillRun::getParentRunId, AidSkillRun::getInvokeSource,
                        AidSkillRun::getSkillVersionId, AidSkillRun::getSkillConfigHash,
                        AidSkillRun::getInputJson, AidSkillRun::getDelFlag)
                .eq(AidSkillRun::getUserId, actorId).eq(AidSkillRun::getDelFlag, "0")
                .and(query -> query.in(AidSkillRun::getId, roots).or().in(AidSkillRun::getRootRunId, roots))
                .orderByAsc(AidSkillRun::getId).last("LIMIT 201"));
        bounded(children.size());
        if (children.isEmpty()) return Set.of();
        List<Long> childIds = children.stream().map(AidSkillRun::getId).toList();
        List<AidSkillRunStep> childSteps = steps.selectList(new LambdaQueryWrapper<AidSkillRunStep>()
                .select(AidSkillRunStep::getId, AidSkillRunStep::getRunId, AidSkillRunStep::getStepExecutionId,
                        AidSkillRunStep::getWorkflowAttempt, AidSkillRunStep::getDelFlag)
                .in(AidSkillRunStep::getRunId, childIds).eq(AidSkillRunStep::getDelFlag, "0")
                .orderByAsc(AidSkillRunStep::getId).last("LIMIT 201"));
        List<AidSkillRunTaskLink> childLinks = links.selectList(new LambdaQueryWrapper<AidSkillRunTaskLink>()
                .select(AidSkillRunTaskLink::getId, AidSkillRunTaskLink::getRunId,
                        AidSkillRunTaskLink::getStepId, AidSkillRunTaskLink::getStepExecutionId,
                        AidSkillRunTaskLink::getWorkflowAttempt, AidSkillRunTaskLink::getLogicalCallKey,
                        AidSkillRunTaskLink::getMediaTaskId, AidSkillRunTaskLink::getDelFlag)
                .in(AidSkillRunTaskLink::getRunId, childIds).eq(AidSkillRunTaskLink::getDelFlag, "0")
                .orderByAsc(AidSkillRunTaskLink::getId).last("LIMIT 201"));
        return classify(actorId, parent, outerSteps, reservations, children, childSteps, childLinks, tasks);
    }

    static Set<Long> classify(Long actorId, AidRuntimeRun parent, List<AidRuntimeRunStep> outerSteps,
                              List<AidBillingAuthorizationReservation> reservations,
                              List<AidSkillRun> children, List<AidSkillRunStep> childSteps,
                              List<AidSkillRunTaskLink> childLinks, Map<Long, AidMediaTask> tasks)
    {
        bounded(outerSteps.size()); bounded(reservations.size()); bounded(children.size());
        bounded(childSteps.size()); bounded(childLinks.size()); bounded(tasks.size());
        if (parent == null || parent.getId() == null || actorId == null
                || !Objects.equals(actorId, parent.getUserId())) return Set.of();
        Map<Long, AidSkillRun> byRun = new HashMap<>();
        for (AidSkillRun child : children)
            if (child.getId() == null || byRun.put(child.getId(), child) != null)
                throw new IllegalStateException("Ambiguous linked Skill cost scope");
        Map<Long, AidSkillRunStep> byStep = new HashMap<>();
        for (AidSkillRunStep step : childSteps)
            if (step.getId() == null || byStep.put(step.getId(), step) != null)
                throw new IllegalStateException("Ambiguous linked Skill cost scope");
        Map<Long, Integer> reservationIds = new HashMap<>();
        Map<Long, Integer> taskIds = new HashMap<>();
        Map<String, Integer> traceIds = new HashMap<>();
        Map<String, Integer> callKeys = new HashMap<>();
        for (AidBillingAuthorizationReservation row : reservations)
        {
            reservationIds.merge(row.getId(), 1, Integer::sum);
            if (row.getMediaTaskId() != null) taskIds.merge(row.getMediaTaskId(), 1, Integer::sum);
            if (row.getBillingTraceId() != null) traceIds.merge(row.getBillingTraceId(), 1, Integer::sum);
            if (row.getLogicalCallKey() != null) callKeys.merge(row.getLogicalCallKey(), 1, Integer::sum);
        }
        Set<Long> identified = new HashSet<>();
        for (AidBillingAuthorizationReservation row : reservations)
        {
            if (!"SKILL_TEXT".equals(row.getOperationKind()) || row.getId() == null
                    || reservationIds.get(row.getId()) != 1 || row.getMediaTaskId() == null
                    || taskIds.get(row.getMediaTaskId()) != 1 || row.getBillingTraceId() == null
                    || traceIds.get(row.getBillingTraceId()) != 1 || row.getLogicalCallKey() == null
                    || callKeys.get(row.getLogicalCallKey()) != 1) continue;
            AidSkillRunStep step = byStep.get(row.getRuntimeStepId());
            AidSkillRun child = step == null ? null : byRun.get(step.getRunId());
            AidMediaTask task = tasks.get(row.getMediaTaskId());
            if (child == null || task == null) continue;
            List<AidSkillRunTaskLink> matchingLinks = childLinks.stream()
                    .filter(link -> Objects.equals(link.getStepId(), step.getId())
                            && Objects.equals(link.getMediaTaskId(), task.getId())).toList();
            if (matchingLinks.size() != 1) continue;
            int matchingOuterSteps = 0;
            for (AidRuntimeRunStep outer : outerSteps)
                if (matches(actorId, parent, outer, row, child, step, matchingLinks.get(0), task, byRun))
                    matchingOuterSteps++;
            if (matchingOuterSteps == 1) identified.add(row.getId());
        }
        return Set.copyOf(identified);
    }

    private static boolean matches(Long actorId, AidRuntimeRun parent, AidRuntimeRunStep outer,
                                   AidBillingAuthorizationReservation row, AidSkillRun child,
                                   AidSkillRunStep step, AidSkillRunTaskLink link, AidMediaTask task,
                                   Map<Long, AidSkillRun> byRun)
    {
        AidSkillRun root = byRun.get(outer.getChildRunId());
        if (!"0".equals(outer.getDelFlag()) || !"SKILL".equals(outer.getStepKind())
                || !"ASSISTANT_SKILL".equals(outer.getOperationKind())
                || !Objects.equals(parent.getId(), outer.getRunId()) || outer.getChildRunId() == null
                || root == null || outer.getSkillVersionId() == null
                || !Objects.equals(outer.getSkillVersionId(), root.getSkillVersionId())
                || !Objects.equals(parent.getId(), row.getRuntimeRunId()) || !"0".equals(row.getDelFlag())
                || !"0".equals(step.getDelFlag()) || !"0".equals(link.getDelFlag())
                || !Objects.equals(child.getId(), link.getRunId())
                || !Objects.equals(step.getStepExecutionId(), link.getStepExecutionId())
                || !Objects.equals(step.getWorkflowAttempt(), link.getWorkflowAttempt())
                || !Objects.equals(row.getAttemptNo(), step.getWorkflowAttempt())
                || row.getLogicalCallKey() == null || row.getLogicalCallKey().isBlank()
                || !Objects.equals(row.getLogicalCallKey(), link.getLogicalCallKey())
                || !Objects.equals(task.getIdempotencyKey(), link.getLogicalCallKey())
                || !Objects.equals(row.getMediaTaskId(), task.getId())
                || !Objects.equals(task.getUserId(), actorId)
                || !"TEXT".equals(task.getMediaType()) || !"SKILL_RUNTIME_STEP".equals(task.getBizTaskType())
                || !Objects.equals(task.getBizTaskId(), child.getId())
                || row.getBillingTraceId() == null || row.getBillingTraceId().isBlank()
                || !Objects.equals(row.getBillingTraceId(), task.getBillingTraceId())
                || !descendsFrom(actorId, child, outer.getChildRunId(), byRun)) return false;
        try
        {
            if (child.getInputJson() == null || child.getInputJson().length() > MAX_IDENTITY_JSON_CHARS
                    || task.getBillingSnapshotJson() == null
                    || task.getBillingSnapshotJson().length() > MAX_IDENTITY_JSON_CHARS) return false;
            JSONObject input = JSON.parseObject(child.getInputJson());
            AssistantSkillBudgetEnvelope envelope = input == null ? null
                    : input.getObject("assistantBudgetEnvelope", AssistantSkillBudgetEnvelope.class);
            AssistantSkillBudgetIdentity.validate(envelope);
            BillingSnapshot snapshot = JSON.parseObject(task.getBillingSnapshotJson(), BillingSnapshot.class);
            // Match historical admission, never today's renewed authorization or live fences.
            return snapshot != null && "RUN_AUTHORIZATION".equals(snapshot.getBudgetMode())
                    && Objects.equals(actorId, envelope.getPayerUserId())
                    && Objects.equals(child.getSkillVersionId(), envelope.getSkillVersionId())
                    && Objects.equals(child.getSkillConfigHash(), envelope.getPackageDigest())
                    && Objects.equals(parent.getId(), envelope.getAssistantRuntimeRunId())
                    && Objects.equals(outer.getId(), envelope.getAssistantParentStepId())
                    && Objects.equals(outer.getStableStepId(), envelope.getAssistantOuterStableStepKey())
                    && Objects.equals(outer.getAttemptNo(), envelope.getAssistantAttemptNo())
                    && Objects.equals(outer.getApprovalId(), envelope.getApprovalId())
                    && Objects.equals(row.getApprovalId(), envelope.getApprovalId())
                    && Objects.equals(row.getAuthorizationId(), envelope.getBillingAuthorizationId())
                    && Objects.equals(row.getAuthorizationRevision(), envelope.getBillingAuthorizationRevision())
                    && Objects.equals(row.getStableStepKey(), AssistantSkillBudgetIdentity.stableStepKey(envelope, step))
                    && Objects.equals(snapshot.getBillingAuthorizationId(), row.getAuthorizationId())
                    && Objects.equals(snapshot.getBillingAuthorizationRevision(), row.getAuthorizationRevision())
                    && Objects.equals(snapshot.getBillingReservationId(), row.getId())
                    && Objects.equals(snapshot.getRuntimeRunId(), parent.getId())
                    && Objects.equals(snapshot.getRuntimeStepId(), step.getId())
                    && Objects.equals(snapshot.getRuntimeRunFencingToken(), envelope.getAssistantRuntimeRunFencingToken())
                    && Objects.equals(snapshot.getRuntimeStepFencingToken(), envelope.getAssistantParentStepFencingToken())
                    && Objects.equals(snapshot.getStableStepKey(), row.getStableStepKey())
                    && Objects.equals(snapshot.getBillingAttemptNo(), row.getAttemptNo())
                    && Objects.equals(snapshot.getLogicalCallKey(), row.getLogicalCallKey())
                    && row.getRequestDigest() != null && !row.getRequestDigest().isBlank()
                    && Objects.equals(snapshot.getRequestDigest(), row.getRequestDigest());
        }
        catch (RuntimeException invalidIdentity) { return false; }
    }

    private static boolean descendsFrom(Long actorId, AidSkillRun child, Long rootId,
                                        Map<Long, AidSkillRun> byRun)
    {
        Set<Long> seen = new HashSet<>();
        AidSkillRun current = child;
        while (current != null && seen.size() < MAX_ROWS)
        {
            if (!seen.add(current.getId()) || !"0".equals(current.getDelFlag())
                    || !Objects.equals(actorId, current.getUserId())
                    || !Objects.equals(rootId, current.getRootRunId())
                    || !AssistantSkillBudgetIdentity.INVOKE_SOURCE.equals(current.getInvokeSource())) return false;
            if (Objects.equals(rootId, current.getId())) return current.getParentRunId() == null;
            current = byRun.get(current.getParentRunId());
        }
        return false;
    }

    private static void bounded(int size)
    {
        if (size > MAX_ROWS) throw new IllegalStateException("Linked Skill cost scope scan is incomplete");
    }
}
