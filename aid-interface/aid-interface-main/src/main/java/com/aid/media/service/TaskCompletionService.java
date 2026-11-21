package com.aid.media.service;

import com.aid.media.provider.ProviderTaskResult;
import com.aid.media.admission.MediaTaskCancellationResult;
import com.aid.media.admission.MediaTaskCancellationRequest;

/**
 * 统一终态处理服务：回调与轮询都走同一入口，幂等收口。
 * 确保同一个任务无论通过回调还是轮询到达终态，都只会被处理一次。
 */
public interface TaskCompletionService {

    /**
     * 统一终态入口：回调或轮询都调用此方法，幂等处理。
     *
     * @param taskId     本地任务ID
     * @param taskResult provider 归一化查询结果
     * @return true = 本线程赢得终态处理权（执行了结算/退款），false = 已被其他路径处理
     */
    boolean completeTask(Long taskId, ProviderTaskResult taskResult);

    /** Confirmed rejection before an asynchronous task was accepted; uses the same terminal CAS and ledger. */
    boolean completeSubmissionFailure(Long taskId, com.aid.media.provider.ProviderSubmitResult receipt);

    /** Rechecks durable synchronous connection ownership; never used for asynchronous upstream tasks. */
    boolean completeSynchronousExecutionFailure(Long taskId, String executionToken);

    /** Ends a failed call under its original execution/task identity and refunds its original hold. */
    boolean completeExecutionFailure(Long taskId, String executionToken, String providerTaskId,
            com.aid.media.model.MediaTaskFailureReason reason, String message);

    /** Internal operator command after an explicit upstream review; task revision must still match. */
    boolean completeManualFailure(Long taskId, java.util.Date expectedUpdateTime, Long operatorId, String reason);

    /**
     * 关闭「占槽后卡在提交阶段」的僵尸任务（PENDING 且无 providerTaskId，多由进程重启遗留）。
     * QUEUED 是合法排队，不允许通过本方法关闭。
     *
     * @param taskId       本地任务ID
     * @param errorMessage 失败原因（落 error_message）
     * @return true = 本线程赢得关闭权（执行了退款），false = 已被其他路径处理
     */
    boolean closeUnsubmittedTask(Long taskId, String errorMessage);

    /**
     * 用户停止父任务时关闭仍处于 QUEUED、尚未提交供应商的媒体子任务并退回冻结积分。
     * 已进入 PENDING/PROCESSING 的任务不由本方法处理，避免把已经发生的供应商调用误报为退款。
     *
     * @param taskId       本地媒体任务ID
     * @param userId       任务所属用户ID
     * @param errorMessage 取消原因
     * @return true = 本线程完成关闭和计费收口，false = 任务已推进或不属于该用户
     */
    boolean cancelQueuedTask(Long taskId, Long userId, String errorMessage);

    /** 用户取消尚未提交供应商的图片/视频任务，返回 CANCELLED/ALREADY_CANCELLED/IN_PROGRESS/FINISHED。 */
    String cancelQueuedUserTask(Long taskId, Long userId);

    /** Legacy Generic Runtime owner cancellation; never substitutes Assistant typed cancellation. */
    String cancelQueuedRuntimeTask(Long taskId, Long userId, Long runId);

    /**
     * 合成提交结果无法确认或应用重启时，把已占槽但未产生上游任务号的任务延迟放回队列，
     * 不退款、不判失败；云端依靠幂等键恢复同一任务，本地重新执行。
     */
    boolean requeueUnsubmittedTask(Long taskId);

    /** Pause a durable, not-yet-submitted task without consuming its retry budget or releasing billing. */
    boolean deferUnsubmittedTask(Long taskId);

    /** Put a canvas video back into the queue after a verified capacity rejection. */
    boolean deferCanvasVideoForProviderCapacity(Long taskId, String executionPermit);

    /**
     * Cancels a durable budget-managed task only after the owning runtime proves stop + NOT_SUBMITTED.
     * Ambiguous or already-entered tasks remain frozen for reconciliation.
     */
    MediaTaskCancellationResult cancelBudgetManagedUnsubmittedTask(Long taskId);

    /** Trusted purpose-aware cancellation; REPLAN requires an exact run lease and intent ticket. */
    MediaTaskCancellationResult cancelBudgetManagedUnsubmittedTask(MediaTaskCancellationRequest request);
}
