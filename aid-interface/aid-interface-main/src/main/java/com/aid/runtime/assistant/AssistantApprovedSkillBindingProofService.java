package com.aid.runtime.assistant;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.aid.common.exception.ServiceException;
import com.aid.runtime.domain.AidRuntimeAgentSkillRef;
import com.aid.runtime.domain.AidRuntimePlan;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.mapper.AidRuntimeAgentSkillRefMapper;
import com.aid.runtime.mapper.AidRuntimePlanMapper;
import com.aid.runtime.mapper.AidRuntimeRunMapper;
import com.aid.skill.domain.AidSkill;
import com.aid.skill.domain.AidSkillVersion;
import com.aid.skill.mapper.AidSkillMapper;
import com.aid.skill.mapper.AidSkillVersionMapper;
import com.aid.skill.service.SkillModelService;
import com.aid.skill.service.SkillRuntimeEntrypointReadinessService;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Freezes and verifies the server-side authority for Skill steps in an approved Assistant plan.
 * The proof is internal run state: model output and client request fields are never authority for a
 * historical package digest.
 */
@Service
@RequiredArgsConstructor
public class AssistantApprovedSkillBindingProofService
{
    public static final String REQUEST_FIELD = "_approvedSkillBindings";
    public static final String SCHEMA_VERSION = "assistant-approved-skill-bindings/v1";
    public static final String PLAN_BINDING_INVALID = "ASSISTANT_PLAN_SKILL_BINDING_INVALID";
    public static final String PLAN_BINDING_UNAVAILABLE = "ASSISTANT_PLAN_SKILL_BINDING_UNAVAILABLE";
    public static final String APPROVED_PROOF_INVALID = "ASSISTANT_APPROVED_SKILL_PROOF_INVALID";
    public static final String APPROVED_BINDING_UNAVAILABLE = "ASSISTANT_APPROVED_SKILL_BINDING_UNAVAILABLE";

    private static final int MAX_PLAN_STEPS = 100;
    private static final String NORMAL = "0";
    private static final Set<String> ROOT_FIELDS = Set.of(
            "schemaVersion", "planRevision", "definitionDigest", "agentVersionId",
            "agentPackageDigest", "bindings", "bindingsDigest");
    private static final Set<String> BINDING_FIELDS = Set.of(
            "stableStepId", "skillId", "skillCode", "skillVersionId", "packageDigest");

    private final AidRuntimeRunMapper runs;
    private final AidRuntimePlanMapper plans;
    private final AidRuntimeAgentSkillRefMapper agentSkillRefs;
    private final AidSkillMapper skills;
    private final AidSkillVersionMapper versions;
    private final SkillRuntimeEntrypointReadinessService readiness;

    /**
     * Called while confirmPlan holds the run and plan rows FOR UPDATE. It locks every involved USER
     * identity/version, resolves the ordinary current authorized package, and writes one complete proof
     * into request_json. The caller persists the changed run in the same transaction as APPROVED.
     */
    public ApprovedPlanProof freezeForApproval(AidRuntimeRun run, AidRuntimePlan plan)
    {
        require(TransactionSynchronizationManager.isActualTransactionActive(), APPROVED_PROOF_INVALID);
        requirePlanIdentity(run, plan, "PROPOSED", PLAN_BINDING_INVALID);
        PlanBindings planned = planBindings(plan);
        List<PlanSkillStep> dynamic = planned.dynamicSteps();
        Map<Long, AidSkillVersion> initialVersions = selectVersions(
                dynamic.stream().map(PlanSkillStep::skillVersionId).collect(Collectors.toCollection(LinkedHashSet::new)),
                PLAN_BINDING_INVALID, false);
        require(initialVersions.values().stream().allMatch(version -> version.getSkillId() != null
                && version.getSkillId() > 0), PLAN_BINDING_INVALID);
        Set<Long> skillIds = initialVersions.values().stream().map(AidSkillVersion::getSkillId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (FixedSkillStep fixed : planned.fixedSteps())
        {
            require(fixed.ref().getSkillId() != null && fixed.ref().getSkillId() > 0,
                    PLAN_BINDING_INVALID);
            skillIds.add(fixed.ref().getSkillId());
        }
        Map<Long, AidSkill> lockedSkills = lockSkills(skillIds);
        Set<Long> versionIds = dynamic.stream().map(PlanSkillStep::skillVersionId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        planned.fixedSteps().stream().map(fixed -> fixed.ref().getSkillVersionId()).forEach(versionIds::add);
        Map<Long, AidSkillVersion> lockedVersions = selectVersions(versionIds, PLAN_BINDING_INVALID, true);
        validateFixedForApproval(planned.fixedSteps(), lockedSkills, lockedVersions);

        List<ApprovedUserSkillBinding> approved = new ArrayList<>(dynamic.size());
        Map<String, BindingCoordinate> byCode = new HashMap<>();
        Map<UserPackageKey, ApprovedUserSkillBinding> verifiedPackages = new HashMap<>();
        for (PlanSkillStep step : dynamic)
        {
            AidSkillVersion version = lockedVersions.get(step.skillVersionId());
            AidSkill identity = version == null ? null : lockedSkills.get(version.getSkillId());
            UserPackageKey key = userPackageKey(identity, version, step.skillCode());
            ApprovedUserSkillBinding verified = key == null ? null : verifiedPackages.get(key);
            ApprovedUserSkillBinding binding;
            if (verified == null)
            {
                binding = resolveForApproval(run, step, identity, version);
                verifiedPackages.put(UserPackageKey.from(binding), binding);
            }
            else binding = forStep(step, verified);
            requireConsistentCode(byCode, binding, PLAN_BINDING_INVALID);
            approved.add(binding);
        }
        approved.sort(bindingComparator());
        writeProof(run, plan, approved);
        return proof(EvidenceMode.PERSISTED_V1, run, plan, planned, approved);
    }

    /** Finds the exact persisted owned run and current APPROVED revision used by context assembly. */
    public ApprovedPlanProof findPersistedApproved(Long userId, Long sessionId, AidRuntimeRun observed,
                                                   AssistantLoopBinding binding)
    {
        if (observed == null || observed.getId() == null || observed.getPlanRevision() == null
                || observed.getPlanRevision() <= 0) return null;
        AidRuntimeRun persisted = runs.selectById(observed.getId());
        require(persisted != null && NORMAL.equals(persisted.getDelFlag())
                        && Objects.equals(persisted.getUserId(), userId)
                        && Objects.equals(persisted.getSessionId(), sessionId)
                        && Objects.equals(persisted.getVersionId(), binding.getAgentVersionId())
                        && Objects.equals(persisted.getVersionDigest(), binding.getPackageDigest())
                        && Objects.equals(persisted.getPlanRevision(), observed.getPlanRevision()),
                APPROVED_PROOF_INVALID);
        AidRuntimePlan plan = plans.selectOne(new LambdaQueryWrapper<AidRuntimePlan>()
                .eq(AidRuntimePlan::getRunId, persisted.getId())
                .eq(AidRuntimePlan::getRevision, persisted.getPlanRevision())
                .eq(AidRuntimePlan::getDelFlag, NORMAL).last("LIMIT 1"));
        if (plan == null || !"APPROVED".equals(plan.getStatus())) return null;
        return requireApproved(persisted, plan);
    }

    /**
     * Loads the exact owned run and APPROVED plan revision used by an internal runtime caller. This
     * is the only entry point for callers that need to cross an admitted package-read boundary but
     * do not already hold the persisted run and plan rows.
     */
    public PersistedApprovedPlan requirePersistedApproved(Long runId, Long userId, Long planRevision)
    {
        require(runId != null && runId > 0 && userId != null && userId > 0
                        && planRevision != null && planRevision > 0,
                APPROVED_PROOF_INVALID);
        AidRuntimeRun run = runs.selectById(runId);
        require(run != null && NORMAL.equals(run.getDelFlag())
                        && Objects.equals(run.getUserId(), userId)
                        && Objects.equals(run.getPlanRevision(), planRevision),
                APPROVED_PROOF_INVALID);
        AidRuntimePlan plan = plans.selectOne(new LambdaQueryWrapper<AidRuntimePlan>()
                .eq(AidRuntimePlan::getRunId, runId)
                .eq(AidRuntimePlan::getRevision, planRevision)
                .eq(AidRuntimePlan::getStatus, "APPROVED")
                .eq(AidRuntimePlan::getDelFlag, NORMAL).last("LIMIT 1"));
        require(plan != null, APPROVED_PROOF_INVALID);
        return new PersistedApprovedPlan(run, plan, requireApproved(run, plan));
    }

    /**
     * Resolves the proof used by operation execution. New v1 metadata is verified exactly and fails
     * closed. Runs approved before v1 are derived only from their persisted APPROVED plan and the
     * immutable owned version, never from the current identity pointer.
     */
    public ApprovedPlanProof requireApproved(AidRuntimeRun run, AidRuntimePlan plan)
    {
        requirePlanIdentity(run, plan, "APPROVED", APPROVED_PROOF_INVALID);
        PlanBindings planned = planBindings(plan);
        JSONObject request = object(run.getRequestJson(), APPROVED_PROOF_INVALID);
        if (request.containsKey(REQUEST_FIELD))
        {
            Object raw = request.get(REQUEST_FIELD);
            require(raw instanceof JSONObject, APPROVED_PROOF_INVALID);
            return persistedProof(run, plan, planned, (JSONObject) raw);
        }
        return legacyProof(run, plan, planned);
    }

    private ApprovedPlanProof persistedProof(AidRuntimeRun run, AidRuntimePlan plan, PlanBindings planned,
                                              JSONObject metadata)
    {
        require(metadata.keySet().equals(ROOT_FIELDS)
                        && SCHEMA_VERSION.equals(string(metadata.get("schemaVersion")))
                        && Objects.equals(positiveString(metadata.get("planRevision")), plan.getRevision())
                        && Objects.equals(string(metadata.get("definitionDigest")), plan.getDefinitionDigest())
                        && Objects.equals(positiveString(metadata.get("agentVersionId")), run.getVersionId())
                        && Objects.equals(string(metadata.get("agentPackageDigest")), run.getVersionDigest()),
                APPROVED_PROOF_INVALID);
        Object bindingsValue = metadata.get("bindings");
        require(bindingsValue instanceof JSONArray, APPROVED_PROOF_INVALID);
        JSONArray rawBindings = (JSONArray) bindingsValue;
        require(rawBindings.size() <= MAX_PLAN_STEPS, APPROVED_PROOF_INVALID);
        List<BindingCoordinate> coordinates = new ArrayList<>(rawBindings.size());
        for (Object raw : rawBindings)
        {
            require(raw instanceof JSONObject, APPROVED_PROOF_INVALID);
            JSONObject value = (JSONObject) raw;
            require(value.keySet().equals(BINDING_FIELDS), APPROVED_PROOF_INVALID);
            coordinates.add(new BindingCoordinate(
                    text(string(value.get("stableStepId")), 64, APPROVED_PROOF_INVALID),
                    positiveStringRequired(value.get("skillId"), APPROVED_PROOF_INVALID),
                    text(string(value.get("skillCode")), 100, APPROVED_PROOF_INVALID),
                    positiveStringRequired(value.get("skillVersionId"), APPROVED_PROOF_INVALID),
                    digest(string(value.get("packageDigest")), APPROVED_PROOF_INVALID)));
        }
        require(Objects.equals(string(metadata.get("bindingsDigest")), bindingsDigest(coordinates)),
                APPROVED_PROOF_INVALID);
        require(coordinates.size() == planned.dynamicSteps().size(), APPROVED_PROOF_INVALID);

        List<ApprovedUserSkillBinding> resolved = new ArrayList<>(coordinates.size());
        Map<String, BindingCoordinate> byCode = new HashMap<>();
        Map<UserPackageKey, ApprovedUserSkillBinding> verifiedPackages = new HashMap<>();
        for (int index = 0; index < coordinates.size(); index++)
        {
            BindingCoordinate coordinate = coordinates.get(index);
            PlanSkillStep step = planned.dynamicSteps().get(index);
            require(Objects.equals(coordinate.stableStepId(), step.stableStepId())
                            && Objects.equals(coordinate.skillCode(), step.skillCode())
                            && Objects.equals(coordinate.skillVersionId(), step.skillVersionId()),
                    APPROVED_PROOF_INVALID);
            UserPackageKey key = UserPackageKey.from(coordinate);
            ApprovedUserSkillBinding verified = verifiedPackages.get(key);
            ApprovedUserSkillBinding binding;
            if (verified == null)
            {
                binding = resolveAdmitted(run, step, coordinate);
                verifiedPackages.put(key, binding);
            }
            else binding = forStep(step, verified);
            requireConsistentCode(byCode, binding, APPROVED_PROOF_INVALID);
            resolved.add(binding);
        }
        return proof(EvidenceMode.PERSISTED_V1, run, plan, planned, resolved);
    }

    private ApprovedPlanProof legacyProof(AidRuntimeRun run, AidRuntimePlan plan, PlanBindings planned)
    {
        List<ApprovedUserSkillBinding> resolved = new ArrayList<>(planned.dynamicSteps().size());
        Map<String, BindingCoordinate> byCode = new HashMap<>();
        Map<UserPackageKey, ApprovedUserSkillBinding> verifiedPackages = new HashMap<>();
        Map<Long, AidSkillVersion> immutableVersions = selectVersions(planned.dynamicSteps().stream()
                        .map(PlanSkillStep::skillVersionId)
                        .collect(Collectors.toCollection(LinkedHashSet::new)),
                APPROVED_BINDING_UNAVAILABLE, false);
        for (PlanSkillStep step : planned.dynamicSteps())
        {
            AidSkillVersion version = immutableVersions.get(step.skillVersionId());
            require(version != null, APPROVED_BINDING_UNAVAILABLE);
            BindingCoordinate coordinate = new BindingCoordinate(step.stableStepId(), version.getSkillId(),
                    step.skillCode(), step.skillVersionId(),
                    digest(version.getPackageDigest(), APPROVED_BINDING_UNAVAILABLE));
            UserPackageKey key = UserPackageKey.from(coordinate);
            ApprovedUserSkillBinding verified = verifiedPackages.get(key);
            ApprovedUserSkillBinding binding;
            if (verified == null)
            {
                binding = resolveAdmitted(run, step, coordinate);
                verifiedPackages.put(key, binding);
            }
            else binding = forStep(step, verified);
            requireConsistentCode(byCode, binding, APPROVED_PROOF_INVALID);
            resolved.add(binding);
        }
        return proof(EvidenceMode.LEGACY_DERIVED, run, plan, planned, resolved);
    }

    private ApprovedUserSkillBinding resolveForApproval(AidRuntimeRun run, PlanSkillStep step,
                                                        AidSkill identity, AidSkillVersion version)
    {
        require(identity != null && version != null
                        && Objects.equals(version.getSkillId(), identity.getId())
                        && "USER".equals(identity.getOwnerType())
                        && Objects.equals(identity.getOwnerUserId(), run.getUserId())
                        && Objects.equals(identity.getSkillCode(), step.skillCode())
                        && Objects.equals(identity.getCurrentVersionId(), version.getId())
                        && ("PRIVATE".equals(identity.getVisibility())
                            || "PUBLIC".equals(identity.getVisibility()))
                        && "ENTRYPOINT".equals(identity.getInvocationScope())
                        && NORMAL.equals(identity.getStatus()) && NORMAL.equals(identity.getDelFlag())
                        && "PROMPT".equals(version.getExecutorType())
                        && "ENTRYPOINT".equals(version.getInvocationScope())
                        && ("PRIVATE".equals(version.getVisibility())
                            || "PUBLIC".equals(version.getVisibility()))
                        && ("PRIVATE".equals(version.getPublishStatus())
                            || "PUBLISHED".equals(version.getPublishStatus()))
                        && NORMAL.equals(version.getStatus()) && NORMAL.equals(version.getDelFlag())
                        && textOutput(version)
                        && validDigest(version.getPackageDigest()),
                PLAN_BINDING_INVALID);
        SkillRuntimeEntrypointReadinessService.ReadyEntrypoint ready;
        try
        {
            ready = readiness.requireReadyMethod(identity, version.getId(), run.getUserId(), false);
        }
        catch (RuntimeException unavailable)
        {
            throw failure(PLAN_BINDING_UNAVAILABLE);
        }
        require(ready != null && ready.rootVersion() != null
                        && Objects.equals(ready.rootVersion().getId(), version.getId())
                        && Objects.equals(ready.rootVersion().getPackageDigest(), version.getPackageDigest()),
                PLAN_BINDING_UNAVAILABLE);
        return new ApprovedUserSkillBinding(step.position(), step.stableStepId(), identity.getId(),
                identity.getSkillCode(), version.getId(), version.getPackageDigest(), identity,
                ready.rootVersion());
    }

    private void validateFixedForApproval(List<FixedSkillStep> steps,
                                          Map<Long, AidSkill> lockedSkills,
                                          Map<Long, AidSkillVersion> lockedVersions)
    {
        Set<FixedPackageKey> verified = new HashSet<>();
        for (FixedSkillStep step : steps)
        {
            AidRuntimeAgentSkillRef ref = step.ref();
            FixedPackageKey key = new FixedPackageKey(ref.getSkillId(), ref.getSkillVersionId(),
                    ref.getSkillCode(), ref.getPackageDigest());
            if (!verified.add(key)) continue;
            AidSkill identity = lockedSkills.get(ref.getSkillId());
            AidSkillVersion version = lockedVersions.get(ref.getSkillVersionId());
            require(identity != null && version != null
                            && Objects.equals(version.getSkillId(), identity.getId())
                            && Objects.equals(ref.getSkillId(), identity.getId())
                            && Objects.equals(ref.getSkillCode(), identity.getSkillCode())
                            && Objects.equals(ref.getPackageDigest(), version.getPackageDigest())
                            && "PLATFORM".equals(identity.getOwnerType())
                            && "PUBLIC".equals(identity.getVisibility())
                            && NORMAL.equals(identity.getStatus()) && NORMAL.equals(identity.getDelFlag())
                            && "PUBLIC".equals(version.getVisibility())
                            && "PUBLISHED".equals(version.getPublishStatus())
                            && "PROMPT".equals(version.getExecutorType())
                            && "INTERNAL".equals(version.getInvocationScope())
                            && NORMAL.equals(version.getStatus()) && NORMAL.equals(version.getDelFlag())
                            && textOutput(version) && validDigest(version.getPackageDigest()),
                    PLAN_BINDING_INVALID);
            try
            {
                SkillRuntimeEntrypointReadinessService.ReadyEntrypoint ready =
                        readiness.requireReadyMethod(identity, version.getId(), ref.getPackageDigest());
                require(ready != null && ready.rootVersion() != null
                                && Objects.equals(version.getId(), ready.rootVersion().getId())
                                && Objects.equals(ref.getPackageDigest(), ready.rootVersion().getPackageDigest()),
                        PLAN_BINDING_UNAVAILABLE);
            }
            catch (ServiceException unavailable)
            {
                if (PLAN_BINDING_UNAVAILABLE.equals(unavailable.getMessage())) throw unavailable;
                throw failure(PLAN_BINDING_UNAVAILABLE);
            }
            catch (RuntimeException unavailable)
            {
                throw failure(PLAN_BINDING_UNAVAILABLE);
            }
        }
    }

    private ApprovedUserSkillBinding resolveAdmitted(AidRuntimeRun run, PlanSkillStep step,
                                                      BindingCoordinate coordinate)
    {
        AidSkillVersion immutable = versions.selectById(coordinate.skillVersionId());
        AidSkill identity = immutable == null ? null : skills.selectById(immutable.getSkillId());
        require(identity != null && immutable != null
                        && Objects.equals(coordinate.skillId(), identity.getId())
                        && Objects.equals(immutable.getSkillId(), identity.getId())
                        && "USER".equals(identity.getOwnerType())
                        && Objects.equals(identity.getOwnerUserId(), run.getUserId())
                        && Objects.equals(identity.getSkillCode(), coordinate.skillCode())
                        && Objects.equals(step.skillCode(), coordinate.skillCode())
                        && Objects.equals(step.skillVersionId(), coordinate.skillVersionId())
                        && Objects.equals(immutable.getPackageDigest(), coordinate.packageDigest()),
                APPROVED_PROOF_INVALID);
        SkillRuntimeEntrypointReadinessService.ReadyEntrypoint ready;
        try
        {
            ready = readiness.requireReadyMethod(identity, coordinate.skillVersionId(),
                    coordinate.packageDigest(), run.getUserId(), true);
        }
        catch (RuntimeException unavailable)
        {
            throw failure(APPROVED_BINDING_UNAVAILABLE);
        }
        AidSkillVersion version = ready == null ? null : ready.rootVersion();
        require(version != null && Objects.equals(version.getId(), coordinate.skillVersionId())
                        && Objects.equals(version.getPackageDigest(), coordinate.packageDigest())
                        && "ENTRYPOINT".equals(version.getInvocationScope())
                        && textOutput(version),
                APPROVED_BINDING_UNAVAILABLE);
        return new ApprovedUserSkillBinding(step.position(), step.stableStepId(), identity.getId(),
                identity.getSkillCode(), version.getId(), version.getPackageDigest(), identity, version);
    }

    private PlanBindings planBindings(AidRuntimePlan plan)
    {
        JSONObject definition = object(plan.getDefinitionJson(), APPROVED_PROOF_INVALID);
        Object stepsValue = definition.get("steps");
        require(stepsValue instanceof JSONArray, APPROVED_PROOF_INVALID);
        JSONArray rawSteps = (JSONArray) stepsValue;
        require(!rawSteps.isEmpty() && rawSteps.size() <= MAX_PLAN_STEPS,
                APPROVED_PROOF_INVALID);
        List<AidRuntimeAgentSkillRef> refs = agentSkillRefs.selectList(
                new LambdaQueryWrapper<AidRuntimeAgentSkillRef>()
                        .eq(AidRuntimeAgentSkillRef::getAgentVersionId, plan.getAgentVersionId())
                        .orderByAsc(AidRuntimeAgentSkillRef::getSkillCode));
        Map<String, AidRuntimeAgentSkillRef> fixedByCode = new HashMap<>();
        for (AidRuntimeAgentSkillRef ref : refs)
            require(ref != null && StrUtil.isNotBlank(ref.getSkillCode())
                            && ref.getSkillVersionId() != null && ref.getSkillVersionId() > 0
                            && fixedByCode.putIfAbsent(ref.getSkillCode(), ref) == null,
                    APPROVED_PROOF_INVALID);

        List<PlanSkillStep> dynamic = new ArrayList<>();
        List<FixedSkillStep> fixedSteps = new ArrayList<>();
        Set<SkillKey> approved = new HashSet<>();
        Set<String> stableIds = new HashSet<>();
        Set<Integer> positions = new HashSet<>();
        for (Object raw : rawSteps)
        {
            require(raw instanceof JSONObject, APPROVED_PROOF_INVALID);
            JSONObject step = (JSONObject) raw;
            String stable = text(string(step.get("stableStepId")), 64, APPROVED_PROOF_INVALID);
            Integer position = positiveInteger(step.get("position"));
            require(stableIds.add(stable) && position != null && position > 0 && positions.add(position),
                    APPROVED_PROOF_INVALID);
            String executorKind = string(step.get("executorKind"));
            require("TOOL".equals(executorKind) || "SKILL".equals(executorKind), APPROVED_PROOF_INVALID);
            if (!"SKILL".equals(executorKind)) continue;
            String code = text(string(step.get("bindingCode")), 100, APPROVED_PROOF_INVALID);
            Long versionId = positiveStringRequired(step.get("bindingVersion"), APPROVED_PROOF_INVALID);
            approved.add(new SkillKey(code, versionId));
            AidRuntimeAgentSkillRef fixed = fixedByCode.get(code);
            if (fixed != null)
            {
                require(Objects.equals(fixed.getSkillVersionId(), versionId), APPROVED_PROOF_INVALID);
                fixedSteps.add(new FixedSkillStep(position, stable, fixed));
                continue;
            }
            dynamic.add(new PlanSkillStep(position, stable, code, versionId));
        }
        dynamic.sort(Comparator.comparingInt(PlanSkillStep::position)
                .thenComparing(PlanSkillStep::stableStepId));
        fixedSteps.sort(Comparator.comparingInt(FixedSkillStep::position)
                .thenComparing(FixedSkillStep::stableStepId));
        return new PlanBindings(List.copyOf(dynamic), List.copyOf(fixedSteps), Set.copyOf(approved));
    }

    private void writeProof(AidRuntimeRun run, AidRuntimePlan plan,
                            List<ApprovedUserSkillBinding> bindings)
    {
        JSONObject request = object(run.getRequestJson(), APPROVED_PROOF_INVALID);
        List<BindingCoordinate> coordinates = bindings.stream().map(BindingCoordinate::from).toList();
        JSONArray values = new JSONArray();
        for (BindingCoordinate binding : coordinates)
        {
            JSONObject value = new JSONObject();
            value.put("stableStepId", binding.stableStepId());
            value.put("skillId", String.valueOf(binding.skillId()));
            value.put("skillCode", binding.skillCode());
            value.put("skillVersionId", String.valueOf(binding.skillVersionId()));
            value.put("packageDigest", binding.packageDigest());
            values.add(value);
        }
        JSONObject metadata = new JSONObject();
        metadata.put("schemaVersion", SCHEMA_VERSION);
        metadata.put("planRevision", String.valueOf(plan.getRevision()));
        metadata.put("definitionDigest", plan.getDefinitionDigest());
        metadata.put("agentVersionId", String.valueOf(run.getVersionId()));
        metadata.put("agentPackageDigest", run.getVersionDigest());
        metadata.put("bindings", values);
        metadata.put("bindingsDigest", bindingsDigest(coordinates));
        request.put(REQUEST_FIELD, metadata);
        run.setRequestJson(request.toJSONString());
    }

    private ApprovedPlanProof proof(EvidenceMode mode, AidRuntimeRun run, AidRuntimePlan plan,
                                    PlanBindings planned,
                                    List<ApprovedUserSkillBinding> bindings)
    {
        Map<String, ApprovedUserSkillBinding> byStableStep = new LinkedHashMap<>();
        for (ApprovedUserSkillBinding binding : bindings)
            require(byStableStep.putIfAbsent(binding.stableStepId(), binding) == null, APPROVED_PROOF_INVALID);
        Map<SkillKey, String> packageDigests = new LinkedHashMap<>();
        for (FixedSkillStep fixed : planned.fixedSteps())
        {
            AidRuntimeAgentSkillRef ref = fixed.ref();
            putPackageDigest(packageDigests, new SkillKey(ref.getSkillCode(), ref.getSkillVersionId()),
                    ref.getPackageDigest());
        }
        for (ApprovedUserSkillBinding binding : bindings)
            putPackageDigest(packageDigests, new SkillKey(binding.skillCode(), binding.skillVersionId()),
                    binding.packageDigest());
        require(packageDigests.keySet().equals(planned.approvedSkillKeys()), APPROVED_PROOF_INVALID);
        return new ApprovedPlanProof(mode, run.getId(), plan.getRevision(), plan.getDefinitionDigest(),
                run.getVersionId(), run.getVersionDigest(), List.copyOf(bindings),
                Map.copyOf(byStableStep), Map.copyOf(packageDigests));
    }

    private void putPackageDigest(Map<SkillKey, String> packageDigests, SkillKey key, String value)
    {
        String packageDigest = digest(value, APPROVED_PROOF_INVALID);
        String existing = packageDigests.putIfAbsent(key, packageDigest);
        require(existing == null || Objects.equals(existing, packageDigest), APPROVED_PROOF_INVALID);
    }

    private Map<Long, AidSkill> lockSkills(Set<Long> skillIds)
    {
        if (skillIds.isEmpty()) return Map.of();
        List<AidSkill> locked = skills.selectList(new LambdaQueryWrapper<AidSkill>()
                .in(AidSkill::getId, skillIds).orderByAsc(AidSkill::getId).last("FOR UPDATE"));
        require(locked.size() == skillIds.size(), PLAN_BINDING_INVALID);
        return locked.stream().collect(Collectors.toMap(AidSkill::getId, Function.identity()));
    }

    private Map<Long, AidSkillVersion> selectVersions(Set<Long> versionIds, String code, boolean lock)
    {
        if (versionIds.isEmpty()) return Map.of();
        LambdaQueryWrapper<AidSkillVersion> query = new LambdaQueryWrapper<AidSkillVersion>()
                .in(AidSkillVersion::getId, versionIds).orderByAsc(AidSkillVersion::getId);
        if (lock) query.last("FOR UPDATE");
        List<AidSkillVersion> selected = versions.selectList(query);
        require(selected.size() == versionIds.size(), code);
        return selected.stream().collect(Collectors.toMap(AidSkillVersion::getId, Function.identity()));
    }

    private void requirePlanIdentity(AidRuntimeRun run, AidRuntimePlan plan,
                                     String expectedStatus, String code)
    {
        require(run != null && plan != null && run.getId() != null && run.getUserId() != null
                        && Objects.equals(plan.getRunId(), run.getId())
                        && Objects.equals(plan.getRevision(), run.getPlanRevision())
                        && Objects.equals(plan.getAgentVersionId(), run.getVersionId())
                        && Objects.equals(plan.getInputContextDigest(), run.getContextDigest())
                        && expectedStatus.equals(plan.getStatus()) && NORMAL.equals(plan.getDelFlag())
                        && StrUtil.isNotBlank(plan.getDefinitionJson())
                        && Objects.equals(plan.getDefinitionDigest(), SecureUtil.sha256(plan.getDefinitionJson()))
                        && validDigest(run.getVersionDigest()), code);
    }

    private void requireConsistentCode(Map<String, BindingCoordinate> byCode,
                                       ApprovedUserSkillBinding binding, String code)
    {
        BindingCoordinate coordinate = BindingCoordinate.from(binding);
        BindingCoordinate existing = byCode.putIfAbsent(binding.skillCode(), coordinate);
        require(existing == null || (Objects.equals(existing.skillId(), coordinate.skillId())
                && Objects.equals(existing.skillVersionId(), coordinate.skillVersionId())
                && Objects.equals(existing.packageDigest(), coordinate.packageDigest())), code);
    }

    private UserPackageKey userPackageKey(AidSkill identity, AidSkillVersion version, String skillCode)
    {
        return identity == null || version == null ? null : new UserPackageKey(identity.getId(),
                version.getId(), skillCode, version.getPackageDigest());
    }

    private ApprovedUserSkillBinding forStep(PlanSkillStep step, ApprovedUserSkillBinding verified)
    {
        return new ApprovedUserSkillBinding(step.position(), step.stableStepId(), verified.skillId(),
                verified.skillCode(), verified.skillVersionId(), verified.packageDigest(),
                verified.identity(), verified.version());
    }

    private Comparator<ApprovedUserSkillBinding> bindingComparator()
    {
        return Comparator.comparingInt(ApprovedUserSkillBinding::position)
                .thenComparing(ApprovedUserSkillBinding::stableStepId);
    }

    private String bindingsDigest(List<BindingCoordinate> bindings)
    {
        StringBuilder canonical = new StringBuilder(SCHEMA_VERSION).append('\n');
        for (BindingCoordinate binding : bindings)
        {
            appendCanonical(canonical, binding.stableStepId());
            appendCanonical(canonical, String.valueOf(binding.skillId()));
            appendCanonical(canonical, binding.skillCode());
            appendCanonical(canonical, String.valueOf(binding.skillVersionId()));
            appendCanonical(canonical, binding.packageDigest());
        }
        return SecureUtil.sha256(canonical.toString());
    }

    private void appendCanonical(StringBuilder value, String part)
    {
        value.append(part.length()).append(':').append(part).append('\n');
    }

    private JSONObject object(String value, String code)
    {
        try
        {
            JSONObject result = JSON.parseObject(value);
            require(result != null, code);
            return result;
        }
        catch (RuntimeException invalid)
        {
            if (invalid instanceof ServiceException service) throw service;
            throw failure(code);
        }
    }

    private Long positiveStringRequired(Object value, String code)
    {
        Long result = positiveString(value);
        require(result != null, code);
        return result;
    }

    private Long positiveString(Object value)
    {
        try
        {
            if (!(value instanceof String text) || !text.matches("[1-9][0-9]{0,18}")) return null;
            long parsed = Long.parseLong(text);
            return parsed > 0 ? parsed : null;
        }
        catch (RuntimeException invalid) { return null; }
    }

    private Integer positiveInteger(Object value)
    {
        if (!(value instanceof Number number)) return null;
        long parsed = number.longValue();
        return parsed > 0 && parsed <= Integer.MAX_VALUE && number.doubleValue() == parsed
                ? (int) parsed : null;
    }

    private String string(Object value)
    {
        return value instanceof String text ? text : null;
    }

    private String text(String value, int maximum, String code)
    {
        require(StrUtil.isNotBlank(value) && value.length() <= maximum, code);
        return value;
    }

    private String digest(String value, String code)
    {
        require(validDigest(value), code);
        return value;
    }

    private boolean validDigest(String value)
    {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private boolean textOutput(AidSkillVersion version)
    {
        try { return "TEXT".equalsIgnoreCase(SkillModelService.outputKind(version)); }
        catch (RuntimeException invalid) { return false; }
    }

    private void require(boolean condition, String code)
    {
        if (!condition) throw failure(code);
    }

    private ServiceException failure(String code)
    {
        return new ServiceException(code);
    }

    public enum EvidenceMode { PERSISTED_V1, LEGACY_DERIVED }

    public record ApprovedPlanProof(EvidenceMode evidenceMode,
                                    Long runId,
                                    Long planRevision,
                                    String definitionDigest,
                                    Long agentVersionId,
                                    String agentPackageDigest,
                                    List<ApprovedUserSkillBinding> userBindings,
                                    Map<String, ApprovedUserSkillBinding> userBindingsByStableStep,
                                    Map<SkillKey, String> approvedPackageDigests)
    {
        public boolean allowsSkill(String code, Long versionId)
        {
            return packageDigest(code, versionId) != null;
        }

        public String packageDigest(String code, Long versionId)
        {
            return code == null || versionId == null ? null
                    : approvedPackageDigests.get(new SkillKey(code, versionId));
        }

        public ApprovedUserSkillBinding userBinding(String stableStepId)
        {
            return stableStepId == null ? null : userBindingsByStableStep.get(stableStepId);
        }
    }

    public record PersistedApprovedPlan(AidRuntimeRun run, AidRuntimePlan plan,
                                        ApprovedPlanProof proof) { }

    public record ApprovedUserSkillBinding(int position,
                                           String stableStepId,
                                           Long skillId,
                                           String skillCode,
                                           Long skillVersionId,
                                           String packageDigest,
                                           AidSkill identity,
                                           AidSkillVersion version) { }

    public record SkillKey(String skillCode, Long skillVersionId) { }

    private record PlanBindings(List<PlanSkillStep> dynamicSteps,
                                List<FixedSkillStep> fixedSteps,
                                Set<SkillKey> approvedSkillKeys) { }

    private record PlanSkillStep(int position, String stableStepId, String skillCode, Long skillVersionId) { }

    private record FixedSkillStep(int position, String stableStepId, AidRuntimeAgentSkillRef ref) { }

    private record FixedPackageKey(Long skillId, Long skillVersionId, String skillCode, String packageDigest) { }

    private record UserPackageKey(Long skillId, Long skillVersionId, String skillCode, String packageDigest)
    {
        private static UserPackageKey from(ApprovedUserSkillBinding binding)
        {
            return new UserPackageKey(binding.skillId(), binding.skillVersionId(), binding.skillCode(),
                    binding.packageDigest());
        }

        private static UserPackageKey from(BindingCoordinate binding)
        {
            return new UserPackageKey(binding.skillId(), binding.skillVersionId(), binding.skillCode(),
                    binding.packageDigest());
        }
    }

    private record BindingCoordinate(String stableStepId, Long skillId, String skillCode,
                                     Long skillVersionId, String packageDigest)
    {
        private static BindingCoordinate from(ApprovedUserSkillBinding binding)
        {
            return new BindingCoordinate(binding.stableStepId(), binding.skillId(), binding.skillCode(),
                    binding.skillVersionId(), binding.packageDigest());
        }
    }
}
