package com.aid.runtime.assistant.execution;

import cn.hutool.crypto.SecureUtil;
import com.aid.assistant.artifact.domain.AidRuntimeArtifact;
import com.aid.assistant.artifact.domain.AidRuntimeArtifactAdoption;
import com.aid.assistant.artifact.mapper.AidRuntimeArtifactAdoptionMapper;
import com.aid.assistant.artifact.mapper.AidRuntimeArtifactMapper;
import com.aid.assistant.domain.AidAssistantSession;
import com.aid.assistant.dto.AssistantRequests;
import com.aid.assistant.effect.AssistantArtifactAdoptionEffectProjector;
import com.aid.assistant.effect.AssistantRuntimeToolEffectProjector;
import com.aid.assistant.effect.AssistantTargetEffectProjection;
import com.aid.assistant.effect.AssistantTargetEffectService;
import com.aid.assistant.effect.VerifiedArtifactAdoptionEffect;
import com.aid.assistant.effect.VerifiedRuntimeToolEffect;
import com.aid.assistant.error.AssistantServiceException;
import com.aid.assistant.mapper.AidAssistantSessionMapper;
import com.aid.assistant.scope.ResolvedContextRef;
import com.aid.assistant.scope.RuntimeScopeAccessRegistry;
import com.aid.assistant.vo.AssistantTargetViews;
import com.aid.runtime.domain.AidRuntimeApproval;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.domain.AidRuntimeRunStep;
import com.aid.runtime.mapper.AidRuntimeApprovalMapper;
import com.aid.runtime.mapper.AidRuntimeRunMapper;
import com.aid.runtime.mapper.AidRuntimeRunStepMapper;
import com.aid.runtime.service.RuntimeToolRegistry;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Strictly derives locatable targets from persisted, contract-valid receipts. */
@Slf4j
@Service
public class AssistantTargetEffectServiceImpl implements AssistantTargetEffectService {
    private final AidAssistantSessionMapper sessionMapper;
    private final AidRuntimeRunMapper runMapper;
    private final AidRuntimeRunStepMapper stepMapper;
    private final AidRuntimeApprovalMapper approvalMapper;
    private final AidRuntimeArtifactMapper artifactMapper;
    private final AidRuntimeArtifactAdoptionMapper adoptionMapper;
    private final AssistantPlanExecutionScopeCodec scopeCodec;
    private final RuntimeToolRegistry toolRegistry;
    private final RuntimeScopeAccessRegistry scopeAccess;
    private final Map<String, AssistantRuntimeToolEffectProjector> toolProjectors;
    private final Map<String, AssistantArtifactAdoptionEffectProjector> adoptionProjectors;

    @Autowired
    public AssistantTargetEffectServiceImpl(AidAssistantSessionMapper sessionMapper,
                                            AidRuntimeRunMapper runMapper,
                                            AidRuntimeRunStepMapper stepMapper,
                                            AidRuntimeApprovalMapper approvalMapper,
                                            AidRuntimeArtifactMapper artifactMapper,
                                            AidRuntimeArtifactAdoptionMapper adoptionMapper,
                                            AssistantPlanExecutionScopeCodec scopeCodec,
                                            RuntimeToolRegistry toolRegistry,
                                            RuntimeScopeAccessRegistry scopeAccess,
                                            @Autowired(required = false)
                                            List<AssistantRuntimeToolEffectProjector> toolBeans,
                                            @Autowired(required = false)
                                            List<AssistantArtifactAdoptionEffectProjector> adoptionBeans) {
        this.sessionMapper = sessionMapper;
        this.runMapper = runMapper;
        this.stepMapper = stepMapper;
        this.approvalMapper = approvalMapper;
        this.artifactMapper = artifactMapper;
        this.adoptionMapper = adoptionMapper;
        this.scopeCodec = scopeCodec;
        this.toolRegistry = toolRegistry;
        this.scopeAccess = scopeAccess;
        this.toolProjectors = uniqueToolProjectors(toolBeans);
        this.adoptionProjectors = uniqueAdoptionProjectors(adoptionBeans);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, List<AssistantTargetViews.TargetEffect>> projectSteps(
            Long userId, AidAssistantSession session, AidRuntimeRun run, List<AidRuntimeRunStep> steps) {
        if (!owns(userId, session, run) || steps == null || steps.isEmpty()) return Map.of();
        List<AidRuntimeRunStep> candidates = steps.stream()
                .filter(step -> canProjectStep(run, step)).toList();
        if (candidates.isEmpty()) return Map.of();
        Map<Long, AidRuntimeApproval> approvalById = new HashMap<>();
        List<Long> approvalIds = candidates.stream().map(AidRuntimeRunStep::getApprovalId)
                .filter(Objects::nonNull).distinct().toList();
        if (!approvalIds.isEmpty())
            approvalMapper.selectBatchIds(approvalIds).forEach(value -> approvalById.put(value.getId(), value));
        Map<String, ResolvedContextRef> resolvedTargets = new HashMap<>();
        Map<Long, List<AssistantTargetViews.TargetEffect>> result = new LinkedHashMap<>();
        for (AidRuntimeRunStep step : candidates) {
            AssistantTargetViews.TargetEffect effect = projectStep(userId, session, run, step,
                    approvalById.get(step == null ? null : step.getApprovalId()), resolvedTargets);
            if (effect != null) result.put(step.getId(), List.of(effect));
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, AssistantTargetViews.TargetEffect> projectArtifacts(
            Long userId, AidAssistantSession session, List<AidRuntimeArtifact> artifacts) {
        if (session == null || artifacts == null || artifacts.isEmpty()
                || !Objects.equals(userId, session.getUserId()) || !"ASSISTANT".equals(session.getSessionType()))
            return Map.of();
        return projectArtifacts(userId, artifacts, session);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, AssistantTargetViews.TargetEffect> projectArtifacts(
            Long userId, List<AidRuntimeArtifact> artifacts) {
        return projectArtifacts(userId, artifacts, null);
    }

    private Map<Long, AssistantTargetViews.TargetEffect> projectArtifacts(
            Long userId, List<AidRuntimeArtifact> artifacts, AidAssistantSession fixedSession) {
        if (userId == null || artifacts == null || artifacts.isEmpty()) return Map.of();
        List<AidRuntimeArtifact> candidates = artifacts.stream()
                .filter(Objects::nonNull)
                .filter(value -> value.getId() != null && value.getProducerRunId() != null)
                .toList();
        if (candidates.isEmpty()) return Map.of();

        Map<Long, AidRuntimeRun> runById = new HashMap<>();
        runMapper.selectBatchIds(candidates.stream().map(AidRuntimeArtifact::getProducerRunId)
                        .distinct().toList())
                .stream().filter(Objects::nonNull)
                .forEach(value -> runById.put(value.getId(), value));

        Map<Long, AidAssistantSession> sessionById = new HashMap<>();
        if (fixedSession != null) {
            sessionById.put(fixedSession.getId(), fixedSession);
        } else {
            List<Long> sessionIds = runById.values().stream().map(AidRuntimeRun::getSessionId)
                    .filter(Objects::nonNull).distinct().toList();
            if (!sessionIds.isEmpty()) {
                sessionMapper.selectBatchIds(sessionIds).stream().filter(Objects::nonNull)
                        .forEach(value -> sessionById.put(value.getId(), value));
            }
        }

        List<Long> artifactIds = candidates.stream().map(AidRuntimeArtifact::getId).distinct().toList();
        Map<String, AidRuntimeArtifactAdoption> adoptionBySessionAndArtifact = new HashMap<>();
        adoptionMapper.selectLatestSucceededForArtifacts(userId, artifactIds).stream()
                .filter(Objects::nonNull)
                .filter(value -> value.getSessionId() != null && value.getArtifactId() != null)
                .forEach(value -> adoptionBySessionAndArtifact.put(
                        adoptionKey(value.getSessionId(), value.getArtifactId()), value));

        Map<String, ResolvedContextRef> resolvedTargets = new HashMap<>();
        Map<Long, AssistantTargetViews.TargetEffect> result = new LinkedHashMap<>();
        for (AidRuntimeArtifact artifact : candidates) {
            AidRuntimeRun run = runById.get(artifact.getProducerRunId());
            AidAssistantSession session = run == null ? null : sessionById.get(run.getSessionId());
            if (!owns(userId, session, run)
                    || fixedSession != null && !Objects.equals(fixedSession.getId(), run.getSessionId())) continue;
            AidRuntimeArtifactAdoption adoption = adoptionBySessionAndArtifact.get(
                    adoptionKey(session.getId(), artifact.getId()));
            AssistantTargetViews.TargetEffect effect = projectArtifact(
                    userId, session, artifact, adoption, resolvedTargets);
            if (effect != null) result.put(artifact.getId(), effect);
        }
        return Map.copyOf(result);
    }

    @Override
    @Transactional(readOnly = true)
    public AssistantTargetViews.TargetEffect projectArtifact(Long userId, AidRuntimeArtifact artifact) {
        if (artifact == null) return null;
        return projectArtifacts(userId, List.of(artifact)).get(artifact.getId());
    }

    @Override
    @Transactional(readOnly = true)
    public AssistantTargetViews.EffectResolved resolve(Long userId, AssistantRequests.EffectResolve request) {
        AidAssistantSession session = requireSession(userId, request == null ? null : request.getSessionId());
        AssistantTargetViews.TargetEffect effect;
        if (request.getEffectId().startsWith("runtime-step:")) {
            Long stepId = positiveId(request.getEffectId().substring("runtime-step:".length()));
            AidRuntimeRunStep step = stepMapper.selectById(stepId);
            AidRuntimeRun run = step == null ? null : runMapper.selectById(step.getRunId());
            if (!owns(userId, session, run)) effect = null;
            else effect = projectStep(userId, session, run, step,
                    step.getApprovalId() == null ? null : approvalMapper.selectById(step.getApprovalId()),
                    new HashMap<>());
        } else {
            Long adoptionId = positiveId(request.getEffectId().substring("artifact-adoption:".length()));
            AidRuntimeArtifactAdoption adoption = adoptionMapper.selectById(adoptionId);
            AidRuntimeArtifact artifact = adoption == null ? null : artifactMapper.selectAuthorizedForSession(
                    userId, session.getId(), adoption.getArtifactId());
            effect = projectArtifact(userId, session, artifact, adoption, new HashMap<>());
        }
        AssistantTargetViews.EffectResolved result = new AssistantTargetViews.EffectResolved();
        result.setSessionId(String.valueOf(session.getId()));
        result.setEffectId(request.getEffectId());
        result.setObservedSessionRevision(value(session.getStateRevision()));
        result.setLastEventSeq(value(session.getLastEventSeq()));
        if (effect == null || !"AVAILABLE".equals(effect.getLocateState())) {
            result.setLocateState("UNAVAILABLE");
            result.setReasonCode("EFFECT_UNAVAILABLE");
            return result;
        }
        ResolvedContextRef current = resolveCurrent(userId, effect.getResultTargetRef());
        if (current == null) {
            result.setLocateState("UNAVAILABLE");
            result.setReasonCode("TARGET_UNAVAILABLE");
            return result;
        }
        result.setLocateState("AVAILABLE");
        result.setCurrentTargetRef(target(current.contextRef()));
        result.setAllowedActions(List.of("LOCATE"));
        return result;
    }

    private boolean canProjectStep(AidRuntimeRun run, AidRuntimeRunStep step) {
        return step != null && step.getId() != null && Objects.equals(step.getRunId(), run.getId())
                && "0".equals(step.getDelFlag()) && "SUCCEEDED".equals(step.getStatus())
                && "TOOL".equals(step.getStepKind()) && step.getApprovalId() != null
                && step.getToolCode() != null && step.getOutputJson() != null
                && toolProjectors.containsKey(step.getToolCode());
    }

    private AssistantTargetViews.TargetEffect projectStep(Long userId, AidAssistantSession session,
                                                          AidRuntimeRun run, AidRuntimeRunStep step,
                                                          AidRuntimeApproval approval,
                                                          Map<String, ResolvedContextRef> resolvedTargets) {
        if (!canProjectStep(run, step)) return null;
        AssistantRuntimeToolEffectProjector projector = toolProjectors.get(step.getToolCode());
        if (projector == null) return null;
        try {
            if (approval == null || !Objects.equals(approval.getRunId(), run.getId())
                    || !Objects.equals(approval.getUserId(), userId) || !"0".equals(approval.getDelFlag())
                    || !Set.of("ACCEPTED", "CONSUMED").contains(approval.getStatus())
                    || !Objects.equals(approval.getPlanRevision(), step.getPlanRevision())) return null;
            AssistantPlanExecutionScopeCodec.Persisted scope = scopeCodec.decode(approval.getScopeJson());
            if (!Objects.equals(scope.digest(), approval.getScopeDigest())
                    || !Objects.equals(scope.context().getString("digest"), approval.getContextDigest())
                    || !Objects.equals(scope.decision().getLong("observedPlanRevision"), step.getPlanRevision())
                    || !Objects.equals(scope.plan().getString("stableStepId"), step.getStableStepId())
                    || !Objects.equals(scope.plan().getInteger("position"), step.getPlanPosition())
                    || !"TOOL".equals(scope.plan().getString("executorKind"))
                    || !Objects.equals(scope.plan().getString("bindingCode"), step.getToolCode())
                    || !Objects.equals(scope.operationScope().getToolCode(), step.getToolCode())
                    || scope.tool() == null) return null;
            String contractVersion = scope.plan().getString("bindingVersion");
            String schemaDigest = scope.tool().getString("schemaDigest");
            toolRegistry.requirePinnedOutput(step.getToolCode(), contractVersion, schemaDigest, step.getOutputJson());
            List<String> refs = step.getOutputArtifactRef() == null ? List.of()
                    : JSON.parseArray(step.getOutputArtifactRef(), String.class);
            AssistantTargetEffectProjection projection = projector.project(new VerifiedRuntimeToolEffect(
                    userId, session.getId(), run.getId(), step.getId(), step.getIdempotencyKey(),
                    step.getToolCode(), contractVersion,
                    schemaDigest, scope.executionManifestJson(), scope.argumentsJson(),
                    step.getOutputJson(), refs == null ? List.of() : List.copyOf(refs), scope.preparedPayloadJson()));
            return effect("runtime-step:" + step.getId(), "RUNTIME_STEP", String.valueOf(step.getId()),
                    step.getToolCode(), null, projection, userId, resolvedTargets);
        } catch (RuntimeException invalid) {
            log.info("assistant effect omitted, stepId={}, reason={}", step.getId(), invalid.getClass().getSimpleName());
            return null;
        }
    }

    private AssistantTargetViews.TargetEffect projectArtifact(Long userId, AidAssistantSession session,
                                                              AidRuntimeArtifact artifact,
                                                              AidRuntimeArtifactAdoption supplied,
                                                              Map<String, ResolvedContextRef> resolvedTargets) {
        if (artifact == null || artifact.getId() == null || !"0".equals(artifact.getDelFlag())) return null;
        AidRuntimeArtifactAdoption adoption = supplied == null
                ? adoptionMapper.selectLatestSucceeded(userId, session.getId(), artifact.getId()) : supplied;
        if (adoption == null || !Objects.equals(adoption.getUserId(), userId)
                || !Objects.equals(adoption.getSessionId(), session.getId())
                || !Objects.equals(adoption.getArtifactId(), artifact.getId())
                || !"SUCCEEDED".equals(adoption.getStatus()) || !"0".equals(adoption.getDelFlag())
                || adoption.getReceiptJson() == null || adoption.getResultTargetRevision() == null) return null;
        AssistantArtifactAdoptionEffectProjector projector = adoptionProjectors.get(adoption.getTargetKind());
        if (projector == null) return null;
        try {
            AssistantTargetEffectProjection projection = projector.project(new VerifiedArtifactAdoptionEffect(
                    userId, session.getId(), session.getProjectId(), session.getWorkspaceId(),
                    artifact.getId(), adoption.getId(), adoption.getClientRequestId(), adoption.getRequestDigest(),
                    adoption.getTargetKind(),
                    adoption.getTargetObjectId(), adoption.getExpectedTargetRevision(),
                    adoption.getResultTargetRevision(), artifact.getContentHash(), adoption.getReceiptJson()));
            return effect("artifact-adoption:" + adoption.getId(), "ARTIFACT_ADOPTION",
                    String.valueOf(adoption.getId()), null, String.valueOf(artifact.getId()), projection, userId,
                    resolvedTargets);
        } catch (RuntimeException invalid) {
            log.info("assistant artifact effect omitted, adoptionId={}, reason={}", adoption.getId(),
                    invalid.getClass().getSimpleName());
            return null;
        }
    }

    private AssistantTargetViews.TargetEffect effect(String effectId, String sourceKind, String sourceId,
                                                     String toolCode, String artifactId,
                                                     AssistantTargetEffectProjection projection, Long userId,
                                                     Map<String, ResolvedContextRef> resolvedTargets) {
        if (projection == null || projection.targetIdentity() == null || projection.resultTargetRef() == null
                || projection.effectKind() == null) return null;
        AssistantTargetViews.TargetEffect result = new AssistantTargetViews.TargetEffect();
        result.setEffectId(effectId);
        result.setSourceKind(sourceKind);
        result.setSourceId(sourceId);
        result.setEffectKind(projection.effectKind());
        result.setToolCode(toolCode);
        result.setArtifactId(artifactId);
        ResolvedContextRef current = resolveIdentity(userId, projection.targetIdentity(), resolvedTargets);
        if (current == null) {
            result.setLocateState("UNAVAILABLE");
            result.setReasonCode("TARGET_UNAVAILABLE");
            return result;
        }
        result.setResultTargetRef(projection.resultTargetRef());
        result.setLocateState("AVAILABLE");
        result.setAllowedActions(List.of("LOCATE"));
        return result;
    }

    private ResolvedContextRef resolveCurrent(Long userId, AssistantTargetViews.TargetRef ref) {
        return resolveIdentity(userId, identity(ref), new HashMap<>());
    }

    private ResolvedContextRef resolveIdentity(Long userId, AssistantRequests.ContextRef identity) {
        return resolveIdentity(userId, identity, new HashMap<>());
    }

    private ResolvedContextRef resolveIdentity(Long userId, AssistantRequests.ContextRef identity,
                                               Map<String, ResolvedContextRef> resolvedTargets) {
        if (identity == null) return null;
        String key = JSON.toJSONString(identity);
        if (resolvedTargets.containsKey(key)) return resolvedTargets.get(key);
        ResolvedContextRef resolved = null;
        try {
            List<ResolvedContextRef> values = scopeAccess.resolveCurrentAll(userId, List.of(identity));
            if (values.size() == 1) resolved = values.get(0);
        } catch (RuntimeException denied) {
            // A revoked, deleted or otherwise unavailable target is deliberately not projected.
        }
        resolvedTargets.put(key, resolved);
        return resolved;
    }

    private static String adoptionKey(Long sessionId, Long artifactId) {
        return sessionId + ":" + artifactId;
    }

    private AssistantRequests.ContextRef identity(AssistantTargetViews.TargetRef ref) {
        AssistantRequests.ContextRef value = new AssistantRequests.ContextRef();
        value.setKind(ref.getKind());
        value.setProjectId(longValue(ref.getProjectId()));
        value.setEpisodeId(longValue(ref.getEpisodeId()));
        value.setWorkspaceId(longValue(ref.getWorkspaceId()));
        value.setObjectId(ref.getObjectId());
        value.setEntityType(ref.getEntityType());
        value.setEntityId(ref.getEntityId());
        value.setScriptId(longValue(ref.getScriptId()));
        value.setContentHash(ref.getContentHash());
        if (ref.getSelection() != null) {
            AssistantRequests.ScriptSelection selection = new AssistantRequests.ScriptSelection();
            selection.setStart(ref.getSelection().getStart());
            selection.setEnd(ref.getSelection().getEnd());
            selection.setSelectedTextHash(ref.getSelection().getSelectedTextHash());
            value.setSelection(selection);
        }
        return value;
    }

    private AssistantTargetViews.TargetRef target(AssistantRequests.ContextRef ref) {
        AssistantTargetViews.TargetRef value = new AssistantTargetViews.TargetRef();
        value.setKind(ref.getKind());
        value.setProjectId(string(ref.getProjectId()));
        value.setEpisodeId(string(ref.getEpisodeId()));
        value.setWorkspaceId(string(ref.getWorkspaceId()));
        value.setObjectId(ref.getObjectId());
        value.setVersion(ref.getVersion());
        value.setEntityType(ref.getEntityType());
        value.setEntityId(ref.getEntityId());
        value.setScriptId(string(ref.getScriptId()));
        value.setContentHash(ref.getContentHash());
        if (ref.getSelection() != null) {
            AssistantTargetViews.Selection selection = new AssistantTargetViews.Selection();
            selection.setStart(ref.getSelection().getStart());
            selection.setEnd(ref.getSelection().getEnd());
            selection.setSelectedTextHash(ref.getSelection().getSelectedTextHash());
            value.setSelection(selection);
        }
        return value;
    }

    private AidAssistantSession requireSession(Long userId, Long sessionId) {
        AidAssistantSession session = sessionMapper.selectOne(new LambdaQueryWrapper<AidAssistantSession>()
                .eq(AidAssistantSession::getId, sessionId).eq(AidAssistantSession::getUserId, userId)
                .eq(AidAssistantSession::getSessionType, "ASSISTANT")
                .eq(AidAssistantSession::getDelFlag, "0").last("LIMIT 1"));
        if (session == null) throw new AssistantServiceException(HttpStatus.NOT_FOUND.value(),
                "ASSISTANT_SESSION_NOT_FOUND", "\u52a9\u624b\u4f1a\u8bdd\u4e0d\u5b58\u5728", sessionId);
        return session;
    }

    private boolean owns(Long userId, AidAssistantSession session, AidRuntimeRun run) {
        return session != null && run != null && Objects.equals(session.getUserId(), userId)
                && Objects.equals(run.getUserId(), userId) && Objects.equals(run.getSessionId(), session.getId())
                && "ASSISTANT".equals(session.getSessionType()) && "0".equals(session.getDelFlag())
                && "0".equals(run.getDelFlag());
    }

    private static Map<String, AssistantRuntimeToolEffectProjector> uniqueToolProjectors(
            List<AssistantRuntimeToolEffectProjector> beans) {
        Map<String, AssistantRuntimeToolEffectProjector> result = new HashMap<>();
        for (AssistantRuntimeToolEffectProjector bean : beans == null ? List.<AssistantRuntimeToolEffectProjector>of() : beans) {
            if (bean == null || bean.supportedToolCodes() == null || bean.supportedToolCodes().isEmpty())
                throw new IllegalStateException("Assistant Tool effect projector has no codes");
            for (String code : bean.supportedToolCodes())
                if (code == null || result.putIfAbsent(code, bean) != null)
                    throw new IllegalStateException("Duplicate assistant Tool effect projector: " + code);
        }
        return Map.copyOf(result);
    }

    private static Map<String, AssistantArtifactAdoptionEffectProjector> uniqueAdoptionProjectors(
            List<AssistantArtifactAdoptionEffectProjector> beans) {
        Map<String, AssistantArtifactAdoptionEffectProjector> result = new HashMap<>();
        for (AssistantArtifactAdoptionEffectProjector bean : beans == null
                ? List.<AssistantArtifactAdoptionEffectProjector>of() : beans) {
            if (bean == null || bean.targetKind() == null || result.putIfAbsent(bean.targetKind(), bean) != null)
                throw new IllegalStateException("Duplicate assistant adoption effect projector");
        }
        return Map.copyOf(result);
    }

    private static Long positiveId(String value) {
        try {
            long id = Long.parseLong(value);
            return id > 0 ? id : null;
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static Long longValue(String value) {
        if (value == null) return null;
        try { return Long.valueOf(value); }
        catch (NumberFormatException invalid) { return null; }
    }

    private static String string(Long value) { return value == null ? null : String.valueOf(value); }
    private static long value(Long value) { return value == null ? 0L : value; }
}
