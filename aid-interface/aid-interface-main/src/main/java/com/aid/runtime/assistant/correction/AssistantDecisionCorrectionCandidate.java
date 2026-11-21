package com.aid.runtime.assistant.correction;

import com.aid.runtime.assistant.AssistantDecisionValidationFeedback;
import com.alibaba.fastjson2.JSONObject;

import java.nio.charset.StandardCharsets;

/** Server-owned context candidate for the single successor bound to a correction SLOT. */
public record AssistantDecisionCorrectionCandidate(String slotDigest,
                                                   String feedbackDigest,
                                                   int candidateIteration,
                                                   String feedbackCanonicalJson,
                                                   String schemaDiagnosticsJson)
{
    public static final String OBSERVATION_KIND = "NATIVE_DECISION_VALIDATION";
    public static final String AUTHORITY = "SERVER_VERIFIED_DECISION_CORRECTION";
    public static final String SCHEMA_VERSION = "assistant-native-decision-validation/v1";
    private static final String SHA256 = "^[0-9a-f]{64}$";
    private static final int MAX_BYTES = 4_096;

    public AssistantDecisionCorrectionCandidate(String slotDigest, String feedbackDigest,
                                                int candidateIteration, String feedbackCanonicalJson)
    {
        this(slotDigest, feedbackDigest, candidateIteration, feedbackCanonicalJson, null);
    }

    public AssistantDecisionCorrectionCandidate
    {
        if (!digest(slotDigest) || !digest(feedbackDigest) || candidateIteration < 1
                || feedbackCanonicalJson == null)
            throw new IllegalArgumentException("Invalid decision correction candidate");
        AssistantDecisionValidationFeedback feedback =
                AssistantDecisionValidationFeedback.parse(feedbackCanonicalJson);
        if (!feedbackDigest.equals(feedback.digest())
                || observation(slotDigest, feedbackDigest, candidateIteration,
                feedbackCanonicalJson).toJSONString().getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IllegalArgumentException("Invalid decision correction candidate");
        if (schemaDiagnosticsJson != null)
            AssistantSchemaCorrectionDiagnostics.validate(schemaDiagnosticsJson,
                    slotDigest, feedback.trustedContractDigest());
    }

    public JSONObject observation()
    {
        return observation(slotDigest, feedbackDigest, candidateIteration, feedbackCanonicalJson);
    }

    public JSONObject diagnosticObservation()
    {
        if (schemaDiagnosticsJson == null) return null;
        return AssistantSchemaCorrectionDiagnostics.validate(schemaDiagnosticsJson, slotDigest,
                AssistantDecisionValidationFeedback.parse(feedbackCanonicalJson).trustedContractDigest());
    }

    private static JSONObject observation(String slotDigest, String feedbackDigest,
                                          int candidateIteration, String feedbackCanonicalJson)
    {
        JSONObject value = new JSONObject();
        value.put("observationKind", OBSERVATION_KIND);
        value.put("authority", AUTHORITY);
        value.put("schemaVersion", SCHEMA_VERSION);
        value.put("slotDigest", slotDigest);
        value.put("feedbackDigest", feedbackDigest);
        value.put("candidateIteration", candidateIteration);
        value.put("feedback", feedbackCanonicalJson);
        return value;
    }

    private static boolean digest(String value)
    {
        return value != null && value.matches(SHA256);
    }
}
