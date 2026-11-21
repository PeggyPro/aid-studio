package com.aid.assistant.service;

import com.aid.common.exception.ServiceException;
import com.aid.runtime.tool.AssistantModelCatalogRuntimeToolProvider;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.schema.JSONSchema;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;

/** Public catalog facts, not billing authority. Shared by parent and Skill observation consumers. */
@Component
public class ModelCatalogObservationProjector implements AssistantToolObservationProjector {
    public static final String SCHEMA_VERSION = "model-catalog-observation/v1";
    private static final Set<String> BILLING = Set.of("modelCode", "modelName", "providerName", "modelType",
            "modelTypeName", "generateMode", "billingMode", "isFree", "meterType", "meterTypeName",
            "billingDesc", "creditUnit");
    private static final Set<String> RULE = Set.of("skuCode", "skuName", "meterType", "meterTypeName",
            "unit", "unitName", "estimated", "resolution", "generateMode", "durationMin", "durationMax",
            "inputTokensMin", "inputTokensMax", "outputPixelsMin", "outputPixelsMax", "outputPixelsPerUnit",
            "referenceImageCountMin", "referenceImageCountMax", "inputVideoCountMin", "inputVideoCountMax",
            "audioMode", "unitPrice", "pricePerSecond", "packagePrice", "inputPricePerMillion",
            "outputPricePerMillion", "inputImagePrice", "inputImageFreeCount", "inputVideoPricePerSecond");
    private static final Set<String> INPUT_PRICING = Set.of("imageSupported", "imageUnitPrice", "imageFreeCount",
            "imageMaxCount", "videoSupported", "videoUnitPrice", "videoMaxSeconds", "videoMaxCount");
    private static final Set<String> TOKEN_RANGES = Set.of("inputTokensMin", "inputTokensMax");

    @Override public boolean supports(String code) {
        return AssistantModelCatalogRuntimeToolProvider.isCatalogCode(code);
    }

    @Override public JSONObject project(Input input) {
        if (input == null || !supports(input.toolCode()) || input.userId() == null || input.userId() <= 0
                || input.runId() == null || input.runId() <= 0 || input.stepId() == null || input.stepId() <= 0
                || input.stableStepId() == null || input.stableStepId().isBlank() || input.maxUtf8Bytes() < 1)
            throw invalid();
        var descriptor = AssistantModelCatalogRuntimeToolProvider.descriptorForCode(input.toolCode());
        if (!descriptor.contractVersion().equals(input.contractVersion())
                || !descriptor.schemaDigest().equals(input.schemaDigest())) throw invalid();
        JSONObject arguments = object(input.argumentsJson()), output = object(input.outputJson());
        String operation = AssistantModelCatalogRuntimeToolProvider.DETAIL.equals(input.toolCode())
                ? "DETAIL" : arguments.getString("operation");
        if (!JSONSchema.of(JSON.parseObject(descriptor.inputSchema())).validate(arguments).isSuccess()
                || !JSONSchema.of(JSON.parseObject(descriptor.outputSchema())).validate(output).isSuccess()
                || !Objects.equals(operation, output.getString("operation"))) throw invalid();

        JSONObject page = (JSONObject) AssistantObservationSanitizer.project(output, 0);
        JSONObject detail = output.getJSONObject("detail");
        if (detail != null && detail.get("capability") != null)
            page.getJSONObject("detail").put("capability", catalogData(detail.getJSONObject("capability"), 0));
        if (detail != null && detail.get("billing") != null) {
            if (!"DETAIL".equals(operation) || !"AVAILABLE".equals(detail.getString("lookupStatus"))) throw invalid();
            JSONObject billing = detail.getJSONObject("billing");
            for (String key : Set.of("modelCode", "generateMode")) {
                if (!Objects.equals(arguments.getString(key), detail.getString(key))
                        || !Objects.equals(detail.getString(key), billing.getString(key))) throw invalid();
            }
            JSONObject safeBilling = scalars(billing, BILLING);
            safeBilling.put("rules", rules(billing.getJSONArray("rules")));
            safeBilling.put("columns", columns(billing.getJSONArray("columns")));
            if (billing.get("inputPricing") != null)
                safeBilling.put("inputPricing", scalars(billing.getJSONObject("inputPricing"), INPUT_PRICING));
            JSONObject safeDetail = page.getJSONObject("detail");
            safeDetail.put("billing", safeBilling);
            if (detail.get("quoteRequired") instanceof Boolean)
                safeDetail.put("quoteRequired", detail.get("quoteRequired"));
        }
        JSONObject result = new JSONObject();
        result.put("schemaVersion", SCHEMA_VERSION);
        result.put("untrustedData", true);
        result.put("contentDisposition", "UNTRUSTED_SOURCE_DATA");
        result.put("sourceRunId", String.valueOf(input.runId()));
        result.put("sourceStepId", String.valueOf(input.stepId()));
        result.put("toolCode", input.toolCode());
        result.put("contractVersion", input.contractVersion());
        result.put("schemaDigest", input.schemaDigest());
        result.put("pricingInterpretation", "Public catalog rates only; not a quote, debit authorization or settled cost. Preserve units, conditions and estimated flags; execution uses authoritative quote and settlement.");
        result.put("page", page);
        if (result.toJSONString().getBytes(StandardCharsets.UTF_8).length > input.maxUtf8Bytes())
            throw new ServiceException("ASSISTANT_RESULT_OBSERVATION_BYTE_BUDGET_EXCEEDED");
        return result;
    }

    private static JSONArray rules(JSONArray source) {
        JSONArray result = new JSONArray();
        if (source == null) return result;
        if (source.size() > 100) throw invalid();
        for (int i = 0; i < source.size(); i++) {
            JSONObject item = source.getJSONObject(i), safe = scalars(item, RULE);
            if (item.get("matchConditions") != null) {
                JSONObject conditions = item.getJSONObject("matchConditions");
                JSONObject projected = (JSONObject) catalogData(conditions, 0);
                for (String key : TOKEN_RANGES) if (conditions.containsKey(key)) {
                    Object value = conditions.get(key);
                    if (value != null && !(value instanceof Number)) throw invalid();
                    projected.put(key, value);
                }
                // Never silently omit a price condition and present a conditional price as universal.
                if (!conditions.equals(projected)) throw invalid();
                safe.put("matchConditions", projected);
            }
            result.add(safe);
        }
        return result;
    }

    private static JSONArray columns(JSONArray source) {
        JSONArray result = new JSONArray();
        if (source == null) return result;
        if (source.size() > 32) throw invalid();
        for (int i = 0; i < source.size(); i++) {
            JSONObject column = source.getJSONObject(i);
            if (RULE.contains(column.getString("key")))
                result.add(scalars(column, Set.of("key", "label", "unit", "type")));
        }
        return result;
    }

    private static JSONObject scalars(JSONObject source, Set<String> fields) {
        if (source == null) throw invalid();
        JSONObject result = new JSONObject();
        for (String key : fields) if (source.containsKey(key)) {
            Object value = source.get(key);
            if (value != null && !(value instanceof String || value instanceof Number || value instanceof Boolean))
                throw invalid();
            result.put(key, AssistantObservationSanitizer.project(value, 0));
        }
        return result;
    }

    /** Parameter definitions may be nested. Fail at a bound instead of silently deleting deeper constraints. */
    private static Object catalogData(Object value, int depth) {
        if (depth > 24) throw invalid();
        if (value == null || value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof String text) {
            if (text.length() > 50_000) throw invalid();
            return AssistantObservationSanitizer.URL.matcher(text).replaceAll("[redacted-url]");
        }
        if (value instanceof JSONObject object) {
            if (object.size() > 100) throw invalid();
            JSONObject result = new JSONObject();
            for (String key : object.keySet()) if (!AssistantObservationSanitizer.sensitive(key))
                result.put(key, catalogData(object.get(key), depth + 1));
            return result;
        }
        if (value instanceof JSONArray array) {
            if (array.size() > 100) throw invalid();
            JSONArray result = new JSONArray();
            for (Object item : array) result.add(catalogData(item, depth + 1));
            return result;
        }
        throw invalid();
    }

    private static JSONObject object(String json) {
        try {
            JSONObject result = JSON.parseObject(json);
            if (result != null) return result;
        } catch (RuntimeException malformed) { throw invalid(); }
        throw invalid();
    }

    private static ServiceException invalid() { return new ServiceException("MODEL_CATALOG_OBSERVATION_INVALID"); }
}
