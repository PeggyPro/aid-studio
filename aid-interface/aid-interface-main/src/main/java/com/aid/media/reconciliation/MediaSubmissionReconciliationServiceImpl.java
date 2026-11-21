package com.aid.media.reconciliation;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.json.JSONUtil;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.billing.dto.BillingBudgetContext;
import com.aid.billing.model.BillingSnapshot;
import com.aid.media.domain.AidMediaSubmissionEvidence;
import com.aid.media.domain.AidMediaSubmissionReconciliation;
import com.aid.media.enums.MediaTaskStatus;
import com.aid.media.mapper.AidMediaSubmissionEvidenceMapper;
import com.aid.media.mapper.AidMediaSubmissionReconciliationMapper;
import com.aid.media.provider.ProviderSubmitResult;
import com.aid.media.provider.ProviderTaskResult;
import com.aid.media.service.TaskCompletionService;
import com.aid.media.service.TaskDispatchService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Evidence-driven submission recovery. It never calls submit again and never infers a refund from age,
 * a missing provider id, a transport exception, or an unrecognized provider response.
 */
@Slf4j
@Service
public class MediaSubmissionReconciliationServiceImpl implements MediaSubmissionReconciliationService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.aid.billing.service.IAccountUpdateService accountUpdateService;
    @Override
    public Optional<String> loadLocalReviewReason(Long taskId) {
        if (taskId == null) return Optional.empty();
        var record = reconciliationMapper.selectOne(new LambdaQueryWrapper<AidMediaSubmissionReconciliation>()
                .eq(AidMediaSubmissionReconciliation::getTaskId, taskId)
                .eq(AidMediaSubmissionReconciliation::getDelFlag, "0"));
        return record != null && "LEGACY_WALLET_REVIEW_REQUIRED".equals(record.getLastErrorCode())
                ? Optional.of("历史任务状态与原账本不一致，已暂停自动退款；请核对原始结算记录")
                : Optional.empty();
    }

    static final String STATUS_PENDING = "PENDING";
    static final String STATUS_CLAIMED = "CLAIMED";
    static final String STATUS_ACCEPTED = "ACCEPTED";
    static final String STATUS_TERMINAL = "TERMINAL";
    static final String STATUS_NOT_ACCEPTED = "NOT_ACCEPTED";
    static final String STATUS_UNSUPPORTED = "UNSUPPORTED";
    static final String STATUS_RESOLVED = "RESOLVED";
    static final int MAX_PAYLOAD_BYTES = 1024 * 1024;
    private static final long LEASE_MILLIS = 60_000L;

    private final AidMediaSubmissionReconciliationMapper reconciliationMapper;
    private final AidMediaSubmissionEvidenceMapper evidenceMapper;
    private final AidMediaTaskMapper taskMapper;
    private final TaskDispatchService taskDispatchService;
    private final TaskCompletionService taskCompletionService;
    private final ObjectProvider<ProviderSubmitReceiptApplier> receiptApplierProvider;
    private final TransactionTemplate requiresNew;
    @org.springframework.beans.factory.annotation.Autowired
    private ObjectProvider<MediaSubmissionAnomalyObserver> anomalyObservers;

    @org.springframework.beans.factory.annotation.Autowired
    private com.aid.aid.mapper.MediaExecutionPermitMapper executionPermitMapper;

    @Override
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void recordOperatorTerminalFailure(AidMediaTask task, Long operatorId, String reason) {
        if (task == null || !MediaTaskStatus.FAILED.name().equals(task.getStatus())
                || operatorId == null || operatorId <= 0 || StrUtil.isBlank(reason))
            throw new IllegalArgumentException("Operator terminal evidence required");
        String payload = JSONUtil.toJsonStr(Map.of("operatorId", operatorId.toString(), "confirmed", true,
                "reason", com.aid.media.provider.ProviderErrorSanitizer.safeMessage(reason, "人工确认失败")));
        var reconciliation = lockOrCreate(task);
        if (STATUS_CLAIMED.equals(reconciliation.getStatus()) && reconciliation.getLeaseUntil() != null
                && reconciliation.getLeaseUntil().after(new Date()))
            throw new com.aid.common.exception.ServiceException("该任务正在核对结果，请稍后再次确认");
        var evidence = appendEvidence(reconciliation, task, "OPERATOR_TERMINAL_V1", "OPERATOR_FAILURE",
                MediaSubmissionEvidenceVerdict.TERMINAL, task.getProviderTaskId(), "MANUAL_FAILED",
                MediaTaskStatus.FAILED.name(), payload, null, false);
        schedule(reconciliation, STATUS_RESOLVED, null, null, evidence, null);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void recordLocalTerminalFailure(AidMediaTask task, com.aid.media.model.MediaTaskFailureReason reason) {
        if (task == null || !MediaTaskStatus.FAILED.name().equals(task.getStatus()))
            throw new IllegalArgumentException("A failed task is required");
        Objects.requireNonNull(reason, "Failure reason required");
        var reconciliation = lockOrCreate(task);
        String payload = JSONUtil.toJsonStr(Map.of("failureReason", reason.name(),
                "executionToken", StrUtil.blankToDefault(task.getExecutionPermitToken(), ""),
                "providerTaskId", StrUtil.blankToDefault(task.getProviderTaskId(), "")));
        var evidence = appendEvidence(reconciliation, task, "LOCAL_TASK_FAILURE_V1", "LOCAL_TASK_FAILURE",
                MediaSubmissionEvidenceVerdict.TERMINAL, task.getProviderTaskId(), reason.name(),
                MediaTaskStatus.FAILED.name(), payload, reason.name(), false);
        schedule(reconciliation, STATUS_RESOLVED, null, null, evidence, null);
    }

    public MediaSubmissionReconciliationServiceImpl(
            AidMediaSubmissionReconciliationMapper reconciliationMapper,
            AidMediaSubmissionEvidenceMapper evidenceMapper,
            AidMediaTaskMapper taskMapper,
            TaskDispatchService taskDispatchService,
            TaskCompletionService taskCompletionService,
            ObjectProvider<ProviderSubmitReceiptApplier> receiptApplierProvider,
            PlatformTransactionManager transactionManager) {
        this.reconciliationMapper = reconciliationMapper;
        this.evidenceMapper = evidenceMapper;
        this.taskMapper = taskMapper;
        this.taskDispatchService = taskDispatchService;
        this.taskCompletionService = taskCompletionService;
        this.receiptApplierProvider = receiptApplierProvider;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public MediaSubmissionEvidenceVerdict recordProviderReceipt(AidMediaTask task, ProviderSubmitResult result) {
        requirePersistedTask(task);
        ProviderSubmissionReceiptSnapshot snapshot = ProviderSubmissionReceiptSnapshot.capture(result);
        MediaSubmissionEvidenceVerdict verdict = classifyReceipt(snapshot);
        String payload = snapshot == null ? null : JSONUtil.toJsonStr(snapshot);
        String errorCode = null;
        if (payload != null && payload.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
            payload = null;
            verdict = MediaSubmissionEvidenceVerdict.UNSUPPORTED;
            errorCode = "RECEIPT_PAYLOAD_TOO_LARGE";
        } else if (snapshot != null && !snapshot.isReplayable()) {
            payload = null;
            verdict = MediaSubmissionEvidenceVerdict.UNSUPPORTED;
            errorCode = "INLINE_BINARY_RECEIPT_UNSUPPORTED";
        }
        String persistedPayload = payload;
        String persistedError = errorCode;
        MediaSubmissionEvidenceVerdict persistedVerdict = verdict;
        requiresNew.executeWithoutResult(status -> recordEvidence(task, "PROVIDER_RECEIPT_V1",
                "PROVIDER_RESPONSE", persistedVerdict, result == null ? null : result.getProviderTaskId(),
                null, persistedVerdict == MediaSubmissionEvidenceVerdict.TERMINAL
                        ? (snapshot.hasTerminalFailure() ? MediaTaskStatus.FAILED.name() : MediaTaskStatus.SUCCEEDED.name()) : null,
                persistedPayload, persistedError));
        return verdict;
    }

    @Override
    public void recordOutcomeUnknown(AidMediaTask task, Throwable cause) {
        requirePersistedTask(task);
        requiresNew.executeWithoutResult(status -> {
            AidMediaSubmissionReconciliation reconciliation = lockOrCreate(task);
            AidMediaSubmissionEvidence latest = selectRecoveryEvidence(task.getId());
            if (latest != null && (MediaSubmissionEvidenceVerdict.ACCEPTED.name().equals(latest.getVerdict())
                    || MediaSubmissionEvidenceVerdict.TERMINAL.name().equals(latest.getVerdict()))) {
                schedule(reconciliation, STATUS_PENDING,
                        new Date(System.currentTimeMillis() + 30_000L), null, latest, null);
                return;
            }
            if (latest != null && MediaSubmissionEvidenceVerdict.UNSUPPORTED.name().equals(latest.getVerdict())) {
                schedule(reconciliation, STATUS_UNSUPPORTED, null,
                        StrUtil.blankToDefault(reconciliation.getLastErrorCode(), "PROTOCOL_EVIDENCE_UNSUPPORTED"),
                        latest, null);
                return;
            }
            String errorCode = cause == null ? "SUBMISSION_OUTCOME_UNKNOWN"
                    : safeCode(cause.getClass().getSimpleName());
            appendEvidence(reconciliation, task, "LOCAL_SUBMISSION_BOUNDARY_V1", "LOCAL_RECEIPT",
                    MediaSubmissionEvidenceVerdict.UNKNOWN, task.getProviderTaskId(), null, null,
                    MediaSubmissionFailureDiagnostics.snapshot(cause), errorCode, true);
        });
    }

    @Override
    public boolean hasRecoverableReceipt(AidMediaTask task) {
        AidMediaSubmissionEvidence evidence = selectRecoveryEvidence(task.getId());
        return evidence != null
                && (MediaSubmissionEvidenceVerdict.TERMINAL.name().equals(evidence.getVerdict())
                || MediaSubmissionEvidenceVerdict.ACCEPTED.name().equals(evidence.getVerdict()))
                && validEvidence(task, taskIdentity(task), evidence);
    }

    @Override
    public Optional<AuthoritativeTerminalBillingEvidence> loadTerminalBillingEvidence(AidMediaTask task) {
        if (task == null || task.getId() == null || !terminal(task)) return Optional.empty();
        TaskIdentity identity;
        try {
            identity = taskIdentity(task);
        } catch (RuntimeException invalidIdentity) {
            return Optional.empty();
        }
        AidMediaSubmissionEvidence evidence = selectLatestEvidenceByVerdict(
                task.getId(), MediaSubmissionEvidenceVerdict.TERMINAL);
        if (!validEvidence(task, identity, evidence)
                || !Objects.equals(task.getStatus(), evidence.getTerminalStatus())) {
            return Optional.empty();
        }
        try {
            if ("LOCAL_TASK_FAILURE".equals(evidence.getEvidenceType())
                    || SynchronousExecutionFailureEvidence.TYPE.equals(evidence.getEvidenceType())
                    || "OPERATOR_FAILURE".equals(evidence.getEvidenceType())) {
                return Optional.of(new AuthoritativeTerminalBillingEvidence(evidence.getTerminalStatus(), Map.of()));
            }
            if ("PROVIDER_RESPONSE".equals(evidence.getEvidenceType())) {
                ProviderSubmissionReceiptSnapshot receipt = JSONUtil.toBean(
                        evidence.getSanitizedPayloadJson(), ProviderSubmissionReceiptSnapshot.class);
                if (receipt == null || !receipt.isReplayable()) return Optional.empty();
                return Optional.of(new AuthoritativeTerminalBillingEvidence(
                        evidence.getTerminalStatus(), immutableUsage(receipt.getUsage())));
            }
            if ("KNOWN_TASK_QUERY".equals(evidence.getEvidenceType())) {
                ProviderTaskEvidenceSnapshot snapshot = JSONUtil.toBean(
                        evidence.getSanitizedPayloadJson(), ProviderTaskEvidenceSnapshot.class);
                if (snapshot == null) return Optional.empty();
                return Optional.of(new AuthoritativeTerminalBillingEvidence(
                        evidence.getTerminalStatus(), immutableUsage(queryUsage(snapshot))));
            }
        } catch (RuntimeException invalidPayload) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private static Map<String, Object> queryUsage(ProviderTaskEvidenceSnapshot snapshot) {
        Map<String, Object> usage = new LinkedHashMap<>();
        if (snapshot.getResultCount() != null && snapshot.getResultCount() > 0) {
            usage.put("actualImageCount", snapshot.getResultCount());
            usage.put("resultCount", snapshot.getResultCount());
        }
        if (snapshot.getVideoDurationSeconds() != null && snapshot.getVideoDurationSeconds() > 0) {
            usage.put("actualDuration", snapshot.getVideoDurationSeconds());
        } else if (snapshot.getOutputDurationSeconds() != null && snapshot.getOutputDurationSeconds() > 0) {
            usage.put("actualDuration", snapshot.getOutputDurationSeconds());
        }
        if (snapshot.getProviderCredits() != null && snapshot.getProviderCredits().signum() > 0) {
            usage.put("actualProviderCredits", snapshot.getProviderCredits());
        }
        if (snapshot.getInputVideoSeconds() != null && snapshot.getInputVideoSeconds() >= 0) {
            usage.put("actualInputVideoSeconds", snapshot.getInputVideoSeconds());
        }
        if (snapshot.getInputImageCount() != null && snapshot.getInputImageCount() >= 0) {
            usage.put("actualInputImageCount", snapshot.getInputImageCount());
        }
        if (snapshot.getVideoBillingDurationSeconds() != null && snapshot.getVideoBillingDurationSeconds().signum() > 0) {
            usage.put("actualDuration", snapshot.getVideoBillingDurationSeconds());
        }
        if (snapshot.getInputVideoBillingDurationSeconds() != null && snapshot.getInputVideoBillingDurationSeconds().signum() >= 0) {
            usage.put("actualInputVideoSeconds", snapshot.getInputVideoBillingDurationSeconds());
        }
        if (snapshot.getCompletionTokens() != null && snapshot.getCompletionTokens() > 0) {
            usage.put("completion_tokens", snapshot.getCompletionTokens());
            usage.put("output_tokens", snapshot.getCompletionTokens());
        }
        if (snapshot.getTotalTokens() != null && snapshot.getTotalTokens() > 0) {
            usage.put("total_tokens", snapshot.getTotalTokens());
        }
        return usage;
    }

    private static Map<String, Object> immutableUsage(Map<String, Object> usage) {
        return usage == null || usage.isEmpty() ? Map.of() : Map.copyOf(usage);
    }

    @Override
    public int reconcileDueSubmissions(int batchSize) {
        int limit = Math.max(1, Math.min(batchSize, 200));
        registerEndedCalls(limit);
        try {
            if (anomalyObservers != null) anomalyObservers.orderedStream().forEach(observer -> {
                try { observer.refreshSubmissionAnomalies(limit); }
                catch (RuntimeException failure) {
                    log.warn("media submission diagnostic projection unavailable, errorType={}",
                            failure.getClass().getSimpleName());
                }
            });
        } catch (RuntimeException failure) {
            log.warn("media submission diagnostic observers unavailable, errorType={}",
                    failure.getClass().getSimpleName());
        }
        Date now = new Date();
        LambdaQueryWrapper<AidMediaSubmissionReconciliation> query = new LambdaQueryWrapper<>();
        query.eq(AidMediaSubmissionReconciliation::getDelFlag, "0");
        query.and(states -> states.eq(AidMediaSubmissionReconciliation::getStatus, STATUS_PENDING)
                .or(expired -> expired.eq(AidMediaSubmissionReconciliation::getStatus, STATUS_CLAIMED)
                        .le(AidMediaSubmissionReconciliation::getLeaseUntil, now)));
        query.and(due -> due.isNull(AidMediaSubmissionReconciliation::getNextRetryAt)
                .or().le(AidMediaSubmissionReconciliation::getNextRetryAt, now));
        query.orderByAsc(AidMediaSubmissionReconciliation::getNextRetryAt,
                AidMediaSubmissionReconciliation::getId);
        query.last("LIMIT " + limit);
        List<AidMediaSubmissionReconciliation> candidates = reconciliationMapper.selectList(query);
        if (candidates == null || candidates.isEmpty()) return 0;
        int handled = 0;
        for (AidMediaSubmissionReconciliation candidate : candidates) {
            if (processCandidate(candidate.getId(), now)) handled++;
        }
        return handled;
    }

    /** Restore missing reconciliation enrollment, without resubmitting or declaring a terminal. */
    private void registerEndedCalls(int limit) {
        var query = new LambdaQueryWrapper<AidMediaTask>();
        query.select(AidMediaTask::getId, AidMediaTask::getUserId,
                AidMediaTask::getProtocol, AidMediaTask::getMediaType);
        query.eq(AidMediaTask::getStatus, MediaTaskStatus.PENDING.name())
                .exists("SELECT 1 FROM aid_media_execution_permit p WHERE p.task_id=aid_media_task.id AND p.token=aid_media_task.execution_permit_token AND p.state='RELEASED' AND p.released_at IS NOT NULL AND p.execution_mode IN ('SYNC_TEXT','SYNC_IMAGE','SYNC_AUDIO','SUBMITTING','LEGACY')")
                .notExists("SELECT 1 FROM aid_media_submission_reconciliation r WHERE r.task_id=aid_media_task.id AND r.del_flag='0'")
                .orderByAsc(AidMediaTask::getId).last("LIMIT " + limit);
        for (var task : taskMapper.selectList(query)) {
            requiresNew.executeWithoutResult(status -> lockOrCreate(task));
        }
    }

    @Override
    public boolean reconcileDueSubmission(Long taskId) {
        return reconcileOneSubmission(taskId, false);
    }

    @Override
    public boolean reconcileSubmissionNow(Long taskId) {
        return reconcileOneSubmission(taskId, true);
    }

    private boolean reconcileOneSubmission(Long taskId, boolean explicitReview) {
        if (taskId == null) return false;
        var candidate = reconciliationMapper.selectOne(new LambdaQueryWrapper<AidMediaSubmissionReconciliation>()
                .eq(AidMediaSubmissionReconciliation::getTaskId, taskId)
                .eq(AidMediaSubmissionReconciliation::getDelFlag, "0").last("LIMIT 1"));
        return candidate != null && processCandidate(candidate.getId(), new Date(), explicitReview);
    }

    private boolean processCandidate(Long id, Date now) {
        return processCandidate(id, now, false);
    }

    private boolean processCandidate(Long id, Date now, boolean explicitReview) {
        String owner = UUID.randomUUID().toString();
        AidMediaSubmissionReconciliation claim = claim(id, owner, now, explicitReview);
        if (claim == null) return false;
        try {
            processClaim(claim, owner);
        } catch (RuntimeException ex) {
            log.warn("media submission reconciliation attempt failed, taskId={}, errorType={}",
                    claim.getTaskId(), ex.getClass().getSimpleName());
            retry(claim, owner, safeCode(ex.getClass().getSimpleName()));
        }
        return true;
    }

    private AidMediaSubmissionReconciliation claim(Long id, String owner, Date now, boolean explicitReview) {
        return requiresNew.execute(status -> {
            AidMediaSubmissionReconciliation current = reconciliationMapper.selectById(id);
            if (current == null || !"0".equals(current.getDelFlag())) return null;
            if (!explicitReview && current.getNextRetryAt() != null && current.getNextRetryAt().after(now)) return null;
            boolean pending = STATUS_PENDING.equals(current.getStatus());
            boolean expired = STATUS_CLAIMED.equals(current.getStatus())
                    && current.getLeaseUntil() != null && !current.getLeaseUntil().after(now);
            if (!pending && !expired) return null;
            LambdaUpdateWrapper<AidMediaSubmissionReconciliation> update = new LambdaUpdateWrapper<>();
            update.eq(AidMediaSubmissionReconciliation::getId, current.getId());
            update.eq(AidMediaSubmissionReconciliation::getStatus, current.getStatus());
            if (current.getStateRevision() == null) update.isNull(AidMediaSubmissionReconciliation::getStateRevision);
            else update.eq(AidMediaSubmissionReconciliation::getStateRevision, current.getStateRevision());
            update.set(AidMediaSubmissionReconciliation::getStatus, STATUS_CLAIMED);
            update.set(AidMediaSubmissionReconciliation::getLeaseOwner, owner);
            update.set(AidMediaSubmissionReconciliation::getLeaseUntil, new Date(now.getTime() + LEASE_MILLIS));
            update.set(AidMediaSubmissionReconciliation::getFencingToken, value(current.getFencingToken()) + 1L);
            update.set(AidMediaSubmissionReconciliation::getAttemptCount, count(current.getAttemptCount()) + 1);
            update.set(AidMediaSubmissionReconciliation::getStateRevision, value(current.getStateRevision()) + 1L);
            update.set(AidMediaSubmissionReconciliation::getUpdateTime, now);
            if (reconciliationMapper.update(null, update) != 1) return null;
            return reconciliationMapper.selectById(id);
        });
    }

    private void processClaim(AidMediaSubmissionReconciliation claim, String owner) {
        AidMediaTask task = taskMapper.selectById(claim.getTaskId());
        if (task == null) {
            finish(claim, owner, STATUS_UNSUPPORTED, null, "LOCAL_TASK_MISSING");
            return;
        }
        if (terminal(task)) {
            finish(claim, owner, STATUS_RESOLVED, null, null);
            return;
        }
        TaskIdentity identity;
        try {
            identity = taskIdentity(task);
        } catch (RuntimeException invalidIdentity) {
            finish(claim, owner, STATUS_UNSUPPORTED, null, "BUDGET_SUBMISSION_IDENTITY_INVALID");
            return;
        }
        AidMediaSubmissionEvidence latest = selectRecoveryEvidence(task.getId());
        if (latest != null && !validEvidence(task, identity, latest)) {
            finish(claim, owner, STATUS_UNSUPPORTED, latest, "EVIDENCE_INTEGRITY_INVALID");
            return;
        }
        if (latest != null && MediaSubmissionEvidenceVerdict.TERMINAL.name().equals(latest.getVerdict())) {
            applyTerminalEvidence(claim, owner, task, latest);
            return;
        }
        if (latest != null && MediaSubmissionEvidenceVerdict.ACCEPTED.name().equals(latest.getVerdict())
                && StrUtil.isNotBlank(latest.getProviderTaskId())) {
            attachAccepted(claim, owner, task, latest.getProviderTaskId());
            return;
        }
        if (StrUtil.isNotBlank(task.getProviderTaskId())) {
            queryKnownProviderTask(claim, owner, task);
            return;
        }
        if (latest != null && MediaSubmissionEvidenceVerdict.UNSUPPORTED.name().equals(latest.getVerdict())) {
            finish(claim, owner, STATUS_UNSUPPORTED, null,
                    StrUtil.blankToDefault(claim.getLastErrorCode(), "PROTOCOL_EVIDENCE_UNSUPPORTED"));
            return;
        }
        if (recoverClosedSynchronousExecution(claim, owner, task)) return;
        retry(claim, owner, "AUTHORITATIVE_EVIDENCE_UNAVAILABLE");
    }

    private boolean recoverClosedSynchronousExecution(AidMediaSubmissionReconciliation claim,
                                                      String owner, AidMediaTask task) {
        if (StrUtil.isBlank(task.getExecutionPermitToken())) return false;
        var permit = executionPermitMapper.selectByToken(task.getExecutionPermitToken());
        if (permit != null && "LEGACY".equals(permit.getExecutionMode())) {
            var ledger = accountUpdateService.inspectLedger(task.getBillingTraceId());
            var frozen = Objects.requireNonNullElse(task.getFrozenAmount(), java.math.BigDecimal.ZERO);
            boolean free = frozen.signum() == 0 && ledger != null && ledger.empty();
            boolean unconsumed = ledger != null && ledger.valid()
                    && Objects.equals(task.getUserId(), ledger.userId())
                    && frozen.compareTo(ledger.frozenAmount()) == 0
                    && ledger.grossConsumedAmount().signum() == 0
                    && ledger.settleUnfrozenAmount().signum() == 0
                    && (ledger.unfrozenAmount().signum() == 0 || ledger.unfrozenAmount().compareTo(frozen) == 0);
            boolean settled = com.aid.billing.service.SettledTextLedgerEvidence.matches(task, ledger);
            if (!free && !unconsumed && !settled) {
                requiresNew.executeWithoutResult(status -> {
                    var locked = reconciliationMapper.selectByTaskIdForUpdate(task.getId());
                    if (activeClaim(locked, claim, owner, new Date())) {
                        String payload = JSONUtil.toJsonStr(Map.of("diagnosticOnly", true,
                                "reviewCode", "LEGACY_WALLET_REVIEW_REQUIRED"));
                        appendEvidence(locked, task, "LEGACY_WALLET_REVIEW_V1", "LOCAL_LEDGER_REVIEW",
                                MediaSubmissionEvidenceVerdict.UNKNOWN, null, null, null,
                                payload, "LEGACY_WALLET_REVIEW_REQUIRED", false);
                    }
                });
                retry(claim, owner, "LEGACY_WALLET_REVIEW_REQUIRED");
                return true;
            }
        }
        if (permit != null && "ASYNC".equals(permit.getExecutionMode()) && "ACTIVE".equals(permit.getState())) {
            var source = evidenceMapper.selectOne(new LambdaQueryWrapper<AidMediaSubmissionEvidence>()
                    .eq(AidMediaSubmissionEvidence::getTaskId, task.getId())
                    .eq(AidMediaSubmissionEvidence::getEvidenceType, "PROVIDER_RESPONSE")
                    .eq(AidMediaSubmissionEvidence::getVerdict, MediaSubmissionEvidenceVerdict.UNKNOWN.name())
                    .eq(AidMediaSubmissionEvidence::getDelFlag, "0")
                    .orderByDesc(AidMediaSubmissionEvidence::getObservedAt, AidMediaSubmissionEvidence::getId)
                    .last("LIMIT 1"));
            var applier = receiptApplierProvider.getIfAvailable();
            if (source != null && applier != null && validEvidence(task, taskIdentity(task), source)) {
                var snapshot = JSONUtil.toBean(source.getSanitizedPayloadJson(), ProviderSubmissionReceiptSnapshot.class);
                if (snapshot != null && applier.recoverReturnedSynchronousExecution(task, snapshot.restore()))
                    permit = executionPermitMapper.selectByToken(task.getExecutionPermitToken());
            }
        }
        var proof = SynchronousExecutionFailureEvidence.capture(task, permit);
        if (proof == null || !renewClaim(claim, owner)) return false;
        AidMediaSubmissionEvidence recorded = requiresNew.execute(status -> {
            var locked = reconciliationMapper.selectByTaskIdForUpdate(task.getId());
            if (!activeClaim(locked, claim, owner, new Date())) return null;
            return appendEvidence(locked, task, "LOCAL_SYNC_EXECUTION_V1",
                    SynchronousExecutionFailureEvidence.TYPE, MediaSubmissionEvidenceVerdict.TERMINAL,
                    null, SynchronousExecutionFailureEvidence.STATUS, MediaTaskStatus.FAILED.name(),
                    JSONUtil.toJsonStr(proof), SynchronousExecutionFailureEvidence.STATUS, false);
        });
        if (recorded == null) return true;
        // Persist first. A crash here is recovered through the same evidence replay path.
        AidMediaSubmissionEvidence evidence = selectRecoveryEvidence(task.getId());
        if (validEvidence(task, taskIdentity(task), evidence)) {
            applyTerminalEvidence(claim, owner, task, evidence);
        } else {
            retry(claim, owner, "LOCAL_EXECUTION_EVIDENCE_RELOAD_PENDING");
        }
        return true;
    }

    private void queryKnownProviderTask(AidMediaSubmissionReconciliation claim, String owner, AidMediaTask task) {
        AuthoritativeProviderTaskQuery query = taskDispatchService.queryAuthoritativeProviderTask(task);
        if (terminal(taskMapper.selectById(task.getId()))) {
            finish(claim, owner, STATUS_RESOLVED, null, null);
            return;
        }
        if (query != null && "QUERY_IN_PROGRESS".equals(query.errorCode())) {
            retry(claim, owner, query.errorCode());
            return;
        }
        if (query == null || query.outcome() == AuthoritativeProviderTaskQuery.Outcome.UNKNOWN) {
            appendQueryEvidence(claim, task, query, MediaSubmissionEvidenceVerdict.UNKNOWN, null);
            retry(claim, owner, query == null ? "QUERY_RESULT_MISSING" : query.errorCode());
            return;
        }
        if (query.outcome() == AuthoritativeProviderTaskQuery.Outcome.UNSUPPORTED) {
            appendQueryEvidence(claim, task, query, MediaSubmissionEvidenceVerdict.UNSUPPORTED, null);
            finish(claim, owner, STATUS_UNSUPPORTED, null, query.errorCode());
            return;
        }
        ProviderTaskResult result = query.taskResult();
        MediaSubmissionEvidenceVerdict verdict = classifyQuery(result);
        String payload = result == null ? null : JSONUtil.toJsonStr(ProviderTaskEvidenceSnapshot.capture(result));
        AidMediaSubmissionEvidence evidence = appendQueryEvidence(claim, task, query, verdict, payload);
        if (evidence == null) return;
        if (verdict == MediaSubmissionEvidenceVerdict.TERMINAL) {
            if (!renewClaim(claim, owner)) return;
            boolean completed = taskCompletionService.completeTask(task.getId(), result);
            AidMediaTask current = taskMapper.selectById(task.getId());
            if (terminal(current)) finish(claim, owner, STATUS_RESOLVED, evidence, null);
            else retry(claim, owner, completed ? "TERMINAL_STATE_RELOAD_PENDING" : "TERMINAL_CAS_NOT_WON");
        } else if (verdict == MediaSubmissionEvidenceVerdict.ACCEPTED) {
            attachAccepted(claim, owner, task, task.getProviderTaskId());
        } else {
            retry(claim, owner, "AUTHORITATIVE_QUERY_INCONCLUSIVE");
        }
    }

    private AidMediaSubmissionEvidence appendQueryEvidence(AidMediaSubmissionReconciliation claim, AidMediaTask task,
                                                            AuthoritativeProviderTaskQuery query,
                                                            MediaSubmissionEvidenceVerdict verdict, String payload) {
        String adapter = query == null ? "KNOWN_TASK_QUERY" : StrUtil.blankToDefault(query.adapterCode(), "KNOWN_TASK_QUERY");
        ProviderTaskResult result = query == null ? null : query.taskResult();
        return requiresNew.execute(status -> {
            AidMediaSubmissionReconciliation locked = reconciliationMapper.selectByTaskIdForUpdate(task.getId());
            Date now = new Date();
            if (!activeClaim(locked, claim, claim.getLeaseOwner(), now)) return null;
            return appendEvidence(locked, task, adapter, "KNOWN_TASK_QUERY", verdict, task.getProviderTaskId(),
                    result == null ? null : result.getProviderStatus(),
                    verdict == MediaSubmissionEvidenceVerdict.TERMINAL && result != null ? result.getStatus() : null,
                    payload, query == null ? "QUERY_RESULT_MISSING" : query.errorCode(), false);
        });
    }

    private AidMediaSubmissionEvidence selectRecoveryEvidence(Long taskId) {
        AidMediaSubmissionEvidence terminal = selectLatestEvidenceByVerdict(
                taskId, MediaSubmissionEvidenceVerdict.TERMINAL);
        if (terminal != null) return terminal;
        AidMediaSubmissionEvidence accepted = selectLatestEvidenceByVerdict(
                taskId, MediaSubmissionEvidenceVerdict.ACCEPTED);
        if (accepted != null) return accepted;
        AidMediaSubmissionEvidence unsupported = selectLatestEvidenceByVerdict(
                taskId, MediaSubmissionEvidenceVerdict.UNSUPPORTED);
        return unsupported != null ? unsupported : evidenceMapper.selectLatestByTaskId(taskId);
    }

    private AidMediaSubmissionEvidence selectLatestEvidenceByVerdict(
            Long taskId, MediaSubmissionEvidenceVerdict verdict) {
        return evidenceMapper.selectOne(new QueryWrapper<AidMediaSubmissionEvidence>()
                .eq("task_id", taskId)
                .eq("verdict", verdict.name())
                .eq("del_flag", "0")
                .orderByDesc("observed_at", "id")
                .last("LIMIT 1"));
    }

    private boolean validEvidence(AidMediaTask task, TaskIdentity identity,
                                  AidMediaSubmissionEvidence evidence) {
        if (evidence == null || !Objects.equals(task.getId(), evidence.getTaskId())
                || !matchesTaskIdentity(task, identity, evidence)) return false;
        String expectedDigest = evidenceDigest(evidence.getTaskId(), evidence.getAdapterCode(),
                evidence.getEvidenceType(), evidence.getVerdict(), evidence.getProviderTaskId(),
                evidence.getProviderStatus(), evidence.getTerminalStatus(), evidence.getSanitizedPayloadJson());
        if (!Objects.equals(expectedDigest, evidence.getEvidenceDigest())) return false;
        MediaSubmissionEvidenceVerdict verdict;
        try {
            verdict = MediaSubmissionEvidenceVerdict.valueOf(evidence.getVerdict());
        } catch (RuntimeException invalid) {
            return false;
        }
        try {
            if (verdict == MediaSubmissionEvidenceVerdict.TERMINAL) {
                if (!Boolean.TRUE.equals(evidence.getTerminalConfirmed())) return false;
                if ("LOCAL_TASK_FAILURE".equals(evidence.getEvidenceType())) {
                    var decision = JSONUtil.parseObj(evidence.getSanitizedPayloadJson());
                    var reason = com.aid.media.model.MediaTaskFailureReason.valueOf(decision.getStr("failureReason"));
                    boolean accepted = StrUtil.isNotBlank(task.getProviderTaskId());
                    return MediaTaskStatus.FAILED.name().equals(task.getStatus())
                            && MediaTaskStatus.FAILED.name().equals(evidence.getTerminalStatus())
                            && accepted == (reason == com.aid.media.model.MediaTaskFailureReason.UPSTREAM_QUERY_UNAVAILABLE)
                            && Objects.equals(decision.getStr("executionToken"), StrUtil.blankToDefault(task.getExecutionPermitToken(), ""))
                            && Objects.equals(decision.getStr("providerTaskId"), StrUtil.blankToDefault(task.getProviderTaskId(), ""));
                }
                if ("OPERATOR_FAILURE".equals(evidence.getEvidenceType())) {
                    var decision = JSONUtil.parseObj(evidence.getSanitizedPayloadJson());
                    return MediaTaskStatus.FAILED.name().equals(task.getStatus())
                            && MediaTaskStatus.FAILED.name().equals(evidence.getTerminalStatus())
                            && Boolean.TRUE.equals(decision.getBool("confirmed"))
                            && decision.getLong("operatorId", 0L) > 0
                            && StrUtil.isNotBlank(decision.getStr("reason"));
                }
                if (SynchronousExecutionFailureEvidence.TYPE.equals(evidence.getEvidenceType())) {
                    if (!MediaTaskStatus.FAILED.name().equals(evidence.getTerminalStatus())
                            || StrUtil.isBlank(task.getExecutionPermitToken())) return false;
                    var proof = JSONUtil.toBean(evidence.getSanitizedPayloadJson(), SynchronousExecutionFailureEvidence.class);
                    return proof != null && proof.matches(task,
                            executionPermitMapper.selectByToken(task.getExecutionPermitToken()));
                }
                if ("PROVIDER_RESPONSE".equals(evidence.getEvidenceType())) {
                    ProviderSubmissionReceiptSnapshot snapshot = JSONUtil.toBean(
                            evidence.getSanitizedPayloadJson(), ProviderSubmissionReceiptSnapshot.class);
                    return snapshot != null && snapshot.isReplayable()
                            && Objects.equals(snapshot.getProviderTaskId(), evidence.getProviderTaskId())
                            && ((snapshot.hasTerminalFailure()
                                    && MediaTaskStatus.FAILED.name().equals(evidence.getTerminalStatus()))
                                || (snapshot.getConfirmedFailureCode() == null && snapshot.hasTerminalOutput()
                                    && MediaTaskStatus.SUCCEEDED.name().equals(evidence.getTerminalStatus())));
                }
                if ("KNOWN_TASK_QUERY".equals(evidence.getEvidenceType())) {
                    ProviderTaskEvidenceSnapshot snapshot = JSONUtil.toBean(
                            evidence.getSanitizedPayloadJson(), ProviderTaskEvidenceSnapshot.class);
                    return snapshot != null && Boolean.TRUE.equals(evidence.getQuerySuccessful())
                            && Objects.equals(task.getProviderTaskId(), evidence.getProviderTaskId())
                            && Boolean.TRUE.equals(snapshot.getQuerySuccessful())
                            && Boolean.TRUE.equals(snapshot.getTerminalConfirmed())
                            && (MediaTaskStatus.SUCCEEDED.name().equals(snapshot.getStatus())
                                || MediaTaskStatus.FAILED.name().equals(snapshot.getStatus()))
                            && Objects.equals(snapshot.getStatus(), evidence.getTerminalStatus())
                            && Objects.equals(snapshot.getProviderStatus(), evidence.getProviderStatus());
                }
                return false;
            }
            if (verdict == MediaSubmissionEvidenceVerdict.ACCEPTED) {
                if (StrUtil.isBlank(evidence.getProviderTaskId())
                        || Boolean.TRUE.equals(evidence.getTerminalConfirmed())) return false;
                if ("PROVIDER_RESPONSE".equals(evidence.getEvidenceType())) {
                    ProviderSubmissionReceiptSnapshot snapshot = JSONUtil.toBean(
                            evidence.getSanitizedPayloadJson(), ProviderSubmissionReceiptSnapshot.class);
                    return snapshot != null && Objects.equals(snapshot.getProviderTaskId(), evidence.getProviderTaskId())
                            && snapshot.getConfirmedFailureCode() == null && !snapshot.hasTerminalOutput();
                }
                if ("KNOWN_TASK_QUERY".equals(evidence.getEvidenceType())) {
                    ProviderTaskEvidenceSnapshot snapshot = JSONUtil.toBean(
                            evidence.getSanitizedPayloadJson(), ProviderTaskEvidenceSnapshot.class);
                    return snapshot != null && Boolean.TRUE.equals(evidence.getQuerySuccessful())
                            && Objects.equals(task.getProviderTaskId(), evidence.getProviderTaskId())
                            && Boolean.TRUE.equals(snapshot.getQuerySuccessful())
                            && !Boolean.TRUE.equals(snapshot.getTerminalConfirmed())
                            && MediaTaskStatus.PROCESSING.name().equals(snapshot.getStatus())
                            && Objects.equals(snapshot.getProviderStatus(), evidence.getProviderStatus());
                }
                return false;
            }
            return true;
        } catch (RuntimeException invalidPayload) {
            return false;
        }
    }

    private boolean renewClaim(AidMediaSubmissionReconciliation claim, String owner) {
        return Boolean.TRUE.equals(requiresNew.execute(status -> {
            Date now = new Date();
            AidMediaSubmissionReconciliation locked =
                    reconciliationMapper.selectByTaskIdForUpdate(claim.getTaskId());
            if (!activeClaim(locked, claim, owner, now)) return false;
            LambdaUpdateWrapper<AidMediaSubmissionReconciliation> update = new LambdaUpdateWrapper<>();
            update.eq(AidMediaSubmissionReconciliation::getId, claim.getId());
            update.eq(AidMediaSubmissionReconciliation::getStatus, STATUS_CLAIMED);
            update.eq(AidMediaSubmissionReconciliation::getLeaseOwner, owner);
            update.eq(AidMediaSubmissionReconciliation::getFencingToken, claim.getFencingToken());
            update.gt(AidMediaSubmissionReconciliation::getLeaseUntil, now);
            update.set(AidMediaSubmissionReconciliation::getLeaseUntil,
                    new Date(now.getTime() + LEASE_MILLIS));
            update.setSql("state_revision = state_revision + 1");
            update.set(AidMediaSubmissionReconciliation::getUpdateTime, now);
            return reconciliationMapper.update(null, update) == 1;
        }));
    }

    private static boolean activeClaim(AidMediaSubmissionReconciliation locked,
                                       AidMediaSubmissionReconciliation expected,
                                       String owner, Date now) {
        return locked != null && Objects.equals(locked.getId(), expected.getId())
                && STATUS_CLAIMED.equals(locked.getStatus())
                && Objects.equals(owner, locked.getLeaseOwner())
                && Objects.equals(expected.getFencingToken(), locked.getFencingToken())
                && locked.getLeaseUntil() != null && locked.getLeaseUntil().after(now);
    }

    private void applyTerminalEvidence(AidMediaSubmissionReconciliation claim, String owner, AidMediaTask task,
                                       AidMediaSubmissionEvidence evidence) {
        if (!renewClaim(claim, owner)) return;
        boolean completed;
        if (SynchronousExecutionFailureEvidence.TYPE.equals(evidence.getEvidenceType())) {
            var proof = JSONUtil.toBean(evidence.getSanitizedPayloadJson(), SynchronousExecutionFailureEvidence.class);
            completed = proof != null && taskCompletionService.completeSynchronousExecutionFailure(
                    task.getId(), proof.getExecutionToken());
        } else if ("PROVIDER_RESPONSE".equals(evidence.getEvidenceType())) {
            ProviderSubmitReceiptApplier applier = receiptApplierProvider.getIfAvailable();
            if (applier == null || StrUtil.isBlank(evidence.getSanitizedPayloadJson())) {
                finish(claim, owner, STATUS_UNSUPPORTED, evidence, "RECEIPT_REPLAY_UNAVAILABLE");
                return;
            }
            ProviderSubmissionReceiptSnapshot snapshot = JSONUtil.toBean(
                    evidence.getSanitizedPayloadJson(), ProviderSubmissionReceiptSnapshot.class);
            if (snapshot == null || !snapshot.isReplayable()) {
                finish(claim, owner, STATUS_UNSUPPORTED, evidence, "RECEIPT_REPLAY_UNAVAILABLE");
                return;
            }
            completed = applier.applyRecordedProviderReceipt(task.getId(), snapshot.restore());
        } else if ("KNOWN_TASK_QUERY".equals(evidence.getEvidenceType())) {
            ProviderTaskEvidenceSnapshot snapshot = JSONUtil.toBean(
                    evidence.getSanitizedPayloadJson(), ProviderTaskEvidenceSnapshot.class);
            completed = snapshot != null && taskCompletionService.completeTask(task.getId(), snapshot.restore());
        } else {
            finish(claim, owner, STATUS_UNSUPPORTED, evidence, "EVIDENCE_ADAPTER_UNSUPPORTED");
            return;
        }
        AidMediaTask current = taskMapper.selectById(task.getId());
        if (terminal(current)) finish(claim, owner, STATUS_RESOLVED, evidence, null);
        else retry(claim, owner, completed ? "TERMINAL_STATE_RELOAD_PENDING" : "TERMINAL_CAS_NOT_WON");
    }

    private void attachAccepted(AidMediaSubmissionReconciliation claim, String owner,
                                AidMediaTask task, String providerTaskId) {
        if (!renewClaim(claim, owner)) return;
        boolean attached = taskDispatchService.attachAcceptedProviderTask(task.getId(), providerTaskId);
        if (attached) finish(claim, owner, STATUS_ACCEPTED, null, null);
        else retry(claim, owner, "ACCEPTED_TASK_ATTACH_PENDING");
    }

    private void retry(AidMediaSubmissionReconciliation claim, String owner, String errorCode) {
        int attempts = Math.max(1, count(claim.getAttemptCount()));
        long delaySeconds = Math.min(3600L, 30L << Math.min(attempts - 1, 7));
        finish(claim, owner, STATUS_PENDING, null, errorCode,
                new Date(System.currentTimeMillis() + delaySeconds * 1000L));
    }

    private void finish(AidMediaSubmissionReconciliation claim, String owner, String target,
                        AidMediaSubmissionEvidence evidence, String errorCode) {
        finish(claim, owner, target, evidence, errorCode, null);
    }

    private void finish(AidMediaSubmissionReconciliation claim, String owner, String target,
                        AidMediaSubmissionEvidence evidence, String errorCode, Date nextRetry) {
        requiresNew.executeWithoutResult(status -> {
            LambdaUpdateWrapper<AidMediaSubmissionReconciliation> update = new LambdaUpdateWrapper<>();
            update.eq(AidMediaSubmissionReconciliation::getId, claim.getId());
            update.eq(AidMediaSubmissionReconciliation::getStatus, STATUS_CLAIMED);
            update.eq(AidMediaSubmissionReconciliation::getLeaseOwner, owner);
            update.eq(AidMediaSubmissionReconciliation::getFencingToken, claim.getFencingToken());
            update.gt(AidMediaSubmissionReconciliation::getLeaseUntil, new Date());
            update.set(AidMediaSubmissionReconciliation::getStatus, target);
            update.set(AidMediaSubmissionReconciliation::getNextRetryAt, nextRetry);
            update.set(AidMediaSubmissionReconciliation::getLeaseOwner, null);
            update.set(AidMediaSubmissionReconciliation::getLeaseUntil, null);
            update.set(AidMediaSubmissionReconciliation::getLastErrorCode, safeCode(errorCode));
            if (evidence != null) {
                update.set(AidMediaSubmissionReconciliation::getLastEvidenceId, evidence.getId());
                update.set(AidMediaSubmissionReconciliation::getLastEvidenceDigest, evidence.getEvidenceDigest());
            }
            update.setSql("state_revision = state_revision + 1");
            update.set(AidMediaSubmissionReconciliation::getUpdateTime, new Date());
            reconciliationMapper.update(null, update);
        });
    }

    private void recordEvidence(AidMediaTask task, String adapterCode, String evidenceType,
                                MediaSubmissionEvidenceVerdict verdict, String providerTaskId,
                                String providerStatus, String terminalStatus, String payload, String errorCode) {
        AidMediaSubmissionReconciliation reconciliation = lockOrCreate(task);
        AidMediaSubmissionEvidence evidence = appendEvidence(reconciliation, task, adapterCode, evidenceType,
                verdict, providerTaskId, providerStatus, terminalStatus, payload, errorCode, true);
        if (terminal(task)) {
            schedule(reconciliation, STATUS_RESOLVED, null, errorCode, evidence, null);
        }
    }

    private AidMediaSubmissionEvidence appendEvidence(AidMediaSubmissionReconciliation reconciliation,
                                                       AidMediaTask task, String adapterCode, String evidenceType,
                                                       MediaSubmissionEvidenceVerdict verdict, String providerTaskId,
                                                       String providerStatus, String terminalStatus, String payload,
                                                       String errorCode, boolean scheduleState) {
        TaskIdentity identity = taskIdentity(task);
        String digest = evidenceDigest(task.getId(), adapterCode, evidenceType, verdict.name(), providerTaskId,
                providerStatus, terminalStatus, payload);
        AidMediaSubmissionEvidence evidence = new AidMediaSubmissionEvidence();
        evidence.setReconciliationId(reconciliation.getId());
        evidence.setTaskId(task.getId());
        evidence.setAdapterCode(adapterCode);
        evidence.setEvidenceType(evidenceType);
        evidence.setVerdict(verdict.name());
        evidence.setProviderTaskId(providerTaskId);
        evidence.setRequestDigest(identity.requestDigest());
        evidence.setSubmissionKey(identity.submissionKey());
        evidence.setEvidenceDigest(digest);
        evidence.setProviderStatus(providerStatus);
        evidence.setTerminalStatus(terminalStatus);
        evidence.setQuerySuccessful(evidenceType.equals("KNOWN_TASK_QUERY")
                ? verdict != MediaSubmissionEvidenceVerdict.UNKNOWN && verdict != MediaSubmissionEvidenceVerdict.UNSUPPORTED
                : null);
        evidence.setTerminalConfirmed(verdict == MediaSubmissionEvidenceVerdict.TERMINAL ? Boolean.TRUE : null);
        evidence.setSanitizedPayloadJson(payload);
        evidence.setObservedAt(new Date());
        evidence.setDelFlag("0");
        evidence.setCreateBy(Objects.toString(task.getUserId(), ""));
        evidence.setCreateTime(new Date());
        evidence.setUpdateBy(evidence.getCreateBy());
        evidence.setUpdateTime(evidence.getCreateTime());
        try {
            evidenceMapper.insert(evidence);
        } catch (DuplicateKeyException duplicate) {
            evidence = evidenceMapper.selectOne(new LambdaQueryWrapper<AidMediaSubmissionEvidence>()
                    .eq(AidMediaSubmissionEvidence::getTaskId, task.getId())
                    .eq(AidMediaSubmissionEvidence::getEvidenceDigest, digest)
                    .last("LIMIT 1"));
        }
        if (scheduleState) {
            String target = switch (verdict) {
                case ACCEPTED, TERMINAL, UNKNOWN -> STATUS_PENDING;
                case DEFINITIVELY_NOT_ACCEPTED -> STATUS_NOT_ACCEPTED;
                case UNSUPPORTED -> STATUS_UNSUPPORTED;
            };
            Date next = STATUS_PENDING.equals(target)
                    ? new Date(System.currentTimeMillis()
                        + (verdict == MediaSubmissionEvidenceVerdict.ACCEPTED
                            || verdict == MediaSubmissionEvidenceVerdict.TERMINAL ? 30_000L : 60_000L))
                    : null;
            schedule(reconciliation, target, next, errorCode, evidence, null);
        } else if (STATUS_CLAIMED.equals(reconciliation.getStatus())) {
            LambdaUpdateWrapper<AidMediaSubmissionReconciliation> update = new LambdaUpdateWrapper<>();
            update.eq(AidMediaSubmissionReconciliation::getId, reconciliation.getId());
            update.eq(AidMediaSubmissionReconciliation::getStatus, STATUS_CLAIMED);
            update.eq(AidMediaSubmissionReconciliation::getLeaseOwner, reconciliation.getLeaseOwner());
            update.eq(AidMediaSubmissionReconciliation::getFencingToken, reconciliation.getFencingToken());
            update.gt(AidMediaSubmissionReconciliation::getLeaseUntil, new Date());
            update.set(AidMediaSubmissionReconciliation::getLastEvidenceId, evidence.getId());
            update.set(AidMediaSubmissionReconciliation::getLastEvidenceDigest, evidence.getEvidenceDigest());
            update.set(AidMediaSubmissionReconciliation::getLastErrorCode, safeCode(errorCode));
            update.setSql("state_revision = state_revision + 1");
            update.set(AidMediaSubmissionReconciliation::getUpdateTime, new Date());
            reconciliationMapper.update(null, update);
        }
        return evidence;
    }

    private AidMediaSubmissionReconciliation lockOrCreate(AidMediaTask task) {
        AidMediaSubmissionReconciliation current = reconciliationMapper.selectByTaskIdForUpdate(task.getId());
        if (current != null) return current;
        AidMediaSubmissionReconciliation value = new AidMediaSubmissionReconciliation();
        value.setTaskId(task.getId());
        value.setProtocol(StrUtil.blankToDefault(task.getProtocol(), "UNKNOWN"));
        value.setMediaType(StrUtil.blankToDefault(task.getMediaType(), "UNKNOWN"));
        value.setStatus(STATUS_PENDING);
        value.setNextRetryAt(new Date());
        value.setAttemptCount(0);
        value.setFencingToken(0L);
        value.setStateRevision(0L);
        value.setDelFlag("0");
        value.setCreateBy(Objects.toString(task.getUserId(), ""));
        value.setCreateTime(new Date());
        value.setUpdateBy(value.getCreateBy());
        value.setUpdateTime(value.getCreateTime());
        try {
            reconciliationMapper.insert(value);
        } catch (DuplicateKeyException duplicate) {
            return reconciliationMapper.selectByTaskIdForUpdate(task.getId());
        }
        return value;
    }

    private void schedule(AidMediaSubmissionReconciliation reconciliation, String target, Date nextRetry,
                          String errorCode, AidMediaSubmissionEvidence evidence, String leaseOwner) {
        LambdaUpdateWrapper<AidMediaSubmissionReconciliation> update = new LambdaUpdateWrapper<>();
        update.eq(AidMediaSubmissionReconciliation::getId, reconciliation.getId());
        update.set(AidMediaSubmissionReconciliation::getStatus, target);
        update.set(AidMediaSubmissionReconciliation::getNextRetryAt, nextRetry);
        update.set(AidMediaSubmissionReconciliation::getLeaseOwner, leaseOwner);
        update.set(AidMediaSubmissionReconciliation::getLeaseUntil, null);
        update.set(AidMediaSubmissionReconciliation::getLastErrorCode, safeCode(errorCode));
        if (evidence != null) {
            update.set(AidMediaSubmissionReconciliation::getLastEvidenceId, evidence.getId());
            update.set(AidMediaSubmissionReconciliation::getLastEvidenceDigest, evidence.getEvidenceDigest());
        }
        update.setSql("state_revision = state_revision + 1");
        update.set(AidMediaSubmissionReconciliation::getUpdateTime, new Date());
        reconciliationMapper.update(null, update);
    }

    private boolean matchesTaskIdentity(AidMediaTask task, TaskIdentity current,
                                        AidMediaSubmissionEvidence evidence) {
        if (BillingBudgetContext.isBudgetManagedBizTaskType(task.getBizTaskType())) {
            return StrUtil.isNotBlank(current.requestDigest()) && StrUtil.isNotBlank(current.submissionKey())
                    && Objects.equals(current.requestDigest(), evidence.getRequestDigest())
                    && Objects.equals(current.submissionKey(), evidence.getSubmissionKey());
        }
        return (StrUtil.isBlank(evidence.getRequestDigest())
                    || Objects.equals(current.requestDigest(), evidence.getRequestDigest()))
                && (StrUtil.isBlank(evidence.getSubmissionKey())
                    || Objects.equals(current.submissionKey(), evidence.getSubmissionKey()));
    }

    private static String evidenceDigest(Long taskId, String adapterCode, String evidenceType,
                                         String verdict, String providerTaskId, String providerStatus,
                                         String terminalStatus, String payload) {
        return SecureUtil.sha256(String.join("|", Objects.toString(taskId, ""),
                Objects.toString(adapterCode, ""), Objects.toString(evidenceType, ""),
                Objects.toString(verdict, ""), Objects.toString(providerTaskId, ""),
                Objects.toString(providerStatus, ""), Objects.toString(terminalStatus, ""),
                Objects.toString(payload, "")));
    }

    private static TaskIdentity taskIdentity(AidMediaTask task) {
        if (BillingBudgetContext.isBudgetManagedBizTaskType(task.getBizTaskType())) {
            if (StrUtil.isBlank(task.getBillingSnapshotJson())) {
                throw new IllegalStateException("BUDGET_SUBMISSION_IDENTITY_INVALID");
            }
            BillingSnapshot snapshot;
            try {
                snapshot = JSONUtil.toBean(task.getBillingSnapshotJson(), BillingSnapshot.class);
            } catch (RuntimeException invalid) {
                throw new IllegalStateException("BUDGET_SUBMISSION_IDENTITY_INVALID", invalid);
            }
            if (snapshot == null || StrUtil.isBlank(snapshot.getRequestDigest())
                    || StrUtil.isBlank(snapshot.getLogicalCallKey())
                    || StrUtil.isBlank(task.getRequestHash()) || StrUtil.isBlank(task.getIdempotencyKey())
                    || !Objects.equals(snapshot.getLogicalCallKey(), task.getIdempotencyKey())) {
                throw new IllegalStateException("BUDGET_SUBMISSION_IDENTITY_INVALID");
            }
            // 报价摘要是规范 JSON 的摘要，媒体去重哈希还包含媒体类型、用户和稳定字段串；
            // 两者使用不同算法，不能要求相等。证据身份同时绑定两者，任何一项变化均不可复用。
            // 旧同值快照保持原证据摘要兼容；正常不同值任务使用带版本前缀的组合摘要。
            String evidenceRequestDigest = Objects.equals(snapshot.getRequestDigest(), task.getRequestHash())
                    ? snapshot.getRequestDigest()
                    : SecureUtil.sha256("BUDGET_SUBMISSION_IDENTITY_V2|"
                            + snapshot.getRequestDigest() + "|" + task.getRequestHash());
            return new TaskIdentity(evidenceRequestDigest, snapshot.getLogicalCallKey());
        }
        String requestDigest = task.getRequestHash();
        String submissionKey = task.getIdempotencyKey();
        if (StrUtil.isNotBlank(task.getBillingSnapshotJson())) {
            try {
                BillingSnapshot snapshot = JSONUtil.toBean(task.getBillingSnapshotJson(), BillingSnapshot.class);
                if (snapshot != null) {
                    requestDigest = StrUtil.blankToDefault(snapshot.getRequestDigest(), requestDigest);
                    submissionKey = StrUtil.blankToDefault(snapshot.getLogicalCallKey(), submissionKey);
                }
            } catch (RuntimeException ignored) { }
        }
        return new TaskIdentity(requestDigest, submissionKey);
    }

    private static MediaSubmissionEvidenceVerdict classifyReceipt(ProviderSubmissionReceiptSnapshot receipt) {
        if (receipt == null) return MediaSubmissionEvidenceVerdict.UNKNOWN;
        if (receipt.getConfirmedFailureCode() != null)
            return receipt.hasTerminalFailure() ? MediaSubmissionEvidenceVerdict.TERMINAL : MediaSubmissionEvidenceVerdict.UNKNOWN;
        if (receipt.hasTerminalOutput()) return MediaSubmissionEvidenceVerdict.TERMINAL;
        if (StrUtil.isNotBlank(receipt.getProviderTaskId())) return MediaSubmissionEvidenceVerdict.ACCEPTED;
        return MediaSubmissionEvidenceVerdict.UNKNOWN;
    }

    private static MediaSubmissionEvidenceVerdict classifyQuery(ProviderTaskResult result) {
        if (result == null || !Boolean.TRUE.equals(result.getQuerySuccessful())) {
            return MediaSubmissionEvidenceVerdict.UNKNOWN;
        }
        if (Boolean.TRUE.equals(result.getTerminalConfirmed())
                && (MediaTaskStatus.SUCCEEDED.name().equals(result.getStatus())
                    || MediaTaskStatus.FAILED.name().equals(result.getStatus()))) {
            return MediaSubmissionEvidenceVerdict.TERMINAL;
        }
        if (!Boolean.TRUE.equals(result.getTerminalConfirmed())
                && MediaTaskStatus.PROCESSING.name().equals(result.getStatus())) {
            return MediaSubmissionEvidenceVerdict.ACCEPTED;
        }
        return MediaSubmissionEvidenceVerdict.UNKNOWN;
    }

    private static boolean terminal(AidMediaTask task) {
        return task != null && (MediaTaskStatus.SUCCEEDED.name().equals(task.getStatus())
                || MediaTaskStatus.FAILED.name().equals(task.getStatus())
                || MediaTaskStatus.CANCELLED.name().equals(task.getStatus()));
    }

    private static void requirePersistedTask(AidMediaTask task) {
        if (task == null || task.getId() == null) throw new IllegalArgumentException("persisted media task required");
    }

    private static long value(Long value) { return value == null ? 0L : value; }
    private static int count(Integer value) { return value == null ? 0 : value; }

    private static String safeCode(String value) {
        if (value == null) return null;
        String normalized = value.replaceAll("[^A-Za-z0-9_.:-]", "_");
        return normalized.substring(0, Math.min(normalized.length(), 64));
    }

    private record TaskIdentity(String requestDigest, String submissionKey) { }
}
