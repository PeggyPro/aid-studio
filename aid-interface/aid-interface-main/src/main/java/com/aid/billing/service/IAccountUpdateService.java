package com.aid.billing.service;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;

/**
 * 统一账户变更执行器：所有对 aid_user_profile + aid_balance_log 的写操作统一走这里。
 *
 * @author 视觉AID
 */
public interface IAccountUpdateService {

    /**
     * Read-only aggregate of the committed, non-deleted wallet ledger rows for one billing trace.
     * EMPTY distinguishes a legitimate zero-wallet trace from invalid evidence. INVALID means the
     * trace is duplicated, mixed across users, contains deleted/unsupported rows, or violates the
     * signed amount contract. Callers must fail closed.
     */
    enum WalletLedgerEvidenceState { EMPTY, VALID, INVALID }

    record WalletLedgerEvidence(String traceId,
                                WalletLedgerEvidenceState state,
                                Long userId,
                                BigDecimal frozenAmount,
                                BigDecimal grossConsumedAmount,
                                BigDecimal settleRefundedAmount,
                                BigDecimal unfrozenAmount,
                                BigDecimal settleUnfrozenAmount,
                                BigDecimal netConsumedAmount) {
        public boolean valid() { return state == WalletLedgerEvidenceState.VALID; }
        public boolean empty() { return state == WalletLedgerEvidenceState.EMPTY; }
    }

    /**
     * 冻结：balance -= amount, frozenBalance += amount。
     *
     * @param userId        用户ID
     * @param amount        冻结金额
     * @param traceId       计费追踪ID
     * @param bizType       业务类型（extract/media）
     * @param bizName       业务描述
     * @throws com.aid.common.exception.ServiceException 预扣余额不足
     */
    void freeze(Long userId, BigDecimal amount, String traceId, String bizType, String bizName);

    /**
     * 冻结并记录本次业务实际使用的模型编码。
     * 多模型业务使用英文逗号分隔，供消费明细转换为前端展示名称。
     * @throws com.aid.common.exception.ServiceException 预扣余额不足
     */
    void freeze(Long userId, BigDecimal amount, String traceId, String bizType, String bizName,
                String modelCode);

    /**
     * 查询指定 trace 是否已有冻结流水，用于续跑 Saga 判断冻结结果是否已提交。
     */
    boolean hasFreezeRecord(String traceId);

    /** Whether the stable billing trace has a committed full unfreeze. */
    boolean hasRefundRecord(String traceId);

    /** Whether the stable billing trace has a committed wallet consumption. */
    boolean hasConsumeRecord(String traceId);

    /** Return the typed aggregate needed to recover wallet/projection splits without guessing. */
    WalletLedgerEvidence inspectLedger(String traceId);

    /** Read at most 200 traces in one bounded query; unsupported or incomplete reads are unavailable. */
    default Map<String, WalletLedgerEvidence> inspectLedgerBatch(Collection<String> traceIds)
    {
        throw new UnsupportedOperationException("批量账本不可用");
    }

    /** 按稳定trace读取已经提交的钱包冻结金额，用于预冻结Saga续跑。 */
    BigDecimal resolveFrozenAmount(String traceId, BigDecimal fallbackAmount);

    /**
     * 结算：frozenBalance -= amount, totalConsumption += amount。
     *
     * @param userId        用户ID
     * @param amount        结算金额
     * @param traceId       计费追踪ID
     * @param bizType       业务类型
     * @param bizName       业务描述
     */
    void settle(Long userId, BigDecimal amount, String traceId, String bizType, String bizName);

    /**
     * 查询同一计费链路已经从冻结余额结算的金额。
     * 历史两位小数流水会按原账本金额返回，用于补偿任务避免重复采用新计算精度。
     *
     * @param traceId 计费追踪ID
     * @param fallbackAmount 未找到消费流水时的兜底金额
     * @return 已落账消费金额或四位小数兜底金额
     */
    BigDecimal resolveConsumedAmount(String traceId, BigDecimal fallbackAmount);

    /**
     * 退回：frozenBalance -= amount, balance += amount。
     *
     * @param userId        用户ID
     * @param amount        退回金额
     * @param traceId       计费追踪ID
     * @param bizType       业务类型
     * @param bizName       业务描述
     */
    void refund(Long userId, BigDecimal amount, String traceId, String bizType, String bizName);

    /**
     * 结算差额退回（从冻结余额）：frozenBalance -= amount, balance += amount。
     *
     * @param userId        用户ID
     * @param amount        退回金额（= frozenAmount - actualAmount）
     * @param traceId       计费追踪ID
     * @param bizType       业务类型
     * @param bizName       业务描述
     */
    void settleRefundFromFrozen(Long userId, BigDecimal amount, String traceId, String bizType, String bizName);

    /**
     * 结算后差额退回：balance += amount, totalConsumption -= amount。
     *
     * @param userId        用户ID
     * @param amount        退回金额
     * @param traceId       计费追踪ID
     * @param bizType       业务类型
     * @param bizName       业务描述
     */
    void settleRefund(Long userId, BigDecimal amount, String traceId, String bizType, String bizName);

    /**
     * 直接消费：balance -= amount, totalConsumption += amount（余额不足抛异常）。
     *
     * @param userId        用户ID
     * @param amount        消费金额
     * @param traceId       关联ID（订单号/任务ID等）
     * @param bizType       业务类型
     * @param bizName       业务描述
     */
    void directConsume(Long userId, BigDecimal amount, String traceId, String bizType, String bizName);

    /**
     * 充值：balance += amount, totalRecharge += amount。
     *
     * @param userId        用户ID
     * @param amount        充值金额
     * @param traceId       关联ID（订单号等）
     * @param bizType       业务类型
     * @param bizName       业务描述
     */
    void recharge(Long userId, BigDecimal amount, String traceId, String bizType, String bizName);

    /**
     * 结算补扣：actual > preHold 时，从可用余额中补扣差额。
     *
     * @param userId   用户ID
     * @param extra    需补扣总金额（actual - preHold）
     * @param traceId  计费追踪ID（与主结算同一 traceId，changeType 不同保证幂等）
     * @param bizType  业务类型
     * @param bizName  业务描述
     * @return 累计补扣成功总额（含历史补扣，可能 < extra）
     */
    BigDecimal settleExtraCharge(Long userId, BigDecimal extra, String traceId, String bizType, String bizName);

    /**
     * 查询用户余额（带 userId 校验）
     *
     * @param userId 用户ID
     * @return 用户账户档案
     */
    com.aid.aid.domain.AidUserProfile getProfile(Long userId);

    /**
     * 余额充足性前置预检（只读，不加锁不冻结）：可用余额 < 预估金额时抛「预扣余额不足」。
     * 用于生成类任务在「建任务记录之前」快速拦截，避免余额不足时空建任务再回滚；
     * 最终以 {@link #freeze} 锁内校验为准，本方法只是提前失败的轻量防线。
     *
     * @param userId 用户ID
     * @param amount 预估金额（null/非正数视为无需预检，直接通过）
     */
    void precheckBalance(Long userId, BigDecimal amount);

    /**
     * 管理员手动调整余额：balance += delta（delta 正数增加 / 负数扣减），不触动累计充值/消费。
     *
     * @param userId  用户ID
     * @param delta   调整金额（正数增加，负数扣减，单位：元）
     * @param traceId 幂等追踪ID（管理员操作流水号）
     * @param bizName 业务描述（管理员调整原因，写入流水）
     */
    void adminAdjust(Long userId, BigDecimal delta, String traceId, String bizName);

    /**
     * 营销奖励入账：balance += amount，不触动累计充值/消费（changeType=reward）。
     * 用于注册赠送、邀请充值返佣等系统自动发放的奖励积分，
     * 与 recharge 区分开，保证累计充值口径只统计真实付费。
     *
     * @param userId  用户ID
     * @param amount  奖励金额（必须为正数）
     * @param traceId 幂等追踪ID（如 register_bonus_{userId} / {orderNo}_INVITE）
     * @param bizType 业务类型（register_bonus注册赠送 / invite_rebate邀请返佣）
     * @param bizName 业务描述（写入流水便于审计）
     */
    void reward(Long userId, BigDecimal amount, String traceId, String bizType, String bizName);
}
