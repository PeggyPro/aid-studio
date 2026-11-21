package com.aid.media.provider;

import com.aid.common.error.TaskErrorCode;
import com.aid.common.error.TaskErrorPresentation;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.service.MediaPreparationErrorCode;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

/** One model/route budget for every text entry. Limits are metadata, never model-name rules. */
public final class TextContextBudget {
    private TextContextBudget() { }
    public record Limits(int context, int input, int output, int utilizationPercent) {
        public long usableContext() { return (long) context * utilizationPercent / 100; }
    }

    public static Limits limits(AiModelConfigVo model) {
        JSONObject cap;
        try { cap = model == null || model.getCapabilityJson() == null
                ? new JSONObject() : JSON.parseObject(model.getCapabilityJson()); }
        catch (RuntimeException invalid) { throw MediaPreparationErrorCode.MODEL_PARAMETERS_INVALID.reject("模型上下文能力配置无效"); }
        if (cap == null) cap = new JSONObject();
        int percent = number(cap, "contextUtilizationPercent");
        if (percent == 0) percent = 90;
        if (percent < 1 || percent > 90)
            throw MediaPreparationErrorCode.MODEL_PARAMETERS_INVALID.reject("上下文使用比例须在1至90之间");
        return new Limits(smaller(number(cap, "contextWindowTokens"), number(cap, "channelContextWindowTokens")),
                smaller(number(cap, "maxInputTokens"), number(cap, "channelMaxInputTokens")),
                smaller(number(cap, "maxOutputTokens"), number(cap, "channelMaxOutputTokens")), percent);
    }

    public static void validate(int input, int conservativeBytes, int output, Limits limits) {
        if (limits.context() == 0)
            throw MediaPreparationErrorCode.MODEL_PARAMETERS_INVALID.reject(
                    "文本模型未配置上下文上限，请先完善模型能力配置");
        boolean tooLarge = input > TextOutputLimitResolver.ABSOLUTE_INPUT_TOKENS
                || conservativeBytes > 16 * 1024 * 1024
                || limits.input() > 0 && input > limits.input()
                || limits.context() > 0 && (long) input + output > limits.usableContext();
        if (!tooLarge) return;
        // Counts and stable limits only: preserve the actual local rejection without logging prompts.
        throw TaskErrorPresentation.fromCode(TaskErrorCode.USER_INPUT_TOO_LONG,
                "上下文超过当前模型预算，请分段处理或选择更大上下文的模型")
                .setDetailMessage(JSONObject.of("reason", "TEXT_CONTEXT_BUDGET_EXCEEDED",
                        "estimatedInputTokens", input, "reservedOutputTokens", output,
                        "contextWindowTokens", limits.context(), "maxInputTokens", limits.input(),
                        "usableContextTokens", limits.usableContext(),
                        "utilizationPercent", limits.utilizationPercent()).toJSONString());
    }

    private static int number(JSONObject cap, String key) {
        if (!cap.containsKey(key)) return 0;
        Object value = cap.get(key);
        if (value == null || value instanceof Number number && number.doubleValue() == 0D) return 0;
        if (!(value instanceof Number number) || number.longValue() < 1
                || number.longValue() > Integer.MAX_VALUE || number.doubleValue() != number.longValue())
            throw MediaPreparationErrorCode.MODEL_PARAMETERS_INVALID.reject("模型上下文能力配置无效");
        return number.intValue();
    }
    private static int smaller(int left, int right) { return left == 0 ? right : right == 0 ? left : Math.min(left, right); }
}
