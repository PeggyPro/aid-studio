package com.aid.runtime.assistant;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.aid.common.exception.ServiceException;
import com.aid.runtime.domain.AidRuntimeAgentToolRef;
import com.aid.runtime.domain.AidRuntimePlan;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.mapper.AidRuntimeAgentToolRefMapper;
import com.aid.runtime.mapper.AidRuntimePlanMapper;
import com.aid.runtime.mapper.AidRuntimeRunMapper;
import com.aid.runtime.service.RuntimeToolRegistry;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Creates the server-owned discovery plan containing READ tools with no direct charge. */
@Component
@RequiredArgsConstructor
public class NativeAssistantDiscoveryPlanService
{
    public static final String DEFINITION_KIND = "NATIVE_DISCOVERY_READ";
    private static final int MAX_READ_ATTEMPTS = 10;
    private static final Set<String> SAFE_READ_TOOLS = Set.of(
            "aid.skill.directory",
            "aid.context.prepare",
            "aid.context.read",
            "aid.context.read-v2",
            "aid.conversation.read",
            "aid.agent.read-resource",
            "aid.model.query",
            "aid.model.detail",
            "aid.model.query-scoped",
            "aid.skill.resolve-version",
            "aid.skill.read-resource",
            "aid.skill.result.references",
            "aid.conversation.result.references",
            "canvas.node.read",
            "canvas.story.project.inspect",
            "canvas.story.project.inspect-state",
            "canvas.story.artifact.read",
            "canvas.story.source.read",
            "canvas.story.source.pipeline.inspect",
            "canvas.story.source.chapter.read",
            "canvas.story.source.episode.read",
            "canvas.story.artifact.lines.read");

    private final AidRuntimePlanMapper plans;
    private final AidRuntimeRunMapper runs;
    private final AidRuntimeAgentToolRefMapper toolRefs;
    private final RuntimeToolRegistry tools;
    private final AssistantApprovedSkillBindingProofService approvedSkillBindings;

    /** Called while the planning quote transaction already owns the run row. */
    public Ensured ensure(AidRuntimeRun run, AssistantLoopBinding binding, Long actorId)
    {
        require(TransactionSynchronizationManager.isActualTransactionActive(),
                "NATIVE_DISCOVERY_TRANSACTION_REQUIRED");
        require(run != null && run.getId() != null && run.getVersionId() != null
                        && run.getUserId() != null && Objects.equals(run.getUserId(), actorId)
                        && binding != null && Objects.equals(binding.getAgentVersionId(), run.getVersionId())
                        && Objects.equals(binding.getPackageDigest(), run.getVersionDigest())
                        && NativeAssistantLoopExecutor.PROTOCOL_VERSION.equals(binding.getProtocolVersion()),
                "NATIVE_DISCOVERY_IDENTITY_INVALID");
        long parentRevision = value(run.getPlanRevision());
        if (parentRevision > 0)
        {
            AidRuntimePlan current = plans.selectOne(new LambdaQueryWrapper<AidRuntimePlan>()
                    .eq(AidRuntimePlan::getRunId, run.getId())
                    .eq(AidRuntimePlan::getRevision, parentRevision)
                    .eq(AidRuntimePlan::getDelFlag, "0").last("FOR UPDATE"));
            require(current != null && Objects.equals(current.getAgentVersionId(), run.getVersionId())
                            && StrUtil.isNotBlank(current.getDefinitionJson())
                            && Objects.equals(current.getDefinitionDigest(),
                            SecureUtil.sha256(current.getDefinitionJson())),
                    "NATIVE_DISCOVERY_PLAN_INVALID");
            if ("APPROVED".equals(current.getStatus())) return new Ensured(null, false);
            require("SUPERSEDED".equals(current.getStatus())
                            && List.of("REPLANNING_REQUIRED", "CONTINUE_AGENT_REQUIRED")
                            .contains(run.getNextAction()),
                    "NATIVE_DISCOVERY_PLAN_UNAVAILABLE");
        }

        boolean metadataReads = NativeAssistantBindingPolicy.methodResults(NativeAssistantBindingPolicy.schemaVersion(binding));
        List<AidRuntimeAgentToolRef> bound = toolRefs.selectList(
                new LambdaQueryWrapper<AidRuntimeAgentToolRef>()
                        .eq(AidRuntimeAgentToolRef::getAgentVersionId, run.getVersionId())
                        .in(!metadataReads, AidRuntimeAgentToolRef::getToolCode, SAFE_READ_TOOLS)
                        .orderByAsc(AidRuntimeAgentToolRef::getToolCode));
        List<BoundRead> reads = new ArrayList<>();
        for (AidRuntimeAgentToolRef ref : bound)
        {
            RuntimeToolRegistry.Descriptor descriptor = tools.discoverPinned(binding.getModuleCode(), ref.getToolCode());
            if (descriptor == null) continue;
            boolean unbilledRead = "READ".equals(descriptor.sideEffectLevel()) && !descriptor.billable();
            if (metadataReads && !unbilledRead) continue;
            require(unbilledRead && Objects.equals(ref.getContractVersion(), descriptor.contractVersion())
                            && Objects.equals(ref.getSchemaDigest(), descriptor.schemaDigest())
                            && StrUtil.isNotBlank(descriptor.schemaDigest()),
                    "NATIVE_DISCOVERY_TOOL_INVALID");
            reads.add(new BoundRead(ref.getToolCode(), ref.getContractVersion(), ref.getSchemaDigest()));
        }
        reads.sort(Comparator.comparing(BoundRead::code));
        if (reads.isEmpty()) return new Ensured(null, false);

        long revision = parentRevision + 1L;
        JSONObject definition = definition(reads, parentRevision, metadataReads);
        String definitionJson = definition.toJSONString();
        Date now = new Date();
        AidRuntimePlan plan = new AidRuntimePlan();
        plan.setRunId(run.getId());
        plan.setRevision(revision);
        plan.setParentRevision(value(run.getPlanRevision()));
        plan.setAgentVersionId(run.getVersionId());
        plan.setProtocolVersion(binding.getProtocolVersion());
        plan.setPlannerAttemptNo(0);
        plan.setDefinitionJson(definitionJson);
        plan.setInputContextDigest(run.getContextDigest());
        plan.setDefinitionDigest(SecureUtil.sha256(definitionJson));
        plan.setStatus("PROPOSED");
        plan.setDelFlag("0");
        createAudit(plan, actorId, now);
        require(plans.insert(plan) == 1, "NATIVE_DISCOVERY_PLAN_CREATE_FAILED");

        run.setPlanRevision(revision);
        // Freeze the same empty Skill proof used by every approved TOOL-only plan. The Tool
        // contracts themselves remain bound by Agent refs and the operation resolver.
        approvedSkillBindings.freezeForApproval(run, plan);
        plan.setStatus("APPROVED");
        updateAudit(plan, actorId, now);
        require(plans.updateById(plan) == 1, "NATIVE_DISCOVERY_PLAN_CHANGED");
        run.setStateRevision(value(run.getStateRevision()) + 1L);
        updateAudit(run, actorId, now);
        require(runs.updateById(run) == 1, "NATIVE_DISCOVERY_RUN_CHANGED");
        return new Ensured(plan, true);
    }

    public static boolean isDiscovery(JSONObject definition)
    {
        return definition != null && DEFINITION_KIND.equals(definition.getString("serverPlanKind"));
    }

    /** A replaced discovery plan is still read-only; an execution ancestor requires COMPLETE evidence. */
    public static boolean discoveryOnlyLineage(AidRuntimePlan current, List<AidRuntimePlan> ancestors)
    {
        if (current == null || current.getRevision() == null || current.getRevision() <= 0
                || current.getRunId() == null || current.getAgentVersionId() == null) return false;
        Map<Long, AidRuntimePlan> byRevision = new java.util.HashMap<>();
        byRevision.put(current.getRevision(), current);
        for (AidRuntimePlan row : ancestors == null ? List.<AidRuntimePlan>of() : ancestors) {
            if (row == null || row.getRevision() == null || byRevision.putIfAbsent(row.getRevision(), row) != null)
                return false;
        }
        long revision = current.getRevision();
        while (revision > 0) {
            AidRuntimePlan row = byRevision.get(revision);
            if (row == null || !Objects.equals(row.getRunId(), current.getRunId())
                    || !Objects.equals(row.getAgentVersionId(), current.getAgentVersionId())
                    || !Objects.equals(row.getProtocolVersion(), current.getProtocolVersion())
                    || !(revision == current.getRevision() ? "APPROVED" : "SUPERSEDED").equals(row.getStatus())
                    || StrUtil.isBlank(row.getDefinitionJson())
                    || !Objects.equals(row.getDefinitionDigest(), SecureUtil.sha256(row.getDefinitionJson()))) return false;
            JSONObject definition;
            try { definition = JSON.parseObject(row.getDefinitionJson()); }
            catch (RuntimeException malformed) { return false; }
            long parent = row.getParentRevision() == null ? 0L : row.getParentRevision();
            if (!isDiscovery(definition) || parent < 0 || parent >= revision
                    || definition.getLongValue("parentRevision") != parent) return false;
            revision = parent;
        }
        return true;
    }

    /** Copy only host-owned identity after the assembler verified the frozen plan and its bindings. */
    public static void projectIdentity(JSONObject definition, JSONObject projection)
    {
        if (!isDiscovery(definition)) return;
        projection.put("serverPlanKind", DEFINITION_KIND);
        if (definition.getIntValue("readPolicyRevision") == 2)
            projection.put("readPolicyRevision", 2);
    }

    public static boolean isSafeRead(String toolCode)
    {
        return toolCode != null && SAFE_READ_TOOLS.contains(toolCode);
    }

    /** Revision 2 is written only by the host after checking pinned READ/nonbillable metadata. */
    public static boolean isSafeRead(String code, JSONObject definition)
    {
        if (code != null && isDiscovery(definition) && definition.getIntValue("readPolicyRevision") == 2) {
            JSONArray steps = definition.getJSONArray("steps");
            for (int i = 0; steps != null && i < steps.size(); i++) {
                JSONObject step = steps.getJSONObject(i);
                if (step != null && "TOOL".equals(step.getString("executorKind"))
                        && code.equals(step.getString("bindingCode"))) return true;
            }
            return false;
        }
        return isSafeRead(code);
    }

    private JSONObject definition(List<BoundRead> reads, long parentRevision, boolean metadataReads)
    {
        JSONObject definition = new JSONObject();
        definition.put("serverPlanKind", DEFINITION_KIND);
        if (metadataReads) definition.put("readPolicyRevision", 2);
        definition.put("parentRevision", parentRevision);
        definition.put("goal", "Read only the fixed Agent, Skill, and Canvas context needed to decide the next action.");
        definition.put("assumptions", List.of("These READ tools have no direct tool charge and are side-effect-free. Assistant text-model calls may still cost credits; the run is not declared free."));
        definition.put("deliverables", List.of("Durable observations for the next native Assistant turn."));
        JSONArray steps = new JSONArray();
        int position = 1;
        for (BoundRead read : reads)
        {
            JSONObject step = new JSONObject();
            step.put("stableStepId", "native-discovery/" + read.code().replace('.', '-'));
            step.put("position", position++);
            step.put("operationKind", "READ_ASSISTANT_CONTEXT");
            step.put("executorKind", "TOOL");
            step.put("bindingCode", read.code());
            step.put("bindingVersion", read.contractVersion());
            step.put("dependsOn", new JSONArray());
            step.put("inputArtifactRefs", new JSONArray());
            step.put("inputArtifactBindings", new JSONArray());
            step.put("outputLogicalArtifactIds", new JSONArray());
            step.put("retryClass", "SAFE_READ");
            step.put("maxAttempts", MAX_READ_ATTEMPTS);
            step.put("approvalScope", "READ_ASSISTANT_CONTEXT");
            steps.add(step);
        }
        definition.put("steps", steps);
        definition.put("completionCriteria", List.of(Map.of(
                "criterionId", "discovery-complete",
                "criterionKind", "CONTENT_REQUIREMENT",
                "description", "The Assistant has enough durable observations to answer or propose an execution plan.")));
        return definition;
    }

    private void createAudit(com.aid.common.core.domain.BaseEntity entity, Long actorId, Date now)
    {
        entity.setCreateBy(String.valueOf(actorId));
        entity.setCreateTime(now);
        updateAudit(entity, actorId, now);
    }

    private void updateAudit(com.aid.common.core.domain.BaseEntity entity, Long actorId, Date now)
    {
        entity.setUpdateBy(String.valueOf(actorId));
        entity.setUpdateTime(now);
    }

    private long value(Long value) { return value == null ? 0L : value; }

    private void require(boolean condition, String code)
    {
        if (!condition && "NATIVE_DISCOVERY_TOOL_INVALID".equals(code))
            throw new com.aid.assistant.error.AssistantPlanningPreparationRejectedException(
                    com.aid.assistant.error.AssistantPlanningPreparationRejectedException.Reason.ASSISTANT_NATIVE_TOOL_DEFINITION_INVALID);
        if (!condition) throw new ServiceException(code);
    }

    private record BoundRead(String code, String contractVersion, String schemaDigest) {}

    public record Ensured(AidRuntimePlan plan, boolean created) {}
}
