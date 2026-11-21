package com.aid.compose.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.billing.dto.BillingBudgetContext;
import com.aid.billing.dto.BillingCalcResult;
import com.aid.billing.model.BillingSnapshot;
import com.aid.billing.service.BillingFacadeService;
import com.aid.billing.util.AssistantMediaQuoteDigest;
import com.aid.common.exception.ServiceException;
import com.aid.compose.domain.ComposeBillingSnapshot;
import com.aid.compose.domain.ComposeCommand;
import com.aid.media.admission.MediaTaskIntentAdmissionGate;
import com.aid.media.service.IMediaGenerationService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.util.Date;
import java.util.Objects;

/** Server-only adapter: existing compose planning and prices enter the shared Runtime wallet path. */
@Service
@RequiredArgsConstructor
public class RuntimeComposeTaskService {
    private final CoreComposeService compose;
    private final AidMediaTaskMapper tasks;
    private final BillingFacadeService billing;
    private final MediaTaskIntentAdmissionGate admission;
    private final IMediaGenerationService media;
    private final TransactionTemplate transactions;

    public record Preparation(Long taskId, ComposeCommand command, String engineCode,
                              String requestJson, ComposeBillingSnapshot pricing) { }
    public record Quote(Preparation preparation, String requestDigest, String quoteId, String quoteDigest,
                        Date expiresAt, BigDecimal upperBound) { }

    public Quote prepare(ComposeCommand command) {
        var identity = AssistantMediaQuoteDigest.newQuoteIdentity(300_000L);
        return quote(prepare(command, IdWorker.getId()), identity.quoteId(), identity.expiresAt());
    }

    public Quote reprice(Preparation original) {
        if (original == null) throw new ServiceException("合成准备快照缺失");
        var identity = AssistantMediaQuoteDigest.newQuoteIdentity(300_000L);
        return quote(prepare(original.command(), original.taskId()), identity.quoteId(), identity.expiresAt());
    }

    private Preparation prepare(ComposeCommand command, Long taskId) {
        AidMediaTask task = compose.prepare(command, taskId);
        return new Preparation(taskId, JSON.parseObject(JSON.toJSONString(command), ComposeCommand.class), task.getModelName(),
                task.getRequestJson(), JSON.parseObject(task.getBillingSnapshotJson(), ComposeBillingSnapshot.class));
    }

    public static Quote quote(Preparation prepared, String quoteId, Date expiresAt) {
        String requestDigest = AssistantMediaQuoteDigest.requestDigest(prepared);
        BigDecimal upper = AssistantMediaQuoteDigest.quotedUpperBound(prepared.pricing().getFrozenCredits());
        JSONObject identity = new JSONObject();
        identity.put("requestDigest", requestDigest);identity.put("quoteId", quoteId);
        identity.put("expiresAt", expiresAt == null ? null : expiresAt.getTime());identity.put("upperBound", upper);
        return new Quote(prepared, requestDigest, quoteId, AssistantMediaQuoteDigest.requestDigest(identity), expiresAt, upper);
    }

    /** Validation and repricing are read-only. Replays use the original durable task, never new work. */
    public void validate(Quote quote) {
        requireQuoteIdentity(quote);
        Preparation current = prepare(quote.preparation().command(), quote.preparation().taskId());
        if (!Objects.equals(AssistantMediaQuoteDigest.requestDigest(current), quote.requestDigest())) {
            throw new ServiceException("合成素材、引擎或计价已变化，请重新报价");
        }
    }

    public AidMediaTask submit(Quote quote, BillingBudgetContext context, String bizTaskType) {
        requireQuoteIdentity(quote);
        if (context == null || !context.isRunAuthorizationMode()
                || !BillingBudgetContext.isBudgetManagedBizTaskType(bizTaskType)
                || !Objects.equals(context.getRequestDigest(), quote.requestDigest())
                || !Objects.equals(context.getQuoteId(), quote.quoteId())
                || !Objects.equals(context.getQuoteDigest(), quote.quoteDigest())
                || !Objects.equals(context.getQuoteExpiresAt(), quote.expiresAt())) {
            throw new ServiceException("合成预算身份不匹配");
        }
        AidMediaTask existing = existing(context.getLogicalCallKey());
        if (existing != null) return replay(existing, quote, context, bizTaskType);
        if (!quote.expiresAt().after(new Date())) throw new ServiceException("合成报价已过期");
        AidMediaTask task = compose.prepare(quote.preparation().command(), quote.preparation().taskId());
        Preparation current = new Preparation(task.getId(), quote.preparation().command(), task.getModelName(),
                task.getRequestJson(), JSON.parseObject(task.getBillingSnapshotJson(), ComposeBillingSnapshot.class));
        if (!Objects.equals(AssistantMediaQuoteDigest.requestDigest(current), quote.requestDigest())) {
            throw new ServiceException("合成素材、引擎或计价已变化，请重新报价");
        }
        task.setRequestHash(quote.requestDigest());task.setIdempotencyKey(context.getLogicalCallKey());
        task.setBizTaskType(bizTaskType);task.setBizTaskId(context.getRuntimeStepId());
        TransactionTemplate tx = new TransactionTemplate(transactions.getTransactionManager());
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try {
            tx.executeWithoutResult(status -> {
                tasks.insert(task);
                admission.claimNewBeforeCommit(task, context);
                billing.prepareServiceBilling(task, calculation(quote), context);
                if (tasks.updateById(task) != 1) throw new ServiceException("合成任务保存失败");
            });
        } catch (DuplicateKeyException conflict) {
            AidMediaTask winner = existing(context.getLogicalCallKey());
            if (winner == null) throw conflict;
            return replay(winner, quote, context, bizTaskType);
        }
        media.submitComposeTaskAsync(task.getId());
        return task;
    }

    private AidMediaTask existing(String key) {
        if (key == null || key.isBlank()) throw new ServiceException("合成调用标识缺失");
        return tasks.selectOne(new LambdaQueryWrapper<AidMediaTask>().eq(AidMediaTask::getIdempotencyKey, key));
    }

    private AidMediaTask replay(AidMediaTask task, Quote quote, BillingBudgetContext context, String type) {
        if (!Objects.equals(task.getId(), quote.preparation().taskId())
                || !Objects.equals(task.getUserId(), quote.preparation().command().getUserId())
                || !Objects.equals(task.getProjectId(), quote.preparation().command().getProjectId())
                || !Objects.equals(task.getBizTaskType(), type) || !Objects.equals(task.getBizTaskId(), context.getRuntimeStepId())
                || !"COMPOSE".equals(task.getMediaType()) || !Objects.equals(task.getRequestHash(), quote.requestDigest())) {
            throw new ServiceException("合成幂等键冲突");
        }
        return task;
    }

    private static void requireQuoteIdentity(Quote quote) {
        if (quote == null || quote.preparation() == null || quote.preparation().command() == null
                || quote.preparation().pricing() == null || quote.expiresAt() == null || quote.quoteId() == null
                || !quote.quoteId().matches("q_[0-9a-f]{32}") || quote.expiresAt().getTime() % 1000L != 0L) throw new ServiceException("合成报价缺失");
        Quote expected = quote(quote.preparation(), quote.quoteId(), quote.expiresAt());
        if (!Objects.equals(expected.requestDigest(), quote.requestDigest()) || !Objects.equals(expected.quoteDigest(), quote.quoteDigest())
                || quote.upperBound() == null || expected.upperBound().compareTo(quote.upperBound()) != 0) {
            throw new ServiceException("合成报价摘要不匹配");
        }
    }

    public static BillingCalcResult calculation(Quote quote) {
        ComposeBillingSnapshot price = quote.preparation().pricing();
        BillingSnapshot snapshot = new BillingSnapshot();
        snapshot.setModelName(quote.preparation().engineCode());snapshot.setModelType("VIDEO");
        snapshot.setMeterType("PER_MINUTE");snapshot.setBillingMode("SERVICE_QUOTE");
        snapshot.setUnitPrice(price.getUnitPriceYuan());
        snapshot.setFinalBillingMultiplier(BigDecimal.valueOf(price.getCreditRate()).multiply(price.getProfitMultiplier()));
        snapshot.setExpectedDurationSeconds(Math.toIntExact(price.getEstimatedSeconds()));
        snapshot.setPreHoldAmount(price.getFrozenCredits());snapshot.setRequestDigest(quote.requestDigest());
        snapshot.setQuoteId(quote.quoteId());snapshot.setQuoteDigest(quote.quoteDigest());
        snapshot.setQuoteExpiresAt(quote.expiresAt());snapshot.setAuthorizedChargeCap(quote.upperBound());
        return BillingCalcResult.fixed(price.getFrozenCredits(), snapshot);
    }
}
