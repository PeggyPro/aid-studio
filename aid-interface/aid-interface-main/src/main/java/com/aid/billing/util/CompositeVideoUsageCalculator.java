package com.aid.billing.util;

import com.aid.billing.model.BillingSnapshot;
import com.aid.billing.model.CompositeVideoTokenPricing;
import com.aid.common.exception.ServiceException;
import java.math.BigDecimal;
import java.util.Map;

/** Arithmetic on one frozen SKU. Wallet effects remain in the existing billing facade. */
public final class CompositeVideoUsageCalculator {
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);
    private CompositeVideoUsageCalculator() { }

    public static BigDecimal reserve(CompositeVideoTokenPricing pricing, Map<String, Object> params, BillingSnapshot snapshot) {
        if (pricing == null) return BigDecimal.ZERO;
        if (java.util.stream.Stream.of(pricing.getInputPricePerMillion(), pricing.getTextOutputPricePerMillion(),
                pricing.getReasoningPricePerMillion(), pricing.getVideoOutputPricePerMillion()).anyMatch(value -> value == null || value.signum() < 0)
                || pricing.getEstimatedTextOutputTokens() == null || pricing.getEstimatedTextOutputTokens() < 0
                || pricing.getEstimatedReasoningTokens() == null || pricing.getEstimatedReasoningTokens() < 0
                || pricing.getVideoTokensPerSecond() == null || pricing.getVideoTokensPerSecond().signum() <= 0)
            throw new ServiceException("复合视频计费配置不完整");
        snapshot.setCompositeVideoTokens(pricing);
        // Character count is a conservative text-only estimate, never fabricated provider usage.
        BigDecimal input = cost(number(params, "inputChars", BigDecimal.ZERO), pricing.getInputPricePerMillion());
        BigDecimal output = cost(BigDecimal.valueOf(pricing.getEstimatedTextOutputTokens()), pricing.getTextOutputPricePerMillion())
                .add(cost(BigDecimal.valueOf(pricing.getEstimatedReasoningTokens()), pricing.getReasoningPricePerMillion()));
        snapshot.setEstimatedCompositeInputAmount(input);
        snapshot.setEstimatedCompositeOutputAmount(output);
        snapshot.setCompositeUsageSource("ESTIMATED");
        return input.add(output);
    }

    public static boolean hasObservedUsage(Map<String, Object> usage) {
        return usage != null && (usage.containsKey("input_tokens") || usage.containsKey("video_output_tokens")
                || usage.containsKey("text_output_tokens") || usage.containsKey("reasoning_tokens"));
    }

    public static boolean usesCompositePricing(String snapshotJson) {
        if (snapshotJson == null || snapshotJson.isBlank()) return false;
        BillingSnapshot snapshot = com.alibaba.fastjson2.JSON.parseObject(snapshotJson, BillingSnapshot.class);
        return snapshot != null && snapshot.getCompositeVideoTokens() != null;
    }

    /** Actual total input tokens replace estimated image/video fees; output modalities never overlap. */
    public static BigDecimal settle(BillingSnapshot snapshot, Map<String, Object> usage) {
        CompositeVideoTokenPricing pricing = snapshot.getCompositeVideoTokens();
        snapshot.setCompositeProviderUsage(usage);
        boolean failed = Boolean.TRUE.equals(usage.get("generation_failed"));
        boolean hasInput = usage.containsKey("input_tokens");
        boolean hasVideo = usage.containsKey("video_output_tokens");
        if (hasVideo) {
            BigDecimal billedSeconds = number(usage, "video_output_tokens", BigDecimal.ZERO)
                    .divide(pricing.getVideoTokensPerSecond(), 12, java.math.RoundingMode.HALF_UP);
            snapshot.setOfficialVideoBillingSeconds(billedSeconds);
            snapshot.setActualDurationExactSeconds(billedSeconds);
            snapshot.setActualDurationSeconds(billedSeconds.setScale(0, java.math.RoundingMode.CEILING).intValueExact());
        }
        BigDecimal input = hasInput ? cost(number(usage, "input_tokens", BigDecimal.ZERO), pricing.getInputPricePerMillion())
                : failed ? BigDecimal.ZERO : zero(snapshot.getEstimatedCompositeInputAmount())
                    .add(zero(snapshot.getInputImageAmount())).add(zero(snapshot.getInputVideoAmount()));
        BigDecimal video = hasVideo ? cost(number(usage, "video_output_tokens", BigDecimal.ZERO), pricing.getVideoOutputPricePerMillion())
                : failed ? BigDecimal.ZERO : snapshot.getPricePerSecond().multiply(BigDecimal.valueOf(snapshot.getExpectedDurationSeconds()));
        BigDecimal text = !failed && !usage.containsKey("text_output_tokens")
                ? cost(BigDecimal.valueOf(pricing.getEstimatedTextOutputTokens()), pricing.getTextOutputPricePerMillion())
                : cost(number(usage, "text_output_tokens", BigDecimal.ZERO), pricing.getTextOutputPricePerMillion());
        BigDecimal reasoning = !failed && !usage.containsKey("reasoning_tokens")
                ? cost(BigDecimal.valueOf(pricing.getEstimatedReasoningTokens()), pricing.getReasoningPricePerMillion())
                : cost(number(usage, "reasoning_tokens", BigDecimal.ZERO), pricing.getReasoningPricePerMillion());
        snapshot.setCompositeInputAmount(input);
        snapshot.setCompositeVideoAmount(video);
        snapshot.setCompositeTextAmount(text);
        snapshot.setCompositeReasoningAmount(reasoning);
        snapshot.setCompositeUsageSource(hasInput && hasVideo
                && (pricing.getTextOutputPricePerMillion().signum() == 0 || usage.containsKey("text_output_tokens"))
                && (pricing.getReasoningPricePerMillion().signum() == 0 || usage.containsKey("reasoning_tokens"))
                && !Boolean.FALSE.equals(usage.get("output_usage_complete")) ? "PROVIDER_USAGE" : "PARTIAL_PROVIDER_USAGE");
        return input.add(video).add(text).add(reasoning);
    }

    public static void captureMediaDuration(BillingSnapshot snapshot, Map<String, Object> usage) {
        if (usage != null && usage.containsKey("media_duration_ms"))
            snapshot.setActualMediaDurationMs(number(usage, "media_duration_ms", BigDecimal.ZERO).longValueExact());
    }

    private static BigDecimal cost(BigDecimal tokens, BigDecimal price) { return tokens.multiply(price).divide(MILLION); }
    private static BigDecimal zero(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
    private static BigDecimal number(Map<String, Object> values, String key, BigDecimal missing) {
        Object value = values.get(key);
        if (value == null) return missing;
        try {
            BigDecimal number = new BigDecimal(value.toString());
            if (number.signum() < 0 || number.stripTrailingZeros().scale() > 0) throw new NumberFormatException();
            return number;
        } catch (NumberFormatException invalid) { throw new ServiceException("上游 Token 用量无效"); }
    }
}
