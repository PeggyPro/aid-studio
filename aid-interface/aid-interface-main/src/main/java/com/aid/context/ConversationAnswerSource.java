package com.aid.context;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

/** Exact historical question/answer source shared by memory and bounded recall. */
public final class ConversationAnswerSource {
    private ConversationAnswerSource() { }

    /** Rendering labels belong in a separate projection; they must not change source identity. */
    public static String serialize(String historicalContextDigest, String questionBundleJson,
                                   String acceptedAnswersJson) {
        JSONObject source = new JSONObject();
        source.put("historicalContextDigest", historicalContextDigest);
        appendAnswers(source, questionBundleJson, acceptedAnswersJson);
        return source.toJSONString();
    }

    /** Sources without a runtime-context envelope keep their existing two-field wire format. */
    public static String serialize(String questionBundleJson, String acceptedAnswersJson) {
        JSONObject source = new JSONObject();
        appendAnswers(source, questionBundleJson, acceptedAnswersJson);
        return source.toJSONString();
    }

    private static void appendAnswers(JSONObject source, String questionBundleJson,
                                      String acceptedAnswersJson) {
        if (questionBundleJson == null || acceptedAnswersJson == null)
            throw new IllegalArgumentException("CONVERSATION_ANSWER_SOURCE_UNAVAILABLE");
        source.put("questions", JSON.parse(questionBundleJson));
        source.put("acceptedAnswers", JSON.parse(acceptedAnswersJson));
    }
}
