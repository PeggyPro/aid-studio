package com.aid.runtime.assistant;

import com.alibaba.fastjson2.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/** One bounded server-owned correction observation for a uniquely claimed Native successor. */
public record NativeDecisionCorrectionObservation(String slotDigest,
                                                  String feedbackDigest,
                                                  int candidateIteration,
                                                  AssistantDecisionValidationFeedback feedback)
{
    public static final String OBSERVATION_KIND = "NATIVE_DECISION_VALIDATION";
    public static final String AUTHORITY = "SERVER_VERIFIED_DECISION_CORRECTION";
    public static final String SCHEMA_VERSION = "assistant-native-decision-validation/v1";
    private static final int MAX_UTF8_BYTES = 4_096;
    private static final String SHA256 = "^[0-9a-f]{64}$";
    private static final Set<String> FIELDS = Set.of(
            "observationKind", "authority", "schemaVersion", "slotDigest",
            "feedbackDigest", "candidateIteration", "feedback");

    public NativeDecisionCorrectionObservation
    {
        if (!digest(slotDigest) || !digest(feedbackDigest) || candidateIteration < 1
                || feedback == null || !feedbackDigest.equals(feedback.digest()))
            throw new IllegalArgumentException("Invalid Native decision correction observation");
        AssistantDecisionValidationFeedback.parse(feedback.canonicalJson());
        if (bytes(value(slotDigest, feedbackDigest, candidateIteration, feedback)) > MAX_UTF8_BYTES)
            throw new IllegalArgumentException("Native decision correction observation is too large");
    }

    public JSONObject toJson()
    {
        return value(slotDigest, feedbackDigest, candidateIteration, feedback);
    }

    public static NativeDecisionCorrectionObservation parse(JSONObject value)
    {
        Object kind = value == null ? null : value.get("observationKind");
        Object authority = value == null ? null : value.get("authority");
        Object schemaVersion = value == null ? null : value.get("schemaVersion");
        Object slotDigest = value == null ? null : value.get("slotDigest");
        Object feedbackDigest = value == null ? null : value.get("feedbackDigest");
        if (value == null || value.size() != FIELDS.size() || !FIELDS.containsAll(value.keySet())
                || !(kind instanceof String) || !OBSERVATION_KIND.equals(kind)
                || !(authority instanceof String) || !AUTHORITY.equals(authority)
                || !(schemaVersion instanceof String) || !SCHEMA_VERSION.equals(schemaVersion)
                || !(slotDigest instanceof String) || !(feedbackDigest instanceof String))
            throw new IllegalArgumentException("Invalid Native decision correction observation");
        Object feedbackValue = value.get("feedback");
        Object rawIteration = value.get("candidateIteration");
        if (!(feedbackValue instanceof String canonicalFeedback)
                || !(rawIteration instanceof Integer iteration) || iteration < 1)
            throw new IllegalArgumentException("Invalid Native decision correction observation");
        AssistantDecisionValidationFeedback feedback =
                AssistantDecisionValidationFeedback.parse(canonicalFeedback);
        NativeDecisionCorrectionObservation result = new NativeDecisionCorrectionObservation(
                (String) slotDigest, (String) feedbackDigest, iteration, feedback);
        if (bytes(value) > MAX_UTF8_BYTES)
            throw new IllegalArgumentException("Native decision correction observation is too large");
        return result;
    }

    private static JSONObject value(String slotDigest, String feedbackDigest,
                                    int candidateIteration,
                                    AssistantDecisionValidationFeedback feedback)
    {
        JSONObject value = new JSONObject();
        value.put("observationKind", OBSERVATION_KIND);
        value.put("authority", AUTHORITY);
        value.put("schemaVersion", SCHEMA_VERSION);
        value.put("slotDigest", slotDigest);
        value.put("feedbackDigest", feedbackDigest);
        value.put("candidateIteration", candidateIteration);
        // Keep the public canonical JSON as one string. The ordinary context/snapshot serializer
        // omits nested null map values, while public Issue objects require all three exact fields.
        value.put("feedback", feedback.canonicalJson());
        return value;
    }

    private static boolean digest(String value)
    {
        return value != null && value.matches(SHA256);
    }

    private static int bytes(Object value)
    {
        return NativeAssistantContextProjection.canonical(value)
                .getBytes(StandardCharsets.UTF_8).length;
    }
}
