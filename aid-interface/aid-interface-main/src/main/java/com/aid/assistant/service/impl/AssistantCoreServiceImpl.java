package com.aid.assistant.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.aid.aid.domain.AidComicEpisode;
import com.aid.aid.domain.AidComicProject;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidComicEpisodeMapper;
import com.aid.aid.mapper.AidComicProjectMapper;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.assistant.domain.AidAssistantMessage;
import com.aid.assistant.domain.AidAssistantMessagePart;
import com.aid.assistant.domain.AidAssistantSession;
import com.aid.assistant.artifact.domain.AidRuntimeArtifact;
import com.aid.assistant.artifact.mapper.AidRuntimeArtifactMapper;
import com.aid.assistant.dto.AssistantRequests;
import com.aid.assistant.service.AssistantEntryTransport;
import com.aid.assistant.service.AssistantAutomaticTextExecution;
import com.aid.assistant.dto.AssistantExecutionRequests;
import com.aid.runtime.assistant.correction.AssistantDecisionCorrectionCommandNamespace;
import com.aid.assistant.effect.AssistantTargetEffectService;
import com.aid.assistant.error.AssistantServiceException;
import com.aid.assistant.mapper.AidAssistantMessageMapper;
import com.aid.assistant.mapper.AidAssistantMessagePartMapper;
import com.aid.assistant.mapper.AidAssistantSessionMapper;
import com.aid.assistant.scope.RuntimeScopeAccessRegistry;
import com.aid.assistant.scope.AssistantContextLimits;
import com.aid.assistant.scope.AssistantRuntimeContextManifestService;
import com.aid.assistant.creative.series.FlowScreenplaySourcePolicy;
import com.aid.assistant.scope.ResolvedContextRef;
import com.aid.assistant.realtime.AssistantRealtimeEventPublisher;
import com.alibaba.fastjson2.JSONObject;
import com.aid.assistant.service.AssistantCoreService;
import com.aid.assistant.service.AssistantMessageReferenceProjector;
import com.aid.assistant.service.AssistantControlTextPresentation;
import com.aid.assistant.service.AssistantQuestionControlPolicy;
import com.aid.assistant.service.AssistantImageSkillInvocationResolver;
import com.aid.assistant.service.AssistantSkillSelectionService;
import com.aid.assistant.vo.AssistantViews;
import com.aid.assistant.vo.AssistantExecutionViews;
import com.aid.assistant.vo.AssistantTargetViews;
import com.aid.common.error.TaskErrorCode;
import com.aid.common.error.TaskErrorPresentation;
import com.aid.common.error.TaskErrorResult;
import com.aid.common.error.TaskErrorSnapshot;
import com.aid.common.utils.DateUtils;
import com.aid.runtime.domain.AidRuntimeCommand;
import com.aid.runtime.domain.AidRuntimeEvent;
import com.aid.runtime.domain.AidRuntimeInputDraft;
import com.aid.runtime.domain.AidRuntimeInputRequest;
import com.aid.runtime.domain.AidRuntimeInputView;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.domain.RuntimeRunLifecycle;
import com.aid.runtime.domain.AidRuntimePlan;
import com.aid.runtime.domain.AidRuntimeApproval;
import com.aid.runtime.domain.AidRuntimeAgentToolRef;
import com.aid.runtime.domain.AidRuntimeRunStep;
import com.aid.runtime.mapper.AidRuntimeCommandMapper;
import com.aid.runtime.mapper.AidRuntimeEventMapper;
import com.aid.runtime.mapper.AidRuntimeInputDraftMapper;
import com.aid.runtime.mapper.AidRuntimeInputRequestMapper;
import com.aid.runtime.mapper.AidRuntimeInputViewMapper;
import com.aid.runtime.mapper.AidRuntimeRunMapper;
import com.aid.runtime.mapper.AidRuntimePlanMapper;
import com.aid.runtime.mapper.AidRuntimeApprovalMapper;
import com.aid.runtime.mapper.AidRuntimeAgentToolRefMapper;
import com.aid.runtime.mapper.AidRuntimeRunStepMapper;
import com.aid.runtime.assistant.AssistantApprovedSkillBindingProofService;
import com.aid.runtime.assistant.AssistantLoopBinding;
import com.aid.runtime.assistant.AssistantLoopExecutorRegistry;
import com.aid.runtime.assistant.AssistantModelReadinessException;
import com.aid.runtime.assistant.AssistantModuleAgentResolver;
import com.aid.runtime.assistant.SelectedSkillToolRoutePolicy;
import com.aid.runtime.assistant.SpatialPrevisGuidancePolicy;
import com.aid.runtime.assistant.execution.AssistantPlanInputArtifactResolver;
import com.aid.runtime.assistant.recovery.AssistantRuntimeDueRunWakeup;
import com.aid.runtime.service.RuntimeToolRegistry;
import com.aid.runtime.tool.RuntimeToolProvider;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.aid.assistant.service.AssistantQuiescentRunGuard;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
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
import java.math.RoundingMode;

/** Durable assistant core service. */
@Slf4j
@Service
public class AssistantCoreServiceImpl implements AssistantCoreService
{
    private static final String ACTIVE = "ACTIVE";
    private static final String WAITING_APPROVAL = "WAITING_APPROVAL";
    private static final String WAITING_INPUT = "WAITING_INPUT";
    private static final String REPLANNING_REQUIRED = "REPLANNING_REQUIRED";
    private static final String PAUSED = "PAUSED";
    private static final String UNIVERSAL = "UNIVERSAL";
    private static final String SCREENWRITER = "SCREENWRITER";
    private static final String SPATIAL_PREVIS = "SPATIAL_PREVIS";
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.aid.runtime.assistant.AssistantModuleExtensionRegistry moduleRegistry;
    @jakarta.annotation.Resource
    private com.aid.runtime.assistant.AssistantAgentModelReadinessService selectedTextReadiness;

    private List<String> exposedModules() { return moduleRegistry == null ? EXPOSED_MODULES : moduleRegistry.moduleCodes(); }
    private List<String> exposedModules(String transport) { return moduleRegistry == null ? EXPOSED_MODULES : moduleRegistry.moduleCodes(transport); }
    private static final List<String> EXPOSED_MODULES = List.of(UNIVERSAL, SCREENWRITER, SPATIAL_PREVIS);
    private static final List<String> DISCOVERABLE_MODULES = List.of(UNIVERSAL, SCREENWRITER, SPATIAL_PREVIS);
    private static final String MODULE_AGENT_UNAVAILABLE = "MODULE_AGENT_UNAVAILABLE";
    private static final String MODULE_EXECUTOR_UNAVAILABLE = "MODULE_EXECUTOR_UNAVAILABLE";
    private static final String SPATIAL_ENGINE_UNAVAILABLE = "SPATIAL_ENGINE_UNAVAILABLE";
    private static final String MODULE_AGENT_UNAVAILABLE_MESSAGE = "模块主助手未绑定或当前不可用";
    private static final String MODULE_EXECUTOR_UNAVAILABLE_MESSAGE = "模块执行器当前不可用";
    private static final String SPATIAL_ENGINE_UNAVAILABLE_MESSAGE = "空间预演引擎尚未接通";
    private static final int TASK_PROJECTION_BATCH_SIZE = 200;
    private static final int ACTIVITY_PROJECTION_LIMIT = 100;
    private static final int ACTIVITY_RUN_SCOPE_LIMIT = 200;
    private static final Set<String> PUBLIC_ACTIVITY_EVENT_TYPES = Set.of(
            "plan.created", "plan.approved", "approval.quoted", "approval.accepted",
            "task.created", "task.receipt_linked",
            "operation.step_changed", "operation.approval_required", "operation.approved",
            "decision.superseded", "decision.receipt_archived");
    private static final Set<String> PUBLIC_ACTIVITY_STATUSES = Set.of(
            "ACTIVE", "CREATED", "QUEUED", "PENDING", "PREPARING", "READY", "RUNNING",
            "WAITING_INPUT", "WAITING_APPROVAL", "AWAITING_APPROVAL", "REPLANNING_REQUIRED",
            "PAUSING", "PAUSED", "STOPPING", "UNKNOWN",
            "SUBMITTING", "SUBMITTED", "ACCEPTED", "INFLIGHT", "PROCESSING", "RECONCILING",
            "DECISION_READY", "QUOTED", "APPROVED", "AUTO_APPROVED", "CONSUMED", "SUSPENDED",
            "SUCCEEDED", "COMPLETED", "FAILED", "FAILED_PRETASK", "STOPPED", "CANCELLED",
            "CANCELED", "CANCELLED_PRETASK", "REJECTED", "EXPIRED");

    @Resource
    private AidAssistantSessionMapper sessionMapper;
    @Resource
    private AidAssistantMessageMapper messageMapper;
    @Resource
    private AidAssistantMessagePartMapper messagePartMapper;
    @Resource
    private AidRuntimeRunMapper runMapper;
    @Resource private com.aid.runtime.mapper.AidRuntimeReplanIntentMapper replanIntentMapper;
    @Resource private AssistantRuntimeDueRunWakeup dueRunWakeup;
    @Resource private AssistantQuiescentRunGuard quiescentRuns;
    @Resource private com.aid.billing.mapper.AidBillingAuthorizationReservationMapper replanReservations;
    @Resource private com.aid.runtime.assistant.replan.RuntimeReplanCancellationAuthorizationPort replanCancellationAuthorization;
    @Resource
    private AidRuntimePlanMapper planMapper;
    @Resource
    private AidRuntimeApprovalMapper approvalMapper;
    @Resource
    private AidRuntimeRunStepMapper runStepMapper;
    @Resource
    private AidRuntimeArtifactMapper artifactMapper;
    @Resource
    private AssistantPlanInputArtifactResolver planInputArtifacts;
    @Resource
    private AssistantTargetEffectService targetEffectService;
    @Resource
    private AidRuntimeInputRequestMapper inputRequestMapper;
    @Resource
    private AidRuntimeInputDraftMapper inputDraftMapper;
    @Resource
    private AidRuntimeInputViewMapper inputViewMapper;
    @Resource
    private AidRuntimeEventMapper eventMapper;
    @Resource
    private AssistantRealtimeEventPublisher realtimeEventPublisher;
    @Resource
    private AidRuntimeCommandMapper commandMapper;
    @Resource
    private AidComicProjectMapper projectMapper;
    @Resource
    private AidComicEpisodeMapper episodeMapper;
    @Resource
    private AidMediaTaskMapper mediaTaskMapper;
    @org.springframework.beans.factory.annotation.Autowired
    private com.aid.media.eta.MediaEtaService mediaEtaService;
    @org.springframework.beans.factory.annotation.Autowired
    private com.aid.media.service.MediaConcurrencyLimiter mediaConcurrencyLimiter;

    @Resource
    private RuntimeScopeAccessRegistry scopeAccessRegistry;
    @Resource
    private AssistantContextLimits contextLimits;
    @Resource
    private AssistantRuntimeContextManifestService contextManifests;
    @Resource
    private FlowScreenplaySourcePolicy flowScreenplaySources;
    @Resource
    private AssistantModuleAgentResolver moduleAgents;
    @Resource
    private AssistantSkillSelectionService skillSelections;
    @Resource
    private com.aid.assistant.service.AssistantModelSelectionService modelSelections;
    @Resource
    private com.aid.runtime.mapper.AssistantSkillDirectoryMapper skillDirectory;
    @Resource
    private AssistantImageSkillInvocationResolver imageSkills;
    @Resource
    private AssistantApprovedSkillBindingProofService approvedSkillBindings;
    @Resource
    private AidRuntimeAgentToolRefMapper agentToolRefs;
    @Resource
    private RuntimeToolRegistry runtimeTools;
    @Resource
    private AssistantLoopExecutorRegistry loopExecutors;
    @Autowired(required = false)
    private List<AssistantMessageReferenceProjector> messageReferenceProjectors = List.of();


    @Value("${assistant.realtime.enabled:true}")
    private boolean realtimeEnabled = true;

    @Override
    public AssistantViews.Capabilities capabilities(Long userId, AssistantRequests.Capabilities request)
    {
        requireTransport(request.getTransport());
        AssistantViews.Capabilities result = new AssistantViews.Capabilities();
        result.setTransport(request.getTransport());
        result.setModules(exposedModules(request.getTransport()));
        result.setScreenwriterModes(Arrays.asList("CRAFT", "ADAPT"));
        List<String> kinds = new ArrayList<>();
        for (String kind : Arrays.asList("FLOW_ENTITY", "SCRIPT", "FREE_CANVAS", "CANVAS_RESOURCE", "DOCUMENT", "PREVIZ_PROJECT", "PREVIZ_IMAGE", "PREVIZ_EDITOR"))
        {
            if (scopeAccessRegistry.supports(kind))
            {
                kinds.add(kind);
            }
        }
        result.setContextKinds(kinds);
        result.setFreeCanvasAvailable(kinds.contains("FREE_CANVAS"));
        Map<String, com.aid.runtime.domain.AssistantModuleDirectoryEntry> availableBindings = new LinkedHashMap<>();
        Set<String> duplicateModules = new HashSet<>();
        for (var metadata : skillDirectory.selectModules())
            if (availableBindings.putIfAbsent(metadata.getModuleCode(), metadata) != null)
                duplicateModules.add(metadata.getModuleCode());
        duplicateModules.forEach(availableBindings::remove);
        List<AssistantViews.ModuleCapability> moduleCapabilities = new ArrayList<>();
        boolean planningAvailable = false;
        boolean executionAvailable = false;
        for (String module : exposedModules(request.getTransport()))
        {
            AssistantViews.ModuleCapability capability;
            var metadata = availableBindings.get(module);
            if (metadata == null)
            {
                capability = unavailableModule(module, MODULE_AGENT_UNAVAILABLE,
                        MODULE_AGENT_UNAVAILABLE_MESSAGE);
            }
            else
            {
                capability = availableModule(module, loopExecutors.isAvailable(metadata.getProtocolVersion()));
            }
            capability.setEventStream(realtimeEnabled && ("CANVAS".equals(request.getTransport())
                    || FlowScreenplaySourcePolicy.supportsEntry(module, request.getTransport()) || moduleRegistry != null
                    && moduleRegistry.realtimeWebSocketPath(module, request.getTransport()) != null));
            moduleCapabilities.add(capability);
            if (exposedModules().contains(module))
            {
                planningAvailable |= capability.isPlanningAvailable();
                executionAvailable |= capability.isExecutionAvailable();
            }
        }
        result.setModuleCapabilities(moduleCapabilities);
        result.setPlanningAvailable(planningAvailable);
        result.setExecutionAvailable(executionAvailable);
        result.setEventStream(realtimeEnabled && moduleCapabilities.stream()
                .anyMatch(capability -> Boolean.TRUE.equals(capability.getEventStream())));
        result.setModelSelectionOnDemand("CANVAS".equals(request.getTransport()));
        result.setEventStreamProtocol(result.isEventStream() ? "assistant-realtime/v1" : null);
        var universal = availableBindings.get("UNIVERSAL");
        if (universal != null)
        {
            // Discovery is explicit and paginated; selecting a Skill validates that code only.
            result.setPlanningModelCode(universal.getDefaultModelCode());
        }
        return result;
    }

    private AssistantViews.ModuleCapability availableModule(String moduleCode, boolean executorAvailable)
    {
        AssistantViews.ModuleCapability result = new AssistantViews.ModuleCapability();
        result.setModuleCode(moduleCode);
        applyOperationMode(result, moduleCode);
        // Planning quote preparation calls AssistantLoopExecutor.prepareIteration, so executor
        // readiness is an admission dependency for both planning and subsequent execution.
        result.setPlanningAvailable(executorAvailable);
        result.setExecutionAvailable(executorAvailable);
        if (!executorAvailable)
        {
            result.setPlanningUnavailableReasonCode(MODULE_EXECUTOR_UNAVAILABLE);
            result.setPlanningUnavailableReason(MODULE_EXECUTOR_UNAVAILABLE_MESSAGE);
            result.setExecutionUnavailableReasonCode(MODULE_EXECUTOR_UNAVAILABLE);
            result.setExecutionUnavailableReason(MODULE_EXECUTOR_UNAVAILABLE_MESSAGE);
        }
        return result;
    }

    private AssistantViews.ModuleCapability unavailableModule(String moduleCode, String reasonCode, String reason)
    {
        AssistantViews.ModuleCapability result = new AssistantViews.ModuleCapability();
        result.setModuleCode(moduleCode);
        applyOperationMode(result, moduleCode);
        result.setPlanningAvailable(false);
        result.setExecutionAvailable(false);
        result.setPlanningUnavailableReasonCode(reasonCode);
        result.setPlanningUnavailableReason(reason);
        result.setExecutionUnavailableReasonCode(reasonCode);
        result.setExecutionUnavailableReason(reason);
        return result;
    }

    private void applyOperationMode(AssistantViews.ModuleCapability result, String moduleCode)
    {
        if (SPATIAL_PREVIS.equals(moduleCode))
        {
            result.setOperationMode(SpatialPrevisGuidancePolicy.OPERATION_MODE);
            result.setEngineAvailable(false);
            result.setEngineUnavailableReasonCode(SPATIAL_ENGINE_UNAVAILABLE);
            result.setEngineUnavailableReason(SPATIAL_ENGINE_UNAVAILABLE_MESSAGE);
            return;
        }
        result.setOperationMode("FULL");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AssistantViews.Session openSession(Long userId, AssistantRequests.SessionOpen request)
    {
        String entryTransport = AssistantEntryTransport.resolve(request.getTransport(), request.getWorkspaceId(), request.getModuleCode());
        validateModule(request.getModuleCode(), request.getMode());
        if (!exposedModules(entryTransport).contains(request.getModuleCode()))
            throw validation("transport", "当前助手不支持此入口");
        verifySessionScope(userId, request);
        String identityJson = sessionIdentityJson(request, requestedReasoningPreferences(request, null));
        AidAssistantSession existing = sessionMapper.selectOne(new LambdaQueryWrapper<AidAssistantSession>()
                .eq(AidAssistantSession::getUserId, userId)
                .eq(AidAssistantSession::getClientSessionId, request.getClientSessionId())
                .eq(AidAssistantSession::getSessionType, "ASSISTANT")
                .eq(AidAssistantSession::getDelFlag, "0")
                .last("LIMIT 1"));
        if (existing != null)
        {
            if (!sameSessionScope(existing.getBaseInputJson(), request)
                    || !entryTransport.equals(AssistantEntryTransport.of(existing)))
            {
                throw idempotencyConflict(request.getClientSessionId());
            }
            applyReasoningPreferences(existing, request, userId);
            return toSession(existing);
        }

        Date now = DateUtils.getNowDate();
        AidAssistantSession session = new AidAssistantSession();
        session.setUserId(userId);
        session.setProjectId(orZero(request.getProjectId()));
        session.setEpisodeId(orZero(request.getEpisodeId()));
        session.setWorkspaceId(request.getWorkspaceId());
        session.setNodeObjectId(request.getNodeObjectId());
        session.setSessionType("ASSISTANT");
        session.setEntryTransport(entryTransport);
        session.setClientSessionId(request.getClientSessionId());
        session.setModuleCode(request.getModuleCode());
        session.setMode(request.getMode());
        session.setTitle(request.getTitle().trim());
        session.setBaseInputJson(identityJson);
        ReasoningPreferences reasoning = requestedReasoningPreferences(request, null);
        session.setReasoningEnabled(reasoning.enabled());
        session.setReasoningLevel(reasoning.level());
        session.setShowReasoning(reasoning.show());
        session.setStateRevision(0L);
        session.setLastMessageSeq(0L);
        session.setLastEventSeq(0L);
        session.setFavorite(false);
        session.setStatus(ACTIVE);
        session.setLastActiveTime(now);
        session.setDelFlag("0");
        setCreateAudit(session, userId, now);
        try
        {
            sessionMapper.insert(session);
        }
        catch (DuplicateKeyException duplicate)
        {
            // A duplicate insert may follow an older REPEATABLE READ snapshot; use a current read.
            AidAssistantSession concurrent = sessionMapper.selectOne(new LambdaQueryWrapper<AidAssistantSession>()
                    .eq(AidAssistantSession::getUserId, userId)
                    .eq(AidAssistantSession::getClientSessionId, request.getClientSessionId())
                    .eq(AidAssistantSession::getSessionType, "ASSISTANT")
                    .eq(AidAssistantSession::getDelFlag, "0").last("LIMIT 1 FOR UPDATE"));
            if (concurrent == null || !sameSessionScope(concurrent.getBaseInputJson(), request)
                    || !entryTransport.equals(AssistantEntryTransport.of(concurrent)))
            {
                log.info("助手会话幂等冲突, userId={}, clientSessionId={}", userId, request.getClientSessionId());
                throw idempotencyConflict(request.getClientSessionId());
            }
            applyReasoningPreferences(concurrent, request, userId);
            return toSession(concurrent);
        }
        appendEvent(session, null, "session.opened", id(session.getId()), 0L,
                request.getClientSessionId(), sessionPayload(session), now, userId);
        return toSession(session);
    }

    @Override
    public AssistantViews.Page<AssistantViews.Session> pageSessions(Long userId,
                                                                     AssistantRequests.SessionPage request)
    {
        if (request.getNodeObjectId() != null && request.getWorkspaceId() == null)
            throw failure(HttpStatus.BAD_REQUEST, "INPUT_VALIDATION_FAILED", "节点会话必须指定工作区",
                    Map.of("field", "workspaceId", "reason", "NODE_WORKSPACE_REQUIRED"));
        int limit = request.getLimit() == null ? 20 : request.getLimit();
        LambdaQueryWrapper<AidAssistantSession> query = new LambdaQueryWrapper<AidAssistantSession>()
                .eq(AidAssistantSession::getUserId, userId)
                .eq(AidAssistantSession::getSessionType, "ASSISTANT")
                .eq(AidAssistantSession::getDelFlag, "0")
                .eq(StrUtil.isNotBlank(request.getModuleCode()), AidAssistantSession::getModuleCode,
                        request.getModuleCode())
                .eq(request.getWorkspaceId() != null, AidAssistantSession::getWorkspaceId, request.getWorkspaceId())
                .eq(request.getNodeObjectId() != null, AidAssistantSession::getNodeObjectId, request.getNodeObjectId())
                .isNull(request.getWorkspaceId() != null && request.getNodeObjectId() == null, AidAssistantSession::getNodeObjectId)
                .lt(request.getCursor() != null, AidAssistantSession::getId, request.getCursor())
                .orderByDesc(AidAssistantSession::getId);
        if ("PREVIZ_3DBOX".equals(request.getModuleCode()) && request.getPrevizProjectId() == null) throw validation("previzProjectId", "请指定三维工程");
        if (request.getPrevizProjectId() != null) {
            AssistantRequests.SessionOpen scope = new AssistantRequests.SessionOpen();
            scope.setModuleCode(request.getModuleCode()); scope.setPrevizProjectId(request.getPrevizProjectId());
            verifySessionScope(userId, scope);
            query.apply("JSON_UNQUOTE(JSON_EXTRACT(base_input_json, '$.previzProjectId')) = {0}", request.getPrevizProjectId());
        }
        if (request.getTransport() != null) {
            String transport = AssistantEntryTransport.require(request.getTransport());
            query.apply("COALESCE(entry_transport, CASE WHEN workspace_id > 0 OR module_code = 'SPATIAL_PREVIS' THEN 'CANVAS' ELSE 'MAIN' END) = {0}", transport);
        }
        IPage<AidAssistantSession> page = sessionMapper.selectPage(new Page<>(1, limit + 1, false), query);
        List<AidAssistantSession> records = page.getRecords();
        boolean more = records.size() > limit;
        if (more)
        {
            records = new ArrayList<>(records.subList(0, limit));
        }
        AssistantViews.Page<AssistantViews.Session> result = new AssistantViews.Page<>();
        result.setItems(records.stream().map(this::toSession).collect(Collectors.toList()));
        result.setNextCursor(more && !records.isEmpty() ? id(records.get(records.size() - 1).getId()) : null);
        return result;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AssistantViews.SessionDetail sessionDetail(Long userId, AssistantRequests.SessionDetail request)
    {
        AidAssistantSession session = requireSession(userId, request.getSessionId(), false);
        int limit = request.getMessageLimit() == null ? 50 : request.getMessageLimit();
        LambdaQueryWrapper<AidAssistantMessage> messageQuery = new LambdaQueryWrapper<AidAssistantMessage>()
                .eq(AidAssistantMessage::getSessionId, session.getId())
                .eq(AidAssistantMessage::getDelFlag, "0")
                .lt(request.getBeforeMessageSeq() != null, AidAssistantMessage::getSeqNo,
                        request.getBeforeMessageSeq())
                .orderByDesc(AidAssistantMessage::getSeqNo)
                .last("LIMIT " + (limit + 1));
        List<AidAssistantMessage> messages = messageMapper.selectList(messageQuery);
        boolean earlier = messages.size() > limit;
        if (earlier)
        {
            messages = new ArrayList<>(messages.subList(0, limit));
        }
        Collections.reverse(messages);
        Map<Long, List<AidAssistantMessagePart>> parts = loadParts(messages);
        Map<Long, List<AssistantViews.MessageReference>> references = loadMessageReferences(
                userId, session, messages);

        AssistantViews.SessionDetail result = new AssistantViews.SessionDetail();
        result.setSession(toSession(session));
        result.setMessages(messages.stream().map(item -> toMessage(item, parts.get(item.getId()),
                        references.get(item.getId())))
                .collect(Collectors.toList()));
        result.setHasEarlierMessages(earlier);
        result.setLastEventSeq(value(session.getLastEventSeq()));
        fillActivities(result, session, messages);
        fillInputHistory(result, session, userId, request.getInputHistoryLimit());
        fillConversationArtifacts(result, session, userId, request.getArtifactHistoryLimit());
        if (session.getActiveRuntimeRunId() != null)
        {
            AidRuntimeRun run = runMapper.selectById(session.getActiveRuntimeRunId());
            if (run != null && Objects.equals(run.getUserId(), userId))
            {
                result.setActiveRun(toRun(run, session));
                result.setInputRequest(loadActiveInput(userId, run));
                fillRuntimeSnapshot(result, session, run);
            }
        }
        return result;
    }

    private void fillActivities(AssistantViews.SessionDetail result, AidAssistantSession session,
                                List<AidAssistantMessage> messages)
    {
        List<AidRuntimeEvent> events = eventMapper.selectList(new LambdaQueryWrapper<AidRuntimeEvent>()
                .eq(AidRuntimeEvent::getSessionId, session.getId())
                .in(AidRuntimeEvent::getEventType, PUBLIC_ACTIVITY_EVENT_TYPES)
                .orderByDesc(AidRuntimeEvent::getSeq)
                .last("LIMIT " + (ACTIVITY_PROJECTION_LIMIT + 1)));
        boolean hasMore = events.size() > ACTIVITY_PROJECTION_LIMIT;
        if (hasMore)
        {
            events = new ArrayList<>(events.subList(0, ACTIVITY_PROJECTION_LIMIT));
        }
        Collections.reverse(events);

        Map<Long, JSONObject> payloads = new HashMap<>();
        Map<Long, Long> directStepIds = new HashMap<>();
        Map<Long, Long> mediaTaskIds = new HashMap<>();
        Map<Long, Long> approvalIds = new HashMap<>();
        Set<Long> stepIds = new LinkedHashSet<>();
        Set<Long> taskIds = new LinkedHashSet<>();
        Set<Long> referencedApprovalIds = new LinkedHashSet<>();
        for (AidRuntimeEvent event : events)
        {
            if (!PUBLIC_ACTIVITY_EVENT_TYPES.contains(event.getEventType())) continue;
            JSONObject payload = activityPayload(event.getPayloadJson());
            if (event.getId() != null && payload != null) payloads.put(event.getId(), payload);
            Long stepId = activityStepId(event, payload);
            if (event.getId() != null && stepId != null)
            {
                directStepIds.put(event.getId(), stepId);
                stepIds.add(stepId);
            }
            Long taskId = activityMediaTaskId(event, payload);
            if (event.getId() != null && taskId != null)
            {
                mediaTaskIds.put(event.getId(), taskId);
                taskIds.add(taskId);
            }
            Long approvalId = activityApprovalId(event, payload);
            if (event.getId() != null && approvalId != null)
            {
                approvalIds.put(event.getId(), approvalId);
                referencedApprovalIds.add(approvalId);
            }
        }

        Map<Long, AidRuntimeRunStep> stepsById = stepIds.isEmpty() ? Map.of()
                : runStepMapper.selectList(new LambdaQueryWrapper<AidRuntimeRunStep>()
                        .in(AidRuntimeRunStep::getId, stepIds)
                        .eq(AidRuntimeRunStep::getDelFlag, "0"))
                .stream().collect(Collectors.toMap(AidRuntimeRunStep::getId, Function.identity(),
                        (left, right) -> left, LinkedHashMap::new));
        Map<Long, List<AidRuntimeRunStep>> stepsByTask = taskIds.isEmpty() ? Map.of()
                : runStepMapper.selectList(new LambdaQueryWrapper<AidRuntimeRunStep>()
                        .in(AidRuntimeRunStep::getMediaTaskId, taskIds)
                        .eq(AidRuntimeRunStep::getDelFlag, "0"))
                .stream().collect(Collectors.groupingBy(AidRuntimeRunStep::getMediaTaskId,
                        LinkedHashMap::new, Collectors.toList()));
        Map<Long, List<AidRuntimeRunStep>> stepsByApproval = referencedApprovalIds.isEmpty() ? Map.of()
                : runStepMapper.selectList(new LambdaQueryWrapper<AidRuntimeRunStep>()
                        .in(AidRuntimeRunStep::getApprovalId, referencedApprovalIds)
                        .eq(AidRuntimeRunStep::getDelFlag, "0"))
                .stream().collect(Collectors.groupingBy(AidRuntimeRunStep::getApprovalId,
                        LinkedHashMap::new, Collectors.toList()));

        List<AssistantViews.Activity> activities = new ArrayList<>();
        for (AidRuntimeEvent event : events)
        {
            if (!PUBLIC_ACTIVITY_EVENT_TYPES.contains(event.getEventType())) continue;
            Long directStepId = directStepIds.get(event.getId());
            AidRuntimeRunStep step = directStepId == null ? null : stepsById.get(directStepId);
            if (step != null && !Objects.equals(step.getRunId(), event.getRunId())) step = null;
            if (step == null)
            {
                Long approvalId = approvalIds.get(event.getId());
                List<AidRuntimeRunStep> approvalSteps = approvalId == null ? List.of()
                        : stepsByApproval.getOrDefault(approvalId, List.of());
                step = approvalSteps.stream()
                        .filter(candidate -> Objects.equals(candidate.getRunId(), event.getRunId()))
                        .findFirst().orElse(null);
            }
            if (step == null)
            {
                Long mediaTaskId = mediaTaskIds.get(event.getId());
                List<AidRuntimeRunStep> taskSteps = mediaTaskId == null ? List.of()
                        : stepsByTask.getOrDefault(mediaTaskId, List.of());
                step = taskSteps.stream()
                        .filter(candidate -> Objects.equals(candidate.getRunId(), event.getRunId()))
                        .findFirst().orElse(null);
            }
            AssistantViews.Activity activity = toActivity(event, payloads.get(event.getId()), step);
            if (activity != null) activities.add(activity);
        }
        appendTerminalRunActivities(activities, session, events, messages);
        result.setActivities(activities);
        result.setActivitiesHasMore(hasMore);
    }

    /**
     * Historical activity events intentionally exclude the noisy run.state_changed stream.  Project
     * one stable terminal marker from the authoritative run rows referenced by the current message
     * and activity windows so a newer active run cannot make an older completed run look pending.
     */
    private void appendTerminalRunActivities(List<AssistantViews.Activity> activities,
                                             AidAssistantSession session,
                                             List<AidRuntimeEvent> events,
                                             List<AidAssistantMessage> messages)
    {
        LinkedHashSet<Long> runIds = new LinkedHashSet<>();
        for (AidRuntimeEvent event : events)
        {
            addActivityRunId(runIds, event.getRunId());
        }
        for (AidAssistantMessage message : messages)
        {
            addActivityRunId(runIds, message.getRuntimeRunId());
        }
        if (runIds.isEmpty()) return;

        List<AidRuntimeRun> terminalRuns = runMapper.selectList(new LambdaQueryWrapper<AidRuntimeRun>()
                .in(AidRuntimeRun::getId, runIds)
                .eq(AidRuntimeRun::getSessionId, session.getId())
                .eq(AidRuntimeRun::getUserId, session.getUserId())
                .eq(AidRuntimeRun::getDelFlag, "0")
                .in(AidRuntimeRun::getStatus, RuntimeRunLifecycle.TERMINAL_STATUSES));
        terminalRuns.sort(Comparator
                .comparing(this::terminalRunOccurredAt, Comparator.nullsFirst(Date::compareTo))
                .thenComparing(AidRuntimeRun::getId, Comparator.nullsFirst(Long::compareTo)));
        for (AidRuntimeRun run : terminalRuns)
        {
            AssistantViews.Activity terminal = terminalRunActivity(run);
            if (terminal != null) activities.add(terminal);
        }
    }

    private void addActivityRunId(Set<Long> runIds, Long runId)
    {
        if (runId != null && runId > 0 && runIds.size() < ACTIVITY_RUN_SCOPE_LIMIT)
        {
            runIds.add(runId);
        }
    }

    private AssistantViews.Activity terminalRunActivity(AidRuntimeRun run)
    {
        ActivityPresentation presentation = switch (Objects.toString(run.getStatus(), ""))
        {
            case "SUCCEEDED", "COMPLETED" ->
                    new ActivityPresentation("COMPLETED", "本轮处理已完成");
            case "FAILED" -> new ActivityPresentation("FAILED", "本轮处理失败");
            case "STOPPED", "CANCELLED", "CANCELED" ->
                    new ActivityPresentation("STOPPED", "本轮处理已停止");
            default -> null;
        };
        if (presentation == null || run.getId() == null) return null;
        long revision = value(run.getStateRevision());
        AssistantViews.Activity target = new AssistantViews.Activity();
        target.setActivityId("run-terminal:" + run.getId() + ":" + revision);
        target.setRunId(id(run.getId()));
        target.setStepKind("MAIN_AGENT");
        target.setActivityKind(presentation.kind());
        target.setSafeSummary(presentation.summary());
        target.setStatus(run.getStatus());
        target.setOccurredAt(format(terminalRunOccurredAt(run)));
        return target;
    }

    private Date terminalRunOccurredAt(AidRuntimeRun run)
    {
        return run.getUpdateTime() != null ? run.getUpdateTime() : run.getCreateTime();
    }

    private AssistantViews.Activity toActivity(AidRuntimeEvent event, JSONObject payload,
                                                AidRuntimeRunStep step)
    {
        if (event.getRunId() == null) return null;
        String status = publicActivityStatus(payload);
        ActivityPresentation presentation = activityPresentation(event.getEventType(), payload, step, status);
        if (presentation == null) return null;
        AssistantViews.Activity target = new AssistantViews.Activity();
        target.setActivityId(id(event.getId()));
        target.setRunId(id(event.getRunId()));
        target.setActivityKind(presentation.kind());
        target.setStatus(status);
        target.setSafeSummary(presentation.summary());
        target.setOccurredAt(format(event.getCreateTime()));
        target.setSourceEventSeq(event.getSeq());
        if (List.of("plan.created", "plan.approved").contains(event.getEventType()))
        {
            Long planId = positiveLong(event.getEntityId());
            if (planId != null)
            {
                target.setInteractionId(id(planId));
                target.setInteractionKind("PLAN");
                target.setStatus("plan.approved".equals(event.getEventType()) ? "APPROVED" : "WAITING_APPROVAL");
            }
        }
        if (List.of("operation.approval_required", "operation.approved").contains(event.getEventType()))
        {
            Long approvalId = positiveLong(event.getEntityId());
            if (approvalId != null && step != null && Objects.equals(approvalId, step.getApprovalId()))
            {
                target.setInteractionId(id(approvalId));
                target.setInteractionKind("OPERATION_APPROVAL");
                target.setStatus("operation.approved".equals(event.getEventType()) ? "APPROVED" : "WAITING_APPROVAL");
            }
        }
        if (step != null)
        {
            target.setOuterStepId(id(step.getId()));
            target.setStepKind(step.getStepKind());
            target.setOperationKind(step.getOperationKind());
            target.setToolCode(step.getToolCode());
            if (PUBLIC_ACTIVITY_STATUSES.contains(Objects.toString(step.getStatus(), "")))
            {
                target.setExecutionStatus(step.getStatus());
                target.setExecutionSummary(operationActivity(null, step, step.getStatus()).summary());
                target.setExecutionErrorCode(publicStepErrorCode(step.getErrorMessage()));
            }
            if ("ASSISTANT_SKILL".equals(step.getOperationKind()))
            {
                target.setSkillRunId(id(step.getChildRunId()));
            }
        }
        return target;
    }

    private JSONObject activityPayload(String payloadJson)
    {
        if (StrUtil.isBlank(payloadJson)) return null;
        try
        {
            Object parsed = JSON.parse(payloadJson);
            return parsed instanceof JSONObject object ? object : null;
        }
        catch (RuntimeException ignored)
        {
            return null;
        }
    }

    private Long activityStepId(AidRuntimeEvent event, JSONObject payload)
    {
        if (payload != null)
        {
            for (String field : List.of("outerStepId", "runtimeStepId", "childStepId", "stepId"))
            {
                Long value = positiveLong(payload.get(field));
                if (value != null) return value;
            }
        }
        String type = Objects.toString(event.getEventType(), "");
        if ("operation.step_changed".equals(type) || type.startsWith("decision."))
        {
            return positiveLong(event.getEntityId());
        }
        return null;
    }

    private Long activityMediaTaskId(AidRuntimeEvent event, JSONObject payload)
    {
        Long value = payload == null ? null : positiveLong(payload.get("mediaTaskId"));
        if (value == null && payload != null) value = positiveLong(payload.get("taskId"));
        if (value == null && Objects.toString(event.getEventType(), "").startsWith("task."))
        {
            value = positiveLong(event.getEntityId());
        }
        return value;
    }

    private Long activityApprovalId(AidRuntimeEvent event, JSONObject payload)
    {
        Long value = payload == null ? null : positiveLong(payload.get("approvalId"));
        String type = Objects.toString(event.getEventType(), "");
        if (value == null && ("approval.quoted".equals(type) || "approval.accepted".equals(type)
                || "operation.approval_required".equals(type) || "operation.approved".equals(type)))
        {
            value = positiveLong(event.getEntityId());
        }
        return value;
    }

    private Long positiveLong(Object raw)
    {
        if (raw == null) return null;
        try
        {
            long value = raw instanceof Number number ? number.longValue()
                    : Long.parseLong(String.valueOf(raw));
            return value > 0 ? value : null;
        }
        catch (RuntimeException ignored)
        {
            return null;
        }
    }

    private String publicActivityStatus(JSONObject payload)
    {
        String status = payload == null ? null : payload.getString("status");
        return status != null && PUBLIC_ACTIVITY_STATUSES.contains(status) ? status : null;
    }

    private ActivityPresentation activityPresentation(String eventType, JSONObject payload,
                                                      AidRuntimeRunStep step, String status)
    {
        return switch (Objects.toString(eventType, ""))
        {
            case "plan.created" ->
                    new ActivityPresentation("USER_APPROVAL_REQUIRED", "等待确认处理方案");
            case "plan.approved" ->
                    new ActivityPresentation("AGENT_RESPONDING", "处理方案已确认");
            case "approval.quoted" ->
                    new ActivityPresentation("QUOTE_READY", "报价已准备，等待确认");
            case "approval.accepted" ->
                    new ActivityPresentation("TOOL_PREPARING", "正在准备执行");
            case "operation.approval_required" -> new ActivityPresentation(
                    "USER_APPROVAL_REQUIRED", "等待确认" + activitySubject(step));
            case "operation.approved" -> new ActivityPresentation(
                    "TOOL_PREPARING", "已确认，正在准备" + activitySubject(step));
            case "task.created", "task.receipt_linked", "operation.step_changed" ->
                    operationActivity(payload, step, status);
            case "decision.superseded" ->
                    new ActivityPresentation("STOPPED", "上一轮回复处理已停止");
            case "decision.receipt_archived" ->
                    new ActivityPresentation("COMPLETED", "已整理本轮回复");
            default -> null;
        };
    }

    private ActivityPresentation operationActivity(JSONObject payload, AidRuntimeRunStep step, String status)
    {
        String subject = activitySubject(step);
        String acceptance = payload == null ? null : payload.getString("providerAcceptanceState");
        if ("UNKNOWN".equals(acceptance) || "RECONCILING".equals(status) || "UNKNOWN".equals(status))
        {
            return new ActivityPresentation("TOOL_RECONCILING", "正在核实" + subject + "状态");
        }
        if (status != null && List.of("SUCCEEDED", "COMPLETED", "DECISION_READY").contains(status))
        {
            return new ActivityPresentation("COMPLETED", completedActivitySummary(step));
        }
        if (status != null && List.of("FAILED", "FAILED_PRETASK", "REJECTED", "EXPIRED").contains(status))
        {
            return new ActivityPresentation("FAILED", subject + "处理失败");
        }
        if (status != null && List.of("STOPPED", "CANCELLED", "CANCELED", "CANCELLED_PRETASK",
                "PAUSED", "SUSPENDED").contains(status))
        {
            return new ActivityPresentation("STOPPED", subject + "处理已停止");
        }
        if (status != null && List.of("PAUSING", "STOPPING").contains(status))
        {
            return new ActivityPresentation("TOOL_RECONCILING", "正在收口" + subject);
        }
        if (status != null && List.of("WAITING_APPROVAL", "AWAITING_APPROVAL", "WAITING_INPUT",
                "REPLANNING_REQUIRED").contains(status))
        {
            return new ActivityPresentation("USER_APPROVAL_REQUIRED", "等待确认" + subject);
        }
        if (isMainActivity(step))
        {
            String summary = status != null && List.of("CREATED", "QUEUED", "PENDING").contains(status)
                    ? "回复任务已排队，等待执行" : "正在整理回复";
            return new ActivityPresentation("AGENT_RESPONDING", summary);
        }
        if (isPackageRead(step))
        {
            return new ActivityPresentation("PACKAGE_READING", "正在读取" + subject);
        }
        if (status != null && List.of("PREPARING", "READY", "CREATED").contains(status))
        {
            return new ActivityPresentation("TOOL_PREPARING", "正在准备" + subject);
        }
        if ("SUBMITTING".equals(status))
        {
            return new ActivityPresentation("TOOL_SUBMITTING", "正在提交" + subject);
        }
        if (status == null || List.of("SUBMITTED", "ACCEPTED", "QUEUED", "PENDING", "INFLIGHT", "RUNNING",
                "PROCESSING", "ACTIVE").contains(status))
        {
            return new ActivityPresentation("TOOL_RUNNING", "正在处理" + subject);
        }
        return null;
    }

    private boolean isMainActivity(AidRuntimeRunStep step)
    {
        return step != null && ("MAIN_AGENT".equals(step.getStepKind())
                || "ASSISTANT_DECISION".equals(step.getOperationKind()));
    }

    private boolean isPackageRead(AidRuntimeRunStep step)
    {
        String toolCode = step == null ? null : step.getToolCode();
        return toolCode != null && List.of("aid.skill.read-resource", "aid.agent.read-resource", "canvas.node.read",
                "canvas.project.read", "canvas.story.source.read", "canvas.story.artifact.read",
                "canvas.story.artifact.lines.read", "canvas.story.source.chapter.read",
                "canvas.story.source.episode.read", "canvas.story.source.pipeline.inspect",
                "canvas.story.project.inspect").contains(toolCode);
    }

    private String activitySubject(AidRuntimeRunStep step)
    {
        if (isMainActivity(step)) return "本轮回复";
        String toolCode = step == null ? null : step.getToolCode();
        if (toolCode == null) return step != null && "ASSISTANT_SKILL".equals(step.getOperationKind())
                ? "创作方法" : "当前操作";
        String providerSubject = runtimeTools == null ? null : runtimeTools.activitySubject(toolCode);
        if (providerSubject != null) return providerSubject;
        return switch (toolCode)
        {
            case "aid.skill.resolve-version" -> "创作方法版本";
            case "aid.skill.read-resource" -> "创作方法文件";
            case "aid.agent.read-resource" -> "助手指引";
            case "aid.model.query" -> "可用模型";
            case "aid.model.detail" -> "模型能力";
            case "canvas.node.read" -> "画布内容";
            case "canvas.project.read", "canvas.story.project.inspect" -> "项目信息";
            case "canvas.node.create_image", "canvas.node.create_image_with_settings",
                 "canvas.node.create_image_with_validated_settings",
                 "canvas.media.generate_image", "canvas.media.generate_image_with_parameters",
                 "media.image.generate" -> "画布图像";
            case "canvas.node.create_video", "canvas.node.create_video_with_settings",
                 "canvas.node.create_video_with_validated_settings",
                 "canvas.media.generate_video", "media.video.generate" -> "画布视频";
            case "canvas.node.create_audio", "canvas.media.generate_audio", "media.audio.generate" -> "画布音频";
            case "canvas.node.create_music", "canvas.media.generate_music", "media.music.generate" -> "画布音乐";
            case "canvas.node.create_text", "canvas.node.create_text_from_result", "creative.text.submit" -> "文本内容";
            case "canvas.node.patch" -> "画布节点";
            default -> toolCode.startsWith("canvas.story.") ? "故事工程"
                    : toolCode.startsWith("canvas.") ? "画布操作"
                    : toolCode.startsWith("creative.") ? "创作内容" : "当前操作";
        };
    }

    private String completedActivitySummary(AidRuntimeRunStep step)
    {
        if (isMainActivity(step)) return "已整理本轮回复";
        if (step != null && "aid.skill.resolve-version".equals(step.getToolCode()))
            return "已确认创作方法版本";
        if (step != null && "aid.skill.read-resource".equals(step.getToolCode()))
            return completedSkillResourceSummary(step.getOutputJson());
        if (step != null && ("aid.model.query".equals(step.getToolCode())
                || "aid.model.detail".equals(step.getToolCode())))
            return completedModelQuerySummary(step.getOutputJson());
        return activitySubject(step) + "处理完成";
    }

    private String completedModelQuerySummary(String outputJson)
    {
        if (StrUtil.isBlank(outputJson)) return "可用模型处理完成";
        try
        {
            JSONObject output = JSON.parseObject(outputJson);
            String operation = output == null ? null : output.getString("operation");
            if ("LIST".equals(operation)) return "已查询可用模型列表";
            if (!"DETAIL".equals(operation)) return "可用模型处理完成";
            JSONObject detail = output.getJSONObject("detail");
            return switch (detail == null ? "" : Objects.toString(detail.getString("generateMode"), ""))
            {
                case "image_to_image" -> "已核对图生图模型能力";
                case "text_to_image" -> "已核对文生图模型能力";
                case "text_to_video" -> "已核对文生视频模型能力";
                case "image_to_video" -> "已核对图生视频模型能力";
                case "text_to_audio" -> "已核对文本转音频模型能力";
                case "text_to_music" -> "已核对文本转音乐模型能力";
                default -> "已核对可用模型详情";
            };
        }
        catch (RuntimeException ignored)
        {
            return "可用模型处理完成";
        }
    }

    private String completedSkillResourceSummary(String outputJson)
    {
        if (StrUtil.isBlank(outputJson)) return "创作方法文件读取完成";
        try
        {
            JSONObject output = JSON.parseObject(outputJson);
            Object rawPath = output == null ? null : output.get("path");
            if (!(rawPath instanceof String path) || !safeSkillResourcePath(path))
                return "创作方法文件读取完成";
            if ("SKILL.md".equals(path)) return "已读取创作方法主文件";
            if ("system_prompt.md".equals(path)) return "已读取创作方法系统指引";
            return "已读取创作方法参考：" + path.substring("references/".length());
        }
        catch (RuntimeException ignored)
        {
            return "创作方法文件读取完成";
        }
    }

    private boolean safeSkillResourcePath(String path)
    {
        return "SKILL.md".equals(path) || "system_prompt.md".equals(path)
                || path != null && path.matches("references/[a-z0-9][a-z0-9._/-]{0,180}")
                && !path.contains("..") && !path.contains("//");
    }

    private record ActivityPresentation(String kind, String summary) { }

    private void fillRuntimeSnapshot(AssistantViews.SessionDetail result, AidAssistantSession session,
                                     AidRuntimeRun run)
    {
        AidRuntimePlan plan = planMapper.selectOne(new LambdaQueryWrapper<AidRuntimePlan>()
                .eq(AidRuntimePlan::getRunId, run.getId()).eq(AidRuntimePlan::getDelFlag, "0")
                .orderByDesc(AidRuntimePlan::getRevision).last("LIMIT 1"));
        if (plan != null)
        {
            AssistantViews.Plan projectedPlan = toPlan(run, plan,
                    "CANVAS".equals(AssistantEntryTransport.of(session)));
            if (!WAITING_APPROVAL.equals(run.getStatus())
                    || !"PLAN_EXECUTION_APPROVAL_REQUIRED".equals(run.getNextAction()))
                projectedPlan.setAllowedActions(new ArrayList<>());
            result.setPlan(projectedPlan);
        }
        Long totalSteps = runStepMapper.selectCount(new LambdaQueryWrapper<AidRuntimeRunStep>()
                .eq(AidRuntimeRunStep::getRunId, run.getId()).eq(AidRuntimeRunStep::getDelFlag, "0"));
        List<AidRuntimeRunStep> latestSteps = runStepMapper.selectList(new LambdaQueryWrapper<AidRuntimeRunStep>()
                .eq(AidRuntimeRunStep::getRunId, run.getId()).eq(AidRuntimeRunStep::getDelFlag, "0")
                .orderByDesc(AidRuntimeRunStep::getStepIndex).last("LIMIT 200"));
        List<AidRuntimeRunStep> activeSteps = runStepMapper.selectList(new LambdaQueryWrapper<AidRuntimeRunStep>()
                .eq(AidRuntimeRunStep::getRunId, run.getId()).eq(AidRuntimeRunStep::getDelFlag, "0")
                .in(AidRuntimeRunStep::getStatus, List.of("PREPARING", "AWAITING_APPROVAL", "READY",
                        "SUBMITTING", "INFLIGHT", "RUNNING", "RECONCILING", "DECISION_READY")));
        Map<Long, AidRuntimeRunStep> stepSnapshot = new LinkedHashMap<>();
        latestSteps.forEach(step -> stepSnapshot.put(step.getId(), step));
        activeSteps.forEach(step -> stepSnapshot.put(step.getId(), step));
        List<AidRuntimeRunStep> persistedSteps = new ArrayList<>(stepSnapshot.values());
        persistedSteps.sort(Comparator.comparing(AidRuntimeRunStep::getStepIndex,
                Comparator.nullsLast(Integer::compareTo)));
        Map<Long, List<AssistantTargetViews.TargetEffect>> stepEffects = targetEffectService.projectSteps(
                run.getUserId(), session, run, persistedSteps);
        Map<Long, AidMediaTask> stepTasks = loadAuthorizedStepTasks(run.getUserId(), persistedSteps);
        result.setSteps(persistedSteps.stream().map(step -> toStep(step, stepEffects.get(step.getId()),
                stepTasks.get(step.getId()), "PREVIZ_3DBOX".equals(session.getModuleCode()))).toList());
        result.setStepsHasMore(totalSteps != null && totalSteps > latestSteps.size());
        if (!stepTasks.isEmpty())
        {
            result.setTasks(persistedSteps.stream().filter(step -> stepTasks.containsKey(step.getId()))
                    .map(step -> toTask(stepTasks.get(step.getId()), step)).toList());
        }
        Long totalArtifacts = artifactMapper.selectCount(new LambdaQueryWrapper<AidRuntimeArtifact>()
                .eq(AidRuntimeArtifact::getProducerRunId, run.getId()).eq(AidRuntimeArtifact::getDelFlag, "0"));
        List<AidRuntimeArtifact> artifacts = artifactMapper.selectList(new LambdaQueryWrapper<AidRuntimeArtifact>()
                .eq(AidRuntimeArtifact::getProducerRunId, run.getId()).eq(AidRuntimeArtifact::getDelFlag, "0")
                .orderByDesc(AidRuntimeArtifact::getId).last("LIMIT 200"));
        Collections.reverse(artifacts);
        Map<Long, AssistantTargetViews.TargetEffect> artifactEffects = targetEffectService.projectArtifacts(
                run.getUserId(), session, artifacts);
        result.setArtifacts(artifacts.stream().map(artifact -> toArtifact(
                artifact, artifactEffects.get(artifact.getId()))).toList());
        result.setArtifactsHasMore(totalArtifacts != null && totalArtifacts > artifacts.size());
        AidRuntimeApproval pendingOperation = pendingOperationApproval(run,persistedSteps);
        AidRuntimeApproval approval = pendingOperation!=null ? pendingOperation : approvalMapper.selectOne(new LambdaQueryWrapper<AidRuntimeApproval>()
                .eq(AidRuntimeApproval::getRunId, run.getId())
                .in(AidRuntimeApproval::getStatus, List.of("QUOTED", "ACCEPTED"))
                .eq(AidRuntimeApproval::getDelFlag, "0").orderByDesc(AidRuntimeApproval::getId).last("LIMIT 1"));
        if (approval != null)
        {
            AidRuntimeRunStep step = persistedSteps.stream()
                    .filter(value -> Objects.equals(value.getApprovalId(), approval.getId())).findFirst().orElse(null);
            if (step == null)
            {
                step = runStepMapper.selectOne(new LambdaQueryWrapper<AidRuntimeRunStep>()
                        .eq(AidRuntimeRunStep::getRunId, run.getId())
                        .eq(AidRuntimeRunStep::getApprovalId, approval.getId())
                        .eq(AidRuntimeRunStep::getDelFlag, "0").orderByDesc(AidRuntimeRunStep::getId)
                        .last("LIMIT 1"));
            }
            AssistantViews.Approval projected=toApproval(approval,step);
            if(pendingOperation!=null) {
                boolean quotable=visibleOperationQuoteSource(run,step,approval);
                projected.setAllowedActions(quotable ? ("QUOTED".equals(approval.getStatus())
                        ? List.of("CONFIRM_APPROVAL","QUOTE_OPERATION") : List.of("QUOTE_OPERATION")) : List.of());
            }
            result.setApproval(projected);
        }
    }

    private void fillInputHistory(AssistantViews.SessionDetail result, AidAssistantSession session,
                                  Long userId, Integer requestedLimit)
    {
        int limit = requestedLimit == null ? 20 : requestedLimit;
        List<AidRuntimeInputRequest> inputs = inputRequestMapper.selectList(
                new LambdaQueryWrapper<AidRuntimeInputRequest>()
                        .inSql(AidRuntimeInputRequest::getRunId,
                                "SELECT id FROM aid_runtime_run WHERE session_id = " + session.getId()
                                        + " AND del_flag = '0'")
                        .eq(AidRuntimeInputRequest::getStatus, "SUBMITTED")
                        .orderByDesc(AidRuntimeInputRequest::getCreateTime)
                        .orderByDesc(AidRuntimeInputRequest::getId)
                        .last("LIMIT " + (limit + 1)));
        boolean more = inputs.size() > limit;
        if (more)
        {
            inputs = new ArrayList<>(inputs.subList(0, limit));
        }
        if (inputs.isEmpty())
        {
            result.setInputHistoryHasMore(false);
            return;
        }
        List<Long> requestIds = inputs.stream().map(AidRuntimeInputRequest::getId).toList();
        List<Long> runIds = inputs.stream().map(AidRuntimeInputRequest::getRunId).distinct().toList();
        Map<Long, AidRuntimeRun> runsById = runMapper.selectBatchIds(runIds).stream()
                .collect(Collectors.toMap(AidRuntimeRun::getId, Function.identity()));
        Map<Long, List<AidRuntimeInputDraft>> draftsByRequest = inputDraftMapper.selectList(
                        new LambdaQueryWrapper<AidRuntimeInputDraft>()
                                .in(AidRuntimeInputDraft::getInputRequestId, requestIds)
                                .eq(AidRuntimeInputDraft::getUserId, userId)
                                .orderByAsc(AidRuntimeInputDraft::getQuestionId))
                .stream().collect(Collectors.groupingBy(AidRuntimeInputDraft::getInputRequestId,
                        LinkedHashMap::new, Collectors.toList()));
        Collections.reverse(inputs);
        result.setInputHistory(inputs.stream().map(input -> toInputRequest(
                        input, runsById.get(input.getRunId()),
                        draftsByRequest.getOrDefault(input.getId(), Collections.emptyList())))
                .collect(Collectors.toList()));
        result.setInputHistoryHasMore(more);
    }

    private void fillConversationArtifacts(AssistantViews.SessionDetail result, AidAssistantSession session,
                                           Long userId, Integer requestedLimit)
    {
        int limit = requestedLimit == null ? 100 : requestedLimit;
        List<AidRuntimeArtifact> artifacts = artifactMapper.selectList(
                new LambdaQueryWrapper<AidRuntimeArtifact>()
                        .inSql(AidRuntimeArtifact::getProducerRunId,
                                "SELECT id FROM aid_runtime_run WHERE session_id = " + session.getId()
                                        + " AND del_flag = '0'")
                        .eq(AidRuntimeArtifact::getDelFlag, "0")
                        .orderByDesc(AidRuntimeArtifact::getId)
                        .last("LIMIT " + (limit + 1)));
        boolean more = artifacts.size() > limit;
        List<AidRuntimeArtifact> selected = more
                ? new ArrayList<>(artifacts.subList(0, limit)) : new ArrayList<>(artifacts);
        Collections.reverse(selected);
        Map<Long, AssistantTargetViews.TargetEffect> effects = targetEffectService.projectArtifacts(
                userId, session, selected);
        result.setConversationArtifacts(selected.stream()
                .map(artifact -> toArtifact(artifact, effects.get(artifact.getId())))
                .collect(Collectors.toList()));
        result.setConversationArtifactsHasMore(more);
    }

    private AssistantViews.Plan toPlan(AidRuntimeRun run, AidRuntimePlan source, boolean canvas)
    {
        AssistantViews.Plan result = new AssistantViews.Plan();
        result.setRevision(value(source.getRevision()));
        result.setStatus(source.getStatus());
        result.setDefinitionDigest(source.getDefinitionDigest());
        result.setContextDigest(source.getInputContextDigest());
        JSONObject definition = JSON.parseObject(source.getDefinitionJson());
        if (definition != null)
        {
            String goal = definition.getString("goal");
            result.setGoal(goal);
            result.setTitle(abbreviate(goal, 160));
            result.setSummary(abbreviate(goal, 500));
            result.setAssumptions(stringArray(definition.getJSONArray("assumptions")));
            result.setDeliverables(stringArray(definition.getJSONArray("deliverables")));
            List<AssistantViews.PlanCriterion> projectedCriteria = new ArrayList<>();
            if (definition.getJSONArray("completionCriteria") != null)
            {
                for (Object raw : definition.getJSONArray("completionCriteria"))
                {
                    if (!(raw instanceof JSONObject criterion)) continue;
                    AssistantViews.PlanCriterion projected = new AssistantViews.PlanCriterion();
                    projected.setCriterionId(criterion.getString("criterionId"));
                    projected.setCriterionKind(criterion.getString("criterionKind"));
                    projected.setDescription(criterion.getString("description"));
                    projectedCriteria.add(projected);
                }
            }
            result.setCompletionCriteria(projectedCriteria);
            List<AssistantViews.PlanStep> projectedSteps = new ArrayList<>();
            JSONArray rawSteps = definition.getJSONArray("steps");
            if (rawSteps != null)
            {
                Set<String> failedDependencies = canvas
                        ? planInputArtifacts.failedCanvasMediaDependents(run, source, rawSteps) : Set.of();
                for (Object raw : rawSteps)
                {
                    if (!(raw instanceof JSONObject step)) continue;
                    AssistantViews.PlanStep projected = new AssistantViews.PlanStep();
                    projected.setStableStepId(step.getString("stableStepId"));
                    projected.setPosition(step.getInteger("position"));
                    projected.setOperationKind(step.getString("operationKind"));
                    projected.setExecutorKind(step.getString("executorKind"));
                    projected.setBindingCode(step.getString("bindingCode"));
                    projected.setMaxAttempts(step.getInteger("maxAttempts"));
                    projected.setTitle(step.getString("bindingCode"));
                    projected.setSummary(step.getString("operationKind"));
                    projected.setDependsOn(stringArray(step.getJSONArray("dependsOn")));
                    projected.setInputArtifactRefs(stringArray(step.getJSONArray("inputArtifactRefs")));
                    List<AssistantViews.PlanInputArtifactBinding> bindings = new ArrayList<>();
                    JSONArray rawBindings = step.getJSONArray("inputArtifactBindings");
                    if (rawBindings != null)
                    {
                        for (Object rawBinding : rawBindings)
                        {
                            if (!(rawBinding instanceof JSONObject binding)) continue;
                            AssistantViews.PlanInputArtifactBinding projectedBinding =
                                    new AssistantViews.PlanInputArtifactBinding();
                            projectedBinding.setSourceStableStepId(binding.getString("sourceStableStepId"));
                            projectedBinding.setLogicalArtifactId(binding.getString("logicalArtifactId"));
                            projectedBinding.setArtifactType(binding.getString("artifactType"));
                            bindings.add(projectedBinding);
                        }
                    }
                    projected.setInputArtifactBindings(bindings);
                    if (failedDependencies.contains(projected.getStableStepId()))
                    {
                        projected.setSummary("前置任务失败，未提交");
                        projected.setInputsResolved(false);
                        projected.setInputResolutionErrorCode(AssistantPlanInputArtifactResolver.DEPENDENCY_FAILED);
                        projectedSteps.add(projected);
                        continue;
                    }
                    AssistantPlanInputArtifactResolver.Projection inputProjection =
                            planInputArtifacts.resolveForProjection(run, source, step);
                    AssistantPlanInputArtifactResolver.Resolution resolved = inputProjection.resolution();
                    projected.setResolvedInputArtifactRefs(resolved.inputArtifactRefs());
                    projected.setInputsResolved(resolved.inputsResolved());
                    projected.setInputResolutionErrorCode(inputProjection.inputResolutionErrorCode());
                    if (inputProjection.inputResolutionErrorCode() != null) result.setReplanRequired(true);
                    projectedSteps.add(projected);
                }
            }
            result.setSteps(projectedSteps);
        }
        if ("PROPOSED".equals(source.getStatus())) result.setAllowedActions(List.of("APPROVE_PLAN"));
        return result;
    }

    private AssistantViews.Step toStep(AidRuntimeRunStep source,
                                       List<AssistantTargetViews.TargetEffect> effects,
                                       AidMediaTask authorizedTask, boolean includeChronology)
    {
        AssistantViews.Step result = new AssistantViews.Step();
        result.setStepId(id(source.getId())); result.setStableStepId(source.getStableStepId());
        if (includeChronology) result.setCreatedAt(format(source.getCreateTime()));
        result.setPlanRevision(value(source.getPlanRevision())); result.setPosition(source.getPlanPosition());
        result.setAttemptNo(source.getAttemptNo() == null ? 0 : source.getAttemptNo());
        result.setStepKind(source.getStepKind()); result.setOperationKind(source.getOperationKind());
        result.setToolCode(source.getToolCode()); result.setStatus(source.getStatus());
        result.setApprovalId(id(source.getApprovalId())); result.setMediaTaskId(id(source.getMediaTaskId()));
        result.setProviderAcceptanceState(source.getProviderAcceptanceState());
        if (StrUtil.isNotBlank(source.getOutputArtifactRef()))
            result.setOutputArtifactRefs(JSON.parseArray(source.getOutputArtifactRef(), String.class));
        StepErrorProjection error = projectStepError(source.getErrorMessage(), authorizedTask, source);
        if (error != null)
        {
            result.setErrorCode(error.errorCode());
            result.setSafeMessage(error.safeMessage());
        }
        if (effects != null) result.setEffects(effects);
        return result;
    }

    private AssistantViews.Task toTask(AidMediaTask source, AidRuntimeRunStep step)
    {
        AssistantViews.Task result = new AssistantViews.Task();
        result.setTaskId(id(source.getId())); result.setStepId(id(step.getId()));
        String mediaType = source.getMediaType();
        if (("media.music.generate".equals(step.getToolCode()) || "canvas.media.generate_music".equals(step.getToolCode())) && "AUDIO".equals(mediaType)
                && Objects.equals(step.getMediaTaskId(), source.getId())
                && Objects.equals(source.getBizTaskId(), step.getId())
                && "ASSISTANT_RUNTIME_STEP".equals(source.getBizTaskType()))
            mediaType = "MUSIC";
        result.setMediaType(mediaType); result.setModelCode(source.getModelName());
        result.setStatus(source.getStatus()); result.setBillingStatus(source.getBillingStatus());
        result.setProgressSource(source.getProviderTaskId() == null ? "LOCAL_QUEUE" : "PROVIDER");
        result.setTitle(StrUtil.blankToDefault(step.getToolCode(), mediaType));
        if (List.of("FAILED", "CANCELLED", "CANCELED").contains(source.getStatus()))
        {
            TaskErrorResult snapshot = TaskErrorSnapshot.read(source.getErrorDetailJson());
            result.setErrorMessage(snapshot == null
                    ? TaskErrorPresentation.toUserMessage(source.getModelName(), source.getErrorMessage(), "生成未完成")
                    : snapshot.getUserMessage());
        }
        if (List.of("IMAGE", "VIDEO", "AUDIO", "COMPOSE").contains(source.getMediaType())
                && !List.of("SUCCEEDED", "FAILED", "CANCELLED", "CANCELED").contains(source.getStatus())
                && !"UNKNOWN".equals(step.getProviderAcceptanceState())) {
            Integer position = "QUEUED".equals(source.getStatus())
                    ? mediaConcurrencyLimiter.getQueuePosition(source.getUserId(), source.getId()) : null;
            result.setQueuePosition(position);
            result.setEta(mediaEtaService.estimateMediaTaskSummary(source, position));
        }
        result.setOutputDurationSeconds(source.getOutputDurationSeconds());
        return result;
    }

    private AssistantViews.Artifact toArtifact(AidRuntimeArtifact source)
    {
        return toArtifact(source, null);
    }

    private AssistantViews.Artifact toArtifact(AidRuntimeArtifact source,
                                               AssistantTargetViews.TargetEffect effect)
    {
        AssistantViews.Artifact result = new AssistantViews.Artifact();
        result.setArtifactId(id(source.getId()));
        result.setPresentationRole(com.aid.assistant.artifact.AssistantArtifactPresentation.role(source));
        result.setRef("artifact:" + source.getLogicalArtifactId() + ":" + source.getRevision());
        result.setType(source.getArtifactType()); result.setRevision(value(source.getRevision()));
        result.setStatus(source.getStatus()); result.setReviewStatus(source.getReviewStatus());
        result.setSummary(source.getSummary());
        result.setProducerRunId(id(source.getProducerRunId()));
        result.setProducerStepId(id(source.getProducerStepId()));
        result.setCreatedAt(format(source.getCreateTime()));
        result.setLatestTargetEffect(effect);
        return result;
    }

    private AssistantViews.Approval toApproval(AidRuntimeApproval source, AidRuntimeRunStep step)
    {
        AssistantViews.Approval result = "PLAN_EXECUTION".equals(source.getScopeKind())
                ? new com.aid.runtime.assistant.execution.AssistantOperationApprovalView() : new AssistantViews.Approval();
        result.setApprovalId(id(source.getId())); result.setApprovalRevision(value(source.getStateRevision()));
        result.setScopeKind(source.getScopeKind()); result.setStatus(source.getStatus());
        result.setPlanRevision(value(source.getPlanRevision())); result.setStepId(step == null ? null : id(step.getId()));
        result.setOperationKind(step == null ? null : step.getOperationKind());
        JSONObject scope = JSON.parseObject(source.getScopeJson());
        JSONObject plan = scope == null ? null : scope.getJSONObject("plan");
        JSONObject decision = scope == null ? null : scope.getJSONObject("decision");
        String bindingCode = plan == null ? null : plan.getString("bindingCode");
        String stableStepId = plan == null ? null : plan.getString("stableStepId");
        JSONObject operation = scope == null ? null : scope.getJSONObject("operationScope");
        result.setTitle(switch (StrUtil.nullToEmpty(operation == null ? null : operation.getString("operationKind"))) {
            case "GENERATE_IMAGE" -> "图片生成";
            case "GENERATE_VIDEO" -> "视频生成";
            case "GENERATE_AUDIO" -> "音频生成";
            case "GENERATE_MUSIC" -> "音乐生成";
            default -> StrUtil.isNotBlank(bindingCode) ? bindingCode : source.getScopeKind();
        });
        result.setSummary(StrUtil.isNotBlank(stableStepId) ? stableStepId
                : decision == null ? null : decision.getString("decisionId"));
        result.setTargetRefs(operation == null ? null : operation.get("targetRefs"));
        result.setToolCode(operation == null ? null : operation.getString("toolCode"));
        JSONObject skill = scope == null ? null : scope.getJSONObject("skill");
        result.setSkillCode(skill == null ? null : skill.getString("skillCode"));
        result.setModelCode(operation == null ? null : operation.getString("modelCode"));
        result.setCapabilityCodes(operation == null ? new ArrayList<>()
                : stringArray(operation.getJSONArray("capabilityCodes")));
        result.setMediaTypes(operation == null ? new ArrayList<>()
                : stringArray(operation.getJSONArray("mediaTypes")));
        result.setMaxCharge(source.getMaxCharge() == null ? "0.0000"
                : source.getMaxCharge().setScale(4, RoundingMode.UNNECESSARY).toPlainString());
        result.setQuoteId(source.getQuoteId());
        result.setQuotedUpperBound(result.getMaxCharge());
        result.setHardUpperBoundAvailable(source.getQuoteId() != null || source.getMaxCharge() != null
                && source.getMaxCharge().signum() == 0);
        result.setCurrencyUnit("BALANCE");
        result.setExpiresAt("PLAN_EXECUTION".equals(source.getScopeKind()) && source.getExpiresAt()!=null
                ? source.getExpiresAt().toInstant().toString() : format(source.getExpiresAt()));
        result.setEstimatedAmount(null);result.setPrecisionScale(4);
        if ("QUOTED".equals(source.getStatus())) result.setAllowedActions(List.of("CONFIRM_APPROVAL"));
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AssistantViews.MessageSent sendMessage(Long userId, AssistantRequests.MessageSend request)
    {
        String requestDigest = digest(request);
        AidRuntimeCommand replay = findCommand(userId, request.getSessionId(), request.getClientRequestId(),
                requestDigest);
        if (replay != null)
        {
            return replayResult(replay, AssistantViews.MessageSent.class);
        }

        AidAssistantSession session = requireSession(userId, request.getSessionId(), true);
        replay = findCommand(userId, request.getSessionId(), request.getClientRequestId(), requestDigest);
        if (replay != null)
        {
            return replayResult(replay, AssistantViews.MessageSent.class);
        }
        verifyPersistedSessionScope(userId, session);
        requireRevision(request.getExpectedSessionRevision(), value(session.getStateRevision()));
        if (session.getActiveRuntimeRunId() != null)
        {
            AidRuntimeRun active = requireRun(userId, session.getActiveRuntimeRunId(), true);
            if (!Objects.equals(active.getSessionId(), session.getId()))
                throw failure(HttpStatus.CONFLICT, "SNAPSHOT_REQUIRED", "\u8bf7\u5237\u65b0\u72b6\u6001",
                        runStateDetails(active, session));
            if (active != null && !RuntimeRunLifecycle.isTerminal(active.getStatus()))
            {
                if (request.getExpectedRunRevision() == null)
                    throw validation("expectedRunRevision",
                            "\u5411\u6d3b\u52a8\u8fd0\u884c\u8865\u5145\u6d88\u606f\u65f6\u5fc5\u987b\u63d0\u4f9b\u8fd0\u884c\u7248\u672c");
                requireRevision(request.getExpectedRunRevision(), value(active.getStateRevision()));
                verifyRunContextScope(userId, active);
                if (request.getCanvasBudget() != null)
                    throw validation("canvasBudget", "请通过助手设置修改本轮预算");
                if (request.getSelectedTextModelCode() != null) {
                    JSONObject pinnedText = JSON.parseObject(active.getRequestJson());
                    if (pinnedText == null || !Objects.equals(request.getSelectedTextModelCode(), pinnedText.getString("_selectedTextModelCode")))
                        throw validation("selectedTextModelCode", "当前运行已固定文本模型，请在本轮结束后选择");
                }

                if (request.getSelectedModels() != null && !request.getSelectedModels().isEmpty())
                {
                    JSONObject pinned = JSON.parseObject(active.getRequestJson());
                    JSONArray choices = pinned == null ? null : pinned.getJSONArray("_selectedModels");
                    if (choices == null || choices.size() != request.getSelectedModels().size()
                            || request.getSelectedModels().stream().anyMatch(choice -> choice == null || choices.stream().noneMatch(item -> {
                                JSONObject value = (JSONObject) item;
                                return Objects.equals(choice.getModelCode(), value.getString("modelCode"))
                                        && Objects.equals(choice.getModelType(), value.getString("modelType"));
                            }))) throw validation("selectedModels", "当前运行已固定模型，请在本次处理结束后重新选择");
                }
                if (StrUtil.isNotBlank(request.getSelectedSkillCode()))
                {
                    JSONObject pinnedRequest = JSON.parseObject(active.getRequestJson());
                    JSONObject pinnedSkill = pinnedRequest == null ? null : pinnedRequest.getJSONObject("_selectedSkill");
                    if (pinnedSkill == null || !request.getSelectedSkillCode().equals(pinnedSkill.getString("code")))
                        throw validation("selectedSkillCode", "当前运行已固定 Skill，请新建对话使用其他 Skill");
                }
                if (List.of("RUNNING", "PAUSING", "RECONCILING", PAUSED, WAITING_APPROVAL).contains(active.getStatus()))
                    return queueInterjection(userId, request, requestDigest, session, active);
                log.info("助手会话仍有活动运行, sessionId={}, runId={}", session.getId(), active.getId());
                throw failure(HttpStatus.CONFLICT, "SNAPSHOT_REQUIRED", "请刷新状态",
                        runStateDetails(active, session));
            }
        }
        AssistantRequests.CanvasBudget canvasBudget = request.getCanvasBudget();
        if (canvasBudget != null)
        {
            if (!"CANVAS".equals(AssistantEntryTransport.of(session)) || session.getWorkspaceId() == null)
                throw validation("canvasBudget", "仅画布助手可设置画布预算");
            if (new java.math.BigDecimal(canvasBudget.getPerCallMaxCharge())
                    .compareTo(new java.math.BigDecimal(canvasBudget.getAbsoluteMaxCharge())) > 0)
                throw validation("canvasBudget", "单次预算不能超过本轮总预算");
        }
        validateContextRefs(request.getContextRefs());
        if (session.getNodeObjectId() != null && (request.getContextRefs() == null
                || request.getContextRefs().stream().noneMatch(ref -> ref != null
                && "FREE_CANVAS".equals(ref.getKind())
                && Objects.equals(ref.getWorkspaceId(), session.getWorkspaceId())
                && Objects.equals(ref.getObjectId(), session.getNodeObjectId()))))
            throw validation("contextRefs", "节点会话必须引用当前已保存的原节点");
        AssistantRuntimeContextManifestService.ResolvedManifest manifest =
                contextManifests.resolveForCreate(userId, session, request.getContextRefs());
        JSONObject flowPin = null;
        if (FlowScreenplaySourcePolicy.applies(session)) {
            flowScreenplaySources.validateReferences(userId, session, manifest.resolvedContextRefs());
            flowPin = flowScreenplaySources.pin(userId, session, manifest.resolvedContextRefs());
        }
        AssistantLoopBinding binding;
        try
        {
            binding = moduleAgents.requireActive(session.getModuleCode());
            if (binding == null || binding.getAgentVersionId() == null
                    || StrUtil.isBlank(binding.getPackageDigest())
                    || !loopExecutors.isAvailable(binding.getProtocolVersion()))
            {
                throw new IllegalStateException("Assistant module binding is incomplete");
            }
        }
        catch (AssistantModelReadinessException unavailable)
        {
            throw failure(HttpStatus.UNPROCESSABLE_ENTITY, "MODULE_AGENT_UNAVAILABLE",
                    "\u4e3bAgent\u5f53\u524d\u4e0d\u53ef\u7528",
                    Map.of("moduleCode", session.getModuleCode(), "reason", unavailable.getReason()));
        }
        catch (RuntimeException unavailable)
        {
            throw failure(HttpStatus.UNPROCESSABLE_ENTITY, "MODULE_AGENT_UNAVAILABLE",
                    "\u4e3bAgent\u5f53\u524d\u4e0d\u53ef\u7528",
                    Map.of("moduleCode", session.getModuleCode()));
        }
        AssistantViews.SkillOption selectedSkill = skillSelections.requireSelection(
                binding, request.getSelectedSkillCode(), userId);
        if (request.getSelectedModels() != null && !request.getSelectedModels().isEmpty()
                && (!"CANVAS".equals(AssistantEntryTransport.of(session)) || !UNIVERSAL.equals(session.getModuleCode())))
            throw validation("selectedModels", "当前助手不支持选择图片或视频模型");
        JSONArray selectedModels = modelSelections.freeze(request.getSelectedModels());
        imageSkills.requireComposerSelection(userId, selectedSkill, selectedModels);
        if (request.getSelectedTextModelCode() != null) {
            if (!"PREVIZ_3DBOX".equals(session.getModuleCode())) throw validation("selectedTextModelCode", "当前入口不支持选择主文本模型");
            AssistantLoopBinding selectedBinding = com.aid.runtime.assistant.AssistantTextModelSelection.select(binding, request.getSelectedTextModelCode());
            var readiness = selectedTextReadiness.verifyFrozen(selectedBinding.getModelConfigJson(), selectedBinding.getDefinitionJson());
            if (!readiness.ready()) throw validation("selectedTextModelCode", readiness.reason());
        }

        AidRuntimeCommand command = startCommand(userId, session.getId(), request.getClientRequestId(),
                requestDigest, "MESSAGE_SEND");

        Date now = DateUtils.getNowDate();
        long messageSeq = value(session.getLastMessageSeq()) + 1;
        AidAssistantMessage message = new AidAssistantMessage();
        message.setSessionId(session.getId());
        message.setSeqNo(messageSeq);
        message.setClientMessageId(request.getClientRequestId());
        message.setRequestHash(requestDigest);
        message.setRole("USER");
        message.setStatus("PERSISTED");
        message.setMessageKind("USER_INPUT");
        message.setMediaType("TEXT");
        String joinedText = request.getParts().stream().map(AssistantRequests.MessagePart::getText)
                .collect(Collectors.joining("\n"));
        message.setContentText(joinedText.substring(0, Math.min(1000, joinedText.length())));
        message.setDelFlag("0");
        setCreateAudit(message, userId, now);
        messageMapper.insert(message);
        message.setTurnId(message.getId());

        AidRuntimeRun run = new AidRuntimeRun();
        run.setUserId(userId);
        run.setSessionId(session.getId());
        run.setTurnId(message.getId());
        run.setProjectId(zeroToNull(session.getProjectId()));
        run.setWorkspaceId(session.getWorkspaceId());
        // Node-scoped conversation identity lives on the session and pinned context refs.
        // Runtime nodeObjectId/nodeVersion are an exclusive-node execution claim, not a history filter.
        run.setClientRequestId(request.getClientRequestId());
        run.setRequestDigest(requestDigest);
        JSONObject persistedRequest = JSON.parseObject(JSON.toJSONString(request));
        if (moduleRegistry != null && moduleRegistry.automaticExecutionRequired(session.getModuleCode()))
            persistedRequest.put("executionPreference", "AUTO");
        boolean automaticText = AssistantAutomaticTextExecution.supports(session)
                || moduleRegistry != null && moduleRegistry.supportsConversationBudget(session.getModuleCode())
                    && "AUTO".equals(com.aid.assistant.service.AssistantExecutionMode.requested(persistedRequest));
        if (automaticText)
            persistedRequest.put(AssistantAutomaticTextExecution.FIELD, AssistantAutomaticTextExecution.VERSION);
        persistedRequest.put("contextRefs", manifest.contextRefs());
        JSONObject initialManifest = JSON.parseObject(manifest.manifestJson());
        persistedRequest.put("_contextManifest", initialManifest);
        persistedRequest.put("_messageContextManifest", JSON.parseObject(initialManifest.toJSONString()));
        persistedRequest.put("_messageContextDigest", manifest.digest());
        if (flowPin != null)
            persistedRequest.put(FlowScreenplaySourcePolicy.PIN, flowPin);
        JSONArray referenceSnapshots = messageReferenceSnapshots(
                userId, manifest.resolvedContextRefs(), request.getContextRefs());
        persistedRequest.put("_messageReferenceSnapshots", referenceSnapshots);
        if (selectedSkill != null) persistedRequest.put("_selectedSkill", selectedSkill);
        if (!selectedModels.isEmpty()) persistedRequest.put("_selectedModels", selectedModels);
        if (request.getSelectedTextModelCode() != null) persistedRequest.put("_selectedTextModelCode", request.getSelectedTextModelCode());
        run.setRequestJson(persistedRequest.toJSONString());
        run.setContextDigest(manifest.digest());
        run.setExecutorStrategy("ASSISTANT_LOOP");
        // Pin the immutable main-Agent identity when the turn is created. Later activation changes
        // must not alter planning, approval, recovery or replay semantics for this run.
        run.setVersionId(binding.getAgentVersionId());
        run.setVersionDigest(binding.getPackageDigest());
        run.setStateRevision(0L);
        run.setPlanRevision(0L);
        run.setStopRequested(false);
        run.setNextAction("INITIAL_PLANNING_REQUIRED");
        run.setFencingToken(0L);
        run.setStatus(WAITING_APPROVAL);
        run.setDelFlag("0");
        setCreateAudit(run, userId, now);
        runMapper.insert(run);

        message.setRuntimeRunId(run.getId());
        messageMapper.updateById(message);
        int partSeq = 1;
        for (AssistantRequests.MessagePart requestPart : request.getParts())
        {
            AidAssistantMessagePart part = new AidAssistantMessagePart();
            part.setMessageId(message.getId());
            part.setPartSeq(partSeq++);
            part.setPartType("TEXT");
            part.setSlotName("USER_TEXT");
            part.setTextContent(requestPart.getText());
            part.setContextScope("CURRENT_TURN");
            part.setDelFlag("0");
            setCreateAudit(part, userId, now);
            messagePartMapper.insert(part);
        }

        session.setActiveRuntimeRunId(run.getId());
        session.setLastMessageSeq(messageSeq);
        session.setStateRevision(value(session.getStateRevision()) + 1);
        session.setLastActiveTime(now);
        setUpdateAudit(session, userId, now);
        sessionMapper.updateById(session);
        appendEvent(session, run.getId(), "message.created", id(message.getId()), 1L,
                request.getClientRequestId(), messagePayload(message,
                        appendMessageChoices(session, eventMessageReferences(manifest.contextRefs(), parseMessageReferenceSnapshots(
                                referenceSnapshots, manifest.contextRefs().size())), persistedRequest)), now, userId);
        appendEvent(session, run.getId(), automaticText ? "run.state_changed" : "approval.required", id(run.getId()), 0L,
                request.getClientRequestId(), runPayload(run), now, userId);
        if (automaticText) dueRunWakeup.wakeAfterCommit(run.getId());

        AssistantViews.MessageSent result = new AssistantViews.MessageSent();
        result.setMessageId(id(message.getId()));
        result.setTurnId(id(message.getId()));
        result.setRunId(id(run.getId()));
        result.setSessionRevision(value(session.getStateRevision()));
        result.setRunRevision(value(run.getStateRevision()));
        result.setStatus(run.getStatus());
        result.setNextAction(run.getNextAction());
        result.setRequestDigest(requestDigest);
        result.setLastEventSeq(value(session.getLastEventSeq()));
        completeCommand(command, result, userId, now);
        return result;
    }

    private AssistantViews.MessageSent queueInterjection(Long userId, AssistantRequests.MessageSend request,
                                                          String requestDigest, AidAssistantSession session,
                                                          AidRuntimeRun run)
    {
        if (CollectionUtil.isNotEmpty(request.getContextRefs()))
            throw failure(HttpStatus.CONFLICT, "CONTEXT_REFRESH_REQUIRED",
                    "\u8fd0\u884c\u4e2d\u65b0\u4e0a\u4e0b\u6587\u9700\u5355\u72ec\u5237\u65b0", Map.of("runId", id(run.getId())));
        // RUNNING corrections must retire the old tool scope too. Never replace a plan while
        // a submitted operation or its money outcome is unresolved; reuse deferred reconciliation.
        var boundary = com.aid.assistant.service.AssistantInterjectionBoundaryPolicy.route(run.getStatus(),
                com.aid.assistant.service.AssistantInterjectionBoundaryPolicy.needsSettlementInspection(run.getStatus())
                        && requiresDeferredReplan(run));
        boolean deferred = boundary == com.aid.assistant.service.AssistantInterjectionBoundaryPolicy.Route.DEFERRED_REPLAN;
        boolean pausedReplanning = boundary == com.aid.assistant.service.AssistantInterjectionBoundaryPolicy.Route.PAUSED_REPLAN;
        boolean waitingReplanning = boundary == com.aid.assistant.service.AssistantInterjectionBoundaryPolicy.Route.QUIESCENT_REPLAN;
        boolean spatialReplanning = waitingReplanning && AssistantAutomaticTextExecution.supportsSpatialGuidance(session);
        Date now = DateUtils.getNowDate();
        if (waitingReplanning)
        {
            if (Boolean.TRUE.equals(run.getStopRequested())) throw AssistantQuiescentRunGuard.blocked();
            AssistantQuiescentRunGuard.Graph graph = quiescentRuns.inspect(userId, run.getId(), true);
            quiescentRuns.invalidateQuotes(userId, run.getId(), graph, "USER_REQUIREMENTS_CHANGED");
            planMapper.update(null, new LambdaUpdateWrapper<AidRuntimePlan>()
                    .eq(AidRuntimePlan::getRunId, run.getId()).eq(AidRuntimePlan::getRevision, value(run.getPlanRevision()))
                    .eq(AidRuntimePlan::getInputContextDigest, run.getContextDigest()).eq(AidRuntimePlan::getDelFlag, "0")
                    .in(AidRuntimePlan::getStatus, List.of("PROPOSED", "APPROVED"))
                    .set(AidRuntimePlan::getStatus, "SUPERSEDED").set(AidRuntimePlan::getUpdateBy, String.valueOf(userId))
                    .set(AidRuntimePlan::getUpdateTime, now));
        }
        if (pausedReplanning)
        {
            invalidatePlansAndApprovals(run, session, userId, now);
            staleActiveInputs(run.getId(), userId, now);
        }
        AidRuntimeCommand command = startCommand(userId, session.getId(), request.getClientRequestId(),
                requestDigest, "MESSAGE_INTERJECTION");
        long messageSeq = value(session.getLastMessageSeq()) + 1L;
        String joinedText = request.getParts().stream().map(AssistantRequests.MessagePart::getText)
                .collect(Collectors.joining("\n"));
        AidAssistantMessage message = new AidAssistantMessage();
        message.setSessionId(session.getId()); message.setRuntimeRunId(run.getId());
        message.setSeqNo(messageSeq); message.setClientMessageId(request.getClientRequestId());
        message.setRequestHash(requestDigest); message.setRole("USER"); message.setStatus("PERSISTED");
        message.setMessageKind(deferred ? "USER_INTERJECTION_PENDING" : "USER_INTERJECTION"); message.setMediaType("TEXT");
        message.setContentText(joinedText.substring(0, Math.min(1000, joinedText.length())));
        message.setDelFlag("0"); setCreateAudit(message, userId, now); messageMapper.insert(message);
        message.setTurnId(message.getId()); messageMapper.updateById(message);
        int partSeq = 1;
        for (AssistantRequests.MessagePart requestPart : request.getParts())
        {
            AidAssistantMessagePart part = new AidAssistantMessagePart();
            part.setMessageId(message.getId()); part.setPartSeq(partSeq++); part.setPartType("TEXT");
            part.setSlotName("USER_TEXT"); part.setTextContent(requestPart.getText());
            part.setContextScope("CURRENT_TURN"); part.setDelFlag("0");
            setCreateAudit(part, userId, now); messagePartMapper.insert(part);
        }
        if (deferred)
        {
            JSONObject pending = new JSONObject(); pending.put("messageId",message.getId());
            acceptDeferredReplan(run,session,command,"INTERJECTION",pending,userId,now);
        }
        if (pausedReplanning || waitingReplanning)
        {
            long previousRunRevision = value(run.getStateRevision());
            if (pausedReplanning) run.setResumeStatus(WAITING_APPROVAL);
            if (spatialReplanning)
            {
                JSONObject turn = JSON.parseObject(run.getRequestJson());
                turn.put(AssistantAutomaticTextExecution.FIELD, AssistantAutomaticTextExecution.VERSION);
                turn.put("executionPreference", "AUTO");
                run.setRequestJson(turn.toJSONString());
            }
            run.setNextAction(REPLANNING_REQUIRED);
            run.setStateRevision(previousRunRevision + 1L);
            run.setNextRetryAt(null);
            setUpdateAudit(run, userId, now);
            if (waitingReplanning)
            {
                if (runMapper.update(run, new LambdaUpdateWrapper<AidRuntimeRun>()
                        .eq(AidRuntimeRun::getId, run.getId()).eq(AidRuntimeRun::getStateRevision, previousRunRevision)) != 1)
                    throw AssistantQuiescentRunGuard.blocked();
            }
            else runMapper.updateById(run);
        }
        session.setLastMessageSeq(messageSeq); session.setStateRevision(value(session.getStateRevision()) + 1L);
        session.setLastActiveTime(now); setUpdateAudit(session, userId, now); sessionMapper.updateById(session);
        appendEvent(session, run.getId(), "message.queued", id(message.getId()), 1L,
                request.getClientRequestId(), messagePayload(message,
                        appendMessageChoices(session, List.of(), JSON.parseObject(run.getRequestJson()))), now, userId);
        if (pausedReplanning || waitingReplanning)
            appendEvent(session, run.getId(), "run.replanning_required", id(run.getId()),
                    value(run.getStateRevision()), request.getClientRequestId(), userReplanningPayload(run), now, userId);
        if (spatialReplanning) dueRunWakeup.wakeAfterCommit(run.getId());
        AssistantViews.MessageSent result = new AssistantViews.MessageSent();
        result.setMessageId(id(message.getId())); result.setTurnId(id(message.getId())); result.setRunId(id(run.getId()));
        result.setSessionRevision(value(session.getStateRevision())); result.setRunRevision(value(run.getStateRevision()));
        result.setStatus(run.getStatus()); result.setNextAction((pausedReplanning || waitingReplanning || deferred)
                ? run.getNextAction() : "QUEUED_FOR_NEXT_AGENT_TURN");
        result.setRequestDigest(requestDigest); result.setLastEventSeq(value(session.getLastEventSeq()));
        completeCommand(command, result, userId, now);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AssistantViews.ContextRefreshed refreshContext(Long userId, AssistantRequests.ContextRefresh request)
    {
        AidRuntimeRun discovered = requireRun(userId, request.getRunId(), false);
        String payloadDigest = digest(request);
        AidRuntimeCommand replay = findCommand(userId, discovered.getSessionId(), request.getClientRequestId(),
                payloadDigest);
        if (replay != null) return replayResult(replay, AssistantViews.ContextRefreshed.class);

        AidAssistantSession session = requireSession(userId, discovered.getSessionId(), true);
        AidRuntimeRun run = requireRun(userId, request.getRunId(), true);
        replay = findCommand(userId, session.getId(), request.getClientRequestId(), payloadDigest);
        if (replay != null) return replayResult(replay, AssistantViews.ContextRefreshed.class);
        if (!Objects.equals(session.getActiveRuntimeRunId(), run.getId()))
            throw failure(HttpStatus.CONFLICT, "SNAPSHOT_REQUIRED", "\u8bf7\u5237\u65b0\u72b6\u6001",
                    runStateDetails(run, session));
        requireRevision(request.getExpectedRunRevision(), value(run.getStateRevision()));
        if (!List.of(WAITING_APPROVAL, PAUSED).contains(run.getStatus()))
            throw failure(HttpStatus.CONFLICT, "CONTEXT_REFRESH_BLOCKED", "\u5f53\u524d\u72b6\u6001\u4e0d\u80fd\u5237\u65b0\u4e0a\u4e0b\u6587",
                    runStateDetails(run, session));
        AssistantRequests.ContextRefresh effectiveRequest = request;
        if (Boolean.TRUE.equals(request.getPreserveExistingTargets()))
        {
            if (request.getTargetRefs() == null || !request.getTargetRefs().isEmpty())
                throw validation("targetRefs", "保留原引用时不得同时替换引用目标");
            effectiveRequest = JSON.parseObject(JSON.toJSONString(request), AssistantRequests.ContextRefresh.class);
            effectiveRequest.setPreserveExistingTargets(false);
            effectiveRequest.setTargetRefs(existingRefreshIdentities(session, run));
        }
        validateRefreshIdentities(effectiveRequest.getTargetRefs());
        AssistantRuntimeContextManifestService.ResolvedManifest manifest =
                contextManifests.resolveCurrentForRefresh(userId, session, effectiveRequest.getTargetRefs());
        AidRuntimeCommand command = startCommand(userId, session.getId(), request.getClientRequestId(),
                payloadDigest, "CONTEXT_REFRESH");
        Date now = DateUtils.getNowDate();
        boolean paused = PAUSED.equals(run.getStatus());
        if (requiresDeferredReplan(run))
        {
            JSONObject pending = new JSONObject(); pending.put("request",effectiveRequest);
            acceptDeferredReplan(run,session,command,"CONTEXT_REFRESH",pending,userId,now);
            AssistantViews.ContextRefreshed result = new AssistantViews.ContextRefreshed();
            result.setRunId(id(run.getId()));result.setRunRevision(run.getStateRevision());result.setContextDigest(run.getContextDigest());
            result.setTargetRefs(JSON.parseArray(manifest.targetRefsJson()));result.setStatus("PAUSING");result.setNextAction(run.getNextAction());
            result.setLastEventSeq(value(session.getLastEventSeq()));completeCommand(command,result,userId,now);return result;
        }
        invalidatePlansAndApprovals(run, session, userId, now);
        staleActiveInputs(run.getId(), userId, now);
        JSONObject persisted = JSON.parseObject(run.getRequestJson());
        persisted.put("contextRefs", manifest.contextRefs());
        persisted.put("_contextManifest", JSON.parseObject(manifest.manifestJson()));
        run.setRequestJson(persisted.toJSONString());
        run.setContextDigest(manifest.digest());
        run.setStatus(paused ? PAUSED : WAITING_APPROVAL);
        if (paused) run.setResumeStatus(WAITING_APPROVAL);
        else
        {
            run.setPauseReason(null);
            run.setResumeStatus(null);
        }
        run.setNextAction(REPLANNING_REQUIRED);
        run.setStateRevision(value(run.getStateRevision()) + 1L);
        run.setNextRetryAt(null);
        setUpdateAudit(run, userId, now);
        runMapper.updateById(run);
        appendEvent(session, run.getId(), "context.refreshed", id(run.getId()), run.getStateRevision(),
                request.getClientRequestId(), Map.of("contextDigest", manifest.digest(),
                        "targetRefs", JSON.parseArray(manifest.targetRefsJson())), now, userId);
        AssistantViews.ContextRefreshed result = new AssistantViews.ContextRefreshed();
        result.setRunId(id(run.getId()));
        result.setRunRevision(value(run.getStateRevision()));
        result.setContextDigest(manifest.digest());
        result.setTargetRefs(JSON.parseArray(manifest.targetRefsJson()));
        result.setStatus(run.getStatus());
        result.setNextAction(run.getNextAction());
        result.setLastEventSeq(value(session.getLastEventSeq()));
        completeCommand(command, result, userId, now);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AssistantExecutionViews.PlanConfirmed confirmPlan(Long userId,
                                                              AssistantExecutionRequests.PlanConfirm request)
    {
        AidRuntimeRun discovered = requireRun(userId, request.getRunId(), false);
        String payloadDigest = digest(request);
        AidRuntimeCommand replay = findCommand(userId, discovered.getSessionId(), request.getClientRequestId(), payloadDigest);
        if (replay != null) return replayResult(replay, AssistantExecutionViews.PlanConfirmed.class);
        AidAssistantSession session = requireSession(userId, discovered.getSessionId(), true);
        AidRuntimeRun run = requireRun(userId, request.getRunId(), true);
        replay = findCommand(userId, session.getId(), request.getClientRequestId(), payloadDigest);
        if (replay != null) return replayResult(replay, AssistantExecutionViews.PlanConfirmed.class);
        if (!Objects.equals(session.getActiveRuntimeRunId(), run.getId()))
            throw failure(HttpStatus.CONFLICT, "SNAPSHOT_REQUIRED", "\u8bf7\u5237\u65b0\u72b6\u6001", runStateDetails(run, session));
        if (!WAITING_APPROVAL.equals(run.getStatus())
                || !"PLAN_EXECUTION_APPROVAL_REQUIRED".equals(run.getNextAction()))
            throw failure(HttpStatus.CONFLICT, "PLAN_NOT_CONFIRMABLE", "\u8ba1\u5212\u5f53\u524d\u4e0d\u53ef\u786e\u8ba4",
                    runStateDetails(run, session));
        requireRevision(request.getExpectedRunRevision(), value(run.getStateRevision()));
        verifyRunContextScope(userId, run);
        AidRuntimePlan plan = planMapper.selectOne(new LambdaQueryWrapper<AidRuntimePlan>()
                .eq(AidRuntimePlan::getRunId, run.getId()).eq(AidRuntimePlan::getRevision, request.getPlanRevision())
                .eq(AidRuntimePlan::getDelFlag, "0").last("FOR UPDATE"));
        if (plan == null || !"PROPOSED".equals(plan.getStatus())
                || !Objects.equals(plan.getDefinitionDigest(), request.getDefinitionDigest())
                || !Objects.equals(plan.getInputContextDigest(), run.getContextDigest())
                || !Objects.equals(plan.getAgentVersionId(), run.getVersionId())
                || !Objects.equals(run.getPlanRevision(), plan.getRevision()))
            throw failure(HttpStatus.CONFLICT, "PLAN_NOT_CONFIRMABLE", "\u8ba1\u5212\u5df2\u53d8\u66f4",
                    Map.of("planRevision", request.getPlanRevision()));
        requireSelectedImageRouteForConfirmation(run, plan);
        try
        {
            // This mutates only the locked run snapshot. The request_json proof, plan APPROVED
            // transition and run revision are committed together by this transaction.
            approvedSkillBindings.freezeForApproval(run, plan);
        }
        catch (com.aid.common.exception.ServiceException invalidBinding)
        {
            throw failure(HttpStatus.CONFLICT, invalidBinding.getMessage(),
                    "\u8ba1\u5212\u4e2d\u7684 Skill \u5df2\u53d8\u5316",
                    Map.of("planRevision", request.getPlanRevision()));
        }
        AidRuntimeCommand command = startCommand(userId, session.getId(), request.getClientRequestId(), payloadDigest,
                "PLAN_CONFIRM");
        Date now = DateUtils.getNowDate();
        plan.setStatus("APPROVED");
        setUpdateAudit(plan, userId, now);
        planMapper.updateById(plan);
        run.setStatus(WAITING_APPROVAL);
        run.setNextAction("CONTINUE_AGENT_REQUIRED");
        run.setStateRevision(value(run.getStateRevision()) + 1L);
        setUpdateAudit(run, userId, now);
        runMapper.updateById(run);
        appendEvent(session, run.getId(), "plan.approved", String.valueOf(plan.getId()), plan.getRevision(),
                request.getClientRequestId(), Map.of("planRevision", plan.getRevision(),
                        "definitionDigest", plan.getDefinitionDigest()), now, userId);
        AssistantExecutionViews.PlanConfirmed result = new AssistantExecutionViews.PlanConfirmed();
        result.setRunId(id(run.getId())); result.setRunRevision(value(run.getStateRevision()));
        result.setPlanRevision(value(plan.getRevision())); result.setPlanStatus(plan.getStatus());
        result.setRunStatus(run.getStatus()); result.setNextAction(run.getNextAction());
        result.setLastEventSeq(value(session.getLastEventSeq()));
        completeCommand(command, result, userId, now);
        dueRunWakeup.wakeAfterCommit(run.getId());
        return result;
    }

    private void requireSelectedImageRouteForConfirmation(AidRuntimeRun run, AidRuntimePlan plan)
    {
        SelectedSkillToolRoutePolicy.Requirement requirement =
                SelectedSkillToolRoutePolicy.resolveRequest(run.getRequestJson());
        if (!requirement.active()) return;
        AidRuntimeAgentToolRef published = agentToolRefs.selectOne(
                new LambdaQueryWrapper<AidRuntimeAgentToolRef>()
                        .eq(AidRuntimeAgentToolRef::getAgentVersionId, plan.getAgentVersionId())
                        .eq(AidRuntimeAgentToolRef::getToolCode,
                                AssistantImageSkillInvocationResolver.TOOL_CODE)
                        .last("LIMIT 1"));
        RuntimeToolRegistry.Descriptor descriptor = null;
        RuntimeToolProvider.BillingMediaType mediaType = null;
        if (runtimeTools.supports(AssistantImageSkillInvocationResolver.TOOL_CODE))
        {
            descriptor = runtimeTools.require(AssistantImageSkillInvocationResolver.TOOL_CODE);
            mediaType = runtimeTools.billingMediaType(AssistantImageSkillInvocationResolver.TOOL_CODE);
        }
        boolean requiredToolAvailable = SelectedSkillToolRoutePolicy.matchesRequiredToolBinding(
                published, descriptor, mediaType);
        JSONObject definition;
        try { definition = JSON.parseObject(plan.getDefinitionJson()); }
        catch (RuntimeException invalid)
        {
            throw selectedImageRouteFailure(SelectedSkillToolRoutePolicy.SELECTION_INVALID,
                    plan.getRevision());
        }
        try
        {
            SelectedSkillToolRoutePolicy.requirePlan(requirement, requiredToolAvailable,
                    definition == null ? null : definition.getJSONArray("steps"),
                    false, runtimeTools::billingMediaType);
            imageSkills.requirePinned(run.getId(), run.getUserId());
        }
        catch (com.aid.common.exception.ServiceException rejected)
        {
            String code = SelectedSkillToolRoutePolicy.ownedRejectionCode(rejected);
            if (code == null) throw rejected;
            throw selectedImageRouteFailure(code, plan.getRevision());
        }
    }

    private AssistantServiceException selectedImageRouteFailure(String code, Long planRevision)
    {
        return failure(HttpStatus.CONFLICT, code, "图片Skill计划不可确认",
                Map.of("planRevision", value(planRevision)));
    }

    private boolean requiresDeferredReplan(AidRuntimeRun run)
    {
        return runStepMapper.selectCount(new LambdaQueryWrapper<AidRuntimeRunStep>()
                .eq(AidRuntimeRunStep::getRunId, run.getId()).eq(AidRuntimeRunStep::getDelFlag, "0")
                .and(query -> query.in(AidRuntimeRunStep::getStatus,
                        List.of("SUBMITTING", "INFLIGHT", "RUNNING", "RECONCILING", "DECISION_READY"))
                        .or().eq(AidRuntimeRunStep::getProviderAcceptanceState, "UNKNOWN")
                        .or().nested(scheduled -> scheduled.in(AidRuntimeRunStep::getStatus,
                                List.of("PREPARING", "AWAITING_APPROVAL", "READY"))
                                .and(durable -> durable.isNotNull(AidRuntimeRunStep::getReservationId)
                                        .or().isNotNull(AidRuntimeRunStep::getMediaTaskId)
                                        .or().isNotNull(AidRuntimeRunStep::getChildRunId)
                                        .or().ne(AidRuntimeRunStep::getProviderAcceptanceState, "NOT_SUBMITTED"))))) > 0;
    }

    private void acceptDeferredReplan(AidRuntimeRun run, AidAssistantSession session, AidRuntimeCommand command,
                                      String type, JSONObject pending, Long userId, Date now)
    {
        if (replanIntentMapper.selectActiveByRunIdForUpdate(run.getId()) != null)
            throw failure(HttpStatus.CONFLICT, "REPLAN_ALREADY_PENDING", "请先核实当前调整", runStateDetails(run, session));
        com.aid.runtime.domain.AidRuntimeReplanIntent intent = new com.aid.runtime.domain.AidRuntimeReplanIntent();
        intent.setRunId(run.getId()); intent.setSessionId(session.getId()); intent.setActorId(userId);
        intent.setCommandId(command.getId()); intent.setClientRequestId(command.getClientRequestId());
        intent.setCommandPayloadDigest(command.getPayloadDigest()); intent.setIntentType(type);
        intent.setStatus("CANCEL_REQUESTED"); intent.setActiveSlot(1); intent.setStateRevision(0L);
        intent.setBaseRunRevision(value(run.getStateRevision())); intent.setBasePlanRevision(value(run.getPlanRevision()));
        intent.setBaseContextDigest(run.getContextDigest()); intent.setBaseRequestDigest(SecureUtil.sha256(run.getRequestJson()));
        intent.setPendingPayloadJson(pending.toJSONString()); intent.setPendingPayloadDigest(SecureUtil.sha256(intent.getPendingPayloadJson()));
        intent.setNextRetryAt(now); intent.setDelFlag("0"); setCreateAudit(intent,userId,now); replanIntentMapper.insert(intent);
        staleActiveInputs(run.getId(),userId,now);
        run.setStatus("PAUSING"); run.setPauseReason("USER_REQUEST"); run.setResumeStatus(WAITING_APPROVAL);
        run.setNextAction("CANCEL_FOR_REPLAN"); run.setNextRetryAt(now);
        run.setStateRevision(value(run.getStateRevision())+1); run.setFencingToken(value(run.getFencingToken())+1);
        setUpdateAudit(run,userId,now); runMapper.updateById(run);
        // Fence old workers immediately. Recovery will claim the existing run lease.
        runMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AidRuntimeRun>()
                .eq(AidRuntimeRun::getId,run.getId()).set(AidRuntimeRun::getLeaseOwner,null).set(AidRuntimeRun::getLeaseUntil,null));
        run.setLeaseOwner(null); run.setLeaseUntil(null);
        appendEvent(session,run.getId(),"run.replan_accepted",id(intent.getId()),0L,command.getClientRequestId(),
                Map.of("intentId",id(intent.getId()),"status","CANCEL_REQUESTED","nextAction",run.getNextAction()),now,userId);
        dueRunWakeup.wakeAfterCommit(run.getId());
    }

    @Override
    @Transactional(rollbackFor=Exception.class)
    public boolean completeDeferredReplan(Long runId, com.aid.runtime.assistant.RuntimeLease lease)
    {
        AidRuntimeRun discovered=runMapper.selectById(runId);
        if (discovered==null || lease==null) throw new IllegalArgumentException("Invalid replan identity");
        AidAssistantSession session=requireSession(discovered.getUserId(),discovered.getSessionId(),true);
        AidRuntimeRun run=requireRun(discovered.getUserId(),runId,true);
        verifyPersistedSessionScope(run.getUserId(),session);
        verifyRunContextScope(run.getUserId(),run);
        var intent=replanIntentMapper.selectActiveByRunIdForUpdate(runId);
        if (intent==null) return false;
        replanCancellationAuthorization.lockAndVerify(new com.aid.runtime.assistant.replan.RuntimeReplanCancellationAuthorizationPort.ReplanCancellationAuthorizationRequest(
                runId,session.getId(),run.getUserId(),lease.getOwner(),lease.getFencingToken(),intent.getId(),intent.getStateRevision(),intent.getCommandId()));
        // No context/input application until all reservations reached an authoritative money terminal.
        if (replanReservations.selectCount(com.baomidou.mybatisplus.core.toolkit.Wrappers.<com.aid.billing.domain.AidBillingAuthorizationReservation>lambdaQuery()
                .eq(com.aid.billing.domain.AidBillingAuthorizationReservation::getRuntimeRunId,runId)
                .eq(com.aid.billing.domain.AidBillingAuthorizationReservation::getDelFlag,"0")
                .notIn(com.aid.billing.domain.AidBillingAuthorizationReservation::getState,List.of("RELEASED","SETTLED","REFUNDED")))>0) return false;
        invalidatePlansAndApprovals(run,session,run.getUserId(),new Date());
        JSONObject pending=JSON.parseObject(intent.getPendingPayloadJson());
        AidRuntimeCommand command=commandMapper.selectById(intent.getCommandId());
        Date now=new Date();
        if ("INTERJECTION".equals(intent.getIntentType()))
        {
            AidAssistantMessage message=messageMapper.selectOne(new LambdaQueryWrapper<AidAssistantMessage>()
                    .eq(AidAssistantMessage::getId,pending.getLong("messageId")).eq(AidAssistantMessage::getSessionId,session.getId())
                    .eq(AidAssistantMessage::getRuntimeRunId,runId).eq(AidAssistantMessage::getDelFlag,"0").last("FOR UPDATE"));
            if (message==null || !"USER_INTERJECTION_PENDING".equals(message.getMessageKind())
                    || !Objects.equals(message.getClientMessageId(),intent.getClientRequestId())
                    || !Objects.equals(message.getRequestHash(),intent.getCommandPayloadDigest()))
                throw failure(HttpStatus.CONFLICT,"REPLAN_PAYLOAD_CHANGED","调整内容待核实",null);
            message.setMessageKind("USER_INTERJECTION"); setUpdateAudit(message,run.getUserId(),now); messageMapper.updateById(message);
            run.setStatus(PAUSED); run.setResumeStatus(WAITING_APPROVAL); run.setNextAction(REPLANNING_REQUIRED);
            run.setStateRevision(value(run.getStateRevision())+1); setUpdateAudit(run,run.getUserId(),now);runMapper.updateById(run);
            AssistantViews.MessageSent result=new AssistantViews.MessageSent();
            result.setMessageId(id(message.getId()));result.setTurnId(id(message.getTurnId()));result.setRunId(id(runId));
            result.setSessionRevision(value(session.getStateRevision()));result.setRunRevision(value(run.getStateRevision()));
            result.setStatus(PAUSED);result.setNextAction(REPLANNING_REQUIRED);result.setRequestDigest(intent.getCommandPayloadDigest());
            result.setLastEventSeq(value(session.getLastEventSeq()));completeCommand(command,result,run.getUserId(),now);
        }
        else if ("CONTEXT_REFRESH".equals(intent.getIntentType()))
        {
            AssistantRequests.ContextRefresh request=pending.getObject("request",AssistantRequests.ContextRefresh.class);
            // Re-resolve identities after reconciliation; edits during the wait use the real current state.
            var manifest=contextManifests.resolveCurrentForRefresh(run.getUserId(),session,request.getTargetRefs());
            JSONObject stored=JSON.parseObject(run.getRequestJson());stored.put("contextRefs",manifest.contextRefs());
            stored.put("_contextManifest",JSON.parseObject(manifest.manifestJson()));run.setRequestJson(stored.toJSONString());
            run.setContextDigest(manifest.digest());run.setStatus(PAUSED);run.setResumeStatus(WAITING_APPROVAL);run.setNextAction(REPLANNING_REQUIRED);
            run.setStateRevision(value(run.getStateRevision())+1);setUpdateAudit(run,run.getUserId(),now);runMapper.updateById(run);
            appendEvent(session,runId,"context.refreshed",id(runId),run.getStateRevision(),intent.getClientRequestId(),
                    Map.of("contextDigest",manifest.digest(),"targetRefs",JSON.parseArray(manifest.targetRefsJson())),now,run.getUserId());
            AssistantViews.ContextRefreshed result=new AssistantViews.ContextRefreshed();result.setRunId(id(runId));result.setRunRevision(run.getStateRevision());
            result.setContextDigest(manifest.digest());result.setTargetRefs(JSON.parseArray(manifest.targetRefsJson()));result.setStatus(PAUSED);
            result.setNextAction(REPLANNING_REQUIRED);result.setLastEventSeq(value(session.getLastEventSeq()));completeCommand(command,result,run.getUserId(),now);
        }
        else throw new IllegalStateException("Unknown pending replan type");
        runMapper.update(null,new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AidRuntimeRun>()
                .eq(AidRuntimeRun::getId,runId).set(AidRuntimeRun::getNextRetryAt,null));
        replanIntentMapper.update(null,new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<com.aid.runtime.domain.AidRuntimeReplanIntent>()
                .eq(com.aid.runtime.domain.AidRuntimeReplanIntent::getId,intent.getId())
                .eq(com.aid.runtime.domain.AidRuntimeReplanIntent::getStateRevision,intent.getStateRevision())
                .set(com.aid.runtime.domain.AidRuntimeReplanIntent::getStatus,"APPLIED")
                .set(com.aid.runtime.domain.AidRuntimeReplanIntent::getStateRevision,intent.getStateRevision()+1)
                .set(com.aid.runtime.domain.AidRuntimeReplanIntent::getActiveSlot,null)
                .set(com.aid.runtime.domain.AidRuntimeReplanIntent::getPendingPayloadJson,"{}")
                .set(com.aid.runtime.domain.AidRuntimeReplanIntent::getNextRetryAt,null)
                .set(com.aid.runtime.domain.AidRuntimeReplanIntent::getAppliedAt,now));
        appendEvent(session,runId,"run.replanning_required",id(runId),run.getStateRevision(),intent.getClientRequestId(),userReplanningPayload(run),now,run.getUserId());
        return true;
    }

    private void abandonDeferredReplan(AidRuntimeRun run, AidAssistantSession session, Long userId, Date now)
    {
        var intent=replanIntentMapper.selectActiveByRunIdForUpdate(run.getId());
        if (intent==null) return;
        if ("INTERJECTION".equals(intent.getIntentType())) {
            Long messageId=JSON.parseObject(intent.getPendingPayloadJson()).getLong("messageId");
            messageMapper.update(null,new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AidAssistantMessage>()
                    .eq(AidAssistantMessage::getId,messageId).eq(AidAssistantMessage::getRuntimeRunId,run.getId())
                    .eq(AidAssistantMessage::getMessageKind,"USER_INTERJECTION_PENDING").set(AidAssistantMessage::getMessageKind,"USER_INTERJECTION_CANCELLED"));
        }
        replanIntentMapper.update(null,new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<com.aid.runtime.domain.AidRuntimeReplanIntent>()
                .eq(com.aid.runtime.domain.AidRuntimeReplanIntent::getId,intent.getId())
                .set(com.aid.runtime.domain.AidRuntimeReplanIntent::getStatus,"CANCELLED")
                .set(com.aid.runtime.domain.AidRuntimeReplanIntent::getStateRevision,intent.getStateRevision()+1)
                .set(com.aid.runtime.domain.AidRuntimeReplanIntent::getActiveSlot,null)
                .set(com.aid.runtime.domain.AidRuntimeReplanIntent::getPendingPayloadJson,"{}")
                .set(com.aid.runtime.domain.AidRuntimeReplanIntent::getNextRetryAt,null));
        appendEvent(session,run.getId(),"run.replan_cancelled",id(intent.getId()),
                intent.getStateRevision()+1,intent.getClientRequestId(),Map.of("reason","USER_STOP"),now,userId);
    }

    private void invalidatePlansAndApprovals(AidRuntimeRun run, AidAssistantSession session,
                                             Long userId, Date now)
    {
        List<AidRuntimeRunStep> unsettled = runStepMapper.selectList(new LambdaQueryWrapper<AidRuntimeRunStep>()
                .eq(AidRuntimeRunStep::getRunId, run.getId())
                .and(value -> value.in(AidRuntimeRunStep::getStatus,
                                List.of("SUBMITTING", "INFLIGHT", "RUNNING", "RECONCILING", "DECISION_READY"))
                        .or().eq(AidRuntimeRunStep::getProviderAcceptanceState, "UNKNOWN"))
                .eq(AidRuntimeRunStep::getDelFlag, "0").last("FOR UPDATE"));
        if (!unsettled.isEmpty())
            throw failure(HttpStatus.CONFLICT, "REPLAN_RECONCILIATION_REQUIRED",
                    "\u5df2\u63d0\u4ea4\u4efb\u52a1\u5f85\u5bf9\u8d26",
                    replanningBlockDetails(run, session, unsettled));

        List<AidRuntimeRunStep> scheduled = runStepMapper.selectList(new LambdaQueryWrapper<AidRuntimeRunStep>()
                .eq(AidRuntimeRunStep::getRunId, run.getId())
                .in(AidRuntimeRunStep::getStatus, List.of("PREPARING", "AWAITING_APPROVAL", "READY"))
                .eq(AidRuntimeRunStep::getDelFlag, "0").last("FOR UPDATE"));
        List<AidRuntimeRunStep> durable = scheduled.stream()
                .filter(step -> step.getReservationId() != null || step.getMediaTaskId() != null
                        || step.getChildRunId() != null
                        || !"NOT_SUBMITTED".equals(step.getProviderAcceptanceState()))
                .toList();
        if (!durable.isEmpty())
            throw failure(HttpStatus.CONFLICT, "REPLAN_PENDING_SUBMISSION",
                    "\u5df2\u6392\u671f\u7684\u8c03\u7528\u9700\u5148\u5b89\u5168\u53d6\u6d88",
                    replanningBlockDetails(run, session, durable));
        Set<Long> invalidatedApprovalIds = scheduled.stream()
                .map(AidRuntimeRunStep::getApprovalId).filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (AidRuntimeRunStep step : scheduled)
        {
            step.setStatus("CANCELLED_PRETASK");
            step.setProviderAcceptanceState("NOT_SUBMITTED");
            step.setErrorMessage("USER_REQUIREMENTS_CHANGED");
            setUpdateAudit(step, userId, now);
            runStepMapper.updateById(step);
        }
        planMapper.selectList(new LambdaQueryWrapper<AidRuntimePlan>()
                        .eq(AidRuntimePlan::getRunId, run.getId())
                        .eq(AidRuntimePlan::getRevision, value(run.getPlanRevision()))
                        .eq(AidRuntimePlan::getInputContextDigest, run.getContextDigest())
                        .in(AidRuntimePlan::getStatus, List.of("PROPOSED", "APPROVED"))
                        .eq(AidRuntimePlan::getDelFlag, "0").last("FOR UPDATE"))
                .forEach(plan -> {
                    plan.setStatus("SUPERSEDED");
                    setUpdateAudit(plan, userId, now);
                    planMapper.updateById(plan);
                });
        invalidatedApprovalIds.addAll(approvalMapper.selectList(new LambdaQueryWrapper<AidRuntimeApproval>()
                .select(AidRuntimeApproval::getId).eq(AidRuntimeApproval::getRunId,run.getId())
                .eq(AidRuntimeApproval::getPlanRevision,value(run.getPlanRevision()))
                .eq(AidRuntimeApproval::getContextDigest,run.getContextDigest())
                .in(AidRuntimeApproval::getStatus,List.of("QUOTED","ACCEPTED")).eq(AidRuntimeApproval::getDelFlag,"0"))
                .stream().map(AidRuntimeApproval::getId).toList());
        if (!invalidatedApprovalIds.isEmpty())
        {
            approvalMapper.selectList(new LambdaQueryWrapper<AidRuntimeApproval>()
                            .in(AidRuntimeApproval::getId, invalidatedApprovalIds)
                            .eq(AidRuntimeApproval::getRunId, run.getId())
                            .eq(AidRuntimeApproval::getPlanRevision, value(run.getPlanRevision()))
                            .eq(AidRuntimeApproval::getContextDigest, run.getContextDigest())
                            .in(AidRuntimeApproval::getStatus, List.of("QUOTED", "ACCEPTED"))
                            .eq(AidRuntimeApproval::getDelFlag, "0").last("FOR UPDATE"))
                    .forEach(approval -> {
                        approval.setStatus("INVALIDATED");
                        approval.setInvalidatedAt(now);
                        approval.setStateRevision(value(approval.getStateRevision()) + 1L);
                        setUpdateAudit(approval, userId, now);
                        approvalMapper.updateById(approval);
                    });
        }
    }

    private void staleActiveInputs(Long runId, Long userId, Date now)
    {
        inputRequestMapper.selectList(new LambdaQueryWrapper<AidRuntimeInputRequest>()
                        .eq(AidRuntimeInputRequest::getRunId, runId)
                        .in(AidRuntimeInputRequest::getStatus, List.of("OPEN", "DRAFTING", "SUSPENDED"))
                        .apply("del_flag = '0'")
                        .last("FOR UPDATE"))
                .forEach(input -> {
                    input.setStatus("STALE");
                    setUpdateAudit(input, userId, now);
                    inputRequestMapper.updateById(input);
                });
    }

    private Map<String, Object> replanningBlockDetails(AidRuntimeRun run, AidAssistantSession session,
                                                        List<AidRuntimeRunStep> steps)
    {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("runId", id(run.getId()));
        details.put("runRevision", value(run.getStateRevision()));
        details.put("status", run.getStatus());
        details.put("nextAction", steps.stream().anyMatch(step ->
                        List.of("ASSISTANT_TOOL", "ASSISTANT_SKILL").contains(step.getOperationKind()))
                ? "RECONCILE_OPERATION" : "WAIT_TASK_RECONCILIATION");
        details.put("stepIds", steps.stream().map(item -> id(item.getId())).toList());
        details.put("lastEventSeq", value(session == null ? null : session.getLastEventSeq()));
        return details;
    }

    private List<AssistantRequests.ContextRef> existingRefreshIdentities(AidAssistantSession session, AidRuntimeRun run)
    {
        JSONObject persisted = JSON.parseObject(run.getRequestJson());
        if (persisted == null || !(persisted.get("contextRefs") instanceof JSONArray refs))
            throw failure(HttpStatus.CONFLICT, "CONTEXT_REFRESH_IDENTITY_UNAVAILABLE",
                    "原引用身份不可读取，请重新核对引用范围", id(run.getId()));
        List<AssistantRequests.ContextRef> result = new ArrayList<>();
        for (Object raw : refs)
        {
            if (!(raw instanceof JSONObject value)) throw validation("targetRefs", "原引用身份无效");
            AssistantRequests.ContextRef source = value.toJavaObject(AssistantRequests.ContextRef.class);
            // The immutable session scope supplies the workspace ref during resolution.
            if ("FREE_CANVAS".equals(source.getKind()) && source.getObjectId() == null
                    && Objects.equals(source.getWorkspaceId(), session.getWorkspaceId())) continue;
            AssistantRequests.ContextRef identity = new AssistantRequests.ContextRef();
            identity.setKind(source.getKind());
            identity.setWorkspaceId(source.getWorkspaceId());
            identity.setResourceId(source.getResourceId());
            identity.setObjectId(source.getObjectId());
            // CANVAS_RESOURCE projectId is server-resolved scope metadata, not part of the
            // refresh identity accepted from a client. Preserve only workspaceId/resourceId so
            // a server-persisted resource can pass the same strict refresh identity validation.
            if (!"CANVAS_RESOURCE".equals(source.getKind()))
            {
                identity.setProjectId(source.getProjectId());
            }
            identity.setEpisodeId(source.getEpisodeId());
            identity.setEntityType(source.getEntityType());
            identity.setEntityId(source.getEntityId());
            identity.setScriptId(source.getScriptId());
            identity.setDocumentId(source.getDocumentId());
            identity.setPrevizProjectId(source.getPrevizProjectId());
            identity.setSelection(source.getSelection());
            result.add(identity);
        }
        return result;
    }

    private void validateRefreshIdentities(List<AssistantRequests.ContextRef> refs)
    {
        contextLimits.checkRawRequestBudget(refs);
        if (refs == null) throw validation("targetRefs", "\u4e0a\u4e0b\u6587\u6570\u91cf\u65e0\u6548");
        for (AssistantRequests.ContextRef ref : refs)
        {
            if (ref == null || StrUtil.isBlank(ref.getKind())) throw validation("targetRefs", "\u4e0a\u4e0b\u6587\u6807\u8bc6\u65e0\u6548");
            if ("FREE_CANVAS".equals(ref.getKind()))
            {
                if (ref.getWorkspaceId() == null || ref.getResourceId() != null
                        || StrUtil.isNotBlank(ref.getVersion()))
                    throw validation("targetRefs", "\u753b\u5e03\u5237\u65b0\u53ea\u63a5\u53d7\u5bf9\u8c61\u8eab\u4efd");
            }
            else if ("CANVAS_RESOURCE".equals(ref.getKind()))
            {
                if (ref.getWorkspaceId() == null || ref.getWorkspaceId() < 1
                        || ref.getResourceId() == null || ref.getResourceId() < 1
                        || StrUtil.isNotBlank(ref.getVersion()) || StrUtil.isNotBlank(ref.getContentHash())
                        || StrUtil.isNotBlank(ref.getObjectId()) || ref.getProjectId() != null
                        || ref.getEpisodeId() != null || ref.getScriptId() != null
                        || ref.getDocumentId() != null || ref.getDocumentSessionId() != null
                        || StrUtil.isNotBlank(ref.getEntityId()) || StrUtil.isNotBlank(ref.getEntityType())
                        || ref.getSelection() != null)
                    throw validation("targetRefs", "画布资源身份无效");
            }
            else if ("DOCUMENT".equals(ref.getKind()))
            {
                if (ref.getDocumentId() == null || ref.getDocumentId() < 1 || StrUtil.isNotBlank(ref.getVersion())
                        || StrUtil.isNotBlank(ref.getContentHash()) || ref.getSelection() != null || ref.getScriptId() != null
                        || ref.getResourceId() != null || ref.getWorkspaceId() != null
                        || ref.getObjectId() != null || ref.getEpisodeId() != null
                        || ref.getEntityId() != null)
                    throw validation("targetRefs", "文档刷新只接受文档身份");
            }
            else if ("SCRIPT".equals(ref.getKind()))
            {
                if (ref.getScriptId() == null || ref.getResourceId() != null
                        || StrUtil.isNotBlank(ref.getContentHash()) || StrUtil.isNotBlank(ref.getVersion()))
                    throw validation("targetRefs", "\u811a\u672c\u5237\u65b0\u53ea\u63a5\u53d7\u811a\u672c\u8eab\u4efd");
            }
            else if ("FLOW_ENTITY".equals(ref.getKind()))
            {
                if (ref.getProjectId() == null || ref.getResourceId() != null
                        || StrUtil.isBlank(ref.getEntityType())
                        || StrUtil.isBlank(ref.getEntityId()) || StrUtil.isNotBlank(ref.getVersion()))
                    throw validation("targetRefs", "\u6d41\u7a0b\u5bf9\u8c61\u6807\u8bc6\u65e0\u6548");
            }
            else if ("PREVIZ_IMAGE".equals(ref.getKind()) || "PREVIZ_EDITOR".equals(ref.getKind()))
            {
                if (StrUtil.isBlank(ref.getPrevizProjectId()) || StrUtil.isBlank(ref.getObjectId())
                        || !Objects.equals(ref.getObjectId(), ref.getContentHash()))
                    throw validation("targetRefs", "三维参考图刷新需要完整图片身份");
            }
            else if ("PREVIZ_PROJECT".equals(ref.getKind()))
            {
                if (StrUtil.isBlank(ref.getPrevizProjectId()) || ref.getSourceVersion() != null)
                    throw validation("targetRefs", "三维工程刷新只接受项目身份");
            }
            else throw validation("targetRefs", "\u4e0a\u4e0b\u6587\u7c7b\u578b\u65e0\u6548");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AssistantViews.InputRequest requestInput(Long userId, AssistantRequests.InputRequestCreate request)
    {
        request.setQuestions(AssistantQuestionControlPolicy.normalizeForPersistence(request.getQuestions()));
        validateQuestions(request.getQuestions());
        AidRuntimeRun discovered = requireRun(userId, request.getRunId(), false);
        AidAssistantSession session = requireSession(userId, discovered.getSessionId(), true);
        AidRuntimeRun run = requireRun(userId, request.getRunId(), true);
        verifyPersistedSessionScope(userId, session);
        verifyRunContextScope(userId, run);
        AidRuntimeInputRequest existing = inputRequestMapper.selectOne(
                new LambdaQueryWrapper<AidRuntimeInputRequest>()
                        .eq(AidRuntimeInputRequest::getRunId, run.getId())
                        .eq(AidRuntimeInputRequest::getRequestKey, request.getRequestKey()).last("LIMIT 1"));
        String bundleJson = JSON.toJSONString(request.getQuestions());
        String schemaDigest = SecureUtil.sha256(bundleJson);
        if (existing != null)
        {
            if (!Objects.equals(schemaDigest, existing.getSchemaDigest())
                    || !Objects.equals(request.getContextDigest(), existing.getContextDigest()))
            {
                throw idempotencyConflict(request.getRequestKey());
            }
            return toInputRequest(userId, existing);
        }
        requireRevision(request.getExpectedRunRevision(), value(run.getStateRevision()));
        if (!"RUNNING".equals(run.getStatus()) || !Objects.equals(session.getActiveRuntimeRunId(), run.getId()))
        {
            throw failure(HttpStatus.CONFLICT, "RUN_NOT_PAUSABLE", "运行不可提问", run.getStatus());
        }
        AidRuntimeInputRequest activeInput = inputRequestMapper.selectOne(
                new LambdaQueryWrapper<AidRuntimeInputRequest>()
                        .eq(AidRuntimeInputRequest::getRunId, run.getId())
                        .in(AidRuntimeInputRequest::getStatus, Arrays.asList("OPEN", "DRAFTING", "SUSPENDED"))
                        .orderByDesc(AidRuntimeInputRequest::getRoundNo)
                        .last("LIMIT 1 FOR UPDATE"));
        if (activeInput != null)
        {
            throw failure(HttpStatus.CONFLICT, "INPUT_REQUEST_ACTIVE", "\u5df2\u6709\u5f85\u7b54\u95ee\u9898",
                    inputStateDetails(activeInput, run));
        }
        Date now = DateUtils.getNowDate();
        AidRuntimeInputRequest input = new AidRuntimeInputRequest();
        input.setRunId(run.getId());
        input.setRequestKey(request.getRequestKey());
        Integer maxRound = inputRequestMapper.selectObjs(new LambdaQueryWrapper<AidRuntimeInputRequest>()
                        .select(AidRuntimeInputRequest::getRoundNo).eq(AidRuntimeInputRequest::getRunId, run.getId())
                        .orderByDesc(AidRuntimeInputRequest::getRoundNo).last("LIMIT 1"))
                .stream().findFirst().map(item -> ((Number) item).intValue()).orElse(0);
        input.setRoundNo(maxRound + 1);
        input.setSchemaDigest(schemaDigest);
        input.setContextDigest(request.getContextDigest());
        input.setPlanRevision(value(run.getPlanRevision()));
        input.setStatus("OPEN");
        input.setQuestionBundleJson(bundleJson);
        setCreateAudit(input, userId, now);
        inputRequestMapper.insert(input);

        run.setStatus(WAITING_INPUT);
        run.setNextAction("ANSWER_INPUT");
        run.setStateRevision(value(run.getStateRevision()) + 1);
        setUpdateAudit(run, userId, now);
        runMapper.updateById(run);
        session.setActiveRuntimeRunId(run.getId());
        session.setStateRevision(value(session.getStateRevision()) + 1);
        session.setLastActiveTime(now);
        setUpdateAudit(session, userId, now);
        sessionMapper.updateById(session);
        appendEvent(session, run.getId(), "input.requested", id(input.getId()), 0L,
                request.getRequestKey(), inputPayload(input), now, userId);
        appendEvent(session, run.getId(), "run.state_changed", id(run.getId()),
                value(run.getStateRevision()), request.getRequestKey(), runPayload(run), now, userId);
        return toInputRequest(userId, input);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AssistantViews.Draft saveDraft(Long userId, AssistantRequests.DraftSave request)
    {
        InputLock lock = lockInput(userId, request.getRequestId());
        return saveDraftLocked(userId, lock, request, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AssistantViews.InputView saveView(Long userId, AssistantRequests.ViewSave request)
    {
        InputLock lock = lockInput(userId, request.getRequestId());
        requireActiveInput(lock, Arrays.asList("OPEN", "DRAFTING"), WAITING_INPUT);
        QuestionSchema schema = questionSchema(lock.input);
        if (StrUtil.isNotBlank(request.getCursorQuestionId())
                && schema.byId.get(request.getCursorQuestionId()) == null)
        {
            throw validation("cursorQuestionId", "问题不存在");
        }
        AidRuntimeInputView view = inputViewMapper.selectOne(new LambdaQueryWrapper<AidRuntimeInputView>()
                .eq(AidRuntimeInputView::getInputRequestId, lock.input.getId())
                .eq(AidRuntimeInputView::getUserId, userId).last("FOR UPDATE"));
        Date now = DateUtils.getNowDate();
        if (view == null)
        {
            view = new AidRuntimeInputView();
            view.setInputRequestId(lock.input.getId());
            view.setUserId(userId);
            view.setCursorQuestionId(request.getCursorQuestionId());
            setCreateAudit(view, userId, now);
            inputViewMapper.insert(view);
        }
        else
        {
            if (Objects.equals(view.getCursorQuestionId(), request.getCursorQuestionId()))
            {
                AssistantViews.InputView unchanged = new AssistantViews.InputView();
                unchanged.setRequestId(id(lock.input.getId()));
                unchanged.setCursorQuestionId(view.getCursorQuestionId());
                return unchanged;
            }
            view.setCursorQuestionId(request.getCursorQuestionId());
            setUpdateAudit(view, userId, now);
            inputViewMapper.updateById(view);
        }
        appendEvent(lock.session, lock.run.getId(), "input.view_saved", id(lock.input.getId()), null,
                null, viewPayload(view), now, userId);
        AssistantViews.InputView result = new AssistantViews.InputView();
        result.setRequestId(id(lock.input.getId()));
        result.setCursorQuestionId(view.getCursorQuestionId());
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AssistantViews.InputSubmitted submitInput(Long userId, AssistantRequests.InputSubmit request)
    {
        AidRuntimeInputRequest discoveredInput = inputRequestMapper.selectById(request.getRequestId());
        if (discoveredInput == null)
        {
            throw failure(HttpStatus.NOT_FOUND, "INPUT_REQUEST_NOT_FOUND", "\u95ee\u9898\u4e0d\u5b58\u5728",
                    id(request.getRequestId()));
        }
        AidRuntimeRun discoveredRun = requireRun(userId, discoveredInput.getRunId(), false);
        AidAssistantSession discoveredSession = requireSession(userId, discoveredRun.getSessionId(), false);
        String replayDigest = digest(request);
        AidRuntimeCommand exactReplay = findCommand(userId, discoveredSession.getId(), request.getResponseKey(),
                replayDigest);
        if (exactReplay != null)
        {
            return replayResult(exactReplay, AssistantViews.InputSubmitted.class);
        }
        InputLock lock = lockInput(userId, request.getRequestId());
        exactReplay = findCommand(userId, lock.session.getId(), request.getResponseKey(), replayDigest);
        if (exactReplay != null)
        {
            return replayResult(exactReplay, AssistantViews.InputSubmitted.class);
        }
        requireActiveInput(lock, Arrays.asList("OPEN", "DRAFTING"), WAITING_INPUT);
        requireRevision(request.getExpectedRunRevision(), value(lock.run.getStateRevision()));
        if (!Objects.equals(request.getSchemaDigest(), lock.input.getSchemaDigest())
                || !Objects.equals(request.getContextDigest(), lock.input.getContextDigest()))
        {
            throw failure(HttpStatus.CONFLICT, "INPUT_SCHEMA_STALE", "\u95ee\u9898\u5df2\u66f4\u65b0",
                    schemaStaleDetails(request, lock.input, lock.run));
        }
        AidRuntimeCommand command = startCommand(userId, lock.session.getId(), request.getResponseKey(),
                replayDigest, "INPUT_SUBMIT");

        QuestionSchema schema = questionSchema(lock.input);
        Map<String, AidRuntimeInputDraft> drafts = loadDrafts(userId, lock.input.getId()).stream()
                .collect(Collectors.toMap(AidRuntimeInputDraft::getQuestionId, Function.identity()));
        Map<String, AssistantRequests.AnswerRef> answerRefs = request.getAnswers().stream()
                .collect(Collectors.toMap(AssistantRequests.AnswerRef::getQuestionId, Function.identity(),
                        (first, duplicate) -> first));
        if (answerRefs.size() != schema.questions.size() || !answerRefs.keySet().equals(schema.byId.keySet()))
        {
            throw validation("answers", "答案不完整");
        }
        Map<String, List<String>> choicesByQuestion = drafts.values().stream().collect(Collectors.toMap(
                AidRuntimeInputDraft::getQuestionId, draft -> parseChoices(draft.getChoiceIdsJson())));
        boolean pauseRequested = AssistantQuestionControlPolicy.requestsPause(schema.questions, choicesByQuestion);
        for (AssistantRequests.Question question : schema.questions)
        {
            AssistantRequests.AnswerRef answer = answerRefs.get(question.getQuestionId());
            AidRuntimeInputDraft draft = drafts.get(question.getQuestionId());
            if (draft == null || !Objects.equals(answer.getDraftRevision(), draft.getDraftRevision()))
            {
                throw failure(HttpStatus.CONFLICT, "INPUT_DRAFT_CONFLICT", "答案已更新",
                        draftConflictDetails(question.getQuestionId(), draft, lock.run));
            }
            // A trusted server-owned pause selection keeps all drafts but does not force completion of
            // unrelated business questions. Option identity, exclusivity and text bounds remain strict.
            validateAnswer(question, choicesByQuestion.get(question.getQuestionId()), draft.getFreeText(),
                    !pauseRequested);
        }
        List<AcceptedAnswer> accepted = drafts.values().stream()
                .sorted(Comparator.comparing(AidRuntimeInputDraft::getQuestionId))
                .map(this::acceptedAnswer).collect(Collectors.toList());
        String acceptedJson = JSON.toJSONString(accepted);
        String acceptedDigest = SecureUtil.sha256(acceptedJson + ":" + lock.input.getSchemaDigest()
                + ":" + lock.input.getContextDigest());
        Date now = DateUtils.getNowDate();
        lock.input.setStatus("SUBMITTED");
        lock.input.setAcceptedAnswersJson(acceptedJson);
        lock.input.setAcceptedDigest(acceptedDigest);
        lock.input.setAcceptedKey(request.getResponseKey());
        setUpdateAudit(lock.input, userId, now);
        inputRequestMapper.updateById(lock.input);
        lock.run.setStatus(pauseRequested ? PAUSED : WAITING_APPROVAL);
        lock.run.setNextAction("REPLANNING_REQUIRED");
        lock.run.setPauseReason(pauseRequested ? "USER_REQUEST" : null);
        lock.run.setResumeStatus(pauseRequested ? WAITING_APPROVAL : null);
        if (pauseRequested) lock.run.setNextRetryAt(null);
        lock.run.setStateRevision(value(lock.run.getStateRevision()) + 1);
        setUpdateAudit(lock.run, userId, now);
        runMapper.updateById(lock.run);
        lock.session.setStateRevision(value(lock.session.getStateRevision()) + 1);
        lock.session.setLastActiveTime(now);
        setUpdateAudit(lock.session, userId, now);
        sessionMapper.updateById(lock.session);
        appendEvent(lock.session, lock.run.getId(), "input.submitted", id(lock.input.getId()), null,
                request.getResponseKey(), inputPayload(lock.input), now, userId);
        appendEvent(lock.session, lock.run.getId(), pauseRequested ? "run.paused" : "approval.required", id(lock.run.getId()),
                value(lock.run.getStateRevision()), request.getResponseKey(), runPayload(lock.run), now, userId);
        AssistantViews.InputSubmitted result = submittedView(lock.input, lock.run, lock.session);
        completeCommand(command, result, userId, now);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AssistantViews.Run suspendInput(Long userId, AssistantRequests.InputSuspend request)
    {
        InputLock lock = lockInput(userId, request.getRequestId());
        String commandDigest = digest(request);
        AidRuntimeCommand replay = findCommand(userId, lock.session.getId(), request.getClientRequestId(),
                commandDigest);
        if (replay != null)
        {
            return replayResult(replay, AssistantViews.Run.class);
        }
        requireActiveInput(lock, Arrays.asList("OPEN", "DRAFTING"), WAITING_INPUT);
        requireRevision(request.getExpectedRunRevision(), value(lock.run.getStateRevision()));
        if (!WAITING_INPUT.equals(lock.run.getStatus()))
        {
            throw failure(HttpStatus.CONFLICT, "RUN_NOT_PAUSABLE", "运行不可暂停", lock.run.getStatus());
        }
        AidRuntimeCommand command = startCommand(userId, lock.session.getId(), request.getClientRequestId(),
                commandDigest, "INPUT_SUSPEND");
        List<AssistantRequests.DraftPatch> dirtyPatches = new ArrayList<>(request.getDirtyDrafts());
        Set<String> dirtyQuestionIds = new HashSet<>();
        if (dirtyPatches.stream().anyMatch(item -> item == null || StrUtil.isBlank(item.getQuestionId())
                || !dirtyQuestionIds.add(item.getQuestionId())))
        {
            throw validation("dirtyDrafts", "\u95ee\u9898\u6807\u8bc6\u91cd\u590d");
        }
        dirtyPatches.sort(Comparator.comparing(AssistantRequests.DraftPatch::getQuestionId));
        for (AssistantRequests.DraftPatch patch : dirtyPatches)
        {
            AssistantRequests.DraftSave draftSave = new AssistantRequests.DraftSave();
            draftSave.setRequestId(request.getRequestId());
            draftSave.setQuestionId(patch.getQuestionId());
            draftSave.setExpectedDraftRevision(patch.getExpectedDraftRevision());
            draftSave.setSelectedOptionIds(patch.getSelectedOptionIds());
            draftSave.setFreeText(patch.getFreeText());
            draftSave.setClientOperationId(patch.getClientOperationId());
            saveDraftLocked(userId, lock, draftSave, false);
        }
        Date now = DateUtils.getNowDate();
        lock.input.setStatus("SUSPENDED");
        lock.input.setSuspendedAt(now);
        setUpdateAudit(lock.input, userId, now);
        inputRequestMapper.updateById(lock.input);
        lock.run.setStatus(PAUSED);
        lock.run.setPauseReason("INPUT_DISMISSED");
        lock.run.setResumeStatus(WAITING_INPUT);
        lock.run.setNextAction("RESUME_INPUT");
        lock.run.setStateRevision(value(lock.run.getStateRevision()) + 1);
        setUpdateAudit(lock.run, userId, now);
        runMapper.updateById(lock.run);
        lock.session.setStateRevision(value(lock.session.getStateRevision()) + 1);
        lock.session.setLastActiveTime(now);
        setUpdateAudit(lock.session, userId, now);
        sessionMapper.updateById(lock.session);
        appendEvent(lock.session, lock.run.getId(), "input.suspended", id(lock.input.getId()), null,
                request.getClientRequestId(), inputPayload(lock.input), now, userId);
        appendEvent(lock.session, lock.run.getId(), "run.state_changed", id(lock.run.getId()),
                value(lock.run.getStateRevision()), request.getClientRequestId(), runPayload(lock.run), now, userId);
        AssistantViews.Run result = toRun(lock.run, lock.session);
        completeCommand(command, result, userId, now);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AssistantViews.Run pauseRun(Long userId, AssistantRequests.RunControl request)
    {
        return controlRun(userId, request, "PAUSE");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AssistantViews.Run resumeRun(Long userId, AssistantRequests.RunControl request)
    {
        return controlRun(userId, request, "RESUME");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AssistantViews.Run stopRun(Long userId, AssistantRequests.RunControl request)
    {
        return controlRun(userId, request, "STOP");
    }

    protected AssistantViews.Run controlRun(Long userId, AssistantRequests.RunControl request, String action)
    {
        AidRuntimeRun discovered = requireRun(userId, request.getRunId(), false);
        AidAssistantSession session = requireSession(userId, discovered.getSessionId(), true);
        String commandDigest = SecureUtil.sha256(action + ":" + JSON.toJSONString(request));
        AidRuntimeCommand replay = findCommand(userId, session.getId(), request.getClientRequestId(), commandDigest);
        if (replay != null)
        {
            return replayResult(replay, AssistantViews.Run.class);
        }
        AidRuntimeRun run = requireRun(userId, request.getRunId(), true);
        if ("RESUME".equals(action))
        {
            verifyPersistedSessionScope(userId, session);
            verifyRunContextScope(userId, run);
        }
        if (!Objects.equals(session.getActiveRuntimeRunId(), run.getId()))
        {
            throw failure(HttpStatus.CONFLICT, "SNAPSHOT_REQUIRED", "请刷新状态",
                    runStateDetails(run, session));
        }
        requireRevision(request.getExpectedRevision(), value(run.getStateRevision()));
        AidRuntimeCommand command = startCommand(userId, session.getId(), request.getClientRequestId(),
                commandDigest, "RUN_" + action);
        Date now = DateUtils.getNowDate();
        boolean inputDismissedResume = "RESUME".equals(action)
                && WAITING_INPUT.equals(run.getResumeStatus())
                && "INPUT_DISMISSED".equals(run.getPauseReason());
        if ("PAUSE".equals(action))
        {
            pause(run);
        }
        else if ("RESUME".equals(action))
        {
            resume(run);
        }
        else
        {
            abandonDeferredReplan(run,session,userId,now);
            stop(run);
        }
        run.setStateRevision(value(run.getStateRevision()) + 1);
        setUpdateAudit(run, userId, now);
        runMapper.updateById(run);
        if (inputDismissedResume)
        {
            AidRuntimeInputRequest suspended = inputRequestMapper.selectOne(
                    new LambdaQueryWrapper<AidRuntimeInputRequest>()
                            .eq(AidRuntimeInputRequest::getRunId, run.getId())
                             .eq(AidRuntimeInputRequest::getStatus, "SUSPENDED")
                             .orderByDesc(AidRuntimeInputRequest::getRoundNo).last("LIMIT 1 FOR UPDATE"));
            if (suspended == null)
            {
                throw failure(HttpStatus.CONFLICT, "INPUT_REQUEST_NOT_ACTIVE", "\u95ee\u9898\u72b6\u6001\u51b2\u7a81",
                        runStateDetails(run, session));
            }
            requireActiveInput(new InputLock(session, run, suspended),
                    Collections.singletonList("SUSPENDED"), WAITING_INPUT);
            if (suspended != null)
            {
                suspended.setStatus("DRAFTING");
                suspended.setSuspendedAt(null);
                setUpdateAudit(suspended, userId, now);
                inputRequestMapper.updateById(suspended);
            }
        }
        if ("STOP".equals(action))
        {
            List<AidRuntimeInputRequest> pendingInputs = inputRequestMapper.selectList(
                    new LambdaQueryWrapper<AidRuntimeInputRequest>()
                            .eq(AidRuntimeInputRequest::getRunId, run.getId())
                            .in(AidRuntimeInputRequest::getStatus,
                                    Arrays.asList("OPEN", "DRAFTING", "SUSPENDED")));
            for (AidRuntimeInputRequest pending : pendingInputs)
            {
                pending.setStatus("WITHDRAWN");
                setUpdateAudit(pending, userId, now);
                inputRequestMapper.updateById(pending);
            }
        }
        session.setStateRevision(value(session.getStateRevision()) + 1);
        session.setLastActiveTime(now);
        if (RuntimeRunLifecycle.isTerminal(run.getStatus())
                && Objects.equals(session.getActiveRuntimeRunId(), run.getId()))
        {
            session.setActiveRuntimeRunId(null);
        }
        setUpdateAudit(session, userId, now);
        sessionMapper.updateById(session);
        appendEvent(session, run.getId(), "run.state_changed", id(run.getId()), value(run.getStateRevision()),
                request.getClientRequestId(), runPayload(run), now, userId);
        AssistantViews.Run result = toRun(run, session);
        completeCommand(command, result, userId, now);
        return result;
    }

    @Override
    public AssistantViews.Run runDetail(Long userId, AssistantRequests.RunDetail request)
    {
        AidRuntimeRun run = requireRun(userId, request.getRunId(), false);
        AidAssistantSession session = requireSession(userId, run.getSessionId(), false);
        return toRun(run, session);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AssistantViews.RunStepPage pageRunSteps(Long userId, AssistantRequests.RunStepPage request)
    {
        if (request == null || request.getRunId() == null || request.getRunId() < 1)
        {
            throw validation("runId", "运行标识无效");
        }
        int limit = request.getLimit() == null ? 20 : request.getLimit();
        if (limit < 1 || limit > 50)
        {
            throw validation("limit", "分页大小无效");
        }
        Long beforeStepId = positiveStepCursor(request.getBeforeStepId());
        AidRuntimeRun run = requireRun(userId, request.getRunId(), false);
        AidAssistantSession session = requireRunSession(userId, run);
        List<AidRuntimeRunStep> found = runStepMapper.selectList(
                new LambdaQueryWrapper<AidRuntimeRunStep>()
                        .eq(AidRuntimeRunStep::getRunId, run.getId())
                        .eq(AidRuntimeRunStep::getDelFlag, "0")
                        .lt(beforeStepId != null, AidRuntimeRunStep::getId, beforeStepId)
                        .orderByDesc(AidRuntimeRunStep::getId)
                        .last("LIMIT " + (limit + 1)));
        boolean more = found.size() > limit;
        List<AidRuntimeRunStep> visible = more
                ? new ArrayList<>(found.subList(0, limit)) : found;

        AssistantViews.RunStepPageScope scope = new AssistantViews.RunStepPageScope();
        scope.setSessionId(id(session.getId()));
        scope.setObservedSessionRevision(value(session.getStateRevision()));
        scope.setObservedLastEventSeq(value(session.getLastEventSeq()));
        scope.setRunId(id(run.getId()));
        scope.setObservedRunRevision(value(run.getStateRevision()));
        scope.setRunStatus(run.getStatus());
        scope.setNextAction(run.getNextAction());

        AssistantViews.RunStepPage result = new AssistantViews.RunStepPage();
        result.setScope(scope);
        Map<Long, List<AssistantTargetViews.TargetEffect>> effects = targetEffectService.projectSteps(
                userId, session, run, visible);
        Map<Long, AidMediaTask> stepTasks = loadAuthorizedStepTasks(userId, visible);
        result.setItems(visible.stream().map(step -> toStep(step, effects.get(step.getId()),
                stepTasks.get(step.getId()), "PREVIZ_3DBOX".equals(session.getModuleCode()))).toList());
        result.setHasMore(more);
        result.setNextCursor(more && !visible.isEmpty()
                ? id(visible.get(visible.size() - 1).getId()) : null);
        return result;
    }

    @Override
    public AssistantTargetViews.EffectResolved resolveEffect(Long userId, AssistantRequests.EffectResolve request)
    {
        return targetEffectService.resolve(userId, request);
    }

    @Override
    public AssistantViews.EventPage pageEvents(Long userId, AssistantRequests.EventPage request)
    {
        AidAssistantSession session = requireSession(userId, request.getSessionId(), false);
        int limit = request.getLimit() == null ? 100 : request.getLimit();
        List<AidRuntimeEvent> events = eventMapper.selectList(new LambdaQueryWrapper<AidRuntimeEvent>()
                .eq(AidRuntimeEvent::getSessionId, session.getId())
                .gt(AidRuntimeEvent::getSeq, request.getAfterSeq())
                .orderByAsc(AidRuntimeEvent::getSeq).last("LIMIT " + (limit + 1)));
        boolean more = events.size() > limit;
        if (more)
        {
            events = new ArrayList<>(events.subList(0, limit));
        }
        AssistantViews.EventPage result = new AssistantViews.EventPage();
        result.setItems(events.stream().map(this::toEvent).collect(Collectors.toList()));
        result.setScannedThroughSeq(events.isEmpty() ? request.getAfterSeq()
                : events.get(events.size() - 1).getSeq());
        result.setLastEventSeq(value(session.getLastEventSeq()));
        result.setHasMore(more);
        return result;
    }

    private AssistantViews.Draft saveDraftLocked(Long userId, InputLock lock,
                                                   AssistantRequests.DraftSave request, boolean emitEvent)
    {
        requireActiveInput(lock, Arrays.asList("OPEN", "DRAFTING"), WAITING_INPUT);
        if (!Arrays.asList("OPEN", "DRAFTING").contains(lock.input.getStatus()))
        {
            throw validation("requestId", "问题不可编辑");
        }
        QuestionSchema schema = questionSchema(lock.input);
        AssistantRequests.Question question = schema.byId.get(request.getQuestionId());
        if (question == null)
        {
            throw validation("questionId", "问题不存在");
        }
        validateAnswer(question, request.getSelectedOptionIds(), request.getFreeText(), false);
        String payloadDigest = digestDraft(request);
        AidRuntimeInputDraft draft = inputDraftMapper.selectOne(
                new LambdaQueryWrapper<AidRuntimeInputDraft>()
                        .eq(AidRuntimeInputDraft::getInputRequestId, lock.input.getId())
                        .eq(AidRuntimeInputDraft::getUserId, userId)
                        .eq(AidRuntimeInputDraft::getQuestionId, request.getQuestionId()).last("FOR UPDATE"));
        if (draft != null && Objects.equals(request.getClientOperationId(), draft.getLastClientOperationId()))
        {
            if (!Objects.equals(payloadDigest, draft.getLastPayloadDigest()))
            {
                throw idempotencyConflict(request.getClientOperationId());
            }
            return toDraft(draft, unansweredCount(userId, lock.input, schema), lock.run);
        }
        long currentRevision = draft == null ? 0L : value(draft.getDraftRevision());
        if (!Objects.equals(request.getExpectedDraftRevision(), currentRevision))
        {
            AssistantViews.Draft current = draft == null ? null
                    : toDraft(draft, unansweredCount(userId, lock.input, schema), lock.run);
            throw failure(HttpStatus.CONFLICT, "INPUT_DRAFT_CONFLICT", "答案已更新",
                    draftConflictDetails(request.getQuestionId(), draft, lock.run));
        }
        Date now = DateUtils.getNowDate();
        if (draft == null)
        {
            draft = new AidRuntimeInputDraft();
            draft.setInputRequestId(lock.input.getId());
            draft.setUserId(userId);
            draft.setQuestionId(request.getQuestionId());
            draft.setDraftRevision(1L);
            setCreateAudit(draft, userId, now);
        }
        else
        {
            draft.setDraftRevision(currentRevision + 1);
            setUpdateAudit(draft, userId, now);
        }
        List<String> choices = normalizedChoices(request.getSelectedOptionIds());
        String freeText = StrUtil.isBlank(request.getFreeText()) ? null : request.getFreeText().trim();
        draft.setChoiceIdsJson(JSON.toJSONString(choices));
        draft.setFreeText(freeText);
        draft.setAnswerState(CollectionUtil.isEmpty(choices) && StrUtil.isBlank(freeText) ? "CLEARED"
                : answerComplete(question, choices, freeText) ? "ANSWERED" : "PARTIAL");
        draft.setLastClientOperationId(request.getClientOperationId());
        draft.setLastPayloadDigest(payloadDigest);
        if (draft.getId() == null)
        {
            inputDraftMapper.insert(draft);
        }
        else
        {
            inputDraftMapper.updateById(draft);
        }
        if ("OPEN".equals(lock.input.getStatus()))
        {
            lock.input.setStatus("DRAFTING");
            lock.input.setSuspendedAt(null);
            setUpdateAudit(lock.input, userId, now);
            inputRequestMapper.updateById(lock.input);
        }
        if (emitEvent)
        {
            appendEvent(lock.session, lock.run.getId(), "input.draft_saved", id(draft.getId()),
                    value(draft.getDraftRevision()), request.getClientOperationId(), draftPayload(draft), now, userId);
        }
        return toDraft(draft, unansweredCount(userId, lock.input, schema), lock.run);
    }

    private InputLock lockInput(Long userId, Long requestId)
    {
        AidRuntimeInputRequest discovered = inputRequestMapper.selectById(requestId);
        if (discovered == null)
        {
            throw failure(HttpStatus.NOT_FOUND, "INPUT_REQUEST_NOT_FOUND", "问题不存在", id(requestId));
        }
        AidRuntimeRun discoveredRun = requireRun(userId, discovered.getRunId(), false);
        AidAssistantSession session = requireSession(userId, discoveredRun.getSessionId(), true);
        AidRuntimeRun run = requireRun(userId, discoveredRun.getId(), true);
        AidRuntimeInputRequest input = inputRequestMapper.selectOne(
                new LambdaQueryWrapper<AidRuntimeInputRequest>().eq(AidRuntimeInputRequest::getId, requestId)
                        .last("FOR UPDATE"));
        if (input == null || !Objects.equals(input.getRunId(), run.getId()))
        {
            throw failure(HttpStatus.NOT_FOUND, "INPUT_REQUEST_NOT_FOUND", "问题不存在", id(requestId));
        }
        if (!Objects.equals(input.getContextDigest(), run.getContextDigest()))
            staleInputContext("INPUT_CONTEXT_CHANGED");
        verifyPersistedSessionScope(userId, session);
        verifyRunContextScope(userId, run);
        return new InputLock(session, run, input);
    }

    private void requireActiveInput(InputLock lock, List<String> allowedInputStates, String requiredRunState)
    {
        AidRuntimeInputRequest current = inputRequestMapper.selectOne(
                new LambdaQueryWrapper<AidRuntimeInputRequest>()
                        .eq(AidRuntimeInputRequest::getRunId, lock.run.getId())
                        .in(AidRuntimeInputRequest::getStatus, Arrays.asList("OPEN", "DRAFTING", "SUSPENDED"))
                        .orderByDesc(AidRuntimeInputRequest::getRoundNo)
                        .orderByDesc(AidRuntimeInputRequest::getId)
                        .last("LIMIT 1 FOR UPDATE"));
        if (!Objects.equals(lock.session.getActiveRuntimeRunId(), lock.run.getId())
                || !Objects.equals(requiredRunState, lock.run.getStatus())
                || current == null || !Objects.equals(current.getId(), lock.input.getId())
                || !allowedInputStates.contains(lock.input.getStatus()))
        {
            throw failure(HttpStatus.CONFLICT, "INPUT_REQUEST_NOT_ACTIVE", "\u95ee\u9898\u72b6\u6001\u51b2\u7a81",
                    inputStateDetails(lock.input, lock.run));
        }
    }

    private void pause(AidRuntimeRun run)
    {
        if (RuntimeRunLifecycle.isTerminal(run.getStatus()) || WAITING_INPUT.equals(run.getStatus())
                || PAUSED.equals(run.getStatus())
                || "PAUSING".equals(run.getStatus()) || "RECONCILING".equals(run.getStatus()))
        {
            throw failure(HttpStatus.CONFLICT, "RUN_NOT_PAUSABLE", "运行不可暂停", run.getStatus());
        }
        run.setResumeStatus(run.getStatus());
        run.setPauseReason("USER_REQUEST");
        if (!hasProviderAcceptedMediaTask(run))
        {
            run.setStatus(PAUSED);
        }
        else
        {
            run.setStatus("PAUSING");
            run.setNextAction(hasActivePlanOperation(run)
                    ? "RECONCILE_OPERATION" : "WAIT_TASK_RECONCILIATION");
        }
    }

    private void resume(AidRuntimeRun run)
    {
        if (!PAUSED.equals(run.getStatus()) || StrUtil.isBlank(run.getResumeStatus()))
        {
            throw failure(HttpStatus.CONFLICT, "RUN_NOT_RESUMABLE", "\u8fd0\u884c\u4e0d\u53ef\u6062\u590d",
                    runStateDetails(run, null));
        }
        run.setStatus(run.getResumeStatus());
        // New pause records retain the exact durable next action while status is PAUSED.  Keep a
        // conservative mapping only for rows written by the earlier RESUME sentinel format.
        if ("RESUME".equals(run.getNextAction()))
            run.setNextAction(WAITING_INPUT.equals(run.getStatus())
                    ? "ANSWER_INPUT" : "CONTINUE_AGENT_REQUIRED");
        run.setPauseReason(null);
        run.setResumeStatus(null);
    }

    private void stop(AidRuntimeRun run)
    {
        if (RuntimeRunLifecycle.isTerminal(run.getStatus()))
        {
            throw failure(HttpStatus.CONFLICT, "RUN_NOT_STOPPABLE", "运行不可结束", run.getStatus());
        }
        run.setStopRequested(true);
        if (!hasActiveMediaTask(run) && !hasPendingSubmissionForStop(run))
        {
            run.setStatus("STOPPED");
            run.setNextAction(null);
        }
        else
        {
            run.setStatus("RECONCILING");
            run.setNextAction(hasActivePlanOperation(run) || hasPreparingPlanOperationForStop(run)
                    ? "RECONCILE_OPERATION" : "WAIT_TASK_RECONCILIATION");
        }
    }

    private void validateModule(String moduleCode, String mode)
    {
        if (!exposedModules().contains(moduleCode))
        {
            throw validation("moduleCode", "助手模块不正确");
        }
        if ("SCREENWRITER".equals(moduleCode) && !Arrays.asList("CRAFT", "ADAPT").contains(mode))
        {
            throw validation("mode", "模式不正确");
        }
        if (!"SCREENWRITER".equals(moduleCode) && StrUtil.isNotBlank(mode))
        {
            throw validation("mode", "当前助手无模式");
        }
    }

    private void verifySessionScope(Long userId, AssistantRequests.SessionOpen request)
    {
        if ("PREVIZ_3DBOX".equals(request.getModuleCode()) || request.getPrevizProjectId() != null)
        {
            if (!"PREVIZ_3DBOX".equals(request.getModuleCode()) || request.getPrevizProjectId() == null
                    || request.getWorkspaceId() != null || request.getProjectId() != null || request.getNodeObjectId() != null)
                throw validation("previzProjectId", "三维工程会话范围无效");
            AssistantRequests.ContextRef scope = new AssistantRequests.ContextRef();
            scope.setKind("PREVIZ_PROJECT"); scope.setPrevizProjectId(request.getPrevizProjectId());
            scopeAccessRegistry.resolvePinnedAll(userId, List.of(scope));
        }

        if (request.getNodeObjectId() != null && request.getWorkspaceId() == null)
            throw validation("nodeObjectId", "节点会话必须指定工作区");
        if (request.getProjectId() != null)
        {
            AidComicProject project = projectMapper.selectOne(new LambdaQueryWrapper<AidComicProject>()
                    .eq(AidComicProject::getId, request.getProjectId())
                    .eq(AidComicProject::getUserId, userId)
                    .eq(AidComicProject::getDelFlag, "0").last("LIMIT 1"));
            if (project == null)
            {
                throw failure(HttpStatus.UNPROCESSABLE_ENTITY, "ASSISTANT_SCOPE_UNAVAILABLE",
                        "当前范围不可用", id(request.getProjectId()));
            }
        }
        if (request.getEpisodeId() != null && request.getEpisodeId() == 0L
                && request.getProjectId() == null)
        {
            throw validation("episodeId", "项目级剧本范围必须指定项目");
        }
        if (request.getEpisodeId() != null && request.getEpisodeId() > 0L)
        {
            AidComicEpisode episode = episodeMapper.selectOne(new LambdaQueryWrapper<AidComicEpisode>()
                    .eq(AidComicEpisode::getId, request.getEpisodeId())
                    .eq(AidComicEpisode::getUserId, userId)
                    .eq(request.getProjectId() != null, AidComicEpisode::getProjectId, request.getProjectId())
                    .eq(AidComicEpisode::getDelFlag, "0").last("LIMIT 1"));
            if (episode == null)
            {
                throw failure(HttpStatus.UNPROCESSABLE_ENTITY, "ASSISTANT_SCOPE_UNAVAILABLE",
                        "当前范围不可用", id(request.getEpisodeId()));
            }
        }
        if (request.getWorkspaceId() != null)
        {
            AssistantRequests.ContextRef ref = new AssistantRequests.ContextRef();
            ref.setKind("FREE_CANVAS");
            ref.setWorkspaceId(request.getWorkspaceId());
            scopeAccessRegistry.verifyAll(userId, Collections.singletonList(ref));
            if (request.getNodeObjectId() != null)
            {
                ref.setObjectId(request.getNodeObjectId());
                scopeAccessRegistry.resolveCurrentAll(userId, Collections.singletonList(ref));
            }
        }
    }

    private void verifyPersistedSessionScope(Long userId, AidAssistantSession session)
    {
        AssistantRequests.SessionOpen persisted = new AssistantRequests.SessionOpen();
        persisted.setModuleCode(session.getModuleCode());
        JSONObject sessionIdentity = "PREVIZ_3DBOX".equals(session.getModuleCode()) ? JSON.parseObject(session.getBaseInputJson()) : null;
        if (sessionIdentity != null) persisted.setPrevizProjectId(sessionIdentity.getString("previzProjectId"));
        persisted.setProjectId(zeroToNull(session.getProjectId()));
        persisted.setEpisodeId(zeroToNull(session.getEpisodeId()));
        persisted.setWorkspaceId(session.getWorkspaceId());
        verifySessionScope(userId, persisted);
    }

    private void verifyRunContextScope(Long userId, AidRuntimeRun run)
    {
        if (StrUtil.isBlank(run.getRequestJson()))
        {
            throw failure(HttpStatus.UNPROCESSABLE_ENTITY, "ASSISTANT_SCOPE_UNAVAILABLE",
                    "\u4e0a\u4e0b\u6587\u4e0d\u53ef\u7528", id(run.getId()));
        }
        AidAssistantSession session = requireSession(userId, run.getSessionId(), false);
        contextManifests.requirePinned(userId, session, run);
    }

    private List<FrozenReference> frozenReferences(
            Long userId, AidAssistantSession session, AidRuntimeRun run)
    {
        if (userId == null || session == null || run == null
                || !Objects.equals(session.getUserId(), userId)
                || !Objects.equals(run.getUserId(), userId)
                || !Objects.equals(run.getSessionId(), session.getId())
                || StrUtil.isBlank(run.getRequestJson()))
            staleInputContext("CONTEXT_AUTHORITY_INVALID");
        JSONObject request;
        try
        {
            request = JSON.parseObject(run.getRequestJson());
        }
        catch (RuntimeException invalid)
        {
            staleInputContext("CONTEXT_MANIFEST_INVALID");
            return List.of();
        }
        JSONObject manifest;
        JSONArray rows;
        try
        {
            manifest = request == null ? null : request.getJSONObject("_messageContextManifest");
            rows = manifest == null ? null : manifest.getJSONArray("refs");
        }
        catch (RuntimeException invalid)
        {
            staleInputContext("CONTEXT_MANIFEST_INVALID");
            return List.of();
        }
        if (manifest == null || rows == null || rows.size() > contextLimits.maxReferences() + 1
                || !"assistant-context-manifest/v1".equals(manifest.getString("schemaVersion"))
                || !Objects.equals(request.getString("_messageContextDigest"),
                SecureUtil.sha256(manifest.toJSONString())))
            staleInputContext("CONTEXT_MANIFEST_INVALID");
        JSONObject scope = manifest.getJSONObject("sessionScope");
        if (scope == null
                || !Objects.equals(scope.getString("projectId"), id(zeroToNull(session.getProjectId())))
                || !Objects.equals(scope.getString("episodeId"),
                zeroToNull(session.getProjectId()) == null ? null : id(session.getEpisodeId()))
                || !Objects.equals(scope.getString("workspaceId"), id(session.getWorkspaceId())))
            staleInputContext("SESSION_SCOPE_CHANGED");
        Map<Integer, FrozenPresentation> snapshots =
                parseMessageReferenceSnapshots(request.getJSONArray("_messageReferenceSnapshots"), rows.size());
        List<FrozenReference> result = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++)
        {
            JSONObject row = rows.getJSONObject(index);
            JSONObject canonical = row == null ? null : row.getJSONObject("canonicalRef");
            AssistantRequests.ContextRef ref;
            try
            {
                ref = canonical == null ? null : canonical.toJavaObject(AssistantRequests.ContextRef.class);
                if (ref != null) ref.setKind(row.getString("kind"));
            }
            catch (RuntimeException invalid)
            {
                staleInputContext("CONTEXT_REFERENCE_INVALID");
                return List.of();
            }
            if (ref == null || row == null || StrUtil.isBlank(ref.getKind())
                    || !Objects.equals(ref.getKind(), row.getString("kind"))
                    || !Objects.equals(canonicalContextIdentity(ref), canonical)
                    || StrUtil.isBlank(row.getString("contentRef"))
                    || StrUtil.isBlank(row.getString("access")))
                staleInputContext("CONTEXT_REFERENCE_INVALID");
            FrozenPresentation presentation = snapshots.get(index);
            result.add(new FrozenReference(copyContextRef(ref), row.getString("contentRef"),
                    row.getString("access"), presentation == null
                    ? StrUtil.trimToNull(ref.getReason()) : presentation.label(),
                    presentation == null ? null : presentation.snapshot()));
        }
        contextLimits.checkResolvedIdentityBudget(result.stream().map(FrozenReference::ref).toList());
        return List.copyOf(result);
    }

    private JSONArray messageReferenceSnapshots(Long userId, List<ResolvedContextRef> references,
                                                List<AssistantRequests.ContextRef> requested)
    {
        JSONArray result = new JSONArray();
        if (references == null) return result;
        for (int index = 0; index < references.size(); index++)
        {
            ResolvedContextRef reference = references.get(index);
            if (reference == null || reference.contextRef() == null || isWorkspaceReference(reference.contextRef()))
                continue;
            JSONObject row = new JSONObject();
            row.put("refIndex", index);
            String label = requestedReferenceLabel(index, requested, reference.contextRef());
            if (label != null) row.put("label", label);
            List<AssistantMessageReferenceProjector> projectors = messageReferenceProjectors.stream()
                    .filter(projector -> projector.supports(reference.contextRef().getKind())).toList();
            if (projectors.size() == 1)
            {
                try
                {
                    AssistantMessageReferenceProjector.Snapshot snapshot =
                            projectors.get(0).snapshot(userId, reference);
                    if (snapshot != null)
                    {
                        row.put("mediaKind", snapshot.mediaKind());
                        if (snapshot.previewResourceId() != null)
                            row.put("previewResourceId", id(snapshot.previewResourceId()));
                        if (StrUtil.isNotBlank(snapshot.previewContentHash()))
                            row.put("previewContentHash", snapshot.previewContentHash());
                    }
                }
                catch (RuntimeException unavailable)
                {
                    // Presentation enrichment must not make an already authorized send fail.
                }
            }
            if (row.size() > 1) result.add(row);
        }
        return result;
    }

    private String requestedReferenceLabel(int index, List<AssistantRequests.ContextRef> requested,
                                           AssistantRequests.ContextRef canonical)
    {
        if (requested == null || index < 0 || index >= requested.size() || canonical == null) return null;
        AssistantRequests.ContextRef source = requested.get(index);
        if (source == null || !Objects.equals(source.getKind(), canonical.getKind())) return null;
        JSONObject requestedIdentity = canonicalContextIdentity(source);
        JSONObject canonicalIdentity = canonicalContextIdentity(canonical);
        for (Map.Entry<String, Object> entry : requestedIdentity.entrySet())
        {
            if (!Objects.equals(entry.getValue(), canonicalIdentity.get(entry.getKey()))) return null;
        }
        String label = StrUtil.trimToNull(source.getReason());
        return label != null && label.length() <= 200 ? label : null;
    }

    private Map<Integer, FrozenPresentation> parseMessageReferenceSnapshots(
            JSONArray rows, int referenceCount)
    {
        if (rows == null || rows.isEmpty()) return Collections.emptyMap();
        Map<Integer, FrozenPresentation> result = new HashMap<>();
        for (Object raw : rows)
        {
            if (!(raw instanceof JSONObject row)) continue;
            Integer refIndex = row.getInteger("refIndex");
            String mediaKind = row.getString("mediaKind");
            String resourceId = row.getString("previewResourceId");
            String contentHash = row.getString("previewContentHash");
            String label = StrUtil.trimToNull(row.getString("label"));
            if (refIndex == null || refIndex < 0 || refIndex >= referenceCount
                    || label != null && label.length() > 200
                    || result.containsKey(refIndex)) continue;
            boolean hasProjection = mediaKind != null || StrUtil.isNotBlank(resourceId)
                    || StrUtil.isNotBlank(contentHash);
            if (hasProjection && (mediaKind == null
                    || !Set.of("TEXT", "IMAGE", "VIDEO", "AUDIO", "DOCUMENT", "OTHER")
                    .contains(mediaKind))) continue;
            Long parsedResourceId = null;
            if (StrUtil.isNotBlank(resourceId))
            {
                if (!resourceId.matches("[1-9][0-9]{0,18}")) continue;
                try { parsedResourceId = Long.valueOf(resourceId); }
                catch (NumberFormatException invalid) { continue; }
            }
            if ((parsedResourceId == null) != StrUtil.isBlank(contentHash)
                    || StrUtil.isNotBlank(contentHash) && !contentHash.matches("[a-f0-9]{64}")) continue;
            AssistantMessageReferenceProjector.Snapshot snapshot = hasProjection
                    ? new AssistantMessageReferenceProjector.Snapshot(mediaKind, parsedResourceId, contentHash)
                    : null;
            if (label != null || snapshot != null)
                result.put(refIndex, new FrozenPresentation(label, snapshot));
        }
        return result;
    }

    private AssistantRequests.ContextRef copyContextRef(AssistantRequests.ContextRef source)
    {
        return JSON.parseObject(JSON.toJSONString(source), AssistantRequests.ContextRef.class);
    }

    private JSONObject canonicalContextIdentity(AssistantRequests.ContextRef ref)
    {
        JSONObject value = new JSONObject();
        put(value, "previzProjectId", ref.getPrevizProjectId());
        put(value, "sourceVersion", ref.getSourceVersion());
        put(value, "workspaceId", id(ref.getWorkspaceId()));
        put(value, "resourceId", id(ref.getResourceId()));
        put(value, "objectId", ref.getObjectId());
        put(value, "version", ref.getVersion());
        put(value, "projectId", id(ref.getProjectId()));
        put(value, "episodeId", id(ref.getEpisodeId()));
        put(value, "entityType", ref.getEntityType());
        put(value, "entityId", ref.getEntityId());
        put(value, "scriptId", id(ref.getScriptId()));
        put(value, "documentId", id(ref.getDocumentId()));
        put(value, "documentSessionId", id(ref.getDocumentSessionId()));
        put(value, "contentHash", ref.getContentHash());
        if (ref.getSelection() != null)
        {
            JSONObject selection = new JSONObject();
            selection.put("start", ref.getSelection().getStart());
            selection.put("end", ref.getSelection().getEnd());
            selection.put("selectedTextHash", ref.getSelection().getSelectedTextHash());
            value.put("selection", selection);
        }
        return value;
    }

    private JSONObject canonicalContextIdentityWithoutVersion(AssistantRequests.ContextRef ref)
    {
        JSONObject value = canonicalContextIdentity(ref);
        value.remove("version");
        return value;
    }

    private boolean isWorkspaceReference(AssistantRequests.ContextRef ref)
    {
        return ref != null && "FREE_CANVAS".equals(ref.getKind()) && StrUtil.isBlank(ref.getObjectId());
    }

    private void staleInputContext(String reason)
    {
        throw failure(HttpStatus.CONFLICT, "ASSISTANT_CONTEXT_STALE", "引用上下文已变更", reason);
    }

    private void put(JSONObject target, String key, Object value)
    {
        if (value != null) target.put(key, value);
    }

    private void validateContextRefs(List<AssistantRequests.ContextRef> refs)
    {
        contextLimits.checkRawRequestBudget(refs);
        if (CollectionUtil.isEmpty(refs))
        {
            return;
        }
        for (AssistantRequests.ContextRef ref : refs)
        {
            if (ref == null || StrUtil.isBlank(ref.getKind()))
            {
                throw validation("contextRefs", "上下文标识无效");
            }
            if ("FREE_CANVAS".equals(ref.getKind()))
            {
                boolean hasObject = StrUtil.isNotBlank(ref.getObjectId());
                boolean hasVersion = StrUtil.isNotBlank(ref.getVersion());
                if (ref.getWorkspaceId() == null || ref.getResourceId() != null
                        || hasObject != hasVersion || ref.getProjectId() != null
                        || ref.getEpisodeId() != null || ref.getScriptId() != null
                        || StrUtil.isNotBlank(ref.getEntityId()))
                {
                    throw validation("contextRefs", "画布引用不完整");
                }
            }
            else if ("CANVAS_RESOURCE".equals(ref.getKind()))
            {
                if (ref.getWorkspaceId() == null || ref.getWorkspaceId() < 1
                        || ref.getResourceId() == null || ref.getResourceId() < 1
                        || StrUtil.isNotBlank(ref.getVersion()) || StrUtil.isNotBlank(ref.getContentHash())
                        || StrUtil.isNotBlank(ref.getObjectId()) || ref.getProjectId() != null
                        || ref.getEpisodeId() != null || ref.getScriptId() != null
                        || ref.getDocumentId() != null || ref.getDocumentSessionId() != null
                        || StrUtil.isNotBlank(ref.getEntityId()) || StrUtil.isNotBlank(ref.getEntityType())
                        || ref.getSelection() != null)
                    throw validation("contextRefs", "画布资源引用无效");
            }
            else if ("FLOW_ENTITY".equals(ref.getKind()))
            {
                if (ref.getProjectId() == null || ref.getResourceId() != null
                        || StrUtil.isBlank(ref.getEntityType())
                        || StrUtil.isBlank(ref.getEntityId()) || StrUtil.isBlank(ref.getVersion())
                        || ref.getWorkspaceId() != null || StrUtil.isNotBlank(ref.getObjectId())
                        || ref.getScriptId() != null)
                {
                    throw validation("contextRefs", "流程引用不完整");
                }
            }
            else if ("DOCUMENT".equals(ref.getKind()))
            {
                if (ref.getDocumentId() == null || ref.getDocumentId() < 1 || StrUtil.isBlank(ref.getVersion())
                        || StrUtil.isBlank(ref.getContentHash()) || ref.getSelection() != null || ref.getScriptId() != null
                        || ref.getResourceId() != null || ref.getWorkspaceId() != null
                        || ref.getObjectId() != null || ref.getEpisodeId() != null
                        || ref.getEntityId() != null)
                    throw validation("contextRefs", "文档引用不完整");
            }
            else if ("SCRIPT".equals(ref.getKind()))
            {
                if (ref.getScriptId() == null || ref.getResourceId() != null
                        || StrUtil.isBlank(ref.getContentHash())
                        || ref.getWorkspaceId() != null || StrUtil.isNotBlank(ref.getObjectId())
                        || ref.getProjectId() != null || StrUtil.isNotBlank(ref.getEntityId()))
                {
                    throw validation("contextRefs", "剧本引用不完整");
                }
                if (ref.getSelection() != null
                        && ref.getSelection().getStart() >= ref.getSelection().getEnd())
                {
                    throw validation("selection", "选区不正确");
                }
            }
        }
    }

    private void validateQuestions(List<AssistantRequests.Question> questions)
    {
        if (questions == null || questions.isEmpty() || questions.size() > 100)
        {
            throw validation("questions", "\u95ee\u9898\u6570\u91cf\u65e0\u6548");
        }
        Set<String> validatedIds = new HashSet<>();
        for (AssistantRequests.Question question : questions)
        {
            if (question == null || StrUtil.isBlank(question.getQuestionId())
                    || question.getQuestionId().length() > 64 || !validatedIds.add(question.getQuestionId()))
            {
                throw validation("questionId", "\u95ee\u9898\u6807\u8bc6\u65e0\u6548");
            }
            if (!Arrays.asList("SINGLE", "MULTI", "TEXT").contains(question.getType()))
            {
                throw validation("type", "\u95ee\u9898\u7c7b\u578b\u65e0\u6548");
            }
            if (StrUtil.isBlank(question.getTitle()) || question.getTitle().length() > 300
                    || (question.getDescription() != null && question.getDescription().length() > 1000))
            {
                throw validation("title", "\u95ee\u9898\u6587\u672c\u65e0\u6548");
            }
            if (question.getMinSelections() != null && question.getMinSelections() < 0
                    || question.getMaxSelections() != null && question.getMaxSelections() < 0)
            {
                throw validation("selectionBounds", "\u9009\u62e9\u8303\u56f4\u65e0\u6548");
            }
            List<AssistantRequests.QuestionOption> options = question.getOptions();
            if (options == null || options.size() > 100)
            {
                throw validation("options", "\u95ee\u9898\u9009\u9879\u65e0\u6548");
            }
            Set<String> validatedOptionIds = new HashSet<>();
            for (AssistantRequests.QuestionOption option : options)
            {
                if (option == null || StrUtil.isBlank(option.getOptionId()) || option.getOptionId().length() > 64
                        || !validatedOptionIds.add(option.getOptionId()) || StrUtil.isBlank(option.getLabel())
                        || option.getLabel().length() > 200
                        || option.getDescription() != null && option.getDescription().length() > 500)
                {
                    throw validation("optionId", "\u95ee\u9898\u9009\u9879\u65e0\u6548");
                }
                String controlAction = option.getControlAction();
                if (controlAction != null
                        && (!Arrays.asList(AssistantQuestionControlPolicy.CONTINUE_RUN,
                        AssistantQuestionControlPolicy.PAUSE_RUN).contains(controlAction)
                        || AssistantQuestionControlPolicy.PAUSE_RUN.equals(controlAction)
                        && "MULTI".equals(question.getType()) && !option.isExclusive()))
                    throw validation("controlAction", "暂停选项必须是单选或排他选项");
            }
        }
        Set<String> ids = new HashSet<>();
        for (AssistantRequests.Question question : questions)
        {
            if (!ids.add(question.getQuestionId()))
            {
                throw validation("questionId", "问题编号重复");
            }
            if ("TEXT".equals(question.getType()))
            {
                question.setAllowFreeText(true);
            if (CollectionUtil.isNotEmpty(question.getOptions())
                    || question.getMinSelections() != null && question.getMinSelections() != 0
                    || question.getMaxSelections() != null && question.getMaxSelections() != 0)
                {
                    throw validation("options", "文本问题无选项");
                }
            }
            else
            {
                if (CollectionUtil.isEmpty(question.getOptions()))
                {
                    throw validation("options", "选项不能为空");
                }
                Set<String> optionIds = new HashSet<>();
                for (AssistantRequests.QuestionOption option : question.getOptions())
                {
                    if (!optionIds.add(option.getOptionId()))
                    {
                        throw validation("optionId", "选项编号重复");
                    }
                }
                int minimum = question.getMinSelections() == null
                        ? (question.isRequired() ? 1 : 0) : question.getMinSelections();
                int maximum = question.getMaxSelections() == null
                        ? ("SINGLE".equals(question.getType()) ? 1 : question.getOptions().size())
                        : question.getMaxSelections();
                if (maximum < 1 || minimum > maximum || maximum > question.getOptions().size()
                        || ("SINGLE".equals(question.getType()) && maximum > 1))
                {
                    throw validation("selectionBounds", "选择范围不正确");
                }
            }
        }
    }

    private void validateAnswer(AssistantRequests.Question question, List<String> selected,
                                String freeText, boolean requireComplete)
    {
        List<String> choices = normalizedChoices(selected);
        boolean hasText = StrUtil.isNotBlank(freeText);
        if (freeText != null && freeText.length() > 10000)
        {
            throw validation("freeText", "补充内容过长");
        }
        boolean pauseRequested = AssistantQuestionControlPolicy.requestsPause(question, choices);
        if ("TEXT".equals(question.getType()) && !choices.isEmpty())
        {
            throw validation("selectedOptionIds", "文本问题无选项");
        }
        if (!"TEXT".equals(question.getType()))
        {
            if (hasText && !question.isAllowFreeText())
            {
                throw validation("freeText", "\u5f53\u524d\u95ee\u9898\u4e0d\u5141\u8bb8\u81ea\u7531\u8865\u5145");
            }
            Set<String> allowed = question.getOptions().stream()
                    .map(AssistantRequests.QuestionOption::getOptionId).collect(Collectors.toSet());
            if (!allowed.containsAll(choices))
            {
                throw validation("selectedOptionIds", "选项不存在");
            }
            if ("SINGLE".equals(question.getType()) && choices.size() > 1)
            {
                throw validation("selectedOptionIds", "只能选择一项");
            }
            int exclusiveCount = (int) question.getOptions().stream()
                    .filter(item -> item.isExclusive() && choices.contains(item.getOptionId())).count();
            if (exclusiveCount > 0 && choices.size() > 1)
            {
                throw validation("selectedOptionIds", "互斥选项冲突");
            }
            if (question.getMaxSelections() != null && choices.size() > question.getMaxSelections())
            {
                throw validation("selectedOptionIds", "选择数量过多");
            }
            int minimum = question.getMinSelections() == null ? (question.isRequired() ? 1 : 0)
                    : question.getMinSelections();
            if (requireComplete && !pauseRequested && (!choices.isEmpty() || hasText || question.isRequired())
                    && choices.size() < minimum)
            {
                throw validation("selectedOptionIds", "选择数量不足");
            }
        }
        if (requireComplete && question.isRequired() && !pauseRequested)
        {
            if (!answerComplete(question, choices, freeText))
            {
                throw validation("answers", "必答题未完成");
            }
        }
    }

    private boolean answerComplete(AssistantRequests.Question question, List<String> selected, String freeText)
    {
        List<String> choices = normalizedChoices(selected);
        boolean hasText = StrUtil.isNotBlank(freeText);
        if ("TEXT".equals(question.getType())) return hasText;
        int minimum = question.getMinSelections() == null ? (question.isRequired() ? 1 : 0)
                : question.getMinSelections();
        if (minimum > 0) return choices.size() >= minimum;
        return !question.isRequired() || !choices.isEmpty() || question.isAllowFreeText() && hasText;
    }

    private QuestionSchema questionSchema(AidRuntimeInputRequest input)
    {
        List<AssistantRequests.Question> questions = JSON.parseArray(input.getQuestionBundleJson(),
                AssistantRequests.Question.class);
        if (questions == null)
        {
            questions = Collections.emptyList();
        }
        validateQuestions(questions);
        Map<String, AssistantRequests.Question> byId = questions.stream().collect(
                Collectors.toMap(AssistantRequests.Question::getQuestionId, Function.identity(), (a, b) -> a,
                        LinkedHashMap::new));
        return new QuestionSchema(questions, byId);
    }

    private int unansweredCount(Long userId, AidRuntimeInputRequest input, QuestionSchema schema)
    {
        Map<String, AidRuntimeInputDraft> drafts = loadDrafts(userId, input.getId()).stream()
                .collect(Collectors.toMap(AidRuntimeInputDraft::getQuestionId, Function.identity()));
        int count = 0;
        for (AssistantRequests.Question question : schema.questions)
        {
            if (!question.isRequired())
            {
                continue;
            }
            AidRuntimeInputDraft draft = drafts.get(question.getQuestionId());
            if (draft == null || !answerComplete(question, parseChoices(draft.getChoiceIdsJson()),
                    draft.getFreeText()))
            {
                count++;
            }
        }
        return count;
    }

    private List<AidRuntimeInputDraft> loadDrafts(Long userId, Long requestId)
    {
        return inputDraftMapper.selectList(new LambdaQueryWrapper<AidRuntimeInputDraft>()
                .eq(AidRuntimeInputDraft::getInputRequestId, requestId)
                .eq(AidRuntimeInputDraft::getUserId, userId)
                .orderByAsc(AidRuntimeInputDraft::getQuestionId));
    }

    private AssistantViews.InputRequest loadActiveInput(Long userId, AidRuntimeRun run)
    {
        AidRuntimeInputRequest input = inputRequestMapper.selectOne(new LambdaQueryWrapper<AidRuntimeInputRequest>()
                .eq(AidRuntimeInputRequest::getRunId, run.getId())
                .in(AidRuntimeInputRequest::getStatus, Arrays.asList("OPEN", "DRAFTING", "SUSPENDED"))
                .orderByDesc(AidRuntimeInputRequest::getRoundNo).last("LIMIT 1"));
        return input == null ? null : toInputRequest(userId, input);
    }

    private AssistantViews.InputRequest toInputRequest(Long userId, AidRuntimeInputRequest input)
    {
        AssistantViews.InputRequest result = new AssistantViews.InputRequest();
        result.setRequestId(id(input.getId()));
        result.setRunId(id(input.getRunId()));
        result.setRequestKey(input.getRequestKey());
        result.setRoundNo(input.getRoundNo() == null ? 0 : input.getRoundNo());
        result.setSchemaDigest(input.getSchemaDigest());
        result.setContextDigest(input.getContextDigest());
        result.setPlanRevision(value(input.getPlanRevision()));
        result.setStatus(input.getStatus());
        result.setCreatedAt(format(input.getCreateTime()));
        result.setCompletedAt("SUBMITTED".equals(input.getStatus()) ? format(input.getUpdateTime()) : null);
        QuestionSchema schema = questionSchema(input);
        result.setQuestions(schema.questions.stream().map(this::toQuestion).collect(Collectors.toList()));
        AidRuntimeRun run = runMapper.selectById(input.getRunId());
        int unanswered = unansweredCount(userId, input, schema);
        result.setDrafts(loadDrafts(userId, input.getId()).stream()
                .map(item -> toDraft(item, unanswered, run))
                .collect(Collectors.toList()));
        AidRuntimeInputView view = inputViewMapper.selectOne(new LambdaQueryWrapper<AidRuntimeInputView>()
                .eq(AidRuntimeInputView::getInputRequestId, input.getId())
                .eq(AidRuntimeInputView::getUserId, userId).last("LIMIT 1"));
        if (view != null)
        {
            AssistantViews.InputView viewResult = new AssistantViews.InputView();
            viewResult.setRequestId(id(input.getId()));
            viewResult.setCursorQuestionId(view.getCursorQuestionId());
            result.setView(viewResult);
        }
        return result;
    }

    private AssistantViews.InputRequest toInputRequest(AidRuntimeInputRequest input, AidRuntimeRun run,
                                                        List<AidRuntimeInputDraft> drafts)
    {
        AssistantViews.InputRequest result = new AssistantViews.InputRequest();
        result.setRequestId(id(input.getId()));
        result.setRunId(id(input.getRunId()));
        result.setRequestKey(input.getRequestKey());
        result.setRoundNo(input.getRoundNo() == null ? 0 : input.getRoundNo());
        result.setSchemaDigest(input.getSchemaDigest());
        result.setContextDigest(input.getContextDigest());
        result.setPlanRevision(value(input.getPlanRevision()));
        result.setStatus(input.getStatus());
        result.setCreatedAt(format(input.getCreateTime()));
        result.setCompletedAt("SUBMITTED".equals(input.getStatus()) ? format(input.getUpdateTime()) : null);
        QuestionSchema schema = questionSchema(input);
        result.setQuestions(schema.questions.stream().map(this::toQuestion).collect(Collectors.toList()));
        int unanswered = unansweredCount(schema, drafts);
        result.setDrafts(drafts.stream().map(item -> toDraft(item, unanswered, run))
                .collect(Collectors.toList()));
        return result;
    }

    private int unansweredCount(QuestionSchema schema, List<AidRuntimeInputDraft> draftList)
    {
        Map<String, AidRuntimeInputDraft> drafts = draftList.stream()
                .collect(Collectors.toMap(AidRuntimeInputDraft::getQuestionId, Function.identity()));
        int count = 0;
        for (AssistantRequests.Question question : schema.questions)
        {
            if (!question.isRequired())
            {
                continue;
            }
            AidRuntimeInputDraft draft = drafts.get(question.getQuestionId());
            if (draft == null || !answerComplete(question, parseChoices(draft.getChoiceIdsJson()),
                    draft.getFreeText()))
            {
                count++;
            }
        }
        return count;
    }

    private AssistantViews.Question toQuestion(AssistantRequests.Question source)
    {
        AssistantViews.Question target = new AssistantViews.Question();
        BeanUtils.copyProperties(source, target);
        target.setOptions(source.getOptions().stream().map(item -> {
            AssistantViews.QuestionOption option = new AssistantViews.QuestionOption();
            BeanUtils.copyProperties(item, option);
            return option;
        }).collect(Collectors.toList()));
        return target;
    }

    private AssistantViews.Session toSession(AidAssistantSession source)
    {
        AssistantViews.Session target = new AssistantViews.Session();
        target.setTransport(AssistantEntryTransport.of(source));
        target.setSessionId(id(source.getId()));
        target.setClientSessionId(source.getClientSessionId());
        target.setModuleCode(source.getModuleCode());
        target.setMode(source.getMode());
        target.setTitle(source.getTitle());
        target.setProjectId(id(zeroToNull(source.getProjectId())));
        target.setEpisodeId(zeroToNull(source.getProjectId()) == null ? null : id(source.getEpisodeId()));
        target.setWorkspaceId(id(source.getWorkspaceId()));
        target.setNodeObjectId(source.getNodeObjectId());
        JSONObject identity = "PREVIZ_3DBOX".equals(source.getModuleCode()) ? JSON.parseObject(source.getBaseInputJson()) : null;
        if (identity != null) target.setPrevizProjectId(identity.getString("previzProjectId"));
        boolean legacyReasoning = legacyReasoningPreferences(source.getBaseInputJson());
        target.setReasoningEnabled(legacyReasoning ? true : source.getReasoningEnabled());
        target.setReasoningLevel(source.getReasoningLevel());
        target.setShowReasoning(legacyReasoning ? true : source.getShowReasoning());
        target.setStatus(source.getStatus());
        target.setStateRevision(value(source.getStateRevision()));
        target.setLastMessageSeq(value(source.getLastMessageSeq()));
        target.setLastEventSeq(value(source.getLastEventSeq()));
        target.setActiveRunId(id(source.getActiveRuntimeRunId()));
        target.setLastActiveTime(format(source.getLastActiveTime()));
        return target;
    }

    private AssistantViews.Message toMessage(AidAssistantMessage source, List<AidAssistantMessagePart> parts,
                                             List<AssistantViews.MessageReference> references)
    {
        AssistantViews.Message target = new AssistantViews.Message();
        target.setMessageId(id(source.getId()));
        target.setSeq(value(source.getSeqNo()));
        target.setRole(source.getRole());
        target.setMessageKind(source.getMessageKind());
        target.setStatus(source.getStatus());
        target.setRuntimeRunId(id(source.getRuntimeRunId()));
        target.setTurnId(id(source.getTurnId()));
        target.setContent(AssistantControlTextPresentation.projectText(
                source.getRole(), source.getMessageKind(), source.getContentText()));
        target.setCreatedAt(format(source.getCreateTime()));
        if (references != null) target.setReferences(references);
        if (parts != null)
        {
            if ("ASSISTANT".equals(source.getRole()) && "COMPLETE".equals(source.getStatus())) {
                parts.stream().filter(item -> "FLOW_SCREENPLAY_DRAFT".equals(item.getSlotName())
                        && StrUtil.isNotBlank(item.getMetadataJson())).findFirst()
                        .ifPresent(item -> target.setFlowScreenplayDraft(JSON.parseObject(item.getMetadataJson())));
            }
            List<String> presented = AssistantControlTextPresentation.projectParts(
                    source.getRole(), source.getMessageKind(),
                    parts.stream().map(AidAssistantMessagePart::getTextContent).collect(Collectors.toList()));
            target.setParts(java.util.stream.IntStream.range(0, parts.size()).mapToObj(index -> {
                AidAssistantMessagePart item = parts.get(index);
                AssistantViews.MessagePart part = new AssistantViews.MessagePart();
                part.setPartSeq(item.getPartSeq());
                part.setType(item.getPartType());
                part.setText(presented.get(index));
                return part;
            }).collect(Collectors.toList()));
        }
        return target;
    }

    private Map<Long, List<AssistantViews.MessageReference>> loadMessageReferences(
            Long userId, AidAssistantSession session, List<AidAssistantMessage> messages)
    {
        Map<Long, AidAssistantMessage> originalByRun = messages.stream()
                .filter(message -> "USER".equals(message.getRole()))
                .filter(message -> "USER_INPUT".equals(message.getMessageKind()))
                .filter(message -> message.getRuntimeRunId() != null)
                .filter(message -> Objects.equals(message.getTurnId(), message.getId()))
                .collect(Collectors.toMap(AidAssistantMessage::getRuntimeRunId, Function.identity(),
                        (left, right) -> left, LinkedHashMap::new));
        Set<Long> referenceRunIds = new HashSet<>(originalByRun.keySet());
        messages.stream().filter(message -> "USER".equals(message.getRole())
                && Objects.toString(message.getMessageKind(), "").startsWith("USER_INTERJECTION")
                && message.getRuntimeRunId() != null).forEach(message -> referenceRunIds.add(message.getRuntimeRunId()));
        if (referenceRunIds.isEmpty()) return Collections.emptyMap();
        List<AidRuntimeRun> runs = runMapper.selectList(new LambdaQueryWrapper<AidRuntimeRun>()
                .in(AidRuntimeRun::getId, referenceRunIds)
                .eq(AidRuntimeRun::getUserId, userId)
                .eq(AidRuntimeRun::getSessionId, session.getId())
                .eq(AidRuntimeRun::getDelFlag, "0"));
        Map<Long, List<AssistantViews.MessageReference>> result = new HashMap<>();
        for (AidRuntimeRun run : runs)
        {
            AidAssistantMessage message = originalByRun.get(run.getId());
            JSONObject request = JSON.parseObject(run.getRequestJson());
            if (message != null && Objects.equals(run.getTurnId(), message.getId()))
                result.put(message.getId(), appendMessageChoices(session, projectMessageReferences(userId, session, run), request));
            for (AidAssistantMessage interjection : messages)
                if ("USER".equals(interjection.getRole()) && Objects.equals(interjection.getRuntimeRunId(), run.getId())
                        && Objects.toString(interjection.getMessageKind(), "").startsWith("USER_INTERJECTION"))
                    result.put(interjection.getId(), appendMessageChoices(session, List.of(), request));
        }
        return result;
    }

    private List<AssistantViews.MessageReference> appendMessageChoices(
            AidAssistantSession session, List<AssistantViews.MessageReference> references, JSONObject request)
    {
        var result = new ArrayList<>(references);
        if (request == null || !"CANVAS".equals(AssistantEntryTransport.of(session))) return result;
        JSONObject skill = request.getJSONObject("_selectedSkill");
        if (skill != null && StrUtil.isNotBlank(skill.getString("name")))
        {
            var value = new AssistantViews.MessageReference();
            value.setOrdinal(result.size()); value.setKind("SKILL"); value.setStatus("AVAILABLE");
            value.setLabel(skill.getString("name")); value.setMediaKind("OTHER"); result.add(value);
        }
        JSONArray models = request.getJSONArray("_selectedModels");
        if (models != null) for (int index = 0; index < Math.min(2, models.size()); index++)
        {
            JSONObject model = models.getJSONObject(index);
            if (model == null || StrUtil.isBlank(model.getString("modelName"))) continue;
            var value = new AssistantViews.MessageReference();
            value.setOrdinal(result.size()); value.setKind("MODEL"); value.setStatus("AVAILABLE");
            value.setLabel(model.getString("modelName") + (StrUtil.isBlank(model.getString("providerName"))
                    ? "" : " · " + model.getString("providerName")));
            value.setMediaKind("video".equals(model.getString("modelType")) ? "VIDEO" : "IMAGE");
            result.add(value);
        }
        return result;
    }

    private List<AssistantViews.MessageReference> projectMessageReferences(
            Long userId, AidAssistantSession session, AidRuntimeRun run)
    {
        List<FrozenReference> frozen;
        try
        {
            frozen = frozenReferences(userId, session, run);
        }
        catch (RuntimeException unavailable)
        {
            return List.of();
        }
        List<AssistantViews.MessageReference> result = new ArrayList<>();
        int ordinal = 0;
        for (FrozenReference value : frozen)
        {
            AssistantRequests.ContextRef ref = value.ref();
            if (isWorkspaceReference(ref)) continue;
            AssistantViews.MessageReference view = baseMessageReference(ordinal++, ref, value.label());
            try
            {
                boolean historicalNode = "FREE_CANVAS".equals(ref.getKind())
                        && StrUtil.isNotBlank(ref.getObjectId());
                AssistantRequests.ContextRef identity = ref;
                if (historicalNode)
                {
                    identity = copyContextRef(ref);
                    identity.setVersion(null);
                }
                ResolvedContextRef current = (historicalNode
                        ? scopeAccessRegistry.resolveCurrentAll(userId, List.of(identity))
                        : scopeAccessRegistry.resolvePinnedAll(userId, List.of(identity))).get(0);
                boolean exactAuthority = historicalNode
                        ? Objects.equals(canonicalContextIdentityWithoutVersion(ref),
                        canonicalContextIdentityWithoutVersion(current.contextRef()))
                        : Objects.equals(canonicalContextIdentity(ref), canonicalContextIdentity(current.contextRef()))
                        && Objects.equals(value.contentRef(), current.contentRef());
                if (!exactAuthority)
                {
                    view.setStatus("UNAVAILABLE");
                    result.add(view);
                    continue;
                }
                view.setStatus("AVAILABLE");
                view.setMediaKind(defaultReferenceMediaKind(ref.getKind()));
                List<AssistantMessageReferenceProjector> projectors = messageReferenceProjectors.stream()
                        .filter(projector -> projector.supports(ref.getKind())).toList();
                if (projectors.size() > 1)
                {
                    view.setStatus("UNAVAILABLE");
                    view.setMediaKind(null);
                }
                else if (projectors.size() == 1)
                {
                    AssistantMessageReferenceProjector.Projection projection = projectors.get(0)
                            .project(userId, ref, current, value.snapshot());
                    if (projection == null)
                    {
                        view.setStatus("UNAVAILABLE");
                        view.setMediaKind(null);
                    }
                    else
                    {
                        view.setMediaKind(projection.mediaKind());
                        view.setPreviewUrl(projection.previewUrl());
                    }
                }
            }
            catch (RuntimeException unavailable)
            {
                view.setStatus("UNAVAILABLE");
                view.setMediaKind(null);
                view.setPreviewUrl(null);
            }
            result.add(view);
        }
        return result;
    }

    private List<AssistantViews.MessageReference> eventMessageReferences(
            List<AssistantRequests.ContextRef> references,
            Map<Integer, FrozenPresentation> presentations)
    {
        if (CollectionUtil.isEmpty(references)) return List.of();
        List<AssistantViews.MessageReference> result = new ArrayList<>();
        int ordinal = 0;
        for (int index = 0; index < references.size(); index++)
        {
            AssistantRequests.ContextRef ref = references.get(index);
            if (ref == null || isWorkspaceReference(ref)) continue;
            FrozenPresentation presentation = presentations == null ? null : presentations.get(index);
            AssistantViews.MessageReference view = baseMessageReference(ordinal++, ref,
                    presentation == null ? null : presentation.label());
            view.setStatus("AVAILABLE");
            AssistantMessageReferenceProjector.Snapshot snapshot = presentation == null
                    ? null : presentation.snapshot();
            view.setMediaKind(snapshot == null
                    ? defaultReferenceMediaKind(ref.getKind()) : snapshot.mediaKind());
            result.add(view);
        }
        return result;
    }

    private AssistantViews.MessageReference baseMessageReference(
            int ordinal, AssistantRequests.ContextRef ref, String frozenLabel)
    {
        AssistantViews.MessageReference view = new AssistantViews.MessageReference();
        view.setOrdinal(ordinal);
        view.setKind(ref.getKind());
        view.setLabel(StrUtil.isNotBlank(frozenLabel)
                ? frozenLabel : StrUtil.trimToNull(ref.getReason()));
        view.setWorkspaceId(id(ref.getWorkspaceId()));
        view.setObjectId(ref.getObjectId());
        view.setResourceId(id(ref.getResourceId()));
        return view;
    }

    private String defaultReferenceMediaKind(String kind)
    {
        if ("SCRIPT".equals(kind)) return "TEXT";
        if ("DOCUMENT".equals(kind)) return "DOCUMENT";
        return "OTHER";
    }

    private Map<Long, List<AidAssistantMessagePart>> loadParts(List<AidAssistantMessage> messages)
    {
        if (messages.isEmpty())
        {
            return Collections.emptyMap();
        }
        List<Long> ids = messages.stream().map(AidAssistantMessage::getId).collect(Collectors.toList());
        return messagePartMapper.selectList(new LambdaQueryWrapper<AidAssistantMessagePart>()
                        .in(AidAssistantMessagePart::getMessageId, ids)
                        .eq(AidAssistantMessagePart::getDelFlag, "0")
                        .orderByAsc(AidAssistantMessagePart::getMessageId)
                        .orderByAsc(AidAssistantMessagePart::getPartSeq)).stream()
                .collect(Collectors.groupingBy(AidAssistantMessagePart::getMessageId, LinkedHashMap::new,
                        Collectors.toList()));
    }

    private AssistantViews.Run toRun(AidRuntimeRun source, AidAssistantSession session)
    {
        AssistantViews.Run target = new AssistantViews.Run();
        target.setRunId(id(source.getId()));
        JSONObject original = JSON.parseObject(source.getRequestJson());
        if (original != null)
        {
            target.setExecutionPreference(original.getString("executionPreference"));
            target.setAutomaticTextExecution(AssistantAutomaticTextExecution.enabled(original));
            JSONObject selectedSkill = original.getJSONObject("_selectedSkill");
            if (selectedSkill != null) target.setSelectedSkillCode(selectedSkill.getString("code"));
            JSONArray selectedModels = original.getJSONArray("_selectedModels");
            if (selectedModels != null) target.setSelectedModels(selectedModels.toJavaList(AssistantViews.ModelSelection.class));
        }
        target.setRevision(value(source.getStateRevision()));
        boolean pendingAutomaticText = AssistantAutomaticTextExecution.isDue(source);
        target.setStatus(pendingAutomaticText ? "READY" : source.getStatus());
        target.setPauseReason(source.getPauseReason());
        target.setResumeStatus(source.getResumeStatus());
        target.setStopRequested(Boolean.TRUE.equals(source.getStopRequested()));
        target.setNextAction(pendingAutomaticText ? "ADVANCE_AGENT" : source.getNextAction());
        if (target.isAutomaticTextExecution() && "FAILED".equals(source.getStatus())) {
            try {
                target.setAutomaticTextFailure(com.aid.assistant.error.AssistantPlanningPreparationRejectedException
                        .Reason.valueOf(Objects.toString(source.getErrorMessage(), "")).message());
            } catch (IllegalArgumentException ignored) {
                // A model or tool failure is rendered from its actual step.
            }
        }
        target.setAllowedActions(allowedActions(source));
        target.setActiveTaskIds(activeMediaTaskIds(source));
        target.setLastEventSeq(value(session.getLastEventSeq()));
        return target;
    }

    private List<String> allowedActions(AidRuntimeRun run)
    {
        if (RuntimeRunLifecycle.isTerminal(run.getStatus()))
        {
            return Collections.emptyList();
        }
        if (PAUSED.equals(run.getStatus()))
        {
            return Arrays.asList("RESUME", "STOP");
        }
        if (Arrays.asList("READY", "RUNNING", "WAITING_APPROVAL").contains(run.getStatus()))
        {
            return Arrays.asList("PAUSE", "STOP");
        }
        return Collections.singletonList("STOP");
    }

    private List<String> activeMediaTaskIds(AidRuntimeRun run)
    {
        Set<Long> ids = runStepMapper.selectList(new LambdaQueryWrapper<AidRuntimeRunStep>()
                        .select(AidRuntimeRunStep::getMediaTaskId)
                        .eq(AidRuntimeRunStep::getRunId, run.getId())
                        .isNotNull(AidRuntimeRunStep::getMediaTaskId)
                        .eq(AidRuntimeRunStep::getDelFlag, "0"))
                .stream().map(AidRuntimeRunStep::getMediaTaskId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (run.getMediaTaskId() != null) ids.add(run.getMediaTaskId());
        if (ids.isEmpty()) return Collections.emptyList();
        return mediaTaskMapper.selectList(new LambdaQueryWrapper<AidMediaTask>()
                        .select(AidMediaTask::getId, AidMediaTask::getUserId,
                                AidMediaTask::getBizTaskType, AidMediaTask::getStatus)
                        .in(AidMediaTask::getId, ids)).stream()
                .filter(task -> Objects.equals(task.getUserId(), run.getUserId())
                        && "ASSISTANT_RUNTIME_STEP".equals(task.getBizTaskType())
                        && !Arrays.asList("SUCCEEDED", "FAILED", "CANCELLED").contains(task.getStatus()))
                .map(task -> id(task.getId())).sorted().toList();
    }

    /**
     * A control command must treat an accepted child operation as active even when the legacy
     * run.media_task_id column is empty.  The run row is already locked by the caller, so a
     * Provider admission transaction cannot cross this decision without observing the new run
     * state.  Terminal Provider tasks remain active until their owning step is reconciled.
     */
    private boolean hasActiveMediaTask(AidRuntimeRun run)
    {
        boolean activeStep = runStepMapper.selectList(new LambdaQueryWrapper<AidRuntimeRunStep>()
                        .eq(AidRuntimeRunStep::getRunId, run.getId())
                        .eq(AidRuntimeRunStep::getDelFlag, "0"))
                .stream().anyMatch(step -> Arrays.asList("SUBMITTING", "INFLIGHT", "RUNNING", "RECONCILING")
                        .contains(step.getStatus())
                        || "READY".equals(step.getStatus()) && step.getMediaTaskId() != null);
        if (activeStep) return true;
        if (run.getMediaTaskId() == null) return false;
        AidMediaTask task = mediaTaskMapper.selectById(run.getMediaTaskId());
        return task != null && Objects.equals(task.getUserId(), run.getUserId())
                && !Arrays.asList("SUCCEEDED", "FAILED", "CANCELLED").contains(task.getStatus());
    }

    /**
     * Pause freezes a durable task intent that has not crossed Provider admission.  Such a task
     * already has a mediaTaskId and reservation, but providerAcceptanceState remains NOT_SUBMITTED;
     * the dispatcher defers it while PAUSED and resumes the same identity later.  Only an admitted
     * or unknown submission keeps the run in PAUSING for receipt reconciliation.
     */
    private boolean hasProviderAcceptedMediaTask(AidRuntimeRun run)
    {
        List<AidRuntimeRunStep> runSteps = runStepMapper.selectList(new LambdaQueryWrapper<AidRuntimeRunStep>()
                .eq(AidRuntimeRunStep::getRunId, run.getId())
                .eq(AidRuntimeRunStep::getDelFlag, "0"));
        boolean accepted = runSteps.stream().anyMatch(step ->
                Arrays.asList("SUBMITTING", "ACCEPTED", "UNKNOWN")
                        .contains(step.getProviderAcceptanceState()));
        if (accepted) return true;
        // A bound NOT_SUBMITTED intent is deliberately frozen by pause and must not be mistaken for
        // a legacy in-flight task merely because run.media_task_id was already projected.
        if (runSteps.stream().anyMatch(step -> step.getMediaTaskId() != null)) return false;
        if (run.getMediaTaskId() == null) return false;
        AidMediaTask task = mediaTaskMapper.selectById(run.getMediaTaskId());
        return task != null && Objects.equals(task.getUserId(), run.getUserId())
                && !Arrays.asList("SUCCEEDED", "FAILED", "CANCELLED").contains(task.getStatus());
    }

    private boolean hasActivePlanOperation(AidRuntimeRun run)
    {
        return runStepMapper.selectList(new LambdaQueryWrapper<AidRuntimeRunStep>()
                        .eq(AidRuntimeRunStep::getRunId, run.getId())
                        .in(AidRuntimeRunStep::getOperationKind, List.of("ASSISTANT_TOOL", "ASSISTANT_SKILL"))
                        .in(AidRuntimeRunStep::getStatus,
                                List.of("READY", "SUBMITTING", "INFLIGHT", "RUNNING", "RECONCILING"))
                        .eq(AidRuntimeRunStep::getDelFlag, "0"))
                .stream().findAny().isPresent();
    }

    private boolean hasPreparingPlanOperationForStop(AidRuntimeRun run)
    {
        return runStepMapper.selectList(new LambdaQueryWrapper<AidRuntimeRunStep>()
                        .eq(AidRuntimeRunStep::getRunId, run.getId())
                        .in(AidRuntimeRunStep::getOperationKind, List.of("ASSISTANT_TOOL", "ASSISTANT_SKILL"))
                        .eq(AidRuntimeRunStep::getStatus, "PREPARING")
                        .eq(AidRuntimeRunStep::getProviderAcceptanceState, "NOT_SUBMITTED")
                        .eq(AidRuntimeRunStep::getDelFlag, "0"))
                .stream().findAny().isPresent();
    }

    /**
     * A stopped run must retain a recovery route for a durable operation identity that has not
     * crossed Provider admission. Pause deliberately does not use this predicate: a READY operation
     * remains frozen and may resume with the same approval. Stop instead routes PREPARING through
     * its locked zero-side-effect proof, or READY through reservation release, before STOPPED.
     */
    private boolean hasPendingSubmissionForStop(AidRuntimeRun run)
    {
        return runStepMapper.selectList(new LambdaQueryWrapper<AidRuntimeRunStep>()
                        .eq(AidRuntimeRunStep::getRunId, run.getId())
                        .in(AidRuntimeRunStep::getOperationKind,
                                List.of("ASSISTANT_DECISION", "ASSISTANT_TOOL", "ASSISTANT_SKILL"))
                        .in(AidRuntimeRunStep::getStatus, "PREPARING", "READY")
                        .eq(AidRuntimeRunStep::getProviderAcceptanceState, "NOT_SUBMITTED")
                        .eq(AidRuntimeRunStep::getDelFlag, "0"))
                .stream().findAny().isPresent();
    }

    private AssistantViews.Draft toDraft(AidRuntimeInputDraft source, int unanswered, AidRuntimeRun run)
    {
        AssistantViews.Draft target = new AssistantViews.Draft();
        target.setRequestId(id(source.getInputRequestId()));
        target.setQuestionId(source.getQuestionId());
        target.setDraftRevision(value(source.getDraftRevision()));
        target.setSelectedOptionIds(parseChoices(source.getChoiceIdsJson()));
        target.setFreeText(source.getFreeText());
        target.setAnswerState(source.getAnswerState());
        target.setUnansweredCount(unanswered);
        target.setRunRevision(run == null ? 0L : value(run.getStateRevision()));
        return target;
    }

    private AssistantViews.InputSubmitted submittedView(AidRuntimeInputRequest input, AidRuntimeRun run,
                                                         AidAssistantSession session)
    {
        AssistantViews.InputSubmitted result = new AssistantViews.InputSubmitted();
        result.setRequestId(id(input.getId()));
        result.setAcceptedDigest(input.getAcceptedDigest());
        result.setRunId(id(run.getId()));
        result.setRunRevision(value(run.getStateRevision()));
        result.setStatus(run.getStatus());
        result.setNextAction(run.getNextAction());
        result.setLastEventSeq(value(session.getLastEventSeq()));
        return result;
    }

    private AssistantViews.Event toEvent(AidRuntimeEvent source)
    {
        AssistantViews.Event target = new AssistantViews.Event();
        target.setEventId(id(source.getId()));
        target.setSessionId(id(source.getSessionId()));
        target.setSeq(source.getSeq());
        target.setType(source.getEventType());
        target.setRunId(id(source.getRunId()));
        target.setEntityId(source.getEntityId());
        target.setEntityRevision(source.getEntityRevision());
        target.setCausationId(source.getCausationId());
        target.setOccurredAt(format(source.getCreateTime()));
        target.setData(StrUtil.isBlank(source.getPayloadJson()) ? null : JSON.parse(source.getPayloadJson()));
        return target;
    }

    @Override
    public AidAssistantSession authorizeSession(Long userId, Long sessionId, boolean lock)
    {
        if (lock && !org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Session lock requires transaction");
        AidAssistantSession session = requireSession(userId, sessionId, lock);
        verifyPersistedSessionScope(userId, session);
        return session;
    }

    private AidAssistantSession requireSession(Long userId, Long sessionId, boolean lock)
    {
        AidAssistantSession session = lock
                ? sessionMapper.selectOne(new LambdaQueryWrapper<AidAssistantSession>()
                        .eq(AidAssistantSession::getId, sessionId).eq(AidAssistantSession::getDelFlag, "0")
                        .last("FOR UPDATE"))
                : sessionMapper.selectById(sessionId);
        if (session == null || !"0".equals(session.getDelFlag())
                || !"ASSISTANT".equals(session.getSessionType()))
        {
            throw failure(HttpStatus.NOT_FOUND, "ASSISTANT_SESSION_NOT_FOUND", "会话不存在", id(sessionId));
        }
        if (!Objects.equals(userId, session.getUserId()))
        {
            throw failure(HttpStatus.FORBIDDEN, "ASSISTANT_SESSION_FORBIDDEN", "无权访问会话", id(sessionId));
        }
        return session;
    }

    private AidRuntimeRun requireRun(Long userId, Long runId, boolean lock)
    {
        AidRuntimeRun run = lock
                ? runMapper.selectOne(new LambdaQueryWrapper<AidRuntimeRun>()
                        .eq(AidRuntimeRun::getId, runId).eq(AidRuntimeRun::getDelFlag, "0").last("FOR UPDATE"))
                : runMapper.selectById(runId);
        if (run == null || !"0".equals(run.getDelFlag()) || !Objects.equals(userId, run.getUserId()))
        {
            throw failure(HttpStatus.NOT_FOUND, "RUNTIME_RUN_NOT_FOUND", "运行不存在", id(runId));
        }
        return run;
    }

    private AidAssistantSession requireRunSession(Long userId, AidRuntimeRun run)
    {
        AidAssistantSession session = sessionMapper.selectById(run.getSessionId());
        if (session == null || !"0".equals(session.getDelFlag())
                || !"ASSISTANT".equals(session.getSessionType())
                || !Objects.equals(userId, session.getUserId()))
        {
            throw failure(HttpStatus.NOT_FOUND, "RUNTIME_RUN_NOT_FOUND", "运行不存在", id(run.getId()));
        }
        return session;
    }

    private Long positiveStepCursor(String cursor)
    {
        if (cursor == null) return null;
        if (!cursor.matches("[1-9][0-9]{0,18}"))
        {
            throw validation("beforeStepId", "步骤游标无效");
        }
        try
        {
            long value = Long.parseLong(cursor);
            if (value < 1) throw new NumberFormatException("non-positive cursor");
            return value;
        }
        catch (NumberFormatException invalid)
        {
            throw validation("beforeStepId", "步骤游标无效");
        }
    }

    private void requireTransport(String transport)
    {
        if (!AssistantEntryTransport.supported(transport))
        {
            throw failure(HttpStatus.UNPROCESSABLE_ENTITY, "ASSISTANT_SCOPE_UNAVAILABLE",
                    "当前入口不可用", transport);
        }
    }

    private void requireRevision(Long supplied, long current)
    {
        if (!Objects.equals(supplied, current))
        {
            throw failure(HttpStatus.CONFLICT, "RUNTIME_REVISION_CONFLICT", "状态已更新",
                    new AssistantViews.RevisionDetails(supplied == null ? -1L : supplied, current));
        }
    }

    private AidRuntimeCommand findCommand(Long actorId, Long sessionId, String key, String payloadDigest)
    {
        AssistantDecisionCorrectionCommandNamespace.requireExternalClientRequestId(key);
        AidRuntimeCommand command = commandMapper.selectOne(new LambdaQueryWrapper<AidRuntimeCommand>()
                .eq(AidRuntimeCommand::getActorId, actorId)
                .eq(AidRuntimeCommand::getSessionId, sessionId)
                .eq(AidRuntimeCommand::getClientRequestId, key).last("LIMIT 1"));
        if (command == null)
        {
            return null;
        }
        if (!Objects.equals(payloadDigest, command.getPayloadDigest()))
        {
            throw idempotencyConflict(key);
        }
        if (!"COMPLETED".equals(command.getStatus()) || StrUtil.isBlank(command.getResultJson()))
        {
            throw failure(HttpStatus.CONFLICT, "SNAPSHOT_REQUIRED", "\u8bf7\u5237\u65b0\u72b6\u6001",
                    commandStateDetails(command));
        }
        return command;
    }

    private AidRuntimeCommand startCommand(Long actorId, Long sessionId, String key, String payloadDigest,
                                           String commandType)
    {
        AssistantDecisionCorrectionCommandNamespace.requireExternalClientRequestId(key);
        AidRuntimeCommand command = new AidRuntimeCommand();
        command.setActorId(actorId);
        command.setSessionId(sessionId);
        command.setClientRequestId(key);
        command.setPayloadDigest(payloadDigest);
        command.setCommandType(commandType);
        command.setStatus("PROCESSING");
        Date now = DateUtils.getNowDate();
        setCreateAudit(command, actorId, now);
        try
        {
            commandMapper.insert(command);
        }
        catch (DuplicateKeyException duplicate)
        {
            log.info("助手命令并发冲突, actorId={}, sessionId={}, key={}", actorId, sessionId, key);
            throw failure(HttpStatus.CONFLICT, "SNAPSHOT_REQUIRED", "请刷新状态",
                    commandKeyDetails(sessionId, key));
        }
        return command;
    }

    private void completeCommand(AidRuntimeCommand command, Object result, Long actorId, Date now)
    {
        command.setStatus("COMPLETED");
        command.setResultJson(JSON.toJSONString(result));
        setUpdateAudit(command, actorId, now);
        commandMapper.updateById(command);
    }

    private <T> T replayResult(AidRuntimeCommand command, Class<T> resultType)
    {
        return JSON.parseObject(command.getResultJson(), resultType);
    }

    private void appendEvent(AidAssistantSession session, Long runId, String type, String entityId,
                             Long entityRevision, String causationId, Object payload, Date now, Long actorId)
    {
        session.setLastEventSeq(value(session.getLastEventSeq()) + 1);
        setUpdateAudit(session, actorId, now);
        sessionMapper.updateById(session);
        AidRuntimeEvent event = new AidRuntimeEvent();
        event.setSessionId(session.getId());
        event.setRunId(runId);
        event.setSeq(session.getLastEventSeq());
        event.setEventType(type);
        event.setEntityId(entityId);
        event.setEntityRevision(entityRevision);
        event.setCausationId(causationId);
        event.setPayloadJson(payload == null ? null : JSON.toJSONString(payload));
        event.setPublishAttempt(0);
        event.setCreateTime(now);
        eventMapper.insert(event);
        realtimeEventPublisher.publishAfterCommit(event);
    }

    private Map<String, Object> sessionPayload(AidAssistantSession session)
    {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", id(session.getId()));
        payload.put("moduleCode", session.getModuleCode());
        payload.put("status", session.getStatus());
        return payload;
    }

    private Map<String, Object> messagePayload(AidAssistantMessage message)
    {
        return messagePayload(message, List.of());
    }

    private Map<String, Object> messagePayload(AidAssistantMessage message,
                                               List<AssistantViews.MessageReference> references)
    {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messageId", id(message.getId()));
        payload.put("seq", message.getSeqNo());
        payload.put("role", message.getRole());
        payload.put("references", references == null ? List.of() : references);
        return payload;
    }

    private Map<String, Object> userReplanningPayload(AidRuntimeRun run)
    {
        Map<String, Object> payload = new LinkedHashMap<>(runPayload(run));
        payload.put("replanBoundaryVersion", 1);
        payload.put("retiredPlanRevision", value(run.getPlanRevision()));
        payload.put("retiredContextDigest", run.getContextDigest());
        return payload;
    }

    private Map<String, Object> runPayload(AidRuntimeRun run)
    {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", id(run.getId()));
        payload.put("revision", value(run.getStateRevision()));
        payload.put("status", run.getStatus());
        payload.put("nextAction", run.getNextAction());
        payload.put("pauseReason", run.getPauseReason());
        return payload;
    }

    private Map<String, Object> inputPayload(AidRuntimeInputRequest input)
    {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", id(input.getId()));
        payload.put("status", input.getStatus());
        payload.put("schemaDigest", input.getSchemaDigest());
        return payload;
    }

    private Map<String, Object> inputStateDetails(AidRuntimeInputRequest input, AidRuntimeRun run)
    {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("requestId", id(input.getId()));
        details.put("status", input.getStatus());
        details.put("roundNo", input.getRoundNo());
        details.put("schemaDigest", input.getSchemaDigest());
        details.put("runRevision", value(run.getStateRevision()));
        return details;
    }

    private Map<String, Object> schemaStaleDetails(AssistantRequests.InputSubmit request,
                                                   AidRuntimeInputRequest input, AidRuntimeRun run)
    {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("suppliedSchemaDigest", request.getSchemaDigest());
        details.put("currentSchemaDigest", input.getSchemaDigest());
        details.put("suppliedContextDigest", request.getContextDigest());
        details.put("currentContextDigest", input.getContextDigest());
        details.put("requestId", id(input.getId()));
        details.put("status", input.getStatus());
        details.put("runRevision", value(run.getStateRevision()));
        return details;
    }

    private Map<String, Object> runStateDetails(AidRuntimeRun run, AidAssistantSession session)
    {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("runId", id(run.getId()));
        details.put("revision", value(run.getStateRevision()));
        details.put("status", run.getStatus());
        details.put("pauseReason", run.getPauseReason());
        details.put("lastEventSeq", session == null ? null : value(session.getLastEventSeq()));
        return details;
    }

    private Map<String, Object> draftConflictDetails(String questionId, AidRuntimeInputDraft draft,
                                                      AidRuntimeRun run)
    {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("questionId", questionId);
        details.put("currentDraftRevision", draft == null ? 0L : value(draft.getDraftRevision()));
        details.put("answerState", draft == null ? "EMPTY" : draft.getAnswerState());
        details.put("runRevision", value(run.getStateRevision()));
        return details;
    }

    private Map<String, Object> commandStateDetails(AidRuntimeCommand command)
    {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("clientRequestId", command.getClientRequestId());
        details.put("commandType", command.getCommandType());
        details.put("status", command.getStatus());
        AidAssistantSession session = sessionMapper.selectById(command.getSessionId());
        details.put("lastEventSeq", session == null ? null : value(session.getLastEventSeq()));
        return details;
    }

    private Map<String, Object> commandKeyDetails(Long sessionId, String key)
    {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("clientRequestId", key);
        details.put("status", "PROCESSING");
        AidAssistantSession session = sessionMapper.selectById(sessionId);
        details.put("lastEventSeq", session == null ? null : value(session.getLastEventSeq()));
        return details;
    }

    private Map<String, Object> draftPayload(AidRuntimeInputDraft draft)
    {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", id(draft.getInputRequestId()));
        payload.put("questionId", draft.getQuestionId());
        payload.put("draftRevision", value(draft.getDraftRevision()));
        payload.put("answerState", draft.getAnswerState());
        return payload;
    }

    private Map<String, Object> viewPayload(AidRuntimeInputView view)
    {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", id(view.getInputRequestId()));
        payload.put("cursorQuestionId", view.getCursorQuestionId());
        return payload;
    }

    private AcceptedAnswer acceptedAnswer(AidRuntimeInputDraft draft)
    {
        AcceptedAnswer answer = new AcceptedAnswer();
        answer.questionId = draft.getQuestionId();
        answer.draftRevision = value(draft.getDraftRevision());
        answer.selectedOptionIds = parseChoices(draft.getChoiceIdsJson());
        answer.freeText = draft.getFreeText();
        return answer;
    }

    private List<String> parseChoices(String json)
    {
        if (StrUtil.isBlank(json))
        {
            return new ArrayList<>();
        }
        List<String> values = JSON.parseArray(json, String.class);
        return values == null ? new ArrayList<>() : values;
    }

    private List<String> normalizedChoices(List<String> selected)
    {
        if (CollectionUtil.isEmpty(selected))
        {
            return new ArrayList<>();
        }
        return selected.stream().filter(StrUtil::isNotBlank).map(String::trim).distinct().sorted()
                .collect(Collectors.toList());
    }

    private List<String> stringArray(com.alibaba.fastjson2.JSONArray values)
    {
        if (values == null || values.isEmpty()) return new ArrayList<>();
        List<String> result = new ArrayList<>();
        for (Object raw : values)
        {
            if (raw instanceof String value && StrUtil.isNotBlank(value)) result.add(value);
        }
        return result;
    }

    private String abbreviate(String value, int maxLength)
    {
        if (StrUtil.isBlank(value)) return null;
        String normalized = value.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength - 1) + "\u2026";
    }

    private Map<Long, AidMediaTask> loadAuthorizedStepTasks(Long userId, List<AidRuntimeRunStep> steps)
    {
        if (CollectionUtil.isEmpty(steps)) return Collections.emptyMap();
        Map<Long, AidRuntimeRunStep> stepsById = steps.stream()
                .filter(step -> step.getId() != null && step.getMediaTaskId() != null)
                .collect(Collectors.toMap(AidRuntimeRunStep::getId, Function.identity(), (left, right) -> right,
                        LinkedHashMap::new));
        if (stepsById.isEmpty()) return Collections.emptyMap();
        List<Long> taskIds = stepsById.values().stream().map(AidRuntimeRunStep::getMediaTaskId)
                .distinct().toList();
        Map<Long, AidMediaTask> result = new HashMap<>();
        for (int offset = 0; offset < taskIds.size(); offset += TASK_PROJECTION_BATCH_SIZE)
        {
            List<Long> batch = taskIds.subList(offset, Math.min(offset + TASK_PROJECTION_BATCH_SIZE,
                    taskIds.size()));
            List<AidMediaTask> tasks = mediaTaskMapper.selectList(new LambdaQueryWrapper<AidMediaTask>()
                    .select(AidMediaTask::getId, AidMediaTask::getBizTaskId, AidMediaTask::getBizTaskType,
                            AidMediaTask::getMediaType, AidMediaTask::getModelName, AidMediaTask::getStatus,
                            AidMediaTask::getBillingStatus, AidMediaTask::getProviderTaskId,
                            AidMediaTask::getOutputDurationSeconds, AidMediaTask::getErrorDetailJson,
                            AidMediaTask::getUserId, AidMediaTask::getProtocol,
                            AidMediaTask::getUpstreamAcceptTime, AidMediaTask::getCreateTime,
                            AidMediaTask::getErrorMessage)
                    .in(AidMediaTask::getId, batch)
                    .eq(AidMediaTask::getUserId, userId)
                    .eq(AidMediaTask::getBizTaskType, "ASSISTANT_RUNTIME_STEP"));
            for (AidMediaTask task : tasks)
            {
                AidRuntimeRunStep step = stepsById.get(task.getBizTaskId());
                if (step != null && Objects.equals(step.getMediaTaskId(), task.getId()))
                    result.put(step.getId(), task);
            }
        }
        return result;
    }

    private StepErrorProjection projectStepError(String raw, AidMediaTask authorizedTask,
                                                  AidRuntimeRunStep step)
    {
        if (step != null && "SUCCEEDED".equals(step.getStatus())) return null;
        TaskErrorResult taskError = authorizedTask == null
                ? null : TaskErrorSnapshot.read(authorizedTask.getErrorDetailJson());
        if (taskError != null) return taskErrorProjection(taskError, step);
        String value = StrUtil.trim(raw);
        if (StrUtil.isBlank(value)) return null;
        if (step != null && "NOT_SUBMITTED".equals(step.getProviderAcceptanceState())) {
            if (com.aid.compute.ComputeResourcesUnavailableException.CODE.equals(value))
                return new StepErrorProjection(value,"构建资源维护中，请稍后重试");
            if ("TOOL_WAITING_RESOURCE".equals(value) && "RECONCILING".equals(step.getStatus()))
                return new StepErrorProjection(value,com.aid.runtime.tool.RuntimeResourceWait.message(
                        com.aid.runtime.tool.RuntimeResourceWait.position(step.getOutputJson())));
        }
        TaskErrorCode standardCode = taskErrorCode(value);
        if (standardCode != null) return taskErrorProjection(TaskErrorResult.of(standardCode), step);
        String assistantCode = publicStepErrorCode(value);
        if (assistantCode != null)
            return new StepErrorProjection(assistantCode, stepErrorMessage(assistantCode));
        TaskErrorResult classified = TaskErrorSnapshot.read(TaskErrorSnapshot.fromMessage(value));
        if (!TaskErrorPresentation.isGeneric(classified)) return taskErrorProjection(classified, step);
        return new StepErrorProjection("OPERATION_FAILED", stepErrorMessage("OPERATION_FAILED"));
    }

    private StepErrorProjection taskErrorProjection(TaskErrorResult error, AidRuntimeRunStep step)
    {
        String message = error.getUserMessage();
        if ((TaskErrorCode.UPSTREAM_BAD_REQUEST.name().equals(error.getErrorCode())
                || "UPSTREAM_TECH".equals(error.getErrorType()))
                && step != null && ("MAIN_AGENT".equals(step.getStepKind())
                || "ASSISTANT_DECISION".equals(step.getOperationKind())))
            message = "助手分析服务暂不可用；已完成的操作结果仍保留，请仅继续未完成步骤";
        return new StepErrorProjection(error.getErrorCode(), message);
    }

    private TaskErrorCode taskErrorCode(String value)
    {
        try
        {
            return TaskErrorCode.valueOf(value);
        }
        catch (IllegalArgumentException ignored)
        {
            return null;
        }
    }

    private String publicStepErrorCode(String raw)
    {
        if (StrUtil.isBlank(raw)) return null;
        if (Set.of("NATIVE_DECISION_CORRECTION_SLOT_CLAIMED", "MODEL_OUTPUT_INVALID", "MODEL_OUTPUT_EMPTY", "AGENT_OUTPUT_CONTRACT_INVALID", "MODEL_FUNCTION_NOT_EXPOSED", "MODEL_PLAN_DEPENDENCY_INVALID", "OPERATION_DECISION_INVALID", "COMPLETION_DECISION_REJECTED", "SUPERSEDED_BY_USER_MESSAGE", "PROVIDER_ACCEPTANCE_UNKNOWN",
                "PLAN_INPUT_BINDINGS_UNRESOLVED", "PLAN_DEPENDENCY_INCOMPLETE",
                "PLAN_COMPLETION_EVIDENCE_UNSATISFIABLE",
                "BUDGET_POLICY_MISSING", "BUDGET_POLICY_EXPIRED", "BUDGET_POLICY_INACTIVE", "BUDGET_POLICY_EXHAUSTED",
                "BUDGET_AUTHORIZATION_UNAVAILABLE", "BUDGET_AUTHORIZATION_EXPIRED",
                "BUDGET_AUTHORIZATION_REVOKED", "BUDGET_AUTHORIZATION_CLOSED",
                "BUDGET_AUTHORIZATION_CHANGED", "BUDGET_SCOPE_DENIED", "BUDGET_RENEWAL_CONFIRMATION_REQUIRED",
                "UNSUBMITTED_RESERVATION_RELEASE_UNKNOWN", "MEDIA_TASK_CREATION_UNKNOWN",
                "MEDIA_TASK_IDENTITY_UNKNOWN", "MEDIA_TASK_FAILED", "TOOL_PREPARATION_MODEL_REQUIRED",
                "TOOL_PREPARATION_MODEL_UNAVAILABLE", "TOOL_PREPARATION_SELECTED_MODEL_MISMATCH", "TOOL_PREPARATION_MODEL_CAPABILITY_INVALID",
                "TOOL_PREPARATION_MODEL_PARAMETERS_INVALID", "TOOL_PREPARATION_MEDIA_INPUTS_INVALID",
                "TOOL_PREPARATION_REQUEST_INVALID", "TOOL_PREPARATION_CONTRACT_CHANGED",
                "TOOL_PREPARATION_ARGUMENTS_INVALID", "TOOL_PREPARATION_RESULT_INVALID",
                "TOOL_PREPARATION_PLAN_INVALID", "TOOL_PREPARATION_PLAN_MODE_INVALID",
                "TOOL_PREPARATION_ARTIFACT_CREATE_EXPECTED", "TOOL_PREPARATION_ARTIFACT_REVISION_INVALID",
                "TOOL_PREPARATION_SCOPE_INVALID", "TOOL_PREPARATION_IDENTITY_CHANGED",
                "TOOL_PREPARATION_QUOTE_UNAVAILABLE", "TOOL_PREPARATION_QUOTE_INVALID",
                "TOOL_PREPARATION_QUOTE_EXPIRED",
                "TOOL_PREPARATION_IMAGE_REFERENCE_REQUIRED",
                "TOOL_PREPARATION_SKILL_RESOURCE_PATH_INVALID",
                "TOOL_PREPARATION_SKILL_MASTER_DIGEST_REQUIRED",
                "TOOL_PREPARATION_AGENT_RESOURCE_PATH_INVALID",
                "TOOL_PREPARATION_AGENT_MASTER_DIGEST_REQUIRED",
                "SKILL_MASTER_DIGEST_MISMATCH", "SKILL_MASTER_READ_REQUIRED",
                "AGENT_MASTER_RESOURCE_MISSING", "AGENT_MASTER_DIGEST_MISMATCH",
                "AGENT_MASTER_READ_REQUIRED", "AGENT_RESOURCE_MISSING",
                "PACKAGE_READ_RECEIPT_INVALID",
                "TOOL_PREPARATION_REFERENCE_CONTEXT_INVALID",
                "TOOL_PREPARATION_REFERENCE_CONTEXT_TOO_LARGE", "SKILL_REFERENCE_DESCRIPTION_REQUIRED",
                "SKILL_REFERENCE_MEDIA_UNSUPPORTED", "SKILL_REFERENCE_CONTEXT_TOO_LARGE",
                "SKILL_REFERENCE_COUNT_EXCEEDED").contains(raw)) return raw;
        if (Set.of("SKILL_REFERENCE_CONTEXT_INVALID", "SKILL_REFERENCE_CONTEXT_CHANGED",
                "ASSISTANT_CONTEXT_PROJECTOR_MISSING", "ASSISTANT_CONTEXT_PROJECTOR_AMBIGUOUS")
                .contains(raw)) return "SKILL_REFERENCE_CONTEXT_INVALID";
        if (raw.startsWith("UNAPPROVED_")) return "UNAPPROVED_ACTION";
        return isPublicErrorCode(raw) ? raw : null;
    }

    private boolean isPublicErrorCode(String value)
    {
        if (value.length() > 128 || value.charAt(0) < 'A' || value.charAt(0) > 'Z') return false;
        for (int index = 1; index < value.length(); index++)
        {
            char current = value.charAt(index);
            if (current != '_' && (current < 'A' || current > 'Z') && (current < '0' || current > '9'))
                return false;
        }
        return true;
    }

    private String stepErrorMessage(String code)
    {
        return switch (code)
        {
            case "ASSISTANT_CONTEXT_STALE" -> "引用内容已更新，分析已暂停；请更新引用后继续。已创建节点和提示词保留。";
            case "ASSISTANT_SCOPE_DENIED" -> "引用内容当前不可访问，分析已暂停；请检查访问权限或替换引用。";
            case "SUPERSEDED_BY_USER_MESSAGE" -> "\u5df2根据新消息重新决策";
            case "PROVIDER_ACCEPTANCE_UNKNOWN", "UNSUBMITTED_RESERVATION_RELEASE_UNKNOWN",
                    "MEDIA_TASK_CREATION_UNKNOWN", "MEDIA_TASK_IDENTITY_UNKNOWN" -> "\u4efb务状态待对账";
            case "TOOL_PREPARATION_MODEL_REQUIRED" -> "操作缺少生成模型，尚未提交任务";
            case "TOOL_PREPARATION_ALREADY_COMPLETED" -> "本轮已有相同操作的成功结果，已阻止重复生成；请复用已有结果，只处理尚未完成的步骤";
            case "TOOL_PREPARATION_MODEL_UNAVAILABLE" -> "所选模型当前不可用，尚未提交任务";
            case "TOOL_PREPARATION_SELECTED_MODEL_MISMATCH" -> "准备执行的模型与本次选择不一致，已阻止提交，请重新核对模型";
            case "TOOL_PREPARATION_MODEL_CAPABILITY_INVALID" -> "模型不支持该操作，尚未提交任务";
            case "TOOL_PREPARATION_MODEL_PARAMETERS_INVALID" -> "生成参数不符合模型要求，请按所选模型核对参数；尚未提交任务";
            case "TOOL_PREPARATION_MEDIA_INPUTS_INVALID" -> "素材或生成参数不符合所选能力要求，尚未提交任务";
            case "TOOL_PREPARATION_REQUEST_INVALID", "TOOL_PREPARATION_ARGUMENTS_INVALID" ->
                    "操作参数不符合当前工具契约，尚未提交任务";
            case "PREVIZ_SOURCE_IMPORT_INPUT_INVALID" ->
                    "源码导入路径或目标未通过校验，本次没有写入；已保存的项目文件仍可使用。";
            case "TOOL_PREPARATION_PLAN_INVALID" -> "计划字段缺失或分项信息不完整，尚未提交；请修正计划后继续";
            case "TOOL_PREPARATION_PLAN_MODE_INVALID" -> "计划参数与当前创作模式不匹配，尚未提交；请按工具说明修正后继续";
            case "TOOL_PREPARATION_ARTIFACT_CREATE_EXPECTED" -> "目标文件尚不存在，应使用新建条件（版本0、空内容哈希）；尚未提交";
            case "TOOL_PREPARATION_ARTIFACT_REVISION_INVALID" -> "目标文件版本或内容哈希不匹配，尚未提交；请重新读取目标文件，不要使用工作区版本";
            case "TOOL_PREPARATION_CONTRACT_CHANGED" -> "工具契约已更新，尚未提交任务；请重新规划";
            case "TOOL_PREPARATION_RESULT_INVALID", "TOOL_PREPARATION_SCOPE_INVALID",
                    "TOOL_PREPARATION_IDENTITY_CHANGED" -> "操作准备校验失败，尚未提交任务；请重新规划";
            case "TOOL_PREPARATION_QUOTE_UNAVAILABLE" -> "当前无法生成可靠报价，尚未提交任务";
            case "TOOL_PREPARATION_QUOTE_INVALID" -> "报价快照校验失败，尚未提交任务";
            case "TOOL_PREPARATION_QUOTE_EXPIRED" -> "操作报价在提交前已过期，尚未提交任务；请重新报价";
            case "TOOL_PREPARATION_RESULT_REFERENCE_INVALID" -> "结果引用无效，尚未写入；请重新读取可用结果引用";
            case "TOOL_PREPARATION_RESULT_REFERENCE_FORBIDDEN" -> "无权使用该结果，尚未写入；请使用当前对话允许访问的结果";
            case "TOOL_PREPARATION_RESULT_REFERENCE_NOT_READY" -> "引用的结果尚未成功完成，尚未写入";
            case "TOOL_PREPARATION_RESULT_REFERENCE_CHANGED" -> "结果来源校验不一致，尚未写入；请重新读取可用结果引用";
            case "TOOL_PREPARATION_RESULT_POINTER_INVALID" -> "结果字段路径格式无效，尚未写入；请使用结果引用工具返回的路径";
            case "TOOL_PREPARATION_RESULT_TEXT_NOT_FOUND" -> "指定的结果字段不存在，尚未写入；请重新读取可用结果引用";
            case "TOOL_PREPARATION_RESULT_NOT_TEXT" -> "指定字段不是文本，尚未写入；请选择文本结果字段";
            case "TOOL_PREPARATION_RESULT_TEXT_TOO_LARGE" -> "结果文本超过本次操作长度限制，尚未写入；原文保留，请调整交付范围后重新确认";
            case "TOOL_PREPARATION_RESULT_TEXT_EMPTY" -> "结果文本为空，尚未写入；请检查来源结果";
            case "TOOL_PREPARATION_IMAGE_REFERENCE_REQUIRED" -> "图生图需要图片参考；没有参考图时请选择文生图，尚未提交任务";
            case "TOOL_PREPARATION_SKILL_RESOURCE_PATH_INVALID" -> "Skill资源路径无效，尚未读取或执行";
            case "TOOL_PREPARATION_SKILL_MASTER_DIGEST_REQUIRED" -> "请先读取同一固定版本的Skill主文件，尚未读取子资源";
            case "TOOL_PREPARATION_AGENT_RESOURCE_PATH_INVALID" -> "Agent资源路径无效，尚未读取或执行";
            case "TOOL_PREPARATION_AGENT_MASTER_DIGEST_REQUIRED" -> "请先读取同一固定版本的Agent主文件，尚未读取子资源";
            case "SKILL_MASTER_DIGEST_MISMATCH" -> "固定版本的Skill主文件摘要不匹配，请重新读取主文件";
            case "SKILL_MASTER_READ_REQUIRED" -> "请先读取同一固定版本的Skill主文件，尚未读取子资源";
            case "AGENT_MASTER_RESOURCE_MISSING" -> "固定版本的Agent主文件不存在";
            case "AGENT_MASTER_DIGEST_MISMATCH" -> "固定版本的Agent主文件摘要不匹配，请重新读取主文件";
            case "AGENT_MASTER_READ_REQUIRED" -> "请先读取同一固定版本的Agent主文件，尚未读取子资源";
            case "AGENT_RESOURCE_MISSING" -> "固定版本的Agent参考资源不存在";
            case "PACKAGE_READ_RECEIPT_INVALID" -> "固定包读取回执无效，请重新开始本次任务";
            case "TOOL_PREPARATION_REFERENCE_CONTEXT_INVALID" -> "引用内容不可用，尚未提交任务";
            case "CONTEXT_CONTRACT_DOCUMENT_UNAVAILABLE" ->
                    "工具契约引用不可读取，已完成的操作仍保留；请按当前目录的契约引用继续处理";
            case "AUTO_INPUT_UNRESOLVED" -> "自动执行未能确定可行参数，已完成的内容仍保留";
            case "TOOL_PREPARATION_REFERENCE_CONTEXT_TOO_LARGE" -> "引用内容过大，尚未提交任务";
            case "SKILL_REFERENCE_DESCRIPTION_REQUIRED" -> "媒体引用需要补充文字说明，尚未提交 Skill";
            case "SKILL_REFERENCE_MEDIA_UNSUPPORTED" -> "Skill 不支持该媒体引用，尚未提交";
            case "SKILL_REFERENCE_CONTEXT_TOO_LARGE" -> "Skill 引用内容过大，尚未提交";
            case "SKILL_REFERENCE_COUNT_EXCEEDED" -> "Skill 引用数量过多，尚未提交";
            case "SKILL_REFERENCE_CONTEXT_INVALID" -> "Skill 引用不可用，尚未提交";
            case "NATIVE_DECISION_CORRECTION_SLOT_CLAIMED" -> "这一步计划未通过校验，已转入自动纠正，尚未执行画布操作。";
            case "MODEL_OUTPUT_INVALID" -> "助手返回的执行指令未通过格式或计划校验，本步操作未提交。";
            case "MODEL_OUTPUT_EMPTY" -> "模型未返回正文";
            case "AGENT_OUTPUT_CONTRACT_INVALID" -> "模型返回的内容未通过智能体输出契约校验";
            case "MODEL_FUNCTION_NOT_EXPOSED" -> "模型请求了本轮未开放的操作，该操作未执行；已经生成的结果仍保留。";
            case "MODEL_PLAN_DEPENDENCY_INVALID" -> "模型返回的计划引用了未声明的步骤，本步生成未提交。请重新发起或选择其他模型。";
            case "OPERATION_DECISION_INVALID" -> "助手操作指令无效，本次操作未提交；请重新发起请求";
            case "COMPLETION_DECISION_REJECTED" -> "完成证据校验失败";
            case "PLAN_INPUT_BINDINGS_UNRESOLVED" -> "前置产物尚未生成，本次决定已拒绝；请按真实步骤重新规划，未提交此操作";
            case "PLAN_DEPENDENCY_INCOMPLETE" -> "前置步骤尚未完成，本次决定已拒绝；请先完成依赖，未提交此操作";
            case "PLAN_COMPLETION_EVIDENCE_UNSATISFIABLE" -> "计划声明的完成证据无法由当前步骤生成，本次计划未保存；请使用真实可产出的证据重新规划";
            case "BUDGET_RENEWAL_CONFIRMATION_REQUIRED" -> "消费授权已恢复，请重新报价并明确确认本次新调用";
            case "BUDGET_POLICY_EXHAUSTED" -> "预算次数已用完，操作尚未提交；可在原对话调整限额并确认后继续";
            case "BUDGET_POLICY_EXPIRED", "BUDGET_AUTHORIZATION_EXPIRED" ->
                    "本轮消费授权已到期，历史内容仍保留；请在原对话重新确认授权和本次费用后继续";
            case "BUDGET_POLICY_MISSING", "BUDGET_POLICY_INACTIVE", "BUDGET_AUTHORIZATION_UNAVAILABLE",
                    "BUDGET_AUTHORIZATION_REVOKED", "BUDGET_AUTHORIZATION_CLOSED",
                    "BUDGET_AUTHORIZATION_CHANGED" ->
                    "消费授权不可用，操作尚未提交；请核实权限与授权状态，历史对话仍保留";
            case "BUDGET_SCOPE_DENIED" -> "操作超出当前运行已批准范围，尚未提交；请调整授权后显式恢复";
            case "UNAPPROVED_ACTION" -> "\u64cd作未经授权";
            default -> "\u64cd作执行失败";
        };
    }

    private record StepErrorProjection(String errorCode, String safeMessage) { }

    private String digestDraft(AssistantRequests.DraftSave request)
    {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("questionId", request.getQuestionId());
        canonical.put("selectedOptionIds", normalizedChoices(request.getSelectedOptionIds()));
        canonical.put("freeText", StrUtil.isBlank(request.getFreeText()) ? null : request.getFreeText().trim());
        return SecureUtil.sha256(JSON.toJSONString(canonical));
    }

    private String digest(Object value)
    {
        return SecureUtil.sha256(JSON.toJSONString(value));
    }

    private String sessionIdentityJson(AssistantRequests.SessionOpen request, ReasoningPreferences reasoning)
    {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("moduleCode", request.getModuleCode());
        if (request.getPrevizProjectId() != null) identity.put("previzProjectId", request.getPrevizProjectId());
        identity.put("mode", request.getMode());
        identity.put("projectId", request.getProjectId());
        identity.put("episodeId", request.getEpisodeId());
        identity.put("workspaceId", request.getWorkspaceId());
        if (request.getNodeObjectId() != null) identity.put("nodeObjectId", request.getNodeObjectId());
        identity.put("reasoningPreferencesVersion", 1);
        identity.put("reasoningEnabled", reasoning.enabled());
        identity.put("reasoningLevel", reasoning.level());
        identity.put("showReasoning", reasoning.show());
        return JSON.toJSONString(identity);
    }

    private boolean sameSessionScope(String storedIdentityJson, AssistantRequests.SessionOpen request)
    {
        try
        {
            JSONObject stored = JSON.parseObject(storedIdentityJson);
            return stored != null
                    && Objects.equals(stored.getString("moduleCode"), request.getModuleCode())
                    && Objects.equals(stored.getString("previzProjectId"), request.getPrevizProjectId())
                    && Objects.equals(stored.getString("mode"), request.getMode())
                    && Objects.equals(stored.getLong("projectId"), request.getProjectId())
                    && Objects.equals(stored.getLong("episodeId"), request.getEpisodeId())
                    && Objects.equals(stored.getLong("workspaceId"), request.getWorkspaceId())
                    && Objects.equals(stored.getString("nodeObjectId"), request.getNodeObjectId());
        }
        catch (RuntimeException ignored)
        {
            return false;
        }
    }

    private void applyReasoningPreferences(AidAssistantSession session, AssistantRequests.SessionOpen request,
                                           Long userId)
    {
        JSONObject stored;
        try
        {
            stored = JSON.parseObject(session.getBaseInputJson());
        }
        catch (RuntimeException ignored)
        {
            stored = null;
        }
        boolean legacyDefaults = stored == null || !stored.containsKey("reasoningPreferencesVersion");
        boolean explicit = request.getReasoningEnabled() != null || request.getShowReasoning() != null
                || StrUtil.isNotBlank(request.getReasoningLevel());
        if (!legacyDefaults && !explicit)
        {
            return;
        }
        ReasoningPreferences reasoning = requestedReasoningPreferences(request,
                legacyDefaults ? null : session);
        session.setReasoningEnabled(reasoning.enabled());
        session.setReasoningLevel(reasoning.level());
        session.setShowReasoning(reasoning.show());
        session.setBaseInputJson(sessionIdentityJson(request, reasoning));
        setUpdateAudit(session, userId, DateUtils.getNowDate());
        sessionMapper.updateById(session);
    }

    private boolean legacyReasoningPreferences(String baseInputJson)
    {
        if (StrUtil.isBlank(baseInputJson))
        {
            return true;
        }
        try
        {
            JSONObject stored = JSON.parseObject(baseInputJson);
            return stored != null && !stored.containsKey("reasoningPreferencesVersion");
        }
        catch (RuntimeException ignored)
        {
            return false;
        }
    }

    private ReasoningPreferences requestedReasoningPreferences(AssistantRequests.SessionOpen request,
                                                               AidAssistantSession existing)
    {
        boolean enabled = request.getReasoningEnabled() != null
                ? request.getReasoningEnabled()
                : existing == null || !Boolean.FALSE.equals(existing.getReasoningEnabled());
        boolean show = request.getShowReasoning() != null
                ? request.getShowReasoning()
                : existing == null || !Boolean.FALSE.equals(existing.getShowReasoning());
        String level = StrUtil.isNotBlank(request.getReasoningLevel())
                ? request.getReasoningLevel() : existing == null ? null : existing.getReasoningLevel();
        return new ReasoningPreferences(enabled, level, enabled && show);
    }

    private record ReasoningPreferences(boolean enabled, String level, boolean show) { }

    private record FrozenPresentation(String label,
                                      AssistantMessageReferenceProjector.Snapshot snapshot) { }

    private record FrozenReference(AssistantRequests.ContextRef ref, String contentRef, String access,
                                   String label, AssistantMessageReferenceProjector.Snapshot snapshot) { }

    private AssistantServiceException validation(String field, String reason)
    {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("field", field);
        details.put("reason", reason);
        return failure(HttpStatus.UNPROCESSABLE_ENTITY, "INPUT_VALIDATION_FAILED", "答案不正确", details);
    }

    private AssistantServiceException idempotencyConflict(String key)
    {
        return failure(HttpStatus.CONFLICT, "IDEMPOTENCY_PAYLOAD_CONFLICT", "请求数据冲突", key);
    }

    private AssistantServiceException failure(HttpStatus status, String code, String message, Object details)
    {
        return new AssistantServiceException(status.value(), code, message, details);
    }

    private void setCreateAudit(com.aid.common.core.domain.BaseEntity entity, Long userId, Date now)
    {
        entity.setCreateBy(String.valueOf(userId));
        entity.setCreateTime(now);
        entity.setUpdateBy(String.valueOf(userId));
        entity.setUpdateTime(now);
    }

    private void setUpdateAudit(com.aid.common.core.domain.BaseEntity entity, Long userId, Date now)
    {
        entity.setUpdateBy(String.valueOf(userId));
        entity.setUpdateTime(now);
    }

    private long value(Long source)
    {
        return source == null ? 0L : source;
    }

    private Long orZero(Long source)
    {
        return source == null ? 0L : source;
    }

    private Long zeroToNull(Long source)
    {
        return source == null || source == 0L ? null : source;
    }

    private String id(Long source)
    {
        return source == null ? null : String.valueOf(source);
    }

    private String format(Date source)
    {
        return source == null ? null : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(source);
    }

    private static class InputLock
    {
        private final AidAssistantSession session;
        private final AidRuntimeRun run;
        private final AidRuntimeInputRequest input;

        private InputLock(AidAssistantSession session, AidRuntimeRun run, AidRuntimeInputRequest input)
        {
            this.session = session;
            this.run = run;
            this.input = input;
        }
    }

    private static class QuestionSchema
    {
        private final List<AssistantRequests.Question> questions;
        private final Map<String, AssistantRequests.Question> byId;

        private QuestionSchema(List<AssistantRequests.Question> questions,
                               Map<String, AssistantRequests.Question> byId)
        {
            this.questions = questions;
            this.byId = byId;
        }
    }

    private static class AcceptedAnswer
    {
        public String questionId;
        public long draftRevision;
        public List<String> selectedOptionIds;
        public String freeText;
    }
    @Resource private com.aid.runtime.mapper.AidRuntimeBudgetPolicyMapper budgetPolicyMapper;
    @Resource private com.aid.runtime.assistant.execution.AssistantOperationQuoteLineage operationQuoteLineage;
    /** UI hints never grant admission. The quote command repeats all guards under the run lock. */
    private AidRuntimeApproval pendingOperationApproval(AidRuntimeRun run,List<AidRuntimeRunStep> graph) {
        if(!"WAITING_APPROVAL".equals(run.getStatus()) || !"CONFIRM_OPERATION_APPROVAL".equals(run.getNextAction())
                || Boolean.TRUE.equals(run.getStopRequested()))return null;
        var pending=graph.stream().filter(step->Objects.equals(step.getPlanRevision(),run.getPlanRevision())
                && "AWAITING_APPROVAL".equals(step.getStatus()) && "TOOL".equals(step.getStepKind())
                && step.getApprovalId()!=null).toList();
        if(pending.size()!=1)return null;
        var child=pending.get(0);
        AidRuntimeApproval approval=approvalMapper.selectById(child.getApprovalId());
        if(approval==null || !Objects.equals(approval.getRunId(),run.getId())
                || !Objects.equals(approval.getUserId(),run.getUserId()) || !"0".equals(approval.getDelFlag())
                || !"PLAN_EXECUTION".equals(approval.getScopeKind())
                || !List.of("QUOTED","INVALIDATED").contains(approval.getStatus()))return null;
        return approval;
    }

    private boolean visibleOperationQuoteSource(AidRuntimeRun run,AidRuntimeRunStep step,AidRuntimeApproval approval) {
        if(step==null || !"AWAITING_APPROVAL".equals(step.getStatus())
                || !"NOT_SUBMITTED".equals(step.getProviderAcceptanceState()) || step.getMediaTaskId()!=null
                || step.getReservationId()!=null || step.getChildRunId()!=null || step.getChildRunKind()!=null)return false;
        try {
            JSONObject scope=JSON.parseObject(approval.getScopeJson());
            JSONObject decision=scope==null?null:scope.getJSONObject("decision");
            AidRuntimeRunStep main=decision==null?null:runStepMapper.selectById(decision.getLong("mainAgentStepId"));
            com.aid.runtime.assistant.execution.AssistantOperationQuoteSourceGuard.requireBudgetRenewalOperationIdentity(run,main,step,approval);
            if(!"DECISION_READY".equals(main.getStatus()) || !"MANUAL".equals(approval.getMode()) || approval.getAcceptedAt()!=null)return false;
            if("INVALIDATED".equals(approval.getStatus())) {
                com.aid.runtime.domain.AidRuntimeBudgetPolicy policy=budgetPolicyMapper.selectOne(new LambdaQueryWrapper<com.aid.runtime.domain.AidRuntimeBudgetPolicy>()
                        .eq(com.aid.runtime.domain.AidRuntimeBudgetPolicy::getRunId,run.getId()).eq(com.aid.runtime.domain.AidRuntimeBudgetPolicy::getDelFlag,"0"));
                if(policy==null)return false;
                com.aid.runtime.assistant.execution.AssistantOperationQuoteGrantHistory.requireBudgetRepriceSource(
                        run,approval,policy.getAuthorizationId(),policy.getAuthorizationRevision(),policy.getScopeDigest(),
                        operationQuoteLineage.loadBudgetGrantHistory(run,policy.getAuthorizationId(),
                                approval.getBillingAuthorizationRevision(),policy.getAuthorizationRevision(),false));
            }
            return true;
        } catch(com.aid.common.exception.ServiceException | IllegalArgumentException | com.alibaba.fastjson2.JSONException invalid){return false;}
    }

}
