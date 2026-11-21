package com.aid.media.service.impl;

import com.aid.common.error.TaskErrorResult;
import com.aid.common.error.ErrorNormalizer;
import com.aid.common.error.TaskErrorSnapshot;
import com.aid.common.exception.ServiceException;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.aid.aid.domain.media.AidMediaResult;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.aid.mapper.AidMediaResultMapper;
import com.aid.billing.dto.BillingBudgetContext;
import com.aid.billing.service.BillingFacadeService;
import com.aid.compose.ComposeConstants;
import com.aid.compose.service.ComposeCompletionService;
import com.aid.media.enums.MediaTaskStatus;
import com.aid.media.enums.MediaBillingStatus;
import com.aid.media.admission.MediaTaskCancellationAdmissionDecision;
import com.aid.media.admission.MediaTaskCancellationAdmissionGate;
import com.aid.media.admission.MediaTaskCancellationPurpose;
import com.aid.media.admission.MediaTaskCancellationRequest;
import com.aid.media.admission.MediaTaskCancellationResult;
import com.aid.media.event.MediaQueueSlotReleasedEvent;
import com.aid.media.event.MediaTaskCompletedEvent;
import com.aid.media.event.MediaTaskOssPersistedEvent;
import com.aid.media.provider.ProviderTaskResult;
import com.aid.media.provider.TextFailureBillingPolicy;
import com.aid.media.service.MediaConcurrencyLimiter;
import com.aid.media.service.MediaTaskArchiveService;
import com.aid.media.service.TaskCompletionService;
import com.aid.media.util.MediaTaskPayloadSanitizer;
import com.aid.media.enums.MediaType;
import com.aid.media.eta.MediaEtaRecorder;
import com.aid.modelhealth.service.ModelHealthRecorder;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.model.definition.ModelTaskConfigurationResolver;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Date;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 统一终态处理：回调与轮询都走同一入口，CAS 抢终态处理权，幂等收口。
 */
@Slf4j
@Service
@RequiredArgsConstructor(onConstructor_ = @org.springframework.beans.factory.annotation.Autowired)
public class TaskCompletionServiceImpl implements TaskCompletionService {


    private final AidMediaTaskMapper aidMediaTaskMapper;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AidMediaResultMapper aidMediaResultMapper;

    @jakarta.annotation.Resource
    private com.aid.compose.service.RuntimeComposeResultArchive runtimeComposeResultArchive;
    private final BillingFacadeService billingFacadeService;
    private final MediaConcurrencyLimiter concurrencyLimiter;
    private final ApplicationEventPublisher eventPublisher;
    /** 合成终态收口（COMPOSE 分支，绕开模型计费） */
    private final ComposeCompletionService composeCompletionService;
    /** 终态请求/响应异步归档与数据库载荷压缩 */
    private final MediaTaskArchiveService mediaTaskArchiveService;
    /** 模型健康采集：终态收口点按成功/失败累加时间桶计数（仅上游结果，内部吞异常） */
    private final ModelHealthRecorder modelHealthRecorder;
    /** 可灵原始失败样本记录；内部完全隔离异常，不影响终态事务。 */
    private final KlingTerminalFailureRecorder klingTerminalFailureRecorder;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ModelTaskConfigurationResolver taskConfigurationResolver;

    /** ETA 成功样本旁路采集；字段注入保持既有测试构造器源兼容。 */
    @org.springframework.beans.factory.annotation.Autowired
    private MediaEtaRecorder mediaEtaRecorder;

    /** Runtime/Skill ownership proof; deliberately called before any task row lock. */
    @org.springframework.beans.factory.annotation.Autowired
    private MediaTaskCancellationAdmissionGate mediaTaskCancellationAdmissionGate;

    @org.springframework.beans.factory.annotation.Autowired
    private com.aid.aid.mapper.MediaExecutionPermitMapper executionPermitMapper;

    @org.springframework.beans.factory.annotation.Autowired
    private com.aid.billing.service.ExistingTextSettlementRecovery existingTextSettlementRecovery;

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<com.aid.media.reconciliation.MediaSubmissionReconciliationService> submissionReconciliations;

    private record ManualFailureContext(Long operatorId, String reason) { }

    private record LocalFailureContext(com.aid.media.model.MediaTaskFailureReason reason,
                                       String providerTaskId) { }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean completeManualFailure(Long taskId, Date expectedUpdateTime, Long operatorId, String reason) {
        if (operatorId == null || operatorId <= 0 || StrUtil.isBlank(reason)
                || reason.trim().length() < 2 || reason.length() > 500)
            throw new ServiceException("人工确认失败需要有效操作人和处理原因");
        AidMediaTask task = aidMediaTaskMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AidMediaTask>()
                        .eq(AidMediaTask::getId, taskId).last("FOR UPDATE"));
        if (task == null || !Objects.equals(expectedUpdateTime, task.getUpdateTime())
                || task.getStatus() == null || !List.of("PENDING", "WAIT_POLL", "WAIT_CALLBACK", "PROCESSING").contains(task.getStatus())
                || task.getMediaType() == null || !List.of("IMAGE", "VIDEO", "AUDIO", "TEXT").contains(task.getMediaType())) return false;
        return completeTaskInternal(taskId, ProviderTaskResult.builder().status(MediaTaskStatus.FAILED.name())
                .providerStatus("MANUAL_FAILED").errorMessage(reason)
                .taskError(TaskErrorResult.of(com.aid.common.error.TaskErrorCode.TASK_INTERRUPTED, reason))
                .terminalConfirmed(true).querySuccessful(false).build(), true, false, null,
                new ManualFailureContext(operatorId, reason.trim()));
    }

    /** 媒体类型：合成任务，走独立计费/回写分支 */
    private static final String COMPOSE_MEDIA_TYPE = ComposeConstants.MEDIA_TYPE_COMPOSE;
    private static final long CANVAS_PROVIDER_CAPACITY_DELAY_MS = 30_000L;

    /** 保留模块内既有直接构造调用的源兼容；文件存储已不再参与通用终态构造。 */
    TaskCompletionServiceImpl(AidMediaTaskMapper aidMediaTaskMapper,
                              BillingFacadeService billingFacadeService,
                              MediaConcurrencyLimiter concurrencyLimiter,
                              ApplicationEventPublisher eventPublisher,
                              ComposeCompletionService composeCompletionService,
                              com.aid.common.aid.oss.config.OssConfigManager ignoredOssConfigManager,
                              MediaTaskArchiveService mediaTaskArchiveService,
                              ModelHealthRecorder modelHealthRecorder,
                              KlingTerminalFailureRecorder klingTerminalFailureRecorder) {
        this(aidMediaTaskMapper, billingFacadeService, concurrencyLimiter, eventPublisher,
                composeCompletionService, mediaTaskArchiveService, modelHealthRecorder,
                klingTerminalFailureRecorder);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean completeTask(Long taskId, ProviderTaskResult taskResult) {
        return completeTaskInternal(taskId, taskResult, false, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean completeSubmissionFailure(Long taskId, com.aid.media.provider.ProviderSubmitResult receipt) {
        return completeTaskInternal(taskId, submissionFailureResult(receipt), false, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean completeExecutionFailure(Long taskId, String executionToken, String providerTaskId,
            com.aid.media.model.MediaTaskFailureReason reason, String message) {
        Objects.requireNonNull(reason, "Failure reason required");
        boolean accepted = StrUtil.isNotBlank(providerTaskId);
        if (accepted != (reason == com.aid.media.model.MediaTaskFailureReason.UPSTREAM_QUERY_UNAVAILABLE))
            throw new IllegalArgumentException("Failure reason does not match submission phase");
        String safeMessage = com.aid.media.provider.ProviderErrorSanitizer.safeMessage(message,
                accepted ? "上游连续查询失败，本次任务已结束并退还积分" : "生成请求失败，本次任务已结束并退还积分");
        return completeTaskInternal(taskId, ProviderTaskResult.builder()
                .status(MediaTaskStatus.FAILED.name()).providerStatus(reason.name())
                .errorMessage(safeMessage)
                .taskError(TaskErrorResult.of(com.aid.common.error.TaskErrorCode.TASK_INTERRUPTED, safeMessage))
                .querySuccessful(false).terminalConfirmed(false).build(), !accepted,
                executionToken, null, new LocalFailureContext(reason, providerTaskId));
    }

    private static ProviderTaskResult submissionFailureResult(com.aid.media.provider.ProviderSubmitResult receipt) {
        var snapshot = com.aid.media.reconciliation.ProviderSubmissionReceiptSnapshot.capture(receipt);
        if (snapshot == null || !snapshot.hasTerminalFailure())
            throw new ServiceException("MEDIA_SUBMISSION_FAILURE_EVIDENCE_REQUIRED");
        var safe = snapshot.restore();
        return ProviderTaskResult.builder()
                .status(MediaTaskStatus.FAILED.name())
                .errorMessage(com.aid.media.provider.ProviderErrorSanitizer.safeMessage(
                        safe.getSubmissionErrorMessage(), "媒体处理失败：" + safe.getConfirmedFailureCode()))
                .taskError(TaskErrorSnapshot.read(safe.getErrorDetailJson()))
                .errorDetailJson(safe.getErrorDetailJson())
                .usage(safe.getUsage())
                .providerStatus(safe.getConfirmedFailureCode())
                .querySuccessful(Boolean.TRUE).terminalConfirmed(Boolean.TRUE).build();
    }

    private boolean completeTaskInternal(Long taskId, ProviderTaskResult taskResult,
                                        boolean trustedAdminFailure, boolean submissionFailure) {
        return completeTaskInternal(taskId, taskResult, trustedAdminFailure, submissionFailure, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean completeSynchronousExecutionFailure(Long taskId, String executionToken) {
        AidMediaTask task = aidMediaTaskMapper.selectById(taskId);
        if (executionToken == null || executionToken.isBlank() || task == null
                || !Objects.equals(executionToken, task.getExecutionPermitToken())) return false;
        var permit = executionPermitMapper.selectByToken(executionToken);
        var evidence = com.aid.media.reconciliation.SynchronousExecutionFailureEvidence.capture(task, permit);
        if (evidence == null) return false;
        return completeExecutionFailure(taskId, executionToken, null,
                "SUBMITTING".equals(permit.getExecutionMode())
                        ? com.aid.media.model.MediaTaskFailureReason.SUBMISSION_FAILED
                        : com.aid.media.model.MediaTaskFailureReason.SYNCHRONOUS_INTERRUPTED,
                com.aid.media.reconciliation.SynchronousExecutionFailureEvidence.MESSAGE);
    }

    private boolean completeTaskInternal(Long taskId, ProviderTaskResult taskResult,
                                        boolean trustedAdminFailure, boolean submissionFailure,
                                        String expectedExecutionToken) {
        return completeTaskInternal(taskId, taskResult, trustedAdminFailure, submissionFailure, expectedExecutionToken, null);
    }

    private boolean completeTaskInternal(Long taskId, ProviderTaskResult taskResult,
                                        boolean trustedAdminFailure, boolean submissionFailure,
                                        String expectedExecutionToken, ManualFailureContext manual) {
        return completeTaskInternal(taskId, taskResult, trustedAdminFailure,
                submissionFailure, expectedExecutionToken, manual, null);
    }

    private boolean completeTaskInternal(Long taskId, ProviderTaskResult taskResult, boolean submissionFailure,
                                        String token, ManualFailureContext manual, LocalFailureContext localFailure) {
        return completeTaskInternal(taskId, taskResult, false, submissionFailure, token, manual, localFailure);
    }

    private boolean completeTaskInternal(Long taskId, ProviderTaskResult taskResult, boolean trustedAdminFailure,
                                        boolean submissionFailure, String expectedExecutionToken,
                                        ManualFailureContext manual, LocalFailureContext localFailure) {
        AidMediaTask task = aidMediaTaskMapper.selectById(taskId);
        if (Objects.isNull(task)) {
            log.warn("completeTask 任务不存在, taskId={}", taskId);
            return false;
        }

        String currentStatus = task.getStatus();
        if (expectedExecutionToken != null
                && !Objects.equals(expectedExecutionToken, task.getExecutionPermitToken())) return false;
        if (localFailure != null && !Objects.equals(
                StrUtil.blankToDefault(localFailure.providerTaskId(), ""),
                StrUtil.blankToDefault(task.getProviderTaskId(), ""))) return false;
        if (submissionFailure && (!MediaTaskStatus.PENDING.name().equals(currentStatus)
                || StrUtil.isNotBlank(task.getProviderTaskId()))) return false;
        boolean pendingAcceptedTerminal = !submissionFailure && MediaTaskStatus.PENDING.name().equals(currentStatus)
                && StrUtil.isNotBlank(task.getProviderTaskId())
                && (localFailure != null || Boolean.TRUE.equals(taskResult.getQuerySuccessful())
                && Boolean.TRUE.equals(taskResult.getTerminalConfirmed()));
        boolean directLocalCompose = COMPOSE_MEDIA_TYPE.equals(task.getMediaType())
            && com.aid.compose.config.MpsConfigManager.MODE_LOCAL_FFMPEG.equals(task.getProtocol())
            && MediaTaskStatus.PENDING.name().equals(currentStatus)
            && Boolean.TRUE.equals(taskResult.getQuerySuccessful())
            && Boolean.TRUE.equals(taskResult.getTerminalConfirmed());
        boolean canTransition = MediaTaskStatus.WAIT_POLL.name().equals(currentStatus)
            || MediaTaskStatus.WAIT_CALLBACK.name().equals(currentStatus)
            || MediaTaskStatus.PROCESSING.name().equals(currentStatus)
            || directLocalCompose || submissionFailure || pendingAcceptedTerminal || manual != null;
        if (!canTransition) {
            log.info("completeTask 任务已终态, taskId={}, status={}", taskId, currentStatus);
            return false;
        }

        // 已有上游任务ID时，查询异常或未经厂商文档终态确认的结果不得进入结算/退款。
        if (!trustedAdminFailure && localFailure == null && StrUtil.isNotBlank(task.getProviderTaskId())
                && (!Boolean.TRUE.equals(taskResult.getQuerySuccessful())
                || !Boolean.TRUE.equals(taskResult.getTerminalConfirmed()))) {
            log.warn("completeTask 拒绝未经上游终态确认的收口, taskId={}, providerTaskId={}, providerStatus={}",
                    taskId, task.getProviderTaskId(), taskResult.getProviderStatus());
            return false;
        }

        String targetStatus;
        if (MediaTaskStatus.SUCCEEDED.name().equals(taskResult.getStatus())) {
            targetStatus = MediaTaskStatus.SUCCEEDED.name();
        } else if (MediaTaskStatus.FAILED.name().equals(taskResult.getStatus())) {
            targetStatus = MediaTaskStatus.FAILED.name();
        } else {
            return false;
        }

        TencentStoredAudioResults tencentStoredAudio = null;
        if (MediaTaskStatus.SUCCEEDED.name().equals(targetStatus)
                && TENCENT_CI_PROTOCOL.equalsIgnoreCase(task.getProtocol())) {
            try {
                tencentStoredAudio = resolveTencentStoredAudioResults(task, taskResult);
            } catch (RuntimeException ex) {
                log.error("腾讯云数据万象成功结果未完成权威转存，保留任务继续对账, taskId={}, error={}",
                        taskId, ex.getMessage());
                return false;
            }
        }
        TencentStoredVideoResult tencentStoredVideo = null;
        if (MediaTaskStatus.SUCCEEDED.name().equals(targetStatus)
                && tencentStoredAudio == null
                && ((MediaType.VIDEO.name().equals(task.getMediaType())
                    && (TENCENT_CI_PROTOCOL.equalsIgnoreCase(task.getProtocol())
                        || "tencent-mps:subtitle-erase".equalsIgnoreCase(task.getProtocol())))
                    || COMPOSE_MEDIA_TYPE.equals(task.getMediaType())
                        && "tencent-mps".equalsIgnoreCase(task.getProtocol()))) {
            try {
                tencentStoredVideo = resolveTencentStoredVideoResult(taskResult);
            } catch (RuntimeException ex) {
                log.warn("腾讯云视频结果尚未持久化，继续等待任务对账, taskId={}, errorType={}",
                        taskId, ex.getClass().getSimpleName());
                return false;
            }
        }

        String userStr = manual != null ? String.valueOf(manual.operatorId())
                : task.getUserId() != null ? String.valueOf(task.getUserId()) : "";
        LambdaUpdateWrapper<AidMediaTask> casWrapper = new LambdaUpdateWrapper<>();
        casWrapper.eq(AidMediaTask::getId, taskId);
        if (manual != null) {
            casWrapper.eq(AidMediaTask::getStatus, currentStatus);
        } else if (directLocalCompose || submissionFailure || pendingAcceptedTerminal) {
            casWrapper.eq(AidMediaTask::getStatus, MediaTaskStatus.PENDING.name());
        } else {
            casWrapper.in(AidMediaTask::getStatus,
                MediaTaskStatus.WAIT_POLL.name(),
                MediaTaskStatus.WAIT_CALLBACK.name(),
                MediaTaskStatus.PROCESSING.name());
        }
        // Recheck acceptance atomically: an ID attached after the initial read must win over rejection replay.
        if (submissionFailure) {
            casWrapper.and(noAcceptance -> noAcceptance.isNull(AidMediaTask::getProviderTaskId)
                    .or().eq(AidMediaTask::getProviderTaskId, ""));
            if (expectedExecutionToken != null)
                casWrapper.eq(AidMediaTask::getExecutionPermitToken, expectedExecutionToken);
        }
        if (pendingAcceptedTerminal)
            casWrapper.eq(AidMediaTask::getProviderTaskId, task.getProviderTaskId());
        if (localFailure != null) {
            if (StrUtil.isNotBlank(localFailure.providerTaskId()))
                casWrapper.eq(AidMediaTask::getProviderTaskId, localFailure.providerTaskId());
            if (expectedExecutionToken != null)
                casWrapper.eq(AidMediaTask::getExecutionPermitToken, expectedExecutionToken);
        }
        Date terminalTime = new Date();
        casWrapper.set(AidMediaTask::getStatus, targetStatus);
        casWrapper.set(AidMediaTask::getTerminalTime, terminalTime);
        casWrapper.set(AidMediaTask::getUpdateBy, userStr);
        casWrapper.set(AidMediaTask::getUpdateTime, new Date());
        MediaTaskArchiveService.PreparedTerminalPayload preparedPayload =
            mediaTaskArchiveService.prepareTerminalPayload(task, targetStatus, taskResult.getRawResponse());
        if (!Objects.equals(preparedPayload.getRequestJson(), task.getRequestJson())) {
            casWrapper.set(AidMediaTask::getRequestJson, preparedPayload.getRequestJson());
        }

        if (MediaTaskStatus.SUCCEEDED.name().equals(targetStatus)) {
            casWrapper.set(AidMediaTask::getOriginUrl, taskResult.getResultUrl());
            if (MediaType.VIDEO.name().equals(task.getMediaType())
                    && taskResult.getPersistedResultUrls() != null && !taskResult.getPersistedResultUrls().isEmpty()) {
                casWrapper.set(AidMediaTask::getOssUrl, taskResult.getPersistedResultUrls().get(0));
                if (taskResult.getOutputDurationSeconds() != null)
                    casWrapper.set(AidMediaTask::getOutputDurationSeconds, taskResult.getOutputDurationSeconds());
            }
            if(MediaType.AUDIO.name().equals(task.getMediaType()) && taskResult.getAudioOutputs()!=null && !taskResult.getAudioOutputs().isEmpty()) {
                var first=taskResult.getAudioOutputs().get(0);
                if(Boolean.TRUE.equals(first.getPersisted()))casWrapper.set(AidMediaTask::getOssUrl,first.getUrl());
                if(first.getDurationMs()!=null)casWrapper.set(AidMediaTask::getOutputDurationSeconds,(first.getDurationMs()+999)/1000);
            }
            if(taskResult.getResultText()!=null)casWrapper.set(AidMediaTask::getResultText,taskResult.getResultText());
            if (tencentStoredAudio != null) {
                casWrapper.set(AidMediaTask::getOssUrl, tencentStoredAudio.storedUrls().get(0));
            }
            if (tencentStoredVideo != null) {
                casWrapper.set(AidMediaTask::getOssUrl, tencentStoredVideo.storedUrl());
            }
            casWrapper.set(AidMediaTask::getErrorMessage, null);
            casWrapper.set(AidMediaTask::getErrorDetailJson, null);
            // COMPOSE 成片已由所选云引擎直接写入当前对象存储，或由本地 FFmpeg 上传到当前存储；
            // 随 origin_url 一并保存对象相对路径，避免后续再次下载转存。读取层统一按资源访问地址拼接。
            if (COMPOSE_MEDIA_TYPE.equals(task.getMediaType()) && tencentStoredVideo == null) {
                String composeOssUrl = resolveComposeOssRelativePath(taskResult.getResultUrl());
                if (StrUtil.isNotBlank(composeOssUrl)) {
                    casWrapper.set(AidMediaTask::getOssUrl, composeOssUrl);
                }
            }
        } else {
            TaskErrorResult error = normalizeProviderFailure(taskId, task.getModelName(), taskResult);
            String failureEvidence = MediaType.TEXT.name().equals(task.getMediaType())
                    ? TextFailureBillingPolicy.mergeFailureEvidence(task.getErrorDetailJson(), taskResult.getErrorDetailJson())
                    : task.getErrorDetailJson();
            casWrapper.set(AidMediaTask::getErrorDetailJson,
                    TextFailureBillingPolicy.mergeErrorSnapshot(failureEvidence, error));
            casWrapper.set(AidMediaTask::getErrorMessage,
                MediaTaskPayloadSanitizer.sanitizeForStorage(error.getUserMessage()));
        }
        casWrapper.set(AidMediaTask::getResponseJson, preparedPayload.getResponseJson());

        int rows = aidMediaTaskMapper.update(null, casWrapper);
        if (rows == 0) {
            log.info("completeTask CAS 失败, taskId={} 已被其他路径处理", taskId);
            return false;
        }
        if (MediaTaskStatus.SUCCEEDED.name().equals(targetStatus)) {
            if (tencentStoredAudio == null) {
                persistResultManifest(task, normalizeResultUrls(taskResult), userStr);
                if (MediaType.VIDEO.name().equals(task.getMediaType()) && taskResult.getPersistedResultUrls() != null) {
                    for (int index = 0; index < taskResult.getPersistedResultUrls().size(); index++) {
                        LambdaUpdateWrapper<AidMediaResult> stored = new LambdaUpdateWrapper<>();
                        stored.eq(AidMediaResult::getTaskId, task.getId()).eq(AidMediaResult::getResultIndex, index)
                                .set(AidMediaResult::getOssUrl, taskResult.getPersistedResultUrls().get(index))
                                .set(AidMediaResult::getMimeType, taskResult.getPersistedResultMimeTypes().get(index))
                                .set(AidMediaResult::getFileSize, taskResult.getPersistedResultFileSizes().get(index));
                        if (aidMediaResultMapper.update(null, stored) != 1) throw new ServiceException("视频结果登记失败");
                    }
                }
                if (MediaType.AUDIO.name().equals(task.getMediaType())) {
                    com.aid.media.provider.audio.AudioResultManifestSupport.persist(task, taskResult.getAudioOutputs(), aidMediaResultMapper);
                }
                if (tencentStoredVideo != null) {
                    persistTencentStoredVideoManifest(task, tencentStoredVideo, userStr);
                }
            } else {
                persistTencentStoredAudioManifest(task, tencentStoredAudio, userStr);
            }
        }
        task.setTerminalTime(terminalTime);
        if (MediaTaskStatus.SUCCEEDED.name().equals(targetStatus) && mediaEtaRecorder != null) {
            mediaEtaRecorder.recordSuccess(task);
        }
        if (MediaTaskStatus.FAILED.name().equals(targetStatus)
            && StrUtil.isNotBlank(taskResult.getRawErrorMessage())) {
            klingTerminalFailureRecorder.record(taskId, task.getModelName(), taskResult.getRawErrorMessage());
        }
        mediaTaskArchiveService.archiveAfterCommit(preparedPayload);

        AidMediaTask originalBeforeTerminal = task;
        task = aidMediaTaskMapper.selectById(taskId);

        if (localFailure != null)
            submissionReconciliations.getObject().recordLocalTerminalFailure(task, localFailure.reason());

        runtimeComposeResultArchive.ensureManifest(task);

        // 模型健康采集：本方法是回调/轮询双路径的 CAS 收口点，同一任务只进一次，天然去重；
        // 此处的 SUCCEEDED/FAILED 均来自上游返回结果（含超时无响应关单），符合"只记上游错误"口径。
        if (!trustedAdminFailure && localFailure == null
                && !com.aid.media.reconciliation.SynchronousExecutionFailureEvidence.STATUS.equals(taskResult.getProviderStatus())) {
            recordModelHealth(task, targetStatus, taskResult);
        }

        // 回调与轮询双路径经本方法 CAS 收口，同一 taskId 仅进入一次，故结算/退款天然幂等。
        if (COMPOSE_MEDIA_TYPE.equals(task.getMediaType())) {
            if (MediaTaskStatus.SUCCEEDED.name().equals(targetStatus)) {
                composeCompletionService.onSucceeded(task, taskResult);
                // COMPOSE 直填 oss_url 后 oss_pending 恒为 0，OSS 补偿任务不会再扫到它；
                // 且 MPS 回调路径收口后没有 ensureOssPersisted 兜底（只有轮询路径有），
                // 若不在此处发布 OSS 持久化事件，ComposeResultListener 永远不触发，
                // aid_episode_editor.final_video_url / export_status 将卡在合成中。
                // 故 oss_url 就绪时统一在收口点发布事件（监听器幂等，轮询路径重复发布无副作用）。
                if (StrUtil.isNotBlank(task.getOssUrl())) {
                    registerAfterCommitOssPersisted(task);
                }
            } else {
                composeCompletionService.onFailed(task, taskResult);
            }
            registerAfterCommitRelease(task);
            // 成功时返回 true，使调度赢家继续执行 OSS 回写（成片相对路径回填业务表由 OSS 事件完成）。
            return true;
        }

        Map<String, Object> settleUsage = buildSettleUsage(task, taskResult);
        boolean billingWon;
        if (com.aid.media.reconciliation.SynchronousExecutionFailureEvidence.STATUS.equals(taskResult.getProviderStatus())
                && existingTextSettlementRecovery != null
                && existingTextSettlementRecovery.recover(originalBeforeTerminal, task)) {
            billingWon = true;
        } else if (MediaTaskStatus.SUCCEEDED.name().equals(targetStatus)) {
            billingWon = billingFacadeService.settleBilling(
                task, settleUsage);
            log.info("completeTask 任务成功, taskId={}, billingWon={}", taskId, billingWon);
        } else if (!trustedAdminFailure && com.aid.billing.util.CompositeVideoUsageCalculator.usesCompositePricing(task.getBillingSnapshotJson())
                && com.aid.billing.util.CompositeVideoUsageCalculator.hasObservedUsage(settleUsage)) {
            billingWon = billingFacadeService.settleBilling(task, settleUsage);
        } else if (!trustedAdminFailure && MediaType.TEXT.name().equals(task.getMediaType())
                && task.getBillingStatus() != null
                && TextFailureBillingPolicy.shouldSettle(false,
                    task.getUpstreamAcceptTime() != null, task.getProtocol(),
                    task.getErrorDetailJson(), settleUsage)) {
            // 失败调用只按实际用量结算；缺失用量时沿原冻结流水退款。
            billingWon = billingFacadeService.settleBilling(
                task, settleUsage);
            log.info("completeTask 文本Provider调用后失败，按实际用量结算, taskId={}, billingWon={}",
                taskId, billingWon);
        } else {
            billingWon = billingFacadeService.refundBilling(task);
            log.info("completeTask 任务失败, taskId={}, error={}, billingWon={}",
                taskId, taskResult.getErrorMessage(), billingWon);
        }

        if (billingWon) {
            LambdaUpdateWrapper<AidMediaTask> billingUpdate = new LambdaUpdateWrapper<>();
            billingUpdate.eq(AidMediaTask::getId, taskId);
            billingUpdate.set(AidMediaTask::getActualCost, task.getActualCost());
            billingUpdate.set(AidMediaTask::getBillingSnapshotJson, task.getBillingSnapshotJson());
            billingUpdate.set(AidMediaTask::getBillingStatus, task.getBillingStatus());
            billingUpdate.set(AidMediaTask::getFrozenAmount, task.getFrozenAmount());
            billingUpdate.set(AidMediaTask::getUpdateBy, userStr);
            billingUpdate.set(AidMediaTask::getUpdateTime, new Date());
            aidMediaTaskMapper.update(null, billingUpdate);
        } else {
            AidMediaTask dbTask = aidMediaTaskMapper.selectById(taskId);
            if (dbTask != null) {
                task.setActualCost(dbTask.getActualCost());
                task.setBillingSnapshotJson(dbTask.getBillingSnapshotJson());
                task.setBillingStatus(dbTask.getBillingStatus());
                task.setFrozenAmount(dbTask.getFrozenAmount());
            }
        }

        if (manual != null)
            submissionReconciliations.getObject().recordOperatorTerminalFailure(task, manual.operatorId(), manual.reason());

        // 必须在 afterCommit 中执行，确保 DB 终态落库成功后才释放名额和拉起新任务。
        registerAfterCommitRelease(task);

        if (billingWon && MediaTaskStatus.SUCCEEDED.name().equals(targetStatus)) {
            return true;
        }
        return billingWon;
    }

    /** 保存供应商返回的全部有序结果；主任务继续以第 0 项兼容旧读取链路。 */
    private void persistResultManifest(AidMediaTask task, List<String> resultUrls, String operator) {
        if (aidMediaResultMapper == null || resultUrls.isEmpty()) {
            return;
        }
        for (int index = 0; index < resultUrls.size(); index++) {
            aidMediaResultMapper.upsertTaskResult(
                    task.getId(), index, COMPOSE_MEDIA_TYPE.equals(task.getMediaType())
                            && com.aid.billing.dto.BillingBudgetContext.isBudgetManagedBizTaskType(task.getBizTaskType())
                            ? MediaType.VIDEO.name() : task.getMediaType(), resultUrls.get(index), operator);
        }
    }

    /** Keep structured provider errors through polling/callback terminal persistence. */
    static TaskErrorResult normalizeProviderFailure(Long taskId, String model, ProviderTaskResult result) {
        TaskErrorResult explicit = result.getTaskError();
        if (!com.aid.common.error.TaskErrorPresentation.isGeneric(explicit)) return explicit;
        String raw = StrUtil.blankToDefault(result.getRawErrorMessage(), result.getErrorMessage());
        TaskErrorResult text = ErrorNormalizer.classify(null, model, -1, raw);
        TaskErrorResult response = ErrorNormalizer.classify(null, model, -1, result.getRawResponse());
        if (!com.aid.common.error.TaskErrorPresentation.isGeneric(response)
                && com.aid.common.error.TaskErrorPresentation.specificity(response)
                >= com.aid.common.error.TaskErrorPresentation.specificity(text)) {
            raw = result.getRawResponse();
        }
        return ErrorNormalizer.normalize(String.valueOf(taskId), null, model, -1, raw);
    }

    private static List<String> normalizeResultUrls(ProviderTaskResult taskResult) {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        if (StrUtil.isNotBlank(taskResult.getResultUrl())) {
            ordered.add(taskResult.getResultUrl().trim());
        }
        if (taskResult.getResultUrls() != null) {
            for (String resultUrl : taskResult.getResultUrls()) {
                if (StrUtil.isNotBlank(resultUrl)) ordered.add(resultUrl.trim());
            }
        }
        return new ArrayList<>(ordered);
    }

    private static final String TENCENT_CI_PROTOCOL = "tencent-ci-async-media";

    /** 服务端模型能力声明 AUDIO 输出时，要求 Provider 已完整转存全部有序结果。 */
    private TencentStoredAudioResults resolveTencentStoredAudioResults(AidMediaTask task,
                                                                        ProviderTaskResult taskResult) {
        if (taskConfigurationResolver == null) {
            throw new ServiceException("模型能力解析器不可用");
        }
        AiModelConfigVo config = taskConfigurationResolver.resolve(task);
        if (config == null || !TENCENT_CI_PROTOCOL.equalsIgnoreCase(config.getProtocol())
                || !"tencent_ci_media".equalsIgnoreCase(config.getProviderCode())) {
            throw new ServiceException("任务模型路由不匹配");
        }
        JSONObject capability;
        try {
            capability = JSON.parseObject(config.getCapabilityJson());
        } catch (RuntimeException ex) {
            throw new ServiceException("模型输出能力配置无效");
        }
        JSONArray outputModalities = capability == null
                ? null : capability.getJSONArray("outputModalities");
        if (outputModalities == null || outputModalities.size() != 1) {
            throw new ServiceException("模型输出能力配置缺失");
        }
        String outputModality = outputModalities.getString(0);
        if ("VIDEO".equalsIgnoreCase(outputModality)) {
            return null;
        }
        if (!"AUDIO".equalsIgnoreCase(outputModality)) {
            throw new ServiceException("模型输出能力配置不受支持");
        }
        Integer expectedCount = capability.getInteger("maxOutputCount");
        List<String> originUrls = normalizeResultUrls(taskResult);
        List<String> storedUrls = taskResult.getPersistedResultUrls();
        List<String> mimeTypes = taskResult.getPersistedResultMimeTypes();
        List<Long> fileSizes = taskResult.getPersistedResultFileSizes();
        int actualCount = originUrls.size();
        if (expectedCount == null || expectedCount < 1 || expectedCount > 2
                || actualCount < 1 || actualCount > expectedCount
                || (taskResult.getResultCount() != null && taskResult.getResultCount() != actualCount)
                || storedUrls == null || storedUrls.size() != actualCount
                || mimeTypes == null || mimeTypes.size() != actualCount
                || fileSizes == null || fileSizes.size() != actualCount
                || new LinkedHashSet<>(storedUrls).size() != actualCount) {
            throw new ServiceException("音频结果数量或转存清单不完整");
        }
        for (int index = 0; index < actualCount; index++) {
            String storedUrl = StrUtil.trim(storedUrls.get(index));
            String mimeType = StrUtil.trim(mimeTypes.get(index));
            Long fileSize = fileSizes.get(index);
            if (StrUtil.isBlank(storedUrl) || !storedUrl.startsWith("/") || storedUrl.startsWith("//")
                    || storedUrl.contains("://") || storedUrl.contains("..") || storedUrl.contains("\\")
                    || !Set.of("audio/aac", "audio/mp4", "audio/mpeg", "audio/flac", "audio/amr").contains(mimeType)
                    || fileSize == null || fileSize <= 0L) {
                throw new ServiceException("音频结果转存信息无效");
            }
        }
        return new TencentStoredAudioResults(originUrls, List.copyOf(storedUrls),
                List.copyOf(mimeTypes), List.copyOf(fileSizes));
    }

    private void persistTencentStoredAudioManifest(AidMediaTask task,
                                                    TencentStoredAudioResults results,
                                                    String operator) {
        if (aidMediaResultMapper == null) {
            throw new ServiceException("媒体结果存储不可用");
        }
        for (int index = 0; index < results.originUrls().size(); index++) {
            aidMediaResultMapper.upsertTaskResult(task.getId(), index, MediaType.AUDIO.name(),
                    results.originUrls().get(index), operator);
            LambdaUpdateWrapper<AidMediaResult> update = new LambdaUpdateWrapper<>();
            update.eq(AidMediaResult::getTaskId, task.getId());
            update.eq(AidMediaResult::getResultIndex, index);
            update.set(AidMediaResult::getMediaType, MediaType.AUDIO.name());
            update.set(AidMediaResult::getOssUrl, results.storedUrls().get(index));
            update.set(AidMediaResult::getMimeType, results.mimeTypes().get(index));
            update.set(AidMediaResult::getFileSize, results.fileSizes().get(index));
            update.set(AidMediaResult::getUpdateBy, operator);
            update.set(AidMediaResult::getUpdateTime, new Date());
            if (aidMediaResultMapper.update(null, update) != 1) {
                throw new ServiceException("音频结果权威登记失败");
            }
        }
    }

    private record TencentStoredAudioResults(List<String> originUrls, List<String> storedUrls,
                                             List<String> mimeTypes, List<Long> fileSizes) { }

    private TencentStoredVideoResult resolveTencentStoredVideoResult(ProviderTaskResult result) {
        List<String> stored = result.getPersistedResultUrls();
        List<String> mime = result.getPersistedResultMimeTypes();
        List<Long> sizes = result.getPersistedResultFileSizes();
        if (stored == null || stored.size() != 1 || mime == null || mime.size() != 1
                || sizes == null || sizes.size() != 1) {
            throw new ServiceException("视频结果尚未完成永久存储");
        }
        String url = StrUtil.trim(stored.get(0));
        if (StrUtil.isBlank(url) || !url.startsWith("/") || url.startsWith("//")
                || url.contains("://") || url.contains("..") || url.contains("\\")
                || !Set.of("video/mp4", "video/webm", "video/quicktime").contains(mime.get(0))
                || sizes.get(0) == null || sizes.get(0) <= 0) {
            throw new ServiceException("视频结果永久存储信息无效");
        }
        return new TencentStoredVideoResult(url, mime.get(0), sizes.get(0));
    }

    private void persistTencentStoredVideoManifest(AidMediaTask task, TencentStoredVideoResult result,
                                                   String operator) {
        if (aidMediaResultMapper == null) throw new ServiceException("媒体结果存储不可用");
        LambdaUpdateWrapper<AidMediaResult> update = new LambdaUpdateWrapper<>();
        update.eq(AidMediaResult::getTaskId, task.getId());
        update.eq(AidMediaResult::getResultIndex, 0);
        update.set(AidMediaResult::getOssUrl, result.storedUrl());
        update.set(AidMediaResult::getMimeType, result.mimeType());
        update.set(AidMediaResult::getFileSize, result.fileSize());
        update.set(AidMediaResult::getUpdateBy, operator);
        update.set(AidMediaResult::getUpdateTime, new Date());
        if (aidMediaResultMapper.update(null, update) != 1) {
            throw new ServiceException("视频结果权威登记失败");
        }
    }

    private record TencentStoredVideoResult(String storedUrl, String mimeType, long fileSize) { }

    /**
     * 模型健康采集：COMPOSE 为合成服务（MPS）非 AI 模型，不计入；
     * 成功耗时 = 任务创建到终态收口（异步生成任务的端到端生成耗时）。
     */
    private void recordModelHealth(AidMediaTask task, String targetStatus, ProviderTaskResult taskResult) {
        if (COMPOSE_MEDIA_TYPE.equals(task.getMediaType())) {
            return;
        }
        if (MediaTaskStatus.SUCCEEDED.name().equals(targetStatus)) {
            Long latencyMs = Objects.nonNull(task.getCreateTime())
                    ? System.currentTimeMillis() - task.getCreateTime().getTime() : null;
            modelHealthRecorder.recordSuccess(task.getModelName(), task.getMediaType(), latencyMs);
        } else {
            modelHealthRecorder.recordFailure(task.getModelName(), task.getMediaType(),
                    taskResult.getErrorMessage());
        }
    }

    /**
     * 统一终态入口按媒体类型传递实际用量，保证按张 / 按秒的结算依据不丢失。
     */
    private Map<String, Object> buildSettleUsage(AidMediaTask task, ProviderTaskResult taskResult) {
        if (MediaType.TEXT.name().equals(task.getMediaType())) {
            Map<String, Object> actual = com.aid.media.provider.ProviderUsageSupport.actualUsage(
                    com.aid.media.provider.ProviderUsageSupport.merge(
                            TextFailureBillingPolicy.observedUsage(task.getErrorDetailJson()), taskResult.getUsage()));
            return actual.isEmpty() ? null : actual;
        }
        if (MediaType.AUDIO.name().equals(task.getMediaType()) && taskResult.getUsage() != null) {
            return new HashMap<>(taskResult.getUsage());
        }
        if (Objects.equals(task.getMediaType(), MediaType.IMAGE.name())) {
            int actualCount = 0;
            if (Objects.nonNull(taskResult.getResultCount()) && taskResult.getResultCount() > 0) {
                actualCount = taskResult.getResultCount();
            } else if (taskResult.getResultUrls() != null && !taskResult.getResultUrls().isEmpty()) {
                actualCount = taskResult.getResultUrls().size();
            } else if (StrUtil.isNotBlank(taskResult.getResultUrl())) {
                actualCount = 1;
            }
            Map<String, Object> usage = new HashMap<>();
            usage.put("actualImageCount", Math.max(actualCount, 1));
            usage.put("resultCount", Math.max(actualCount, 1));
            return usage;
        }
        if (Objects.equals(task.getMediaType(), MediaType.VIDEO.name())) {
            Map<String, Object> usage = new HashMap<>();
            if (taskResult.getUsage() != null) usage.putAll(taskResult.getUsage());
            if (taskResult.getProviderCredits() != null && taskResult.getProviderCredits().signum() > 0) {
                usage.put("actualProviderCredits", taskResult.getProviderCredits());
            }
            if (Objects.nonNull(taskResult.getVideoDurationSeconds())
                    && taskResult.getVideoDurationSeconds() > 0) {
                usage.put("actualDuration", taskResult.getVideoDurationSeconds());
            }
            if (Objects.nonNull(taskResult.getInputVideoSeconds())
                    && taskResult.getInputVideoSeconds() >= 0) {
                usage.put("actualInputVideoSeconds", taskResult.getInputVideoSeconds());
            }
            if (Objects.nonNull(taskResult.getInputImageCount())
                    && taskResult.getInputImageCount() >= 0) {
                usage.put("actualInputImageCount", taskResult.getInputImageCount());
            }
            if (taskResult.getVideoBillingDurationSeconds() != null
                    && taskResult.getVideoBillingDurationSeconds().signum() > 0) {
                usage.put("actualDuration", taskResult.getVideoBillingDurationSeconds());
            }
            if (taskResult.getInputVideoBillingDurationSeconds() != null
                    && taskResult.getInputVideoBillingDurationSeconds().signum() >= 0) {
                usage.put("actualInputVideoSeconds", taskResult.getInputVideoBillingDurationSeconds());
            }
            if (Objects.nonNull(taskResult.getCompletionTokens())
                    && taskResult.getCompletionTokens() > 0) {
                usage.put("completion_tokens", taskResult.getCompletionTokens());
                usage.put("output_tokens", taskResult.getCompletionTokens());
            }
            if (Objects.nonNull(taskResult.getTotalTokens())
                    && taskResult.getTotalTokens() > 0) {
                usage.put("total_tokens", taskResult.getTotalTokens());
            }
            return usage.isEmpty() ? null : usage;
        }
        return null;
    }

    /**
     * 解析 COMPOSE 成片的存储对象相对路径（以 {@code /} 起始，不含协议 / 域名 / query）。
     *
     * @param resultUrl 上游返回的成片地址
     * @return 对象相对路径，形如 {@code /compose_result/compose_xxx.mp4}；不适用时返回 null
     */
    private String resolveComposeOssRelativePath(String resultUrl) {
        if (StrUtil.isBlank(resultUrl)) {
            return null;
        }
        if (resultUrl.startsWith("/")) {
            return resultUrl;
        }
        try {
            // 取 URI path，剥离协议 / 域名 / query。
            String path = java.net.URI.create(resultUrl).getPath();
            if (StrUtil.isBlank(path)) {
                return null;
            }
            return path.startsWith("/") ? path : "/" + path;
        } catch (Exception e) {
            log.error("resolveComposeOssRelativePath 解析成片对象路径失败, url={}, err={}", resultUrl, e.getMessage());
            return null;
        }
    }

    /**
     * 注册事务提交后回调：发布 OSS 持久化完成事件，驱动 ComposeResultListener 回填业务表
     * （aid_episode_editor.final_video_url / export_status 或 aid_gen_record）。
     * 必须在 afterCommit 中发布：监听器会重新 selectById 读任务，事务未提交时读不到终态。
     *
     * @param task 已终态且 oss_url 就绪的 COMPOSE 任务
     */
    private void registerAfterCommitOssPersisted(AidMediaTask task) {
        Long tid = task.getId();
        Long userId = task.getUserId();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    eventPublisher.publishEvent(new MediaTaskOssPersistedEvent(this, tid, userId));
                }
            });
        } else {
            // 无事务上下文时直接发布（降级）。
            eventPublisher.publishEvent(new MediaTaskOssPersistedEvent(this, tid, userId));
        }
    }

    /**
     * 注册事务提交后回调：释放并发坑位并发布完成事件触发 drainQueue。
     * 无事务上下文时直接执行（降级）。COMPOSE 与既有分支共用，保证收口语义一致。
     *
     * @param task 已终态任务
     */
    private void registerAfterCommitRelease(AidMediaTask task) {
        Long userId = task.getUserId();
        Long tid = task.getId();
        // 捕获本次不可变许可编号，迟到回调不能释放同任务后续重新取得的许可。
        String executionPermit = task.getExecutionPermitToken();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    concurrencyLimiter.releaseToken(executionPermit);
                    eventPublisher.publishEvent(new MediaTaskCompletedEvent(this, tid, userId));
                }
            });
        } else {
            // 无事务上下文时直接执行（降级）。
            concurrencyLimiter.releaseToken(executionPermit);
            eventPublisher.publishEvent(new MediaTaskCompletedEvent(this, tid, userId));
        }
    }

    /**
     * 未提交僵尸任务收口后的事务提交回调：仅 PENDING（已占槽）才释放并发坑位，统一触发 drainQueue。
     *
     * @param task       已被关闭的任务
     * @param wasPending 关闭前是否为 PENDING（占槽）
     */
    private void registerAfterCommitReleaseForUnsubmitted(AidMediaTask task, boolean wasPending) {
        Long userId = task.getUserId();
        Long tid = task.getId();
        // 捕获本次不可变许可编号，迟到回调不能释放同任务后续重新取得的许可。
        String executionPermit = task.getExecutionPermitToken();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    if (wasPending) {
                        concurrencyLimiter.releaseToken(executionPermit);
                    }
                    eventPublisher.publishEvent(new MediaTaskCompletedEvent(this, tid, userId));
                }
            });
        } else {
            if (wasPending) {
                concurrencyLimiter.releaseToken(executionPermit);
            }
            eventPublisher.publishEvent(new MediaTaskCompletedEvent(this, tid, userId));
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean closeUnsubmittedTask(Long taskId, String errorMessage) {
        AidMediaTask task = aidMediaTaskMapper.selectById(taskId);
        if (task == null) return false;
        var reconciliation = submissionReconciliations.getObject();
        if (reconciliation.hasRecoverableReceipt(task))
            return reconciliation.reconcileSubmissionNow(taskId);
        return completeSynchronousExecutionFailure(taskId, task.getExecutionPermitToken());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean cancelQueuedTask(Long taskId, Long userId, String errorMessage) {
        AidMediaTask task = aidMediaTaskMapper.selectById(taskId);
        if (Objects.isNull(task)) {
            log.info("取消排队媒体任务跳过，任务不存在: taskId={}", taskId);
            return false;
        }
        if (BillingBudgetContext.isBudgetManagedBizTaskType(task.getBizTaskType())) {
            log.info("budget-managed queued task requires runtime cancellation evidence, taskId={}, bizTaskType={}",
                    taskId, task.getBizTaskType());
            return false;
        }
        if (!Objects.equals(userId, task.getUserId())) {
            log.warn("取消排队媒体任务拒绝，用户不匹配: taskId={}, userId={}", taskId, userId);
            return false;
        }
        if (!MediaTaskStatus.QUEUED.name().equals(task.getStatus())) {
            log.info("取消排队媒体任务跳过，任务已推进: taskId={}, status={}", taskId, task.getStatus());
            return false;
        }

        String userStr = String.valueOf(userId);
        String safeMessage = MediaTaskPayloadSanitizer.sanitizeForStorage(errorMessage);
        LambdaUpdateWrapper<AidMediaTask> casWrapper = new LambdaUpdateWrapper<>();
        casWrapper.eq(AidMediaTask::getId, taskId);
        casWrapper.eq(AidMediaTask::getUserId, userId);
        casWrapper.eq(AidMediaTask::getStatus, MediaTaskStatus.QUEUED.name());
        casWrapper.isNull(AidMediaTask::getExecutionPermitToken);
        casWrapper.isNull(AidMediaTask::getUpstreamAcceptTime);
        casWrapper.and(neverAccepted -> neverAccepted.isNull(AidMediaTask::getProviderTaskId)
                .or().eq(AidMediaTask::getProviderTaskId, ""));
        casWrapper.set(AidMediaTask::getStatus, MediaTaskStatus.FAILED.name());
        casWrapper.set(AidMediaTask::getTerminalTime, new Date());
        casWrapper.set(AidMediaTask::getErrorMessage, safeMessage);
        casWrapper.set(AidMediaTask::getErrorDetailJson, TaskErrorSnapshot.fromMessage(safeMessage));
        MediaTaskArchiveService.PreparedTerminalPayload preparedPayload =
            mediaTaskArchiveService.prepareTerminalPayload(
                task, MediaTaskStatus.FAILED.name(), task.getResponseJson());
        if (!Objects.equals(preparedPayload.getRequestJson(), task.getRequestJson())) {
            casWrapper.set(AidMediaTask::getRequestJson, preparedPayload.getRequestJson());
        }
        casWrapper.set(AidMediaTask::getResponseJson, preparedPayload.getResponseJson());
        casWrapper.set(AidMediaTask::getUpdateBy, userStr);
        casWrapper.set(AidMediaTask::getUpdateTime, new Date());
        if (aidMediaTaskMapper.update(null, casWrapper) == 0) {
            log.info("取消排队媒体任务CAS未命中: taskId={}", taskId);
            return false;
        }

        mediaTaskArchiveService.archiveAfterCommit(preparedPayload);
        task = aidMediaTaskMapper.selectById(taskId);
        boolean billingWon = billingFacadeService.refundBilling(task);
        if (billingWon) {
            LambdaUpdateWrapper<AidMediaTask> billingUpdate = new LambdaUpdateWrapper<>();
            billingUpdate.eq(AidMediaTask::getId, taskId);
            billingUpdate.set(AidMediaTask::getBillingStatus, task.getBillingStatus());
            billingUpdate.set(AidMediaTask::getFrozenAmount, task.getFrozenAmount());
            billingUpdate.set(AidMediaTask::getUpdateBy, userStr);
            billingUpdate.set(AidMediaTask::getUpdateTime, new Date());
            aidMediaTaskMapper.update(null, billingUpdate);
        }
        registerAfterCommitReleaseForUnsubmitted(task, false);
        log.info("未提交媒体任务取消收口: taskId={}, billingWon={}", taskId, billingWon);
        return billingWon;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String cancelQueuedUserTask(Long taskId, Long userId) {
        if (taskId == null || taskId <= 0 || userId == null || userId <= 0) {
            throw new ServiceException("任务参数错误");
        }
        AidMediaTask task = aidMediaTaskMapper.selectById(taskId);
        if (task == null || !Objects.equals(task.getUserId(), userId)) {
            throw new ServiceException("任务不存在或无权操作");
        }
        if (!MediaType.IMAGE.name().equals(task.getMediaType())
                && !MediaType.VIDEO.name().equals(task.getMediaType())) {
            throw new ServiceException("该媒体任务暂不支持取消");
        }
        if (!"canvas_node".equals(task.getBizTaskType())
                && !"GENERIC_GENERATION".equals(task.getBizTaskType())) {
            throw new ServiceException("该媒体任务由原业务管理，请使用原任务取消入口");
        }
        return cancelUnsubmittedUserTask(task, userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String cancelQueuedRuntimeTask(Long taskId, Long userId, Long runId) {
        if (taskId == null || taskId <= 0 || userId == null || userId <= 0
                || runId == null || runId <= 0) throw new ServiceException("任务参数错误");
        AidMediaTask task = aidMediaTaskMapper.selectById(taskId);
        if (task == null || !Objects.equals(task.getUserId(), userId)
                || !"RUNTIME_RUN".equals(task.getBizTaskType())
                || !Objects.equals(task.getBizTaskId(), runId)) {
            throw new ServiceException("运行与媒体任务不匹配");
        }
        return cancelUnsubmittedUserTask(task, userId);
    }

    private String cancelUnsubmittedUserTask(AidMediaTask task, Long userId) {
        Long taskId = task.getId();
        String state = task.getStatus();
        if (MediaTaskStatus.CANCELLED.name().equals(state)) return "ALREADY_CANCELLED";
        if (!MediaTaskStatus.QUEUED.name().equals(state)) {
            return MediaTaskStatus.SUCCEEDED.name().equals(state) || MediaTaskStatus.FAILED.name().equals(state)
                    ? "FINISHED" : "IN_PROGRESS";
        }
        if (StrUtil.isNotBlank(task.getProviderTaskId()) || task.getUpstreamAcceptTime() != null) {
            return "IN_PROGRESS";
        }

        String reason = "用户取消，尚未提交上游";
        MediaTaskArchiveService.PreparedTerminalPayload payload =
                mediaTaskArchiveService.prepareTerminalPayload(task, MediaTaskStatus.CANCELLED.name(), task.getResponseJson());
        LambdaUpdateWrapper<AidMediaTask> update = new LambdaUpdateWrapper<>();
        update.eq(AidMediaTask::getId, taskId)
                .eq(AidMediaTask::getUserId, userId)
                .eq(AidMediaTask::getBizTaskType, task.getBizTaskType())
                .eq(AidMediaTask::getStatus, MediaTaskStatus.QUEUED.name())
                .and(w -> w.isNull(AidMediaTask::getProviderTaskId)
                        .or().eq(AidMediaTask::getProviderTaskId, ""))
                .isNull(AidMediaTask::getUpstreamAcceptTime);
        update.set(AidMediaTask::getStatus, MediaTaskStatus.CANCELLED.name());
        update.set(AidMediaTask::getTerminalTime, new Date());
        update.set(AidMediaTask::getErrorMessage, reason);
        update.set(AidMediaTask::getErrorDetailJson, null);
        update.set(AidMediaTask::getRequestJson, payload.getRequestJson());
        update.set(AidMediaTask::getResponseJson, payload.getResponseJson());
        update.set(AidMediaTask::getUpdateBy, String.valueOf(userId));
        update.set(AidMediaTask::getUpdateTime, new Date());
        if (aidMediaTaskMapper.update(null, update) == 0) {
            AidMediaTask current = aidMediaTaskMapper.selectById(taskId);
            if (current != null && MediaTaskStatus.CANCELLED.name().equals(current.getStatus())) return "ALREADY_CANCELLED";
            return current != null && (MediaTaskStatus.SUCCEEDED.name().equals(current.getStatus())
                    || MediaTaskStatus.FAILED.name().equals(current.getStatus())) ? "FINISHED" : "IN_PROGRESS";
        }
        mediaTaskArchiveService.archiveAfterCommit(payload);
        task = aidMediaTaskMapper.selectById(taskId);
        boolean billingWon = billingFacadeService.refundBilling(task);
        if (billingWon) {
            aidMediaTaskMapper.update(null, new LambdaUpdateWrapper<AidMediaTask>()
                    .eq(AidMediaTask::getId, taskId)
                    .set(AidMediaTask::getBillingStatus, task.getBillingStatus())
                    .set(AidMediaTask::getFrozenAmount, task.getFrozenAmount())
                    .set(AidMediaTask::getUpdateTime, new Date()));
        }
        registerAfterCommitReleaseForUnsubmitted(task, false);
        return "CANCELLED";
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean requeueUnsubmittedTask(Long taskId) {
        AidMediaTask task = aidMediaTaskMapper.selectById(taskId);
        if (task == null || !MediaTaskStatus.PENDING.name().equals(task.getStatus())
                || StrUtil.isNotBlank(task.getProviderTaskId())) {
            return false;
        }
        LambdaUpdateWrapper<AidMediaTask> update = new LambdaUpdateWrapper<>();
        update.eq(AidMediaTask::getId, taskId);
        update.eq(AidMediaTask::getStatus, MediaTaskStatus.PENDING.name());
        update.and(w -> w.isNull(AidMediaTask::getProviderTaskId)
                .or().eq(AidMediaTask::getProviderTaskId, ""));
        update.set(AidMediaTask::getStatus, MediaTaskStatus.QUEUED.name());
        update.set(AidMediaTask::getErrorMessage, null);
        update.set(AidMediaTask::getErrorDetailJson, null);
        Date now = new Date();
        update.set(AidMediaTask::getNextPollTime, new Date(now.getTime() + 60_000L));
        update.set(AidMediaTask::getRetryCount, Objects.requireNonNullElse(task.getRetryCount(), 0) + 1);
        update.set(AidMediaTask::getUpdateTime, now);
        if (aidMediaTaskMapper.update(null, update) == 0) {
            return false;
        }
        registerAfterCommitReleaseForUnsubmitted(task, true);
        log.info("合成未确认任务已重新排队, taskId={}", taskId);
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deferUnsubmittedTask(Long taskId) {
        AidMediaTask task = aidMediaTaskMapper.selectById(taskId);
        if (task == null || !BillingBudgetContext.isBudgetManagedBizTaskType(task.getBizTaskType())
                || StrUtil.isNotBlank(task.getProviderTaskId()) || task.getUpstreamAcceptTime() != null) return false;
        if (MediaTaskStatus.QUEUED.name().equals(task.getStatus())) return true;
        if (!MediaTaskStatus.PENDING.name().equals(task.getStatus())) return false;
        LambdaUpdateWrapper<AidMediaTask> update = new LambdaUpdateWrapper<>();
        update.eq(AidMediaTask::getId, taskId);
        update.eq(AidMediaTask::getStatus, MediaTaskStatus.PENDING.name());
        update.and(w -> w.isNull(AidMediaTask::getProviderTaskId)
                .or().eq(AidMediaTask::getProviderTaskId, ""));
        update.isNull(AidMediaTask::getUpstreamAcceptTime);
        update.set(AidMediaTask::getStatus, MediaTaskStatus.QUEUED.name());
        update.set(AidMediaTask::getNextPollTime, new Date(System.currentTimeMillis() + 60_000L));
        update.set(AidMediaTask::getUpdateTime, new Date());
        if (aidMediaTaskMapper.update(null, update) == 0) return false;
        registerAfterCommitReleaseForUnsubmitted(task, true);
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deferCanvasVideoForProviderCapacity(Long taskId, String executionPermit) {
        AidMediaTask task = aidMediaTaskMapper.selectById(taskId);
        if (task == null || StrUtil.isBlank(executionPermit)
                || !Objects.equals(task.getExecutionPermitToken(), executionPermit)) return false;
        Date now = new Date();
        int changed = aidMediaTaskMapper.update(null, new LambdaUpdateWrapper<AidMediaTask>()
                .eq(AidMediaTask::getId, taskId)
                .eq(AidMediaTask::getBizTaskType, "canvas_node")
                .eq(AidMediaTask::getMediaType, MediaType.VIDEO.name())
                .eq(AidMediaTask::getStatus, MediaTaskStatus.PENDING.name())
                .eq(AidMediaTask::getExecutionPermitToken, executionPermit)
                .eq(AidMediaTask::getBillingStatus, MediaBillingStatus.FROZEN.name())
                .and(w -> w.isNull(AidMediaTask::getProviderTaskId).or().eq(AidMediaTask::getProviderTaskId, ""))
                .isNull(AidMediaTask::getUpstreamAcceptTime)
                .set(AidMediaTask::getStatus, MediaTaskStatus.QUEUED.name())
                .set(AidMediaTask::getExecutionPermitToken, null)
                .set(AidMediaTask::getNextPollTime, new Date(now.getTime() + CANVAS_PROVIDER_CAPACITY_DELAY_MS))
                .set(AidMediaTask::getUpdateTime, now));
        if (changed == 0) return false;
        Runnable wakeQueue = () -> {
            concurrencyLimiter.releaseToken(executionPermit);
            eventPublisher.publishEvent(new MediaQueueSlotReleasedEvent(taskId));
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    wakeQueue.run();
                }
            });
        } else {
            wakeQueue.run();
        }
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MediaTaskCancellationResult cancelBudgetManagedUnsubmittedTask(Long taskId) {
        // This compatibility entry also owns a transaction; a default-interface self-call cannot.
        return cancelBudgetManagedUnsubmittedTask(MediaTaskCancellationRequest.stop(taskId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MediaTaskCancellationResult cancelBudgetManagedUnsubmittedTask(
            MediaTaskCancellationRequest request) {
        Long taskId = request == null ? null : request.taskId();
        if (taskId == null || taskId <= 0) {
            throw new ServiceException("MEDIA_TASK_CANCELLATION_TASK_INVALID");
        }
        if (request.purpose() == null || (request.isReplan()
                && (request.expectedRuntimeRunId() == null
                || request.expectedRuntimeRunFencingToken() == null
                || StrUtil.isBlank(request.leaseOwner())
                || request.replanIntentId() == null
                || request.replanIntentStateRevision() == null
                || request.replanCommandId() == null))) {
            throw new ServiceException("MEDIA_TASK_CANCELLATION_TICKET_INVALID");
        }
        // Routing read only: the business handler must acquire Runtime/Skill locks before the task CAS.
        AidMediaTask task = aidMediaTaskMapper.selectById(taskId);
        if (task == null || !BillingBudgetContext.isBudgetManagedBizTaskType(task.getBizTaskType())) {
            return MediaTaskCancellationResult.RECONCILE_ONLY;
        }
        if (MediaTaskStatus.CANCELLED.name().equals(task.getStatus())
                || MediaTaskStatus.SUCCEEDED.name().equals(task.getStatus())
                || MediaTaskStatus.FAILED.name().equals(task.getStatus())) {
            return MediaTaskCancellationResult.ALREADY_TERMINAL;
        }
        if (!Set.of(MediaTaskStatus.QUEUED.name(), MediaTaskStatus.PENDING.name()).contains(task.getStatus())
                || StrUtil.isNotBlank(task.getProviderTaskId()) || task.getUpstreamAcceptTime() != null
                || !MediaBillingStatus.FROZEN.name().equals(task.getBillingStatus())) {
            return MediaTaskCancellationResult.RECONCILE_ONLY;
        }
        MediaTaskCancellationAdmissionDecision decision =
                mediaTaskCancellationAdmissionGate.claimCancellation(task, request);
        if (decision != MediaTaskCancellationAdmissionDecision.CANCEL_NOT_SUBMITTED) {
            return MediaTaskCancellationResult.RECONCILE_ONLY;
        }

        boolean wasPending = MediaTaskStatus.PENDING.name().equals(task.getStatus());
        String reason = request.purpose() == MediaTaskCancellationPurpose.REPLAN
                ? "RUNTIME_REPLAN_CANCELLED_BEFORE_PROVIDER_ENTRY"
                : "RUNTIME_STOPPED_BEFORE_PROVIDER_ENTRY";
        MediaTaskArchiveService.PreparedTerminalPayload payload = mediaTaskArchiveService.prepareTerminalPayload(
                task, MediaTaskStatus.CANCELLED.name(), task.getResponseJson());
        LambdaUpdateWrapper<AidMediaTask> update = new LambdaUpdateWrapper<>();
        update.eq(AidMediaTask::getId, task.getId())
                .eq(AidMediaTask::getBizTaskType, task.getBizTaskType())
                .eq(AidMediaTask::getBizTaskId, task.getBizTaskId())
                .eq(AidMediaTask::getBillingSnapshotJson, task.getBillingSnapshotJson())
                .eq(AidMediaTask::getBillingStatus, MediaBillingStatus.FROZEN.name())
                .in(AidMediaTask::getStatus, MediaTaskStatus.QUEUED.name(), MediaTaskStatus.PENDING.name())
                .and(w -> w.isNull(AidMediaTask::getProviderTaskId)
                        .or().eq(AidMediaTask::getProviderTaskId, ""))
                .isNull(AidMediaTask::getUpstreamAcceptTime);
        Date now = new Date();
        update.set(AidMediaTask::getStatus, MediaTaskStatus.CANCELLED.name());
        update.set(AidMediaTask::getTerminalTime, now);
        update.set(AidMediaTask::getErrorMessage, reason);
        update.set(AidMediaTask::getErrorDetailJson, null);
        update.set(AidMediaTask::getRequestJson, payload.getRequestJson());
        update.set(AidMediaTask::getResponseJson, payload.getResponseJson());
        update.set(AidMediaTask::getUpdateBy,
                request.purpose() == MediaTaskCancellationPurpose.REPLAN
                        ? "runtime-replan" : "runtime-stop");
        update.set(AidMediaTask::getUpdateTime, now);
        if (aidMediaTaskMapper.update(null, update) != 1) {
            return MediaTaskCancellationResult.RECONCILE_ONLY;
        }

        AidMediaTask cancelled = aidMediaTaskMapper.selectById(taskId);
        if (cancelled == null || !MediaTaskStatus.CANCELLED.name().equals(cancelled.getStatus())
                || !billingFacadeService.refundBilling(cancelled)) {
            throw new ServiceException("MEDIA_TASK_CANCELLATION_REFUND_INCOMPLETE");
        }
        aidMediaTaskMapper.update(null, new LambdaUpdateWrapper<AidMediaTask>()
                .eq(AidMediaTask::getId, taskId)
                .set(AidMediaTask::getBillingStatus, cancelled.getBillingStatus())
                .set(AidMediaTask::getFrozenAmount, cancelled.getFrozenAmount())
                .set(AidMediaTask::getActualCost, cancelled.getActualCost())
                .set(AidMediaTask::getUpdateTime, new Date()));
        mediaTaskArchiveService.archiveAfterCommit(payload);
        registerAfterCommitReleaseForUnsubmitted(cancelled, wasPending);
        return MediaTaskCancellationResult.CANCELLED;
    }
}
