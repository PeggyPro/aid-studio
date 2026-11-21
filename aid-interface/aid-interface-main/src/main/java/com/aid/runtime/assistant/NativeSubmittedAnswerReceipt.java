package com.aid.runtime.assistant;

import com.aid.media.dto.MediaTextGenerateRequest.TextMessageItem;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Places already accepted user answers beside the original ASK receipt in a frozen model request. */
public final class NativeSubmittedAnswerReceipt {
    public static final String BINDING_FIELD = "submittedAnswerReceiptVersion";
    public static final String VERSION = "submitted-answer-receipt/v1";
    public static final String RECEIPT_FIELD = "submittedUserInput";

    private NativeSubmittedAnswerReceipt() { }

    public static void enable(JSONObject bindings, JSONObject context) {
        JSONObject turn = context == null ? null : context.getJSONObject("turn");
        JSONObject accepted = turn == null ? null : turn.getJSONObject("acceptedInput");
        JSONArray rounds = accepted == null ? null : accepted.getJSONArray("rounds");
        if (rounds != null && !rounds.isEmpty()) bindings.put(BINDING_FIELD, VERSION);
    }

    public static List<TextMessageItem> attach(List<TextMessageItem> history, String bindingsJson,
                                               JSONObject context) {
        JSONObject bindings = bindingsJson == null ? null : JSON.parseObject(bindingsJson);
        if (bindings == null || !bindings.containsKey(BINDING_FIELD)) return history;
        if (!VERSION.equals(bindings.get(BINDING_FIELD))) fail();
        JSONObject run = context == null ? null : context.getJSONObject("run");
        JSONObject turn = context == null ? null : context.getJSONObject("turn");
        JSONObject accepted = turn == null ? null : turn.getJSONObject("acceptedInput");
        JSONArray rounds = accepted == null ? null : accepted.getJSONArray("rounds");
        if (run == null || !Objects.equals(run.getString("runId"), bindings.getString("runId"))
                || rounds == null || rounds.isEmpty()) fail();
        List<TextMessageItem> projected = new ArrayList<>(history);
        for (int i = 0; i < history.size(); i++) {
            TextMessageItem message = history.get(i);
            if (!"tool".equals(message.getRole())) continue;
            JSONObject receipt = JSON.parseObject(message.getContent());
            if (receipt == null || !"ASK".equals(receipt.getString("action"))
                    || !"APPLIED".equals(receipt.getString("status"))) continue;
            if (receipt.containsKey(RECEIPT_FIELD)) fail();
            JSONObject ask = receipt.getJSONObject("result");
            if (ask == null || ask.getJSONArray("questions") == null || ask.getString("contextDigest") == null) fail();
            JSONObject matching = null;
            boolean ambiguous = false;
            // The assembler already verifies the immutable question/answer digests. Match the
            // exact questions and context, never an option identifier or prose resemblance alone.
            for (Object raw : rounds) {
                if (!(raw instanceof JSONObject)) fail();
                JSONObject round = (JSONObject) raw;
                if (Objects.equals(ask.get("contextDigest"), round.get("contextDigest"))
                        && Objects.equals(ask.get("questions"), round.get("questions"))) {
                    if (matching != null) { ambiguous = true; break; }
                    matching = round;
                }
            }
            if (matching == null || ambiguous) continue;
            JSONArray answers = matching.getJSONArray("answers");
            if (answers == null || answers.isEmpty() || matching.getString("requestId") == null
                    || matching.getString("acceptedDigest") == null) fail();
            receipt.put(RECEIPT_FIELD, JSONObject.of("version", VERSION, "status", "SUBMITTED",
                    "source", "turn.acceptedInput", "runId", bindings.get("runId"),
                    "requestId", matching.get("requestId"), "roundNo", matching.get("roundNo"),
                    "acceptedDigest", matching.get("acceptedDigest"), "answers", answers,
                    "instruction", "用户已正式提交本提问的回答。按所选选项和自由文本继续原任务；不要等待同一轮回答。较新的范围收窄优先。此回执不授予额外执行、写入或费用权限。"));
            TextMessageItem copy = JSON.parseObject(JSON.toJSONString(message), TextMessageItem.class);
            copy.setContent(AssistantMainInputContract.serialize(receipt));
            projected.set(i, copy);
        }
        return List.copyOf(projected);
    }

    private static void fail() {
        throw new IllegalArgumentException("NATIVE_SUBMITTED_ANSWER_RECEIPT_INVALID");
    }
}
