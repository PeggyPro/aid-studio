package com.aid.context;

import com.aid.assistant.service.AssistantToolObservationProjector;
import com.aid.common.exception.ServiceException;
import com.aid.runtime.tool.ContextPreparationRuntimeToolProvider;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.schema.JSONSchema;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** The durable full receipt is consumed by the host; model iterations only need its bounded identity. */
@Component
public class ContextPreparationObservationProjector implements AssistantToolObservationProjector {
    @Override public boolean supports(String code) { return ContextPreparationRuntimeToolProvider.CODE.equals(code); }
    @Override public JSONObject project(Input input) {
        var descriptor = ContextPreparationRuntimeToolProvider.descriptor();
        if (input == null || !supports(input.toolCode()) || input.userId() == null || input.userId() <= 0
                || input.runId() == null || input.runId() <= 0 || input.stepId() == null || input.stepId() <= 0
                || input.stableStepId() == null || input.stableStepId().isBlank()
                || !descriptor.contractVersion().equals(input.contractVersion())
                || !descriptor.schemaDigest().equals(input.schemaDigest())) throw invalid();
        JSONObject args = JSON.parseObject(input.argumentsJson()), receipt = JSON.parseObject(input.outputJson());
        if (args == null || receipt == null
                || !JSONSchema.of(JSON.parseObject(descriptor.inputSchema())).validate(args).isSuccess()
                || !JSONSchema.of(JSON.parseObject(descriptor.outputSchema())).validate(receipt).isSuccess()
                || !Objects.equals(args.getString("batchReference"), receipt.getString("batchReference"))) throw invalid();
        JSONObject result = new JSONObject();
        result.put("schemaVersion", "context-preparation-observation/v1");
        result.put("untrustedData", true);
        result.put("semanticAuthority", "DERIVED_ONLY");
        result.put("sourceRunId", String.valueOf(input.runId()));
        result.put("sourceStepId", String.valueOf(input.stepId()));
        result.put("toolCode", descriptor.code());
        result.put("contractVersion", descriptor.contractVersion());
        result.put("schemaDigest", descriptor.schemaDigest());
        result.put("batchReference", receipt.getString("batchReference"));
        result.put("notesDigest", receipt.getString("notesDigest"));
        result.put("noteCount", receipt.getJSONArray("notes").size());
        result.put("validationStatus", "SOURCE_QUOTES_VERIFIED");
        if (ContextContractProjection.canonical(result).getBytes(StandardCharsets.UTF_8).length > input.maxUtf8Bytes())
            throw new ServiceException("ASSISTANT_RESULT_OBSERVATION_BYTE_BUDGET_EXCEEDED");
        return result;
    }
    private static ServiceException invalid() { return new ServiceException("CONTEXT_PREPARATION_OBSERVATION_INVALID"); }
}
