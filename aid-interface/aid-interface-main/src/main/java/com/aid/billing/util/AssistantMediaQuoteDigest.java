package com.aid.billing.util;

import cn.hutool.crypto.digest.DigestUtil;
import com.aid.billing.dto.BillingCalcResult;
import com.aid.billing.dto.BillingInput;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.util.MediaTaskPayloadSanitizer;
import com.aid.media.service.MediaPreparationErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Date;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** 助手媒体报价与正式预冻结共同使用的规范摘要。 */
public final class AssistantMediaQuoteDigest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final BigDecimal UPPER_BOUND_GUARD = new BigDecimal("0.0001");

    private AssistantMediaQuoteDigest() {
    }

    public record QuoteIdentity(String quoteId, Date expiresAt) { }

    /** Shared budget IDs and database-second precision; no request, wallet or provider side effect. */
    public static QuoteIdentity newQuoteIdentity(long ttlMillis) {
        if (ttlMillis < 1000L) throw new IllegalArgumentException("Quote lifetime must be at least one second");
        long expires = Math.addExact(System.currentTimeMillis(), ttlMillis);
        return new QuoteIdentity("q_" + cn.hutool.core.util.IdUtil.fastSimpleUUID(), new Date(expires / 1000L * 1000L));
    }

    public static String requestDigest(Object normalizedRequest) {
        try {
            // The existing bean serializer does not expose Java record components. Normalize a
            // record to a JSON object first, then retain the same binary-content guard and digest.
            Object serializable = normalizedRequest != null && normalizedRequest.getClass().isRecord()
                    ? com.alibaba.fastjson2.JSON.parseObject(com.alibaba.fastjson2.JSON.toJSONString(normalizedRequest))
                    : normalizedRequest;
            return requestDigestFromJson(MediaTaskPayloadSanitizer.serializeRequest(serializable));
        } catch (com.aid.common.exception.ServiceException invalid) {
            if (MediaPreparationErrorCode.read(invalid.getDetailMessage()) != null) throw invalid;
            throw MediaPreparationErrorCode.MEDIA_INPUTS_INVALID.reject("报价请求无效");
        } catch (RuntimeException invalid) {
            throw MediaPreparationErrorCode.MEDIA_INPUTS_INVALID.reject("报价请求无效");
        }
    }

    public static String requestDigestFromJson(String requestJson) {
        try {
            JsonNode payload = MAPPER.readTree(requestJson);
            if (payload instanceof ObjectNode objectNode) {
                objectNode.remove("billingBudgetContext");
                objectNode.remove(MediaTaskPayloadSanitizer.DELIVERY_AUTHORIZED_IMAGES);
            }
            return DigestUtil.sha256Hex(MAPPER.writeValueAsString(canonical(payload)));
        } catch (Exception error) {
            throw MediaPreparationErrorCode.QUOTE_SNAPSHOT_INVALID.reject("请求摘要生成失败");
        }
    }

    public static String quoteDigest(String quoteId, String requestDigest, Date expiresAt,
                                     AiModelConfigVo modelConfig, BillingInput billingInput,
                                     BillingCalcResult calculation) {
        try {
            Map<String, Object> envelope = new TreeMap<>();
            envelope.put("quoteId", quoteId);
            envelope.put("requestDigest", requestDigest);
            envelope.put("expiresAt", expiresAt == null ? null : expiresAt.getTime());
            envelope.put("modelId", modelConfig.getId());
            envelope.put("modelCode", modelConfig.getModelCode());
            envelope.put("realModelCode", modelConfig.getRealModelCode());
            envelope.put("modelConfigVersion", modelConfig.getConfigVersion());
            envelope.put("providerId", modelConfig.getProviderId());
            envelope.put("credentialVersion", modelConfig.getCredentialVersion());
            envelope.put("capabilityCode", modelConfig.getCapabilityCode());
            envelope.put("bindingCode", modelConfig.getBindingCode());
            envelope.put("billingMode", modelConfig.getBillingMode());
            envelope.put("billingVersion", modelConfig.getBillingVersion());
            envelope.put("billingRuleJson", modelConfig.getBillingRuleJson());
            envelope.put("costCredits", modelConfig.getCostCredits());
            envelope.put("billingMultiplier", modelConfig.getBillingMultiplier());
            envelope.put("isFree", modelConfig.getIsFree());
            envelope.put("billingMediaType", billingInput.getMediaType());
            envelope.put("billingParams", billingInput.getParams());
            envelope.put("inputMetadataPending", billingInput.isInputMetadataPending());
            envelope.put("matched", calculation != null && calculation.isMatched());
            envelope.put("skuCode", calculation == null ? null : calculation.getSkuCode());
            envelope.put("amount", calculation == null ? null : calculation.getAmount());
            envelope.put("quotedUpperBound", calculation == null || calculation.getAmount() == null
                    ? null : quotedUpperBound(calculation.getAmount()));
            envelope.put("snapshot", calculation == null ? null : calculation.getSnapshot());
            JsonNode tree = MAPPER.valueToTree(envelope);
            return DigestUtil.sha256Hex(MAPPER.writeValueAsString(canonical(tree)));
        } catch (Exception error) {
            throw MediaPreparationErrorCode.QUOTE_SNAPSHOT_INVALID.reject("报价摘要生成失败");
        }
    }

    /** 既有计算链先按账户四位精度归一化，额外保留一个最小单位作为严格上界保护。 */
    public static BigDecimal quotedUpperBound(BigDecimal calculatedAmount) {
        if (calculatedAmount == null) {
            return null;
        }
        BigDecimal guarded = calculatedAmount.signum() > 0
                ? calculatedAmount.add(UPPER_BOUND_GUARD) : calculatedAmount;
        return guarded.setScale(4, RoundingMode.CEILING);
    }

    private static JsonNode canonical(JsonNode source) {
        if (source == null || source.isNull() || source.isValueNode()) {
            return source;
        }
        if (source.isArray()) {
            ArrayNode target = MAPPER.createArrayNode();
            source.forEach(item -> target.add(canonical(item)));
            return target;
        }
        ObjectNode target = MAPPER.createObjectNode();
        TreeMap<String, JsonNode> sorted = new TreeMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = source.fields();
        fields.forEachRemaining(field -> sorted.put(field.getKey(), field.getValue()));
        sorted.forEach((name, value) -> target.set(name, canonical(value)));
        return target;
    }
}
