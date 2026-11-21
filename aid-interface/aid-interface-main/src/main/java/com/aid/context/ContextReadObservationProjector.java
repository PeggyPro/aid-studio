package com.aid.context;

import cn.hutool.crypto.SecureUtil;
import com.aid.assistant.service.AssistantToolObservationProjector;
import com.aid.common.exception.ServiceException;
import com.aid.runtime.tool.ConversationHistoryRuntimeToolProvider;
import com.aid.runtime.tool.SharedContextRuntimeToolProvider;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.schema.JSONSchema;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Exact, bounded source data. Schema URLs and original messages must not pass through prose sanitization. */
@Component
public class ContextReadObservationProjector implements AssistantToolObservationProjector {
    public static final String VERSION = "context-read-observation/v1";

    @Override public boolean supports(String code) {
        return SharedContextRuntimeToolProvider.isContextReaderCode(code)
                || ConversationHistoryRuntimeToolProvider.CODE.equals(code);
    }

    @Override public JSONObject project(Input input) {
        if (input == null || !supports(input.toolCode()) || input.userId() == null || input.userId() <= 0
                || input.runId() == null || input.runId() <= 0 || input.stepId() == null || input.stepId() <= 0
                || input.stableStepId() == null || input.stableStepId().isBlank() || input.maxUtf8Bytes() < 1)
            throw invalid();
        boolean shared = SharedContextRuntimeToolProvider.isContextReaderCode(input.toolCode());
        var descriptor = shared ? SharedContextRuntimeToolProvider.descriptorForCode(input.toolCode())
                : ConversationHistoryRuntimeToolProvider.descriptor();
        if (!descriptor.contractVersion().equals(input.contractVersion())
                || !descriptor.schemaDigest().equals(input.schemaDigest())) throw invalid();
        JSONObject arguments = object(input.argumentsJson());
        JSONObject output = object(input.outputJson());
        String inputSchema = descriptor.inputSchema();
        String outputSchema = descriptor.outputSchema();
        if (!JSONSchema.of(JSON.parseObject(inputSchema)).validate(arguments).isSuccess()
                || !JSONSchema.of(JSON.parseObject(outputSchema)).validate(output).isSuccess()
                || !Objects.equals(arguments.getString("operation"), output.getString("operation"))) throw invalid();
        if ("CONTRACT".equals(output.getString("operation"))) validateContractPage(arguments, output);
        JSONObject result = new JSONObject();
        result.put("schemaVersion", VERSION);
        result.put("untrustedData", true);
        result.put("contentDisposition", "UNTRUSTED_SOURCE_DATA");
        result.put("sourceRunId", String.valueOf(input.runId()));
        result.put("sourceStepId", String.valueOf(input.stepId()));
        result.put("toolCode", input.toolCode());
        result.put("contractVersion", input.contractVersion());
        result.put("schemaDigest", input.schemaDigest());
        result.put("offsetUnit", "UTF16_CODE_UNIT_END_EXCLUSIVE");
        result.put("hashAlgorithm", "SHA256_UTF8");
        result.put("page", output);
        if (ContextContractProjection.canonical(result).getBytes(StandardCharsets.UTF_8).length > input.maxUtf8Bytes())
            throw new ServiceException("ASSISTANT_RESULT_OBSERVATION_BYTE_BUDGET_EXCEEDED");
        return result;
    }

    private static void validateContractPage(JSONObject arguments, JSONObject output) {
        String reference = arguments.getString("contractRef");
        if (reference == null || !reference.matches("[a-f0-9]{64}") || output.getBooleanValue("hasMore")
                || output.get("nextBefore") != null || output.getJSONArray("items").size() != 1) throw invalid();
        JSONObject item = output.getJSONArray("items").getJSONObject(0);
        int offset = arguments.getIntValue("offset", 0);
        int maximum = arguments.getIntValue("maxChars", 8192);
        String text = item.getString("text");
        boolean more = item.getBooleanValue("hasMoreText");
        if (!reference.equals(item.getString("id")) || !reference.equals(item.getString("contentHash"))
                || !("contract:" + reference).equals(item.getString("sourceId"))
                || !"CONTRACT".equals(item.getString("role")) || !"0".equals(item.getString("sequence"))
                || item.getIntValue("offset") != offset || text.length() > maximum + 1
                || more && (text.isEmpty() || !Objects.equals(item.getInteger("nextOffset"), offset + text.length()))
                || !more && item.get("nextOffset") != null
                || arguments.getString("contentHash") != null && !reference.equals(arguments.getString("contentHash")))
            throw invalid();
        if (offset == 0 && !more && !reference.equals(SecureUtil.sha256(text))) throw invalid();
    }

    private static JSONObject object(String json) {
        try {
            JSONObject value = JSON.parseObject(json);
            if (value != null) return value;
        } catch (RuntimeException malformed) { throw invalid(); }
        throw invalid();
    }

    private static ServiceException invalid() { return new ServiceException("CONTEXT_READ_OBSERVATION_INVALID"); }
}
