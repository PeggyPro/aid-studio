package com.aid.billing.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.billing.admission.ReleaseEvidence;
import com.aid.billing.admission.ReleaseOutcome;
import com.aid.billing.admission.ReservationIdentity;
import com.aid.billing.admission.UnsubmittedReservationReleaseGuard;
import com.aid.billing.domain.AidBillingAuthorization;
import com.aid.billing.domain.AidBillingAuthorizationReservation;
import com.aid.billing.dto.BillingBudgetContext;
import com.aid.billing.dto.BillingBudgetRequests.AuthorizationQuery;
import com.aid.billing.dto.BillingBudgetRequests.CreateAuthorizationRequest;
import com.aid.billing.dto.BillingBudgetRequests.ReserveCallRequest;
import com.aid.billing.dto.BillingBudgetRequests.ReleaseUnsubmittedReservationRequest;
import com.aid.billing.dto.BillingBudgetRequests.ReviseAuthorizationRequest;
import com.aid.billing.dto.BillingBudgetRequests.RenewAuthorizationRequest;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.aid.billing.dto.BillingBudgetRequests.SubmissionClaimRequest;
import com.aid.billing.dto.BillingBudgetViews.AuthorizationView;
import com.aid.billing.dto.BillingBudgetViews.AuthorizationPageView;
import com.aid.billing.dto.BillingBudgetViews.ReservationView;
import com.aid.billing.dto.BillingBudgetViews.SubmissionClaimResult;
import com.aid.billing.enums.BillingAuthorizationStatus;
import com.aid.billing.enums.BillingConstants;
import com.aid.billing.enums.BillingReservationState;
import com.aid.billing.mapper.AidBillingAuthorizationMapper;
import com.aid.billing.mapper.AidBillingAuthorizationReservationMapper;
import com.aid.billing.model.BillingSnapshot;
import com.aid.billing.service.AssistantBillingBudgetService;
import com.aid.billing.service.IAccountUpdateService;
import com.aid.common.exception.ServiceException;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import java.util.List;
import java.util.Objects;

/** 两类助手共用的Run总预算服务实现。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantBillingBudgetServiceImpl implements AssistantBillingBudgetService {

    private static final String NORMAL = "0";
    private static final String CURRENCY_UNIT = "BALANCE";
    private static final String SYSTEM_OPERATOR = "system";
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(BillingConstants.ACCOUNT_AMOUNT_SCALE);

    private final AidBillingAuthorizationMapper authorizationMapper;
    private final AidBillingAuthorizationReservationMapper reservationMapper;
    private final AidMediaTaskMapper mediaTaskMapper;
    private final IAccountUpdateService accountUpdateService;
    private final List<UnsubmittedReservationReleaseGuard> releaseGuards;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AuthorizationView createAuthorization(CreateAuthorizationRequest request) {
        requireCreateAuthorization(request);
        BigDecimal approvedTotal = normalizeApprovedTotal(request.getApprovedTotal());
        AidBillingAuthorization existing = authorizationMapper.selectByRuntimeRunIdForUpdate(
                request.getRuntimeRunId());
        if (existing != null) {
            requireSameAuthorization(existing, request, approvedTotal);
            return toAuthorizationView(existing);
        }

        Date now = new Date();
        AidBillingAuthorization authorization = new AidBillingAuthorization();
        authorization.setPayerUserId(request.getPayerUserId());
        authorization.setRuntimeRunId(request.getRuntimeRunId());
        authorization.setApprovedTotal(approvedTotal);
        authorization.setReservedAmount(ZERO);
        authorization.setSettledAmount(ZERO);
        authorization.setCurrencyUnit(CURRENCY_UNIT);
        authorization.setPrecisionScale(BillingConstants.ACCOUNT_AMOUNT_SCALE);
        authorization.setStatus(BillingAuthorizationStatus.ACTIVE.name());
        authorization.setRevision(1L);
        authorization.setStateRevision(1L);
        authorization.setPolicyDigest(request.getPolicyDigest());
        authorization.setExpiresAt(truncateToSecond(request.getExpiresAt()));
        authorization.setDelFlag(NORMAL);
        authorization.setCreateBy(operator(request.getOperator()));
        authorization.setCreateTime(now);
        authorization.setUpdateBy(operator(request.getOperator()));
        authorization.setUpdateTime(now);
        try {
            authorizationMapper.insert(authorization);
        } catch (DuplicateKeyException duplicate) {
            AidBillingAuthorization winner = authorizationMapper.selectByRuntimeRunIdForUpdate(
                    request.getRuntimeRunId());
            if (winner == null) {
                throw duplicate;
            }
            requireSameAuthorization(winner, request, approvedTotal);
            return toAuthorizationView(winner);
        }
        return toAuthorizationView(authorization);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AuthorizationView reviseAuthorization(ReviseAuthorizationRequest request) {
        requireReviseAuthorization(request);
        AidBillingAuthorization authorization = requireAuthorizationForUpdate(request.getAuthorizationId());
        if (!Objects.equals(authorization.getPayerUserId(), request.getPayerUserId())) {
            fail("授权用户不匹配", authorization.getId());
        }
        if (!Objects.equals(authorization.getRevision(), request.getExpectedRevision())) {
            fail("授权版本冲突", authorization.getId());
        }
        requireActiveAuthorization(authorization, new Date());

        BigDecimal approvedTotal = normalizeApprovedTotal(request.getApprovedTotal());
        BigDecimal committed = amount(authorization.getReservedAmount())
                .add(amount(authorization.getSettledAmount()));
        if (approvedTotal.compareTo(committed) < 0) {
            fail("授权额度过低", authorization.getId());
        }

        authorization.setApprovedTotal(approvedTotal);
        authorization.setPolicyDigest(request.getPolicyDigest());
        authorization.setExpiresAt(truncateToSecond(request.getExpiresAt()));
        authorization.setRevision(nextRevision(authorization.getRevision()));
        authorization.setStateRevision(nextRevision(authorization.getStateRevision()));
        touch(authorization, request.getOperator());
        authorizationMapper.updateById(authorization);
        return toAuthorizationView(authorization);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AuthorizationView renewExpiredAuthorization(RenewAuthorizationRequest request) {
        requireReviseAuthorization(request);
        AidBillingAuthorization authorization = requireAuthorizationForUpdate(request.getAuthorizationId());
        Date now = new Date();
        if (request.getRuntimeRunId() == null || request.getExpectedStateRevision() == null
                || !Objects.equals(authorization.getPayerUserId(), request.getPayerUserId())
                || !Objects.equals(authorization.getRuntimeRunId(), request.getRuntimeRunId())
                || !Objects.equals(authorization.getRevision(), request.getExpectedRevision())
                || !Objects.equals(authorization.getStateRevision(), request.getExpectedStateRevision()))
            fail("续授权版本冲突", authorization.getId());
        if (!("ACTIVE".equals(authorization.getStatus()) || "EXHAUSTED".equals(authorization.getStatus()))
                || authorization.getExpiresAt() == null || request.getPriorPolicyExpiresAt() == null
                || authorization.getExpiresAt().after(now) && request.getPriorPolicyExpiresAt().after(now))
            fail("授权不可续期", authorization.getId());
        if (authorization.getReservedAmount() == null || authorization.getReservedAmount().signum() != 0
                || authorization.getSettledAmount() == null || authorization.getSettledAmount().signum() < 0
                || pendingReservation(authorization.getId(), true) != null)
            fail("已有调用待核实", authorization.getId());
        BigDecimal total = normalizeApprovedTotal(request.getApprovedTotal());
        Date expires = truncateToSecond(request.getExpiresAt());
        if (total.compareTo(authorization.getSettledAmount()) < 0 || expires == null || !expires.after(now))
            fail("续授权范围无效", authorization.getId());
        long revision = nextRevision(authorization.getRevision());
        long stateRevision = nextRevision(authorization.getStateRevision());
        int changed = authorizationMapper.update(null, new LambdaUpdateWrapper<AidBillingAuthorization>()
                .eq(AidBillingAuthorization::getId, authorization.getId()).eq(AidBillingAuthorization::getDelFlag, "0")
                .eq(AidBillingAuthorization::getPayerUserId, request.getPayerUserId())
                .eq(AidBillingAuthorization::getRuntimeRunId, request.getRuntimeRunId())
                .eq(AidBillingAuthorization::getRevision, request.getExpectedRevision())
                .eq(AidBillingAuthorization::getStateRevision, request.getExpectedStateRevision())
                .set(AidBillingAuthorization::getApprovedTotal, total).set(AidBillingAuthorization::getPolicyDigest, request.getPolicyDigest())
                .set(AidBillingAuthorization::getExpiresAt, expires).set(AidBillingAuthorization::getStatus, "ACTIVE")
                .set(AidBillingAuthorization::getRevision, revision).set(AidBillingAuthorization::getStateRevision, stateRevision)
                .set(AidBillingAuthorization::getUpdateBy, operator(request.getOperator())).set(AidBillingAuthorization::getUpdateTime, now));
        if (changed != 1) fail("续授权版本冲突", authorization.getId());
        authorization.setApprovedTotal(total); authorization.setPolicyDigest(request.getPolicyDigest());
        authorization.setExpiresAt(expires); authorization.setStatus("ACTIVE");
        authorization.setRevision(revision); authorization.setStateRevision(stateRevision);
        return toAuthorizationView(authorization);
    }

    @Override
    public boolean hasUnresolvedReservations(Long authorizationId) {
        if (authorizationId == null) return true;
        // Legacy authorization views normalize missing money to zero; renewal must inspect raw evidence.
        AidBillingAuthorization authorization = authorizationMapper.selectOne(new LambdaQueryWrapper<AidBillingAuthorization>()
                .select(AidBillingAuthorization::getId, AidBillingAuthorization::getReservedAmount,
                        AidBillingAuthorization::getSettledAmount)
                .eq(AidBillingAuthorization::getId, authorizationId).eq(AidBillingAuthorization::getDelFlag, NORMAL)
                .last("LIMIT 1"));
        return authorization == null || authorization.getReservedAmount() == null
                || authorization.getReservedAmount().signum() != 0 || authorization.getSettledAmount() == null
                || authorization.getSettledAmount().signum() < 0 || pendingReservation(authorizationId, false) != null;
    }

    private AidBillingAuthorizationReservation pendingReservation(Long authorizationId, boolean lock) {
        return reservationMapper.selectOne(new LambdaQueryWrapper<AidBillingAuthorizationReservation>()
                .select(AidBillingAuthorizationReservation::getId)
                .eq(AidBillingAuthorizationReservation::getAuthorizationId, authorizationId)
                .eq(AidBillingAuthorizationReservation::getDelFlag, "0")
                .and(q -> q.isNull(AidBillingAuthorizationReservation::getState)
                        .or().notIn(AidBillingAuthorizationReservation::getState, List.of("SETTLED", "REFUNDED", "RELEASED")))
                .last(lock ? "LIMIT 1 FOR UPDATE" : "LIMIT 1"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReservationView reserveCall(ReserveCallRequest request) {
        return reserveCall(request, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReservationView reserveWalletTextCall(ReserveCallRequest request) {
        if (request == null || !"ASSISTANT_DECISION".equals(request.getOperationKind()))
            throw new ServiceException("Wallet text reservation requires an assistant decision");
        return reserveCall(request, true);
    }

    private ReservationView reserveCall(ReserveCallRequest request, boolean walletText) {
        requireReserveCall(request);
        BigDecimal upperBound = normalizeUpperBound(request.getQuotedUpperBound());
        AidBillingAuthorization authorization = requireAuthorizationForUpdate(request.getAuthorizationId());
        requireReservationAuthorization(authorization, request);
        if (!walletText) requireActiveAuthorization(authorization, new Date());
        String reservationKind = walletText ? "ASSISTANT_DECISION_WALLET" : request.getOperationKind();

        AidBillingAuthorizationReservation existing = reservationMapper.selectByLogicalCallKeyForUpdate(
                request.getLogicalCallKey());
        if (existing != null) {
            requireSameReservation(existing, request, upperBound, reservationKind);
            return toReservationView(existing);
        }

        BigDecimal committed = amount(authorization.getReservedAmount())
                .add(amount(authorization.getSettledAmount()));
        if (!walletText && committed.add(upperBound).compareTo(amount(authorization.getApprovedTotal())) > 0) {
            fail("授权预算不足", authorization.getId());
        }

        Date now = new Date();
        AidBillingAuthorizationReservation reservation = new AidBillingAuthorizationReservation();
        reservation.setAuthorizationId(authorization.getId());
        reservation.setAuthorizationRevision(authorization.getRevision());
        reservation.setRuntimeRunId(request.getRuntimeRunId());
        reservation.setRuntimeStepId(request.getRuntimeStepId());
        reservation.setStableStepKey(request.getStableStepKey());
        reservation.setApprovalId(request.getApprovalId());
        reservation.setAttemptNo(request.getAttemptNo());
        reservation.setOperationKind(reservationKind);
        reservation.setLogicalCallKey(request.getLogicalCallKey());
        reservation.setRequestDigest(request.getRequestDigest());
        reservation.setQuoteId(request.getQuoteId());
        reservation.setQuoteDigest(request.getQuoteDigest());
        reservation.setQuoteExpiresAt(truncateToSecond(request.getQuoteExpiresAt()));
        reservation.setBillingTraceId(IdUtil.fastSimpleUUID());
        reservation.setQuotedUpperBound(upperBound);
        reservation.setReservedAmount(upperBound);
        reservation.setWalletFrozenAmount(ZERO);
        reservation.setSettledAmount(ZERO);
        reservation.setReleasedAmount(ZERO);
        reservation.setWalletRefundedAmount(ZERO);
        reservation.setState(BillingReservationState.RESERVING.name());
        reservation.setRevision(1L);
        reservation.setDelFlag(NORMAL);
        reservation.setCreateBy(operator(request.getOperator()));
        reservation.setCreateTime(now);
        reservation.setUpdateBy(operator(request.getOperator()));
        reservation.setUpdateTime(now);
        try {
            reservationMapper.insert(reservation);
        } catch (DuplicateKeyException duplicate) {
            AidBillingAuthorizationReservation winner = reservationMapper
                    .selectByLogicalCallKeyForUpdate(request.getLogicalCallKey());
            if (winner == null) {
                throw duplicate;
            }
            requireSameReservation(winner, request, upperBound, reservationKind);
            return toReservationView(winner);
        }

        updateAuthorizationTotals(authorization, reservation, upperBound, ZERO, request.getOperator());
        return toReservationView(reservation);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public SubmissionClaimResult claimSubmission(SubmissionClaimRequest request) {
        requireSubmissionClaim(request);
        AidBillingAuthorization authorization = requireAuthorizationForUpdate(request.getAuthorizationId());
        if (!Objects.equals(authorization.getRevision(), request.getAuthorizationRevision())
                || !Objects.equals(authorization.getRuntimeRunId(), request.getRuntimeRunId())) {
            fail("授权版本冲突", authorization.getId());
        }

        AidBillingAuthorizationReservation reservation = requireReservationForUpdate(request.getReservationId());
        requireClaimIdentity(reservation, request);
        if (!walletText(reservation)) requireActiveAuthorization(authorization, new Date());
        SubmissionClaimResult result = new SubmissionClaimResult();
        if (BillingReservationState.INFLIGHT.name().equals(reservation.getState())) {
            result.setNewlyClaimed(false);
            result.setAlreadyInflight(true);
            result.setReservation(toReservationView(reservation));
            return result;
        }
        if (!BillingReservationState.RESERVED.name().equals(reservation.getState())) {
            fail("预算状态异常", reservation.getId());
        }
        if (!Objects.equals(reservation.getRevision(), request.getExpectedReservationRevision())) {
            fail("预留版本冲突", reservation.getId());
        }

        reservation.setState(BillingReservationState.INFLIGHT.name());
        reservation.setRevision(nextRevision(reservation.getRevision()));
        touch(reservation, request.getOperator());
        reservationMapper.updateById(reservation);
        result.setNewlyClaimed(true);
        result.setAlreadyInflight(false);
        result.setReservation(toReservationView(reservation));
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReservationView releaseUnsubmittedReservation(
            ReleaseUnsubmittedReservationRequest request) {
        requireReleaseUnsubmitted(request);

        // This first read is deliberately unlocked. It only routes to the guard. The guard locks
        // runtime rows first; authorization and reservation locks are acquired afterwards.
        AidBillingAuthorizationReservation routing = reservationMapper.selectById(request.getReservationId());
        if (routing == null || !NORMAL.equals(routing.getDelFlag())) {
            fail("budget reservation does not exist", request.getReservationId());
        }
        List<UnsubmittedReservationReleaseGuard> matches = releaseGuards == null
                ? List.of()
                : releaseGuards.stream()
                .filter(guard -> guard.supports(runtimeOperationKind(routing)))
                .toList();
        if (matches.size() != 1) {
            fail("unsubmitted reservation release guard is unavailable", routing.getId());
        }
        ReservationIdentity routingIdentity = reservationIdentity(routing);
        ReleaseEvidence evidence = matches.get(0).lockAndVerify(request, routingIdentity);
        requireReleaseEvidence(evidence, request);

        AidBillingAuthorization authorization = requireAuthorizationForUpdate(request.getAuthorizationId());
        AidBillingAuthorizationReservation reservation = requireReservationForUpdate(request.getReservationId());
        requireReleaseIdentity(authorization, reservation, request);

        String releaseKey = "unsubmitted-release:" + reservation.getId();
        BigDecimal reserved = amount(reservation.getReservedAmount());
        AidMediaTask durableTask = mediaTaskMapper.selectByAssistantOperationForUpdate(
                evidence.payerUserId(), evidence.bizTaskType(), evidence.bizTaskId(),
                evidence.logicalCallKey());
        if (BillingReservationState.RELEASED.name().equals(reservation.getState())) {
            if (Objects.equals(reservation.getSettlementKey(), "release:" + reservation.getId())) {
                requirePreHoldCompensationRelease(reservation, durableTask);
            } else {
                requireTerminalAmounts(reservation, releaseKey, ZERO, reserved, ZERO);
            }
            matches.get(0).recordOutcome(evidence, ReleaseOutcome.RELEASED);
            return toReservationView(reservation);
        }
        if (BillingReservationState.RECONCILING.name().equals(reservation.getState())) {
            matches.get(0).recordOutcome(evidence, ReleaseOutcome.RECONCILING);
            return toReservationView(reservation);
        }
        if (!BillingReservationState.RESERVING.name().equals(reservation.getState())
                && !BillingReservationState.RESERVED.name().equals(reservation.getState())) {
            fail("budget reservation is not releasable before submission", reservation.getId());
        }
        if (!Objects.equals(reservation.getRevision(), request.getExpectedReservationRevision())) {
            fail("budget reservation revision conflicts", reservation.getId());
        }

        boolean walletEvidence = amount(reservation.getWalletFrozenAmount()).signum() > 0
                || accountUpdateService.hasFreezeRecord(reservation.getBillingTraceId());
        if (reservation.getMediaTaskId() != null || durableTask != null || walletEvidence) {
            reservation.setState(BillingReservationState.RECONCILING.name());
            reservation.setRevision(nextRevision(reservation.getRevision()));
            touch(reservation, request.getOperator());
            reservationMapper.updateById(reservation);
            matches.get(0).recordOutcome(evidence, ReleaseOutcome.RECONCILING);
            return toReservationView(reservation);
        }

        reservation.setSettledAmount(ZERO);
        reservation.setReleasedAmount(reserved);
        reservation.setWalletRefundedAmount(ZERO);
        reservation.setState(BillingReservationState.RELEASED.name());
        reservation.setSettlementKey(releaseKey);
        reservation.setRevision(nextRevision(reservation.getRevision()));
        touch(reservation, request.getOperator());
        reservationMapper.updateById(reservation);

        updateAuthorizationTotals(authorization, reservation, reserved.negate(), ZERO, request.getOperator());
        matches.get(0).recordOutcome(evidence, ReleaseOutcome.RELEASED);
        return toReservationView(reservation);
    }

    @Override
    @Transactional(readOnly = true)
    public AuthorizationView getAuthorization(Long authorizationId) {
        AidBillingAuthorization authorization = authorizationId == null
                ? null : authorizationMapper.selectById(authorizationId);
        return authorization == null || !NORMAL.equals(authorization.getDelFlag())
                ? null : toAuthorizationView(authorization);
    }

    @Override
    @Transactional(readOnly = true)
    public AuthorizationView getAuthorizationByRunId(Long runtimeRunId) {
        AidBillingAuthorization authorization = runtimeRunId == null
                ? null : authorizationMapper.selectByRuntimeRunId(runtimeRunId);
        return authorization == null ? null : toAuthorizationView(authorization);
    }

    @Override
    @Transactional(readOnly = true)
    public AuthorizationPageView listAuthorizations(AuthorizationQuery query) {
        AuthorizationQuery safeQuery = query == null ? new AuthorizationQuery() : query;
        int pageNum = safeQuery.getPageNum() == null || safeQuery.getPageNum() < 1
                ? 1 : safeQuery.getPageNum();
        int pageSize = safeQuery.getPageSize() == null || safeQuery.getPageSize() < 1
                ? 20 : Math.min(safeQuery.getPageSize(), 100);
        Page<AidBillingAuthorization> page = authorizationMapper.selectPage(
                new Page<>(pageNum, pageSize),
                Wrappers.<AidBillingAuthorization>lambdaQuery()
                        .eq(AidBillingAuthorization::getDelFlag, NORMAL)
                        .eq(safeQuery.getAuthorizationId() != null,
                                AidBillingAuthorization::getId, safeQuery.getAuthorizationId())
                        .eq(safeQuery.getRuntimeRunId() != null,
                                AidBillingAuthorization::getRuntimeRunId, safeQuery.getRuntimeRunId())
                        .eq(safeQuery.getPayerUserId() != null,
                                AidBillingAuthorization::getPayerUserId, safeQuery.getPayerUserId())
                        .eq(StrUtil.isNotBlank(safeQuery.getStatus()),
                                AidBillingAuthorization::getStatus, safeQuery.getStatus())
                        .orderByDesc(AidBillingAuthorization::getId));
        AuthorizationPageView result = new AuthorizationPageView();
        result.setTotal(page.getTotal());
        result.setPageNum(pageNum);
        result.setPageSize(pageSize);
        result.setRows(page.getRecords().stream().map(this::toAuthorizationView).toList());
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReservationView> listReservations(Long authorizationId) {
        if (authorizationId == null) {
            return List.of();
        }
        return reservationMapper.selectList(
                        Wrappers.<AidBillingAuthorizationReservation>lambdaQuery()
                                .eq(AidBillingAuthorizationReservation::getAuthorizationId, authorizationId)
                                .eq(AidBillingAuthorizationReservation::getDelFlag, NORMAL)
                                .orderByAsc(AidBillingAuthorizationReservation::getId))
                .stream().map(this::toReservationView).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ReservationView verifyPreHold(BillingBudgetContext context, AidMediaTask task,
                                         BigDecimal calculatedPreHold, BillingSnapshot snapshot) {
        requireBudgetContext(context);
        if (task == null || task.getId() == null || task.getUserId() == null || snapshot == null) {
            fail("预算上下文无效", null);
        }
        if (!Objects.equals(task.getIdempotencyKey(), context.getLogicalCallKey())) {
            fail("调用标识冲突", task.getId());
        }
        AidBillingAuthorization authorization = authorizationMapper.selectById(context.getAuthorizationId());
        if (authorization == null || !NORMAL.equals(authorization.getDelFlag())) {
            fail("授权不存在", context.getAuthorizationId());
        }
        if (!Objects.equals(authorization.getRevision(), context.getAuthorizationRevision())
                || !Objects.equals(authorization.getRuntimeRunId(), context.getRuntimeRunId())
                || !Objects.equals(authorization.getPayerUserId(), task.getUserId())) {
            fail("授权信息不匹配", authorization.getId());
        }
        AidBillingAuthorizationReservation reservation = reservationMapper.selectById(context.getReservationId());
        requirePreHoldIdentity(reservation, context);
        if (!walletText(reservation)) requireActiveAuthorization(authorization, new Date());
        else if (!"TEXT".equals(task.getMediaType()) || !BillingBudgetContext.ASSISTANT_BIZ_TASK_TYPE.equals(task.getBizTaskType()))
            fail("文本钱包预留不适用于当前任务", task.getId());
        if (reservation.getQuoteExpiresAt() == null
                || !reservation.getQuoteExpiresAt().after(new Date())) {
            fail("报价已过期", reservation.getId());
        }
        if (!Objects.equals(snapshot.getRequestDigest(), reservation.getRequestDigest())
                || !Objects.equals(snapshot.getQuoteId(), reservation.getQuoteId())
                || !Objects.equals(snapshot.getQuoteDigest(), reservation.getQuoteDigest())
                || !sameDate(snapshot.getQuoteExpiresAt(), reservation.getQuoteExpiresAt())
                || amount(snapshot.getAuthorizedChargeCap())
                .compareTo(amount(reservation.getReservedAmount())) != 0) {
            fail("报价摘要不匹配", reservation.getId());
        }
        if (!BillingReservationState.RESERVING.name().equals(reservation.getState())
                && !BillingReservationState.RESERVED.name().equals(reservation.getState())) {
            fail("预算状态异常", reservation.getId());
        }
        BigDecimal preHold = normalizeUpperBound(calculatedPreHold);
        if (preHold.compareTo(amount(reservation.getReservedAmount())) > 0) {
            fail("授权预算不足", reservation.getId());
        }
        if (reservation.getMediaTaskId() != null
                && !Objects.equals(reservation.getMediaTaskId(), task.getId())) {
            fail("预算任务冲突", reservation.getId());
        }

        task.setBillingTraceId(reservation.getBillingTraceId());
        snapshot.setBudgetMode(BillingBudgetContext.MODE_RUN_AUTHORIZATION);
        snapshot.setBillingAuthorizationId(authorization.getId());
        snapshot.setBillingAuthorizationRevision(authorization.getRevision());
        snapshot.setBillingReservationId(reservation.getId());
        snapshot.setBillingReservationRevision(reservation.getRevision());
        snapshot.setRuntimeRunId(reservation.getRuntimeRunId());
        snapshot.setRuntimeStepId(reservation.getRuntimeStepId());
        snapshot.setRuntimeRunFencingToken(context.getRuntimeRunFencingToken());
        snapshot.setRuntimeStepFencingToken(context.getRuntimeStepFencingToken());
        snapshot.setStableStepKey(reservation.getStableStepKey());
        snapshot.setBillingAttemptNo(reservation.getAttemptNo());
        snapshot.setLogicalCallKey(reservation.getLogicalCallKey());
        snapshot.setRequestDigest(reservation.getRequestDigest());
        snapshot.setQuoteId(reservation.getQuoteId());
        snapshot.setQuoteDigest(reservation.getQuoteDigest());
        snapshot.setQuoteExpiresAt(reservation.getQuoteExpiresAt());
        snapshot.setAuthorizedChargeCap(amount(reservation.getReservedAmount()));
        return toReservationView(reservation);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public ReservationView markReserved(BillingBudgetContext context, AidMediaTask task, String operator) {
        requireBudgetContext(context);
        if (task == null || task.getId() == null || StrUtil.isBlank(task.getBillingTraceId())) {
            fail("预算任务无效", null);
        }
        requireAuthorizationForUpdate(context.getAuthorizationId());
        AidBillingAuthorizationReservation reservation = requireReservationForUpdate(context.getReservationId());
        requirePreHoldIdentity(reservation, context);
        if (!Objects.equals(reservation.getBillingTraceId(), task.getBillingTraceId())) {
            fail("计费链路冲突", reservation.getId());
        }
        BigDecimal walletFrozen = amount(task.getFrozenAmount());
        if (!"FROZEN".equals(task.getBillingStatus())
                || walletFrozen.compareTo(amount(reservation.getReservedAmount())) > 0
                || walletFrozen.signum() > 0
                && !accountUpdateService.hasFreezeRecord(reservation.getBillingTraceId())) {
            fail("钱包冻结未确认", reservation.getId());
        }
        if (BillingReservationState.RESERVED.name().equals(reservation.getState())) {
            if (!Objects.equals(reservation.getMediaTaskId(), task.getId())) {
                fail("预算任务冲突", reservation.getId());
            }
            context.setReservationRevision(reservation.getRevision());
            return toReservationView(reservation);
        }
        if (!BillingReservationState.RESERVING.name().equals(reservation.getState())) {
            fail("预算状态异常", reservation.getId());
        }
        reservation.setMediaTaskId(task.getId());
        reservation.setWalletFrozenAmount(walletFrozen);
        reservation.setState(BillingReservationState.RESERVED.name());
        reservation.setRevision(nextRevision(reservation.getRevision()));
        touch(reservation, operator);
        reservationMapper.updateById(reservation);
        context.setReservationRevision(reservation.getRevision());
        return toReservationView(reservation);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void compensatePreHoldFailure(BillingBudgetContext context, AidMediaTask task,
                                         BigDecimal calculatedPreHold, String operator) {
        requireBudgetContext(context);
        AidBillingAuthorization authorization = requireAuthorizationForUpdate(context.getAuthorizationId());
        AidBillingAuthorizationReservation reservation = requireReservationForUpdate(context.getReservationId());
        requirePreHoldIdentity(reservation, context);
        if (task == null || task.getUserId() == null
                || !Objects.equals(authorization.getRevision(), context.getAuthorizationRevision())
                || !Objects.equals(authorization.getRuntimeRunId(), context.getRuntimeRunId())
                || !Objects.equals(authorization.getPayerUserId(), task.getUserId())
                || !Objects.equals(task.getIdempotencyKey(), context.getLogicalCallKey())) {
            fail("预算补偿引用不匹配", reservation.getId());
        }
        if (BillingReservationState.RELEASED.name().equals(reservation.getState())) {
            return;
        }
        if (!BillingReservationState.RESERVING.name().equals(reservation.getState())) {
            fail("预算状态异常", reservation.getId());
        }

        BigDecimal frozenAmount = task != null && task.getFrozenAmount() != null
                ? amount(task.getFrozenAmount())
                : calculatedPreHold == null ? ZERO : amount(calculatedPreHold);
        boolean walletFrozen = accountUpdateService.hasFreezeRecord(reservation.getBillingTraceId());
        if (walletFrozen) {
            frozenAmount = accountUpdateService.resolveFrozenAmount(
                    reservation.getBillingTraceId(), frozenAmount);
        }
        if (walletFrozen && frozenAmount.signum() > 0) {
            accountUpdateService.refund(authorization.getPayerUserId(), frozenAmount,
                    reservation.getBillingTraceId(), "refund", "Run预算预冻结失败补偿");
        }

        BigDecimal reserved = amount(reservation.getReservedAmount());
        reservation.setMediaTaskId(task == null ? reservation.getMediaTaskId() : task.getId());
        reservation.setWalletFrozenAmount(walletFrozen ? frozenAmount : ZERO);
        reservation.setReleasedAmount(reserved);
        reservation.setWalletRefundedAmount(walletFrozen ? frozenAmount : ZERO);
        reservation.setState(BillingReservationState.RELEASED.name());
        reservation.setSettlementKey("release:" + reservation.getId());
        reservation.setRevision(nextRevision(reservation.getRevision()));
        touch(reservation, operator);
        reservationMapper.updateById(reservation);

        updateAuthorizationTotals(authorization, reservation, reserved.negate(), ZERO, operator);
    }

    @Override
    @Transactional(readOnly = true)
    public void assertSubmissionAdmitted(AidMediaTask task) {
        BillingSnapshot snapshot = budgetSnapshot(task);
        if (snapshot == null) {
            return;
        }
        requireSnapshotIdentity(task, snapshot);
        AidBillingAuthorizationReservation reservation = reservationMapper.selectById(
                snapshot.getBillingReservationId());
        if (reservation == null || !NORMAL.equals(reservation.getDelFlag())
                || !BillingReservationState.INFLIGHT.name().equals(reservation.getState())) {
            fail("预算尚未认领", snapshot.getBillingReservationId());
        }
        requireTerminalIdentity(task, snapshot, reservation);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public boolean lockUnsubmittedCancellation(AidMediaTask task) {
        BillingSnapshot snapshot = budgetSnapshot(task);
        if (snapshot == null) return false;
        requireSnapshotIdentity(task, snapshot);
        AidBillingAuthorization authorization = requireAuthorizationForUpdate(snapshot.getBillingAuthorizationId());
        AidBillingAuthorizationReservation reservation = requireReservationForUpdate(snapshot.getBillingReservationId());
        requireTerminalIdentity(task, snapshot, reservation);
        if (!NORMAL.equals(authorization.getDelFlag()) || !NORMAL.equals(reservation.getDelFlag())
                || !Objects.equals(authorization.getPayerUserId(), task.getUserId())
                || !Objects.equals(authorization.getRuntimeRunId(), snapshot.getRuntimeRunId())) {
            fail("Cancellation budget ownership is unavailable", reservation.getId());
        }
        // INFLIGHT/RECONCILING may have reached the Provider even while the task is still QUEUED.
        // Hold these same rows through the following task CAS; the Provider claim uses the same lock.
        return BillingReservationState.RESERVED.name().equals(reservation.getState())
                && amount(reservation.getWalletFrozenAmount()).compareTo(ZERO) >= 0
                && sameAmount(reservation.getWalletFrozenAmount(), task.getFrozenAmount());
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void markSubmissionUnknown(AidMediaTask task, String operator) {
        BillingSnapshot snapshot = budgetSnapshot(task);
        if (snapshot == null) {
            return;
        }
        requireSnapshotIdentity(task, snapshot);
        requireAuthorizationForUpdate(snapshot.getBillingAuthorizationId());
        AidBillingAuthorizationReservation reservation = requireReservationForUpdate(
                snapshot.getBillingReservationId());
        requireTerminalIdentity(task, snapshot, reservation);
        if (BillingReservationState.RECONCILING.name().equals(reservation.getState())) {
            return;
        }
        if (!BillingReservationState.INFLIGHT.name().equals(reservation.getState())) {
            fail("预算状态异常", reservation.getId());
        }
        reservation.setState(BillingReservationState.RECONCILING.name());
        reservation.setRevision(nextRevision(reservation.getRevision()));
        touch(reservation, operator);
        reservationMapper.updateById(reservation);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void settleFromMediaTask(AidMediaTask task, String operator) {
        BillingSnapshot snapshot = budgetSnapshot(task);
        if (snapshot == null) {
            return;
        }
        requireSnapshotIdentity(task, snapshot);
        BigDecimal fallback = task.getActualCost() == null ? task.getFrozenAmount() : task.getActualCost();
        BigDecimal actual = task.getActualCost() == null
                ? accountUpdateService.resolveConsumedAmount(task.getBillingTraceId(), fallback)
                : task.getActualCost();
        actual = amount(actual);
        settleReservation(task, snapshot, actual, false, operator);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void refundFromMediaTask(AidMediaTask task, String operator) {
        BillingSnapshot snapshot = budgetSnapshot(task);
        if (snapshot == null) {
            return;
        }
        requireSnapshotIdentity(task, snapshot);
        settleReservation(task, snapshot, ZERO, true, operator);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void releaseAfterOrphanRefund(AidMediaTask task, String operator) {
        BillingSnapshot snapshot = budgetSnapshot(task);
        if (snapshot == null) {
            return;
        }
        requireSnapshotIdentity(task, snapshot);
        AidBillingAuthorizationReservation reservation = requireReservationForUpdate(
                snapshot.getBillingReservationId());
        requireTerminalIdentity(task, snapshot, reservation);
        BigDecimal walletFrozen = amount(reservation.getWalletFrozenAmount());
        if (walletFrozen.compareTo(ZERO) > 0
                && !accountUpdateService.hasRefundRecord(reservation.getBillingTraceId())) {
            fail("wallet refund proof is missing", reservation.getId());
        }
        settleReservation(task, snapshot, ZERO, true, operator);
    }

    private record SettledRefundBudget(AidBillingAuthorization authorization,
                                       AidBillingAuthorizationReservation reservation) {}

    private SettledRefundBudget settledRefundBudget(AidMediaTask task, BigDecimal originalAmount) {
        BillingSnapshot snapshot = budgetSnapshot(task);
        if (snapshot == null) return null;
        requireSnapshotIdentity(task, snapshot);
        AidBillingAuthorization authorization = requireAuthorizationForUpdate(snapshot.getBillingAuthorizationId());
        AidBillingAuthorizationReservation reservation = requireReservationForUpdate(snapshot.getBillingReservationId());
        requireTerminalIdentity(task, snapshot, reservation);
        BigDecimal settled = amount(reservation.getSettledAmount());
        if (!"FAILED".equals(task.getStatus()) || !"TEXT".equalsIgnoreCase(task.getMediaType())
                || !NORMAL.equals(authorization.getDelFlag()) || !NORMAL.equals(reservation.getDelFlag())
                || !Objects.equals(authorization.getPayerUserId(), task.getUserId())
                || !Objects.equals(authorization.getRuntimeRunId(), snapshot.getRuntimeRunId())
                || authorization.getRevision() == null || authorization.getRevision() < snapshot.getBillingAuthorizationRevision()
                || !BillingReservationState.SETTLED.name().equals(reservation.getState())
                || !Objects.equals("settle:" + task.getId(), reservation.getSettlementKey())
                || !sameAmount(reservation.getWalletFrozenAmount(), originalAmount)
                || !(sameAmount(settled, originalAmount) || settled.signum() == 0)
                || !sameAmount(reservation.getReleasedAmount(), amount(reservation.getReservedAmount()).subtract(settled))
                || !sameAmount(reservation.getWalletRefundedAmount(), originalAmount.subtract(settled))
                || !walletText(reservation) && amount(authorization.getSettledAmount()).compareTo(settled) < 0) {
            fail("历史失败退回预算证据不一致", task.getId());
        }
        return new SettledRefundBudget(authorization, reservation);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void verifySettledTextRefund(AidMediaTask task, BigDecimal originalAmount) {
        settledRefundBudget(task, originalAmount);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void correctSettledTextRefund(AidMediaTask task, BigDecimal originalAmount,
            IAccountUpdateService.WalletLedgerEvidence ledger, String operator) {
        if (ledger == null || !ledger.valid() || !Objects.equals(task.getUserId(), ledger.userId())
                || !Objects.equals(task.getBillingTraceId(), ledger.traceId())
                || !sameAmount(ledger.grossConsumedAmount(), originalAmount)
                || !sameAmount(ledger.settleRefundedAmount(), originalAmount)
                || ledger.netConsumedAmount().signum() != 0) fail("原钱包退回证据不完整", task.getId());
        SettledRefundBudget locked = settledRefundBudget(task, originalAmount);
        if (locked == null) return;
        AidBillingAuthorizationReservation reservation = locked.reservation();
        BigDecimal old = amount(reservation.getSettledAmount());
        if (old.signum() == 0) return;
        reservation.setSettledAmount(ZERO);
        reservation.setReleasedAmount(amount(reservation.getReservedAmount()));
        reservation.setWalletRefundedAmount(amount(reservation.getWalletFrozenAmount()));
        reservation.setRevision(nextRevision(reservation.getRevision()));
        touch(reservation, operator);
        reservationMapper.updateById(reservation);
        updateAuthorizationTotals(locked.authorization(), reservation, ZERO, old.negate(), operator);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recoverSettledTextFromLedger(AidMediaTask task,
            IAccountUpdateService.WalletLedgerEvidence ledger, String operator) {
        var source = com.aid.billing.service.SettledTextLedgerEvidence.SOURCE;
        BillingSnapshot snapshot = budgetSnapshot(task);
        if (snapshot == null) return;
        requireSnapshotIdentity(task, snapshot);
        if (!"FAILED".equals(task.getStatus()) || !"SUCCESS".equals(task.getBillingStatus())
                || !source.equals(snapshot.getSettlementRecoverySource())
                || !com.aid.billing.service.SettledTextLedgerEvidence.matches(task, ledger)
                || !Objects.equals(ledger, accountUpdateService.inspectLedger(task.getBillingTraceId()))
                || !Objects.equals(snapshot.getSettlementRecoveryLedgerDigest(),
                    com.aid.billing.service.SettledTextLedgerEvidence.digest(ledger))
                || task.getActualCost() == null || task.getActualCost().compareTo(ledger.netConsumedAmount()) != 0)
            fail("原结算恢复证据不一致", task.getId());
        settleReservation(task, snapshot, amount(ledger.netConsumedAmount()), false, operator, true);
    }

    private void settleReservation(AidMediaTask task, BillingSnapshot snapshot, BigDecimal actual,
                                   boolean fullRefund, String operator) {
        settleReservation(task, snapshot, actual, fullRefund, operator, false);
    }

    private void settleReservation(AidMediaTask task, BillingSnapshot snapshot, BigDecimal actual,
                                   boolean fullRefund, String operator, boolean committedLedgerRecovery) {
        AidBillingAuthorization authorization = requireAuthorizationForUpdate(
                snapshot.getBillingAuthorizationId());
        AidBillingAuthorizationReservation reservation = requireReservationForUpdate(
                snapshot.getBillingReservationId());
        requireTerminalIdentity(task, snapshot, reservation);
        String settlementKey = (fullRefund ? "refund:" : "settle:") + task.getId();
        String targetState = fullRefund
                ? BillingReservationState.REFUNDED.name() : BillingReservationState.SETTLED.name();
        BigDecimal reserved = amount(reservation.getReservedAmount());
        BigDecimal walletFrozen = amount(reservation.getWalletFrozenAmount());
        if (actual.compareTo(ZERO) < 0 || actual.compareTo(reserved) > 0) {
            fail("settlement amount is outside the reserved budget", reservation.getId());
        }
        if (actual.compareTo(walletFrozen) > 0) {
            fail("settlement amount exceeds the wallet freeze", reservation.getId());
        }
        BigDecimal released = reserved.subtract(actual);
        BigDecimal walletRefunded = walletFrozen.subtract(actual);
        if (fullRefund && BillingReservationState.RELEASED.name().equals(reservation.getState())) {
            requireTerminalAmounts(reservation, "release:" + reservation.getId(), ZERO,
                    reserved, walletFrozen);
            return;
        }
        if (targetState.equals(reservation.getState())) {
            requireTerminalAmounts(reservation, settlementKey, actual, released, walletRefunded);
            return;
        }
        boolean refundableState = BillingReservationState.RESERVED.name().equals(reservation.getState())
                || BillingReservationState.INFLIGHT.name().equals(reservation.getState())
                || BillingReservationState.RECONCILING.name().equals(reservation.getState());
        boolean settleableState = BillingReservationState.INFLIGHT.name().equals(reservation.getState())
                || BillingReservationState.RECONCILING.name().equals(reservation.getState());
        boolean recoverableReserved = committedLedgerRecovery
                && BillingReservationState.RESERVED.name().equals(reservation.getState());
        if (fullRefund ? !refundableState : !settleableState && !recoverableReserved) {
            fail("预算状态异常", reservation.getId());
        }

        if (actual.compareTo(ZERO) < 0 || actual.compareTo(reserved) > 0) {
            fail("结算金额异常", reservation.getId());
        }
        if (actual.compareTo(walletFrozen) > 0) {
            fail("结算金额异常", reservation.getId());
        }

        reservation.setSettledAmount(actual);
        reservation.setReleasedAmount(released);
        reservation.setWalletRefundedAmount(walletRefunded);
        reservation.setState(targetState);
        reservation.setSettlementKey(settlementKey);
        reservation.setRevision(nextRevision(reservation.getRevision()));
        touch(reservation, operator);
        reservationMapper.updateById(reservation);

        updateAuthorizationTotals(authorization, reservation, reserved.negate(), actual, operator);
    }

    private static boolean walletText(AidBillingAuthorizationReservation reservation) {
        return "ASSISTANT_DECISION_WALLET".equals(reservation.getOperationKind());
    }

    private static String runtimeOperationKind(AidBillingAuthorizationReservation reservation) {
        return walletText(reservation) ? "ASSISTANT_DECISION" : reservation.getOperationKind();
    }

    private void updateAuthorizationTotals(AidBillingAuthorization authorization,
            AidBillingAuthorizationReservation reservation, BigDecimal reservedDelta,
            BigDecimal settledDelta, String operator) {
        if (walletText(reservation)) return;
        BigDecimal reserved = amount(authorization.getReservedAmount()).add(reservedDelta);
        BigDecimal settled = amount(authorization.getSettledAmount()).add(settledDelta);
        if (reserved.signum() < 0 || settled.signum() < 0) fail("预算聚合异常", authorization.getId());
        authorization.setReservedAmount(reserved);
        authorization.setSettledAmount(settled);
        authorization.setStateRevision(nextRevision(authorization.getStateRevision()));
        touch(authorization, operator);
        authorizationMapper.updateById(authorization);
    }

    private void requireTerminalAmounts(AidBillingAuthorizationReservation reservation,
                                        String settlementKey,
                                        BigDecimal settled,
                                        BigDecimal released,
                                        BigDecimal walletRefunded) {
        if (!Objects.equals(reservation.getSettlementKey(), settlementKey)
                || !sameAmount(reservation.getSettledAmount(), settled)
                || !sameAmount(reservation.getReleasedAmount(), released)
                || !sameAmount(reservation.getWalletRefundedAmount(), walletRefunded)) {
            fail("terminal replay conflicts with persisted amounts", reservation.getId());
        }
    }

    private void requirePreHoldCompensationRelease(AidBillingAuthorizationReservation reservation,
                                                   AidMediaTask durableTask) {
        BigDecimal reserved = amount(reservation.getReservedAmount());
        BigDecimal walletFrozen = amount(reservation.getWalletFrozenAmount());
        requireTerminalAmounts(reservation, "release:" + reservation.getId(), ZERO,
                reserved, walletFrozen);
        AidMediaTask reservationTask = reservation.getMediaTaskId() == null
                ? null : mediaTaskMapper.selectById(reservation.getMediaTaskId());
        boolean freezeRecorded = accountUpdateService.hasFreezeRecord(reservation.getBillingTraceId());
        boolean refundRecorded = accountUpdateService.hasRefundRecord(reservation.getBillingTraceId());
        if (walletFrozen.signum() < 0
                || walletFrozen.signum() == 0 && (freezeRecorded || refundRecorded)
                || walletFrozen.signum() > 0 && (!freezeRecorded || !refundRecorded)
                || reservationTask != null || durableTask != null) {
            fail("pre-hold compensation release proof is incomplete", reservation.getId());
        }
    }

    private AidBillingAuthorization requireAuthorizationForUpdate(Long id) {
        AidBillingAuthorization authorization = id == null ? null : authorizationMapper.selectByIdForUpdate(id);
        if (authorization == null) {
            fail("授权不存在", id);
        }
        return authorization;
    }

    private AidBillingAuthorizationReservation requireReservationForUpdate(Long id) {
        AidBillingAuthorizationReservation reservation = id == null
                ? null : reservationMapper.selectByIdForUpdate(id);
        if (reservation == null) {
            fail("预算预留不存在", id);
        }
        return reservation;
    }

    private void requireActiveAuthorization(AidBillingAuthorization authorization, Date now) {
        if (!BillingAuthorizationStatus.ACTIVE.name().equals(authorization.getStatus())
                || authorization.getExpiresAt() != null && !authorization.getExpiresAt().after(now)) {
            fail("授权已失效", authorization.getId());
        }
    }

    private void requireReservationAuthorization(AidBillingAuthorization authorization,
                                                 ReserveCallRequest request) {
        if (!Objects.equals(authorization.getPayerUserId(), request.getPayerUserId())
                || !Objects.equals(authorization.getRuntimeRunId(), request.getRuntimeRunId())
                || !Objects.equals(authorization.getRevision(), request.getAuthorizationRevision())) {
            fail("授权信息不匹配", authorization.getId());
        }
    }

    private void requirePreHoldIdentity(AidBillingAuthorizationReservation reservation,
                                        BillingBudgetContext context) {
        if (reservation == null || !NORMAL.equals(reservation.getDelFlag())
                || !Objects.equals(reservation.getAuthorizationId(), context.getAuthorizationId())
                || !Objects.equals(reservation.getAuthorizationRevision(), context.getAuthorizationRevision())
                || !Objects.equals(reservation.getRuntimeRunId(), context.getRuntimeRunId())
                || !Objects.equals(reservation.getRuntimeStepId(), context.getRuntimeStepId())
                || !Objects.equals(reservation.getStableStepKey(), context.getStableStepKey())
                || !Objects.equals(reservation.getAttemptNo(), context.getAttemptNo())
                || !Objects.equals(reservation.getLogicalCallKey(), context.getLogicalCallKey())
                || !Objects.equals(reservation.getRequestDigest(), context.getRequestDigest())
                || !Objects.equals(reservation.getQuoteId(), context.getQuoteId())
                || !Objects.equals(reservation.getQuoteDigest(), context.getQuoteDigest())
                || !sameDate(reservation.getQuoteExpiresAt(), context.getQuoteExpiresAt())) {
            fail("预算引用不匹配", context.getReservationId());
        }
    }

    private void requireClaimIdentity(AidBillingAuthorizationReservation reservation,
                                      SubmissionClaimRequest request) {
        if (!Objects.equals(reservation.getAuthorizationId(), request.getAuthorizationId())
                || !Objects.equals(reservation.getAuthorizationRevision(), request.getAuthorizationRevision())
                || !Objects.equals(reservation.getRuntimeRunId(), request.getRuntimeRunId())
                || !Objects.equals(reservation.getRuntimeStepId(), request.getRuntimeStepId())
                || !Objects.equals(reservation.getStableStepKey(), request.getStableStepKey())
                || !Objects.equals(reservation.getAttemptNo(), request.getAttemptNo())
                || !Objects.equals(reservation.getLogicalCallKey(), request.getLogicalCallKey())
                || !Objects.equals(reservation.getMediaTaskId(), request.getMediaTaskId())) {
            fail("预算认领不匹配", reservation.getId());
        }
    }

    private void requireReleaseIdentity(AidBillingAuthorization authorization,
                                        AidBillingAuthorizationReservation reservation,
                                        ReleaseUnsubmittedReservationRequest request) {
        if (!Objects.equals(authorization.getId(), request.getAuthorizationId())
                || !Objects.equals(authorization.getPayerUserId(), request.getPayerUserId())
                || !Objects.equals(authorization.getRuntimeRunId(), request.getRuntimeRunId())
                || authorization.getRevision() == null
                || authorization.getRevision() < request.getAuthorizationRevision()
                || !Objects.equals(reservation.getAuthorizationId(), request.getAuthorizationId())
                || !Objects.equals(reservation.getAuthorizationRevision(), request.getAuthorizationRevision())
                || !Objects.equals(reservation.getRuntimeRunId(), request.getRuntimeRunId())
                || !Objects.equals(reservation.getRuntimeStepId(), request.getRuntimeStepId())
                || !Objects.equals(reservation.getStableStepKey(), request.getStableStepKey())
                || !Objects.equals(reservation.getApprovalId(), request.getApprovalId())
                || !Objects.equals(reservation.getAttemptNo(), request.getAttemptNo())
                || !Objects.equals(runtimeOperationKind(reservation), request.getOperationKind())
                || !Objects.equals(reservation.getLogicalCallKey(), request.getLogicalCallKey())) {
            fail("unsubmitted reservation release identity conflicts", reservation.getId());
        }
    }

    private void requireReleaseEvidence(ReleaseEvidence evidence,
                                        ReleaseUnsubmittedReservationRequest request) {
        if (evidence == null || evidence.payerUserId() == null
                || !Objects.equals(evidence.payerUserId(), request.getPayerUserId())
                || !Objects.equals(evidence.runtimeRunId(), request.getRuntimeRunId())
                || !Objects.equals(evidence.runtimeStepId(), request.getRuntimeStepId())
                || !Objects.equals(evidence.stableStepKey(), request.getStableStepKey())
                || !Objects.equals(evidence.approvalId(), request.getApprovalId())
                || !Objects.equals(evidence.attemptNo(), request.getAttemptNo())
                || !Objects.equals(evidence.operationKind(), request.getOperationKind())
                || !Objects.equals(evidence.logicalCallKey(), request.getLogicalCallKey())
                || StrUtil.isBlank(evidence.bizTaskType()) || evidence.bizTaskId() == null) {
            fail("unsubmitted reservation release evidence conflicts", request.getReservationId());
        }
    }

    private ReservationIdentity reservationIdentity(AidBillingAuthorizationReservation reservation) {
        return new ReservationIdentity(reservation.getId(), reservation.getAuthorizationId(),
                reservation.getAuthorizationRevision(), reservation.getRuntimeRunId(),
                reservation.getRuntimeStepId(), reservation.getStableStepKey(),
                reservation.getApprovalId(), reservation.getAttemptNo(),
                runtimeOperationKind(reservation), reservation.getLogicalCallKey());
    }

    private void requireTerminalIdentity(AidMediaTask task, BillingSnapshot snapshot,
                                         AidBillingAuthorizationReservation reservation) {
        if (!Objects.equals(reservation.getAuthorizationId(), snapshot.getBillingAuthorizationId())
                || !Objects.equals(reservation.getAuthorizationRevision(),
                snapshot.getBillingAuthorizationRevision())
                || !Objects.equals(reservation.getMediaTaskId(), task.getId())
                || !Objects.equals(reservation.getBillingTraceId(), task.getBillingTraceId())
                || !Objects.equals(reservation.getLogicalCallKey(), task.getIdempotencyKey())
                || !Objects.equals(reservation.getRuntimeRunId(), snapshot.getRuntimeRunId())
                || !Objects.equals(reservation.getRuntimeStepId(), snapshot.getRuntimeStepId())
                || !Objects.equals(reservation.getStableStepKey(), snapshot.getStableStepKey())
                || !Objects.equals(reservation.getAttemptNo(), snapshot.getBillingAttemptNo())
                || !Objects.equals(reservation.getRequestDigest(), snapshot.getRequestDigest())
                || !Objects.equals(reservation.getQuoteId(), snapshot.getQuoteId())
                || !Objects.equals(reservation.getQuoteDigest(), snapshot.getQuoteDigest())
                || !sameDate(reservation.getQuoteExpiresAt(), snapshot.getQuoteExpiresAt())) {
            fail("预算结算不匹配", reservation.getId());
        }
    }

    private void requireSnapshotIdentity(AidMediaTask task, BillingSnapshot snapshot) {
        if (!BillingBudgetContext.MODE_RUN_AUTHORIZATION.equals(snapshot.getBudgetMode())
                || snapshot.getBillingAuthorizationId() == null
                || snapshot.getBillingAuthorizationRevision() == null
                || snapshot.getBillingReservationId() == null
                || snapshot.getBillingReservationRevision() == null
                || snapshot.getRuntimeRunId() == null
                || StrUtil.isBlank(snapshot.getStableStepKey())
                || snapshot.getBillingAttemptNo() == null
                || StrUtil.isBlank(snapshot.getLogicalCallKey())
                || StrUtil.isBlank(snapshot.getRequestDigest())
                || StrUtil.isBlank(snapshot.getQuoteId())
                || StrUtil.isBlank(snapshot.getQuoteDigest())
                || snapshot.getQuoteExpiresAt() == null
                || snapshot.getAuthorizedChargeCap() == null
                || task == null || task.getId() == null
                || !Objects.equals(snapshot.getLogicalCallKey(), task.getIdempotencyKey())) {
            fail("预算快照无效", task == null ? null : task.getId());
        }
    }

    private BillingSnapshot budgetSnapshot(AidMediaTask task) {
        if (task == null || StrUtil.isBlank(task.getBillingSnapshotJson())) {
            return null;
        }
        BillingSnapshot snapshot;
        try {
            snapshot = JSONUtil.toBean(task.getBillingSnapshotJson(), BillingSnapshot.class);
        } catch (Exception error) {
            log.error("计费快照解析失败, taskId={}", task.getId(), error);
            throw new ServiceException("计费快照无效");
        }
        if (StrUtil.isBlank(snapshot.getBudgetMode())) {
            return null;
        }
        if (!BillingBudgetContext.MODE_RUN_AUTHORIZATION.equals(snapshot.getBudgetMode())) {
            fail("预算模式无效", task.getId());
        }
        return snapshot;
    }

    private void requireCreateAuthorization(CreateAuthorizationRequest request) {
        if (request == null || request.getPayerUserId() == null || request.getPayerUserId() <= 0
                || request.getRuntimeRunId() == null || request.getRuntimeRunId() <= 0
                || request.getApprovedTotal() == null || request.getApprovedTotal().signum() < 0
                || StrUtil.isBlank(request.getPolicyDigest())) {
            fail("授权参数错误", null);
        }
    }

    private void requireReviseAuthorization(ReviseAuthorizationRequest request) {
        if (request == null || request.getAuthorizationId() == null
                || request.getPayerUserId() == null || request.getExpectedRevision() == null
                || request.getApprovedTotal() == null || request.getApprovedTotal().signum() < 0
                || StrUtil.isBlank(request.getPolicyDigest())) {
            fail("授权参数错误", null);
        }
    }

    private void requireReserveCall(ReserveCallRequest request) {
        if (request == null || request.getAuthorizationId() == null
                || request.getAuthorizationRevision() == null || request.getPayerUserId() == null
                || request.getRuntimeRunId() == null || StrUtil.isBlank(request.getStableStepKey())
                || request.getAttemptNo() == null || request.getAttemptNo() < 0
                || StrUtil.isBlank(request.getOperationKind()) || StrUtil.isBlank(request.getLogicalCallKey())
                || !isSha256(request.getRequestDigest()) || !isQuoteId(request.getQuoteId())
                || !isSha256(request.getQuoteDigest())
                || request.getQuoteExpiresAt() == null
                || request.getQuotedUpperBound() == null || request.getQuotedUpperBound().signum() < 0) {
            fail("预算参数错误", null);
        }
        // Expiration can occur after the caller checked the quote but before reservation admission.
        // Keep the authority check here, before any ledger access, and expose its precise category.
        if (!request.getQuoteExpiresAt().after(new Date())) {
            throw new ServiceException("报价已过期，请重新报价后确认")
                    .setDetailMessage("ASSISTANT_BUDGET_QUOTE_EXPIRED");
        }
    }

    private void requireSubmissionClaim(SubmissionClaimRequest request) {
        if (request == null || request.getAuthorizationId() == null
                || request.getAuthorizationRevision() == null || request.getReservationId() == null
                || request.getExpectedReservationRevision() == null || request.getRuntimeRunId() == null
                || StrUtil.isBlank(request.getStableStepKey()) || request.getAttemptNo() == null
                || StrUtil.isBlank(request.getLogicalCallKey()) || request.getMediaTaskId() == null) {
            fail("认领参数错误", null);
        }
    }

    private void requireReleaseUnsubmitted(ReleaseUnsubmittedReservationRequest request) {
        if (request == null || request.getAuthorizationId() == null
                || request.getAuthorizationRevision() == null || request.getReservationId() == null
                || request.getExpectedReservationRevision() == null || request.getPayerUserId() == null
                || request.getRuntimeRunId() == null || request.getRuntimeStepId() == null
                || StrUtil.isBlank(request.getStableStepKey()) || request.getApprovalId() == null
                || request.getAttemptNo() == null || request.getAttemptNo() < 0
                || StrUtil.isBlank(request.getOperationKind())
                || StrUtil.isBlank(request.getLogicalCallKey())) {
            fail("unsubmitted reservation release request is invalid", null);
        }
    }

    private void requireBudgetContext(BillingBudgetContext context) {
        if (context == null || !context.isRunAuthorizationMode()
                || context.getAuthorizationId() == null || context.getAuthorizationRevision() == null
                || context.getReservationId() == null || context.getRuntimeRunId() == null
                || StrUtil.isBlank(context.getStableStepKey()) || context.getAttemptNo() == null
                || StrUtil.isBlank(context.getLogicalCallKey()) || !isSha256(context.getRequestDigest())
                || !isQuoteId(context.getQuoteId())
                || !isSha256(context.getQuoteDigest()) || context.getQuoteExpiresAt() == null) {
            fail("预算上下文无效", context == null ? null : context.getReservationId());
        }
    }

    private void requireSameAuthorization(AidBillingAuthorization existing,
                                          CreateAuthorizationRequest request,
                                          BigDecimal approvedTotal) {
        if (!Objects.equals(existing.getPayerUserId(), request.getPayerUserId())
                || amount(existing.getApprovedTotal()).compareTo(approvedTotal) != 0
                || !Objects.equals(existing.getPolicyDigest(), request.getPolicyDigest())
                || !sameDate(existing.getExpiresAt(), request.getExpiresAt())) {
            fail("授权请求冲突", existing.getId());
        }
    }

    private void requireSameReservation(AidBillingAuthorizationReservation existing,
                                        ReserveCallRequest request, BigDecimal upperBound, String reservationKind) {
        if (!Objects.equals(existing.getAuthorizationId(), request.getAuthorizationId())
                || !Objects.equals(existing.getAuthorizationRevision(), request.getAuthorizationRevision())
                || !Objects.equals(existing.getRuntimeRunId(), request.getRuntimeRunId())
                || !Objects.equals(existing.getRuntimeStepId(), request.getRuntimeStepId())
                || !Objects.equals(existing.getStableStepKey(), request.getStableStepKey())
                || !Objects.equals(existing.getAttemptNo(), request.getAttemptNo())
                || !Objects.equals(existing.getOperationKind(), reservationKind)
                || !Objects.equals(existing.getRequestDigest(), request.getRequestDigest())
                || !Objects.equals(existing.getQuoteId(), request.getQuoteId())
                || !Objects.equals(existing.getQuoteDigest(), request.getQuoteDigest())
                || !sameDate(existing.getQuoteExpiresAt(), request.getQuoteExpiresAt())
                || amount(existing.getReservedAmount()).compareTo(upperBound) != 0) {
            fail("预算请求冲突", existing.getId());
        }
    }

    private AuthorizationView toAuthorizationView(AidBillingAuthorization source) {
        AuthorizationView view = new AuthorizationView();
        view.setId(source.getId());
        view.setPayerUserId(source.getPayerUserId());
        view.setRuntimeRunId(source.getRuntimeRunId());
        view.setApprovedTotal(amount(source.getApprovedTotal()));
        view.setReservedAmount(amount(source.getReservedAmount()));
        view.setSettledAmount(amount(source.getSettledAmount()));
        view.setAvailableAmount(amount(source.getApprovedTotal())
                .subtract(amount(source.getReservedAmount()))
                .subtract(amount(source.getSettledAmount())).max(ZERO));
        view.setCurrencyUnit(source.getCurrencyUnit());
        view.setPrecisionScale(source.getPrecisionScale());
        view.setStatus(source.getStatus());
        view.setRevision(source.getRevision());
        view.setStateRevision(source.getStateRevision());
        view.setPolicyDigest(source.getPolicyDigest());
        view.setExpiresAt(source.getExpiresAt());
        view.setCreateTime(source.getCreateTime());
        view.setUpdateTime(source.getUpdateTime());
        return view;
    }

    private ReservationView toReservationView(AidBillingAuthorizationReservation source) {
        ReservationView view = new ReservationView();
        view.setId(source.getId());
        view.setAuthorizationId(source.getAuthorizationId());
        view.setAuthorizationRevision(source.getAuthorizationRevision());
        view.setRuntimeRunId(source.getRuntimeRunId());
        view.setRuntimeStepId(source.getRuntimeStepId());
        view.setStableStepKey(source.getStableStepKey());
        view.setApprovalId(source.getApprovalId());
        view.setAttemptNo(source.getAttemptNo());
        view.setOperationKind(runtimeOperationKind(source));
        view.setLogicalCallKey(source.getLogicalCallKey());
        view.setRequestDigest(source.getRequestDigest());
        view.setQuoteId(source.getQuoteId());
        view.setQuoteDigest(source.getQuoteDigest());
        view.setQuoteExpiresAt(source.getQuoteExpiresAt());
        view.setMediaTaskId(source.getMediaTaskId());
        view.setBillingTraceId(source.getBillingTraceId());
        view.setQuotedUpperBound(amount(source.getQuotedUpperBound()));
        view.setReservedAmount(amount(source.getReservedAmount()));
        view.setWalletFrozenAmount(amount(source.getWalletFrozenAmount()));
        view.setSettledAmount(amount(source.getSettledAmount()));
        view.setReleasedAmount(amount(source.getReleasedAmount()));
        view.setWalletRefundedAmount(amount(source.getWalletRefundedAmount()));
        view.setState(source.getState());
        view.setSettlementKey(source.getSettlementKey());
        view.setRevision(source.getRevision());
        view.setCreateTime(source.getCreateTime());
        view.setUpdateTime(source.getUpdateTime());
        return view;
    }

    private BigDecimal normalizeApprovedTotal(BigDecimal amount) {
        return amount.setScale(BillingConstants.ACCOUNT_AMOUNT_SCALE, RoundingMode.DOWN);
    }

    private BigDecimal normalizeUpperBound(BigDecimal amount) {
        return amount.setScale(BillingConstants.ACCOUNT_AMOUNT_SCALE, RoundingMode.CEILING);
    }

    private BigDecimal amount(BigDecimal value) {
        return value == null ? ZERO : BillingConstants.normalizeAccountAmount(value);
    }

    private boolean sameAmount(BigDecimal left, BigDecimal right) {
        return amount(left).compareTo(amount(right)) == 0;
    }

    private boolean isQuoteId(String value) {
        return value != null && value.matches("q_[0-9a-f]{32}");
    }

    private long nextRevision(Long revision) {
        return revision == null ? 1L : revision + 1L;
    }

    private String operator(String value) {
        return StrUtil.blankToDefault(value, SYSTEM_OPERATOR);
    }

    private void touch(AidBillingAuthorization authorization, String operator) {
        authorization.setUpdateBy(operator(operator));
        authorization.setUpdateTime(new Date());
    }

    private void touch(AidBillingAuthorizationReservation reservation, String operator) {
        reservation.setUpdateBy(operator(operator));
        reservation.setUpdateTime(new Date());
    }

    private boolean sameDate(Date left, Date right) {
        return left == null && right == null
                || left != null && right != null
                && left.getTime() / 1000L == right.getTime() / 1000L;
    }

    /** MySQL 5.7 DATETIME(0) rounds fractional seconds; persist the canonical precision explicitly. */
    private Date truncateToSecond(Date value) {
        return value == null ? null : new Date(value.getTime() / 1000L * 1000L);
    }

    private boolean isSha256(String value) {
        return value != null && value.length() == 64
                && value.chars().allMatch(character -> character >= '0' && character <= '9'
                || character >= 'a' && character <= 'f');
    }

    private void fail(String message, Long id) {
        log.error("Run预算校验失败, message={}, id={}", message, id);
        throw new ServiceException(message);
    }
}
