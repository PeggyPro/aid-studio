package com.aid.assistant.admin;

import com.aid.assistant.admin.AssistantAdminRequests.BudgetQuery;
import com.aid.assistant.admin.AssistantAdminRequests.InputQuery;
import com.aid.assistant.admin.AssistantAdminRequests.PageRequest;
import com.aid.assistant.admin.AssistantAdminRequests.RunQuery;
import com.aid.assistant.admin.AssistantAdminRequests.ToolQuery;
import com.aid.assistant.admin.AssistantAdminViews.BudgetAuthorizationDetail;
import com.aid.assistant.admin.AssistantAdminViews.BudgetAuthorizationSummary;
import com.aid.assistant.admin.AssistantAdminViews.BudgetReservationSummary;
import com.aid.assistant.admin.AssistantAdminViews.EventSummary;
import com.aid.assistant.admin.AssistantAdminViews.InputQuestionTrace;
import com.aid.assistant.admin.AssistantAdminViews.InputRequestDetail;
import com.aid.assistant.admin.AssistantAdminViews.InputRequestSummary;
import com.aid.assistant.admin.AssistantAdminViews.PageView;
import com.aid.assistant.admin.AssistantAdminViews.RunDetail;
import com.aid.assistant.admin.AssistantAdminViews.RunSummary;
import com.aid.assistant.admin.AssistantAdminViews.ToolCatalogItem;
import com.aid.assistant.domain.AidAssistantSession;
import com.aid.assistant.mapper.AidAssistantSessionMapper;
import com.aid.billing.dto.BillingBudgetViews.AuthorizationView;
import com.aid.billing.dto.BillingBudgetViews.AuthorizationPageView;
import com.aid.billing.dto.BillingBudgetViews.ReservationView;
import com.aid.billing.dto.BillingBudgetRequests.AuthorizationQuery;
import com.aid.billing.service.AssistantBillingBudgetService;
import com.aid.common.exception.ServiceException;
import com.aid.runtime.domain.AidRuntimeEvent;
import com.aid.runtime.domain.AidRuntimeInputDraft;
import com.aid.runtime.domain.AidRuntimeInputRequest;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.mapper.AidRuntimeEventMapper;
import com.aid.runtime.mapper.AidRuntimeInputDraftMapper;
import com.aid.runtime.mapper.AidRuntimeInputRequestMapper;
import com.aid.runtime.mapper.AidRuntimeRunMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Read-only projection implementation. Raw prompts, payloads and answer values never enter a view. */
@Service
public class AssistantAdminQueryServiceImpl implements AssistantAdminQueryService
{
    private static final int MAX_PAGE_SIZE = 100;
    private static final int DETAIL_EVENT_LIMIT = 100;

    private final AidRuntimeRunMapper runMapper;
    private final AidRuntimeEventMapper eventMapper;
    private final AidRuntimeInputRequestMapper inputRequestMapper;
    private final AidRuntimeInputDraftMapper inputDraftMapper;
    private final AidAssistantSessionMapper sessionMapper;
    private final AssistantBillingBudgetService billingBudgetService;
    private final AssistantToolCatalog toolCatalog;
    private final AssistantSensitiveReadAudit sensitiveReadAudit;
    private final ObjectMapper objectMapper;

    public AssistantAdminQueryServiceImpl(AidRuntimeRunMapper runMapper,
                                          AidRuntimeEventMapper eventMapper,
                                          AidRuntimeInputRequestMapper inputRequestMapper,
                                          AidRuntimeInputDraftMapper inputDraftMapper,
                                          AidAssistantSessionMapper sessionMapper,
                                          AssistantBillingBudgetService billingBudgetService,
                                          AssistantToolCatalog toolCatalog,
                                          AssistantSensitiveReadAudit sensitiveReadAudit,
                                          ObjectMapper objectMapper)
    {
        this.runMapper = runMapper;
        this.eventMapper = eventMapper;
        this.inputRequestMapper = inputRequestMapper;
        this.inputDraftMapper = inputDraftMapper;
        this.sessionMapper = sessionMapper;
        this.billingBudgetService = billingBudgetService;
        this.toolCatalog = toolCatalog;
        this.sensitiveReadAudit = sensitiveReadAudit;
        this.objectMapper = objectMapper;
    }

    @Override
    public PageView<RunSummary> listRuns(RunQuery request)
    {
        normalize(request);
        LambdaQueryWrapper<AidRuntimeRun> query = Wrappers.lambdaQuery();
        query.eq(request.getRunId() != null, AidRuntimeRun::getId, request.getRunId())
                .eq(request.getSessionId() != null, AidRuntimeRun::getSessionId, request.getSessionId())
                .eq(request.getUserId() != null, AidRuntimeRun::getUserId, request.getUserId())
                .eq(StringUtils.hasText(request.getStatus()), AidRuntimeRun::getStatus, request.getStatus())
                .ge(request.getCreateStartTime() != null, AidRuntimeRun::getCreateTime, request.getCreateStartTime())
                .le(request.getCreateEndTime() != null, AidRuntimeRun::getCreateTime, request.getCreateEndTime())
                .and(w -> w.isNull(AidRuntimeRun::getDelFlag).or().eq(AidRuntimeRun::getDelFlag, "0"));
        if (StringUtils.hasText(request.getModuleCode()) || StringUtils.hasText(request.getMode()))
        {
            List<AidAssistantSession> sessions = sessionMapper.selectList(Wrappers.<AidAssistantSession>lambdaQuery()
                    .select(AidAssistantSession::getId)
                    .eq(StringUtils.hasText(request.getModuleCode()), AidAssistantSession::getModuleCode, request.getModuleCode())
                    .eq(StringUtils.hasText(request.getMode()), AidAssistantSession::getMode, request.getMode())
                    .and(w -> w.isNull(AidAssistantSession::getDelFlag).or().eq(AidAssistantSession::getDelFlag, "0")));
            if (sessions.isEmpty())
            {
                return emptyPage(request);
            }
            query.in(AidRuntimeRun::getSessionId, sessions.stream().map(AidAssistantSession::getId).toList());
        }
        query.orderByDesc(AidRuntimeRun::getId);
        PageHelper.startPage(request.getPageNum(), request.getPageSize());
        List<AidRuntimeRun> runs = runMapper.selectList(query);
        PageInfo<AidRuntimeRun> pageInfo = new PageInfo<>(runs);
        Map<Long, AidAssistantSession> sessions = sessionsForRuns(runs);
        return page(runs.stream().map(run -> runSummary(run, sessions.get(run.getSessionId()))).toList(), pageInfo, request);
    }

    @Override
    public RunDetail getRunDetail(Long runId)
    {
        AidRuntimeRun run = runMapper.selectById(runId);
        if (run == null || "1".equals(run.getDelFlag()))
        {
            throw new ServiceException("运行记录不存在", 404);
        }
        AidAssistantSession session = run.getSessionId() == null ? null : sessionMapper.selectById(run.getSessionId());
        List<AidRuntimeEvent> events = eventMapper.selectList(Wrappers.<AidRuntimeEvent>lambdaQuery()
                .select(AidRuntimeEvent::getId, AidRuntimeEvent::getSeq, AidRuntimeEvent::getEventType,
                        AidRuntimeEvent::getEntityId, AidRuntimeEvent::getEntityRevision,
                        AidRuntimeEvent::getCausationId, AidRuntimeEvent::getCreateTime)
                .eq(AidRuntimeEvent::getRunId, runId)
                .orderByDesc(AidRuntimeEvent::getSeq)
                .last("LIMIT " + DETAIL_EVENT_LIMIT));
        List<AidRuntimeInputRequest> inputs = inputRequestMapper.selectList(Wrappers.<AidRuntimeInputRequest>lambdaQuery()
                .eq(AidRuntimeInputRequest::getRunId, runId)
                .orderByDesc(AidRuntimeInputRequest::getId));
        Map<Long, List<AidRuntimeInputDraft>> drafts = draftsForRequests(inputs);
        RunDetail detail = new RunDetail();
        detail.setRun(runSummary(run, session));
        detail.setRecentEvents(events.stream().sorted(Comparator.comparing(AidRuntimeEvent::getSeq))
                .map(this::eventSummary).toList());
        detail.setInputRequests(inputs.stream().map(input -> inputSummary(input, run.getUserId(), drafts.get(input.getId()))).toList());
        sensitiveReadAudit.record("RUN", runId);
        return detail;
    }

    @Override
    public PageView<ToolCatalogItem> listTools(ToolQuery request)
    {
        normalize(request);
        String keyword = normalized(request.getKeyword());
        List<ToolCatalogItem> filtered = toolCatalog.list().stream()
                .filter(item -> !StringUtils.hasText(keyword)
                        || normalized(item.getCode()).contains(keyword)
                        || normalized(item.getName()).contains(keyword)
                        || normalized(item.getDescription()).contains(keyword))
                .filter(item -> !StringUtils.hasText(request.getSideEffectLevel())
                        || request.getSideEffectLevel().equals(item.getSideEffectLevel()))
                .filter(item -> request.getBillable() == null || request.getBillable() == item.isBillable())
                .filter(item -> request.getEnabled() == null || request.getEnabled() == item.isEnabled())
                .toList();
        int from = Math.min((request.getPageNum() - 1) * request.getPageSize(), filtered.size());
        int to = Math.min(from + request.getPageSize(), filtered.size());
        PageView<ToolCatalogItem> page = new PageView<>();
        page.setItems(new ArrayList<>(filtered.subList(from, to)));
        page.setTotal(filtered.size());
        page.setPageNum(request.getPageNum());
        page.setPageSize(request.getPageSize());
        return page;
    }

    @Override
    public PageView<BudgetAuthorizationSummary> listBudgets(BudgetQuery request)
    {
        normalize(request);
        AuthorizationQuery query = new AuthorizationQuery();
        query.setAuthorizationId(request.getAuthorizationId());
        query.setRuntimeRunId(request.getRunId());
        query.setPayerUserId(request.getPayerUserId());
        query.setStatus(request.getStatus());
        query.setPageNum(request.getPageNum());
        query.setPageSize(request.getPageSize());
        AuthorizationPageView result = billingBudgetService.listAuthorizations(query);
        List<BudgetAuthorizationSummary> items = safe(result.getRows()).stream().map(this::budgetSummary).toList();
        PageView<BudgetAuthorizationSummary> page = new PageView<>();
        page.setItems(items);
        page.setTotal(result.getTotal());
        page.setPageNum(result.getPageNum());
        page.setPageSize(result.getPageSize());
        return page;
    }

    @Override
    public BudgetAuthorizationDetail getBudgetDetail(Long authorizationId)
    {
        AuthorizationView authorization = billingBudgetService.getAuthorization(authorizationId);
        if (authorization == null)
        {
            throw new ServiceException("预算授权不存在", 404);
        }
        BudgetAuthorizationDetail detail = new BudgetAuthorizationDetail();
        detail.setAuthorization(budgetSummary(authorization));
        detail.setReservations(billingBudgetService.listReservations(authorizationId).stream()
                .map(this::reservationSummary).toList());
        sensitiveReadAudit.record("BUDGET", authorizationId);
        return detail;
    }

    @Override
    public PageView<InputRequestSummary> listInputs(InputQuery request)
    {
        normalize(request);
        LambdaQueryWrapper<AidRuntimeInputRequest> query = Wrappers.lambdaQuery();
        query.eq(request.getRequestId() != null, AidRuntimeInputRequest::getId, request.getRequestId())
                .eq(request.getRunId() != null, AidRuntimeInputRequest::getRunId, request.getRunId())
                .eq(StringUtils.hasText(request.getRequestKey()), AidRuntimeInputRequest::getRequestKey, request.getRequestKey())
                .eq(StringUtils.hasText(request.getStatus()), AidRuntimeInputRequest::getStatus, request.getStatus())
                .ge(request.getCreateStartTime() != null, AidRuntimeInputRequest::getCreateTime, request.getCreateStartTime())
                .le(request.getCreateEndTime() != null, AidRuntimeInputRequest::getCreateTime, request.getCreateEndTime());
        if (request.getUserId() != null)
        {
            List<AidRuntimeRun> runs = runMapper.selectList(Wrappers.<AidRuntimeRun>lambdaQuery()
                    .select(AidRuntimeRun::getId).eq(AidRuntimeRun::getUserId, request.getUserId()));
            if (runs.isEmpty())
            {
                return emptyPage(request);
            }
            query.in(AidRuntimeInputRequest::getRunId, runs.stream().map(AidRuntimeRun::getId).toList());
        }
        query.orderByDesc(AidRuntimeInputRequest::getId);
        PageHelper.startPage(request.getPageNum(), request.getPageSize());
        List<AidRuntimeInputRequest> inputs = inputRequestMapper.selectList(query);
        PageInfo<AidRuntimeInputRequest> pageInfo = new PageInfo<>(inputs);
        Map<Long, AidRuntimeRun> runs = runsForInputs(inputs);
        Map<Long, List<AidRuntimeInputDraft>> drafts = draftsForRequests(inputs);
        List<InputRequestSummary> summaries = inputs.stream().map(input -> {
            AidRuntimeRun run = runs.get(input.getRunId());
            return inputSummary(input, run == null ? null : run.getUserId(), drafts.get(input.getId()));
        }).toList();
        return page(summaries, pageInfo, request);
    }

    @Override
    public InputRequestDetail getInputDetail(Long requestId)
    {
        AidRuntimeInputRequest input = inputRequestMapper.selectById(requestId);
        if (input == null)
        {
            throw new ServiceException("输入请求不存在", 404);
        }
        AidRuntimeRun run = runMapper.selectById(input.getRunId());
        List<AidRuntimeInputDraft> drafts = inputDraftMapper.selectList(Wrappers.<AidRuntimeInputDraft>lambdaQuery()
                .eq(AidRuntimeInputDraft::getInputRequestId, requestId)
                .orderByAsc(AidRuntimeInputDraft::getQuestionId));
        InputRequestDetail detail = new InputRequestDetail();
        detail.setRequest(inputSummary(input, run == null ? null : run.getUserId(), drafts));
        detail.setQuestions(questionTraces(input, drafts));
        sensitiveReadAudit.record("INPUT", requestId);
        return detail;
    }

    private Map<Long, AidAssistantSession> sessionsForRuns(List<AidRuntimeRun> runs)
    {
        Set<Long> ids = runs.stream().map(AidRuntimeRun::getSessionId).filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (ids.isEmpty()) return Collections.emptyMap();
        return sessionMapper.selectBatchIds(ids).stream().collect(Collectors.toMap(AidAssistantSession::getId,
                Function.identity(), (left, right) -> left, LinkedHashMap::new));
    }

    private Map<Long, AidRuntimeRun> runsForInputs(List<AidRuntimeInputRequest> inputs)
    {
        Set<Long> ids = inputs.stream().map(AidRuntimeInputRequest::getRunId).filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (ids.isEmpty()) return Collections.emptyMap();
        return runMapper.selectBatchIds(ids).stream().collect(Collectors.toMap(AidRuntimeRun::getId,
                Function.identity(), (left, right) -> left, LinkedHashMap::new));
    }

    private Map<Long, List<AidRuntimeInputDraft>> draftsForRequests(List<AidRuntimeInputRequest> inputs)
    {
        List<Long> ids = inputs.stream().map(AidRuntimeInputRequest::getId).toList();
        if (ids.isEmpty()) return Collections.emptyMap();
        return inputDraftMapper.selectList(Wrappers.<AidRuntimeInputDraft>lambdaQuery()
                        .in(AidRuntimeInputDraft::getInputRequestId, ids))
                .stream().collect(Collectors.groupingBy(AidRuntimeInputDraft::getInputRequestId));
    }

    private RunSummary runSummary(AidRuntimeRun run, AidAssistantSession session)
    {
        RunSummary view = new RunSummary();
        view.setId(run.getId()); view.setSessionId(run.getSessionId()); view.setTurnId(run.getTurnId());
        view.setUserId(run.getUserId()); view.setModuleCode(session == null ? null : session.getModuleCode());
        view.setMode(session == null ? null : session.getMode()); view.setExecutorStrategy(run.getExecutorStrategy());
        view.setStatus(run.getStatus()); view.setStateRevision(run.getStateRevision()); view.setPlanRevision(run.getPlanRevision());
        view.setPauseReason(run.getPauseReason()); view.setResumeStatus(run.getResumeStatus());
        view.setStopRequested(run.getStopRequested()); view.setNextAction(run.getNextAction());
        view.setNextRetryAt(run.getNextRetryAt()); view.setCreateTime(run.getCreateTime()); view.setUpdateTime(run.getUpdateTime());
        return view;
    }

    private EventSummary eventSummary(AidRuntimeEvent event)
    {
        EventSummary view = new EventSummary();
        view.setId(event.getId()); view.setSeq(event.getSeq()); view.setType(event.getEventType());
        view.setEntityId(event.getEntityId()); view.setEntityRevision(event.getEntityRevision());
        view.setCausationId(event.getCausationId()); view.setCreateTime(event.getCreateTime());
        return view;
    }

    private InputRequestSummary inputSummary(AidRuntimeInputRequest input, Long userId, List<AidRuntimeInputDraft> drafts)
    {
        int questionCount = questionMetadata(input.getQuestionBundleJson()).size();
        int answered = (int) safe(drafts).stream().filter(draft -> "ANSWERED".equals(draft.getAnswerState())).count();
        InputRequestSummary view = new InputRequestSummary();
        view.setId(input.getId()); view.setRunId(input.getRunId()); view.setUserId(userId);
        view.setRequestKey(input.getRequestKey()); view.setRoundNo(input.getRoundNo());
        view.setSchemaDigest(input.getSchemaDigest()); view.setContextDigest(input.getContextDigest());
        view.setPlanRevision(input.getPlanRevision()); view.setStatus(input.getStatus());
        view.setQuestionCount(questionCount); view.setAnsweredCount(Math.min(answered, questionCount));
        view.setUnansweredCount(Math.max(0, questionCount - answered));
        view.setSuspendedAt(input.getSuspendedAt()); view.setExpiresAt(input.getExpiresAt());
        view.setCreateTime(input.getCreateTime()); view.setUpdateTime(input.getUpdateTime());
        return view;
    }

    private List<InputQuestionTrace> questionTraces(AidRuntimeInputRequest input, List<AidRuntimeInputDraft> drafts)
    {
        Map<String, QuestionMetadata> questions = questionMetadata(input.getQuestionBundleJson());
        Map<String, AidRuntimeInputDraft> draftMap = safe(drafts).stream().collect(Collectors.toMap(
                AidRuntimeInputDraft::getQuestionId, Function.identity(), (left, right) ->
                        left.getDraftRevision() >= right.getDraftRevision() ? left : right, LinkedHashMap::new));
        LinkedHashSet<String> ids = new LinkedHashSet<>(questions.keySet());
        ids.addAll(draftMap.keySet());
        return ids.stream().map(id -> {
            QuestionMetadata metadata = questions.getOrDefault(id, new QuestionMetadata(id, null, false));
            AidRuntimeInputDraft draft = draftMap.get(id);
            InputQuestionTrace view = new InputQuestionTrace();
            view.setQuestionId(id); view.setKind(metadata.kind()); view.setRequired(metadata.required());
            view.setAnswerState(draft == null ? "UNANSWERED" : draft.getAnswerState());
            view.setDraftRevision(draft == null || draft.getDraftRevision() == null ? 0L : draft.getDraftRevision());
            view.setSelectedCount(draft == null ? 0 : arraySize(draft.getChoiceIdsJson()));
            view.setFreeTextLength(draft == null || draft.getFreeText() == null ? 0 : draft.getFreeText().length());
            view.setFreeTextPreviewRedacted(draft != null && StringUtils.hasText(draft.getFreeText()) ? "已脱敏" : null);
            view.setUpdateTime(draft == null ? null : draft.getUpdateTime());
            return view;
        }).toList();
    }

    private Map<String, QuestionMetadata> questionMetadata(String json)
    {
        if (!StringUtils.hasText(json)) return Collections.emptyMap();
        try
        {
            JsonNode root = objectMapper.readTree(json);
            JsonNode items = root.isArray() ? root : root.path("questions");
            if (!items.isArray()) return Collections.emptyMap();
            Map<String, QuestionMetadata> result = new LinkedHashMap<>();
            for (JsonNode item : items)
            {
                String id = text(item, "questionId");
                if (!StringUtils.hasText(id)) id = text(item, "id");
                if (!StringUtils.hasText(id)) continue;
                String kind = text(item, "kind");
                if (!StringUtils.hasText(kind)) kind = text(item, "type");
                result.put(id, new QuestionMetadata(id, kind, item.path("required").asBoolean(false)));
            }
            return result;
        }
        catch (Exception ignored)
        {
            return Collections.emptyMap();
        }
    }

    private int arraySize(String json)
    {
        if (!StringUtils.hasText(json)) return 0;
        try
        {
            JsonNode node = objectMapper.readTree(json);
            return node.isArray() ? node.size() : 0;
        }
        catch (Exception ignored)
        {
            return 0;
        }
    }

    private String text(JsonNode node, String field)
    {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private BudgetAuthorizationSummary budgetSummary(AuthorizationView source)
    {
        BudgetAuthorizationSummary view = new BudgetAuthorizationSummary();
        view.setId(source.getId()); view.setRunId(source.getRuntimeRunId()); view.setPayerUserId(source.getPayerUserId());
        view.setRevision(source.getRevision()); view.setApprovedTotal(source.getApprovedTotal());
        view.setReservedAmount(source.getReservedAmount()); view.setSettledAmount(source.getSettledAmount());
        view.setCurrencyUnit(source.getCurrencyUnit()); view.setStatus(source.getStatus());
        view.setExpiresAt(source.getExpiresAt()); view.setPolicyDigest(source.getPolicyDigest());
        view.setCreateTime(source.getCreateTime()); view.setUpdateTime(source.getUpdateTime());
        return view;
    }

    private BudgetReservationSummary reservationSummary(ReservationView source)
    {
        BudgetReservationSummary view = new BudgetReservationSummary();
        view.setId(source.getId()); view.setAuthorizationRevision(source.getAuthorizationRevision());
        view.setLogicalCallKey(source.getLogicalCallKey()); view.setAttemptNo(source.getAttemptNo());
        view.setOperationKind(source.getOperationKind()); view.setApprovalId(source.getApprovalId());
        view.setRuntimeStepId(source.getRuntimeStepId()); view.setMediaTaskId(source.getMediaTaskId());
        view.setBillingTraceId(source.getBillingTraceId()); view.setRequestDigest(source.getRequestDigest());
        view.setQuoteDigest(source.getQuoteDigest()); view.setQuotedUpperBound(source.getQuotedUpperBound());
        view.setReservedAmount(source.getReservedAmount()); view.setWalletFrozenAmount(source.getWalletFrozenAmount());
        view.setSettledAmount(source.getSettledAmount()); view.setReleasedAmount(source.getReleasedAmount());
        view.setWalletRefundedAmount(source.getWalletRefundedAmount()); view.setState(source.getState());
        view.setSettlementKey(source.getSettlementKey()); view.setCreateTime(source.getCreateTime());
        view.setUpdateTime(source.getUpdateTime());
        return view;
    }

    private void normalize(PageRequest request)
    {
        request.setPageNum(request.getPageNum() == null || request.getPageNum() < 1 ? 1 : request.getPageNum());
        request.setPageSize(request.getPageSize() == null || request.getPageSize() < 1 ? 20
                : Math.min(request.getPageSize(), MAX_PAGE_SIZE));
    }

    private String normalized(String value)
    {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private <T> List<T> safe(List<T> list)
    {
        return list == null ? Collections.emptyList() : list;
    }

    private <T> PageView<T> page(List<T> items, PageInfo<?> pageInfo, PageRequest request)
    {
        PageView<T> page = new PageView<>();
        page.setItems(items); page.setTotal(pageInfo.getTotal());
        page.setPageNum(request.getPageNum()); page.setPageSize(request.getPageSize());
        return page;
    }

    private <T> PageView<T> emptyPage(PageRequest request)
    {
        PageView<T> page = new PageView<>();
        page.setItems(Collections.emptyList()); page.setTotal(0);
        page.setPageNum(request.getPageNum()); page.setPageSize(request.getPageSize());
        return page;
    }

    private record QuestionMetadata(String id, String kind, boolean required)
    {
    }
}
