package com.aid.runtime.tool;

import cn.hutool.crypto.SecureUtil;
import com.aid.common.exception.ServiceException;
import com.aid.context.ContextContractReader;
import com.aid.context.ConversationContextMemoryStore;
import com.aid.runtime.service.RuntimeToolRegistry;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.alibaba.fastjson2.schema.JSONSchema;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** One bounded source-reading contract for conversation history and immutable runtime catalogs. */
@Component
public class SharedContextRuntimeToolProvider implements RuntimeToolProvider {
    public static final String CODE = "aid.context.read";
    public static final String VERSION = "1";
    public static final String INPUT = inputSchema();
    public static final String OUTPUT = outputSchema();
    private static final String DIGEST = SecureUtil.sha256(INPUT + "\n" + OUTPUT);
    private static final JSONSchema INPUT_SCHEMA = JSONSchema.of(JSON.parseObject(INPUT));
    private static final JSONSchema HISTORY_INPUT_SCHEMA = JSONSchema.of(JSON.parseObject(ConversationHistoryRuntimeToolProvider.INPUT));
    private static final JSONSchema OUTPUT_SCHEMA = JSONSchema.of(JSON.parseObject(OUTPUT));
    private static final Set<String> CONTRACT_ARGUMENTS =
            Set.of("operation", "contractRef", "offset", "maxChars", "contentHash", "pageSize");
    private final ContextContractReader contracts;
    private final ConversationHistoryRuntimeToolProvider history;

    public SharedContextRuntimeToolProvider(ContextContractReader contracts,
                                            ConversationHistoryRuntimeToolProvider history) {
        this.contracts = Objects.requireNonNull(contracts);
        this.history = Objects.requireNonNull(history);
    }

    public static RuntimeToolRegistry.Descriptor descriptor() {
        return new RuntimeToolRegistry.Descriptor(CODE, VERSION, DIGEST, "Read needed context",
                "MESSAGES/ANSWERS read original conversation sources with the history cursors (maxChars up to 3000). "
                        + "CONTRACT reads an exact contractRef from this run's catalog (maxChars up to 8192). "
                        + "For hasMoreText, keep the same source identity/contentHash and continue at nextOffset. "
                        + "Contracts are fixed discovery data, not current execution permission or a price quote. "
                        + "This read has no direct tool charge; assistant model calls can still cost credits.",
                INPUT, OUTPUT, "READ", false, "REQUEST_DIGEST", false, "REPLAY_SAFE",
                List.of("conversation.history", "context.contract.read"));
    }

    public static boolean isContextReaderCode(String code) {
        return CODE.equals(code) || SharedContextReadV2ToolProvider.CODE.equals(code);
    }

    public static RuntimeToolRegistry.Descriptor descriptorForCode(String code) {
        if (CODE.equals(code)) return descriptor();
        if (SharedContextReadV2ToolProvider.CODE.equals(code)) return SharedContextReadV2ToolProvider.descriptor();
        return null;
    }

    @Override public List<RuntimeToolRegistry.Descriptor> descriptors() { return List.of(descriptor()); }
    @Override public OutputReferenceKind planningOutputReferenceKind(RuntimeToolRegistry.Descriptor descriptor) {
        return OutputReferenceKind.OBSERVATION;
    }

    @Override public RuntimeToolPreparation prepare(RuntimeToolInvocation invocation) {
        try {
            validatedArguments(invocation);
        } catch (ServiceException invalid) {
            throw new RuntimeToolPreparationRejectedException(
                    RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_ARGUMENTS_INVALID, invalid);
        }
        return RuntimeToolProvider.super.prepare(invocation);
    }

    /** Historical prepared reads may predate preparation checks; deterministic bad arguments have no side effects. */
    @Override public RuntimeToolResult executePrepared(RuntimeToolPreparedInvocation prepared,
                                                       com.aid.billing.dto.BillingBudgetContext budget) {
        try {
            return RuntimeToolProvider.super.executePrepared(prepared, budget);
        } catch (ServiceException invalid) {
            if (!"CONTEXT_READ_ARGUMENTS_INVALID".equals(invalid.getMessage())) throw invalid;
            return invalidReadResult();
        }
    }

    private static RuntimeToolResult invalidReadResult() {
        RuntimeToolResult rejected = new RuntimeToolResult();
        rejected.setStatus("FAILED");
        rejected.setErrorCode("CONTEXT_READ_ARGUMENTS_INVALID");
        rejected.setSafeSummary("上下文读取参数无效；未执行生成或写入操作");
        return rejected;
    }

    static RuntimeToolResult missingContractResult() {
        RuntimeToolResult rejected = new RuntimeToolResult();
        rejected.setStatus("FAILED");
        rejected.setErrorCode("CONTEXT_CONTRACT_DOCUMENT_UNAVAILABLE");
        rejected.setSafeSummary("本轮范围内不存在该契约来源；请使用当前目录提供的契约引用。未执行生成或写入操作。");
        rejected.setRetryable(false);
        return rejected;
    }

    /** Immutable contract reads can be reconstructed from durable source data, without any submission. */
    @Override public RuntimeToolResult reconcilePrepared(RuntimeToolPreparedInvocation prepared) {
        if (prepared == null || prepared.getDescriptor() == null
                || !descriptor().equals(prepared.getDescriptor()))
            return RuntimeToolResult.unknown("CONTEXT_READ_IDENTITY_INVALID", "上下文读取身份无法核验");
        RuntimeToolInvocation invocation = new RuntimeToolInvocation();
        invocation.setUserId(prepared.getUserId());
        invocation.setRunId(prepared.getRunId());
        invocation.setToolCode(prepared.getToolCode());
        invocation.setContractVersion(prepared.getContractVersion());
        invocation.setSchemaDigest(prepared.getSchemaDigest());
        invocation.setArgumentsJson(prepared.getPreparedPayloadJson());
        try {
            JSONObject args = validatedArguments(invocation);
            // History is mutable. Without a durable receipt, do not claim a later page was the original result.
            if (!"CONTRACT".equals(args.getString("operation")))
                return RuntimeToolProvider.super.reconcilePrepared(prepared);
            return readContract(invocation, args);
        } catch (ContextContractReader.DocumentUnavailable missing) {
            return missingContractResult();
        } catch (ServiceException invalid) {
            if (!"CONTEXT_READ_ARGUMENTS_INVALID".equals(invalid.getMessage())) throw invalid;
            return invalidReadResult();
        } catch (IllegalStateException | IllegalArgumentException unavailable) {
            return RuntimeToolResult.unknown("CONTEXT_READ_SOURCE_UNAVAILABLE", "原始上下文或当前访问权限无法核验");
        }
    }

    private static JSONObject validatedArguments(RuntimeToolInvocation invocation) {
        if (invocation == null || invocation.getUserId() == null || invocation.getRunId() == null
                || !CODE.equals(invocation.getToolCode()) || !VERSION.equals(invocation.getContractVersion())
                || !DIGEST.equals(invocation.getSchemaDigest())) throw invalid();
        JSONObject args;
        try { args = JSON.parseObject(invocation.getArgumentsJson()); }
        catch (RuntimeException malformed) { throw invalid(); }
        return validateSourceArguments(args);
    }

    static JSONObject validateSourceArguments(JSONObject args) {
        if (args == null || !INPUT_SCHEMA.validate(args).isSuccess()) throw invalid();
        if (!"CONTRACT".equals(args.getString("operation"))) {
            if (args.get("contractRef") != null) throw invalid();
            JSONObject source = new JSONObject(args);
            source.remove("contractRef");
            if (!HISTORY_INPUT_SCHEMA.validate(source).isSuccess()) throw invalid();
            boolean answer = "ANSWERS".equals(args.getString("operation"));
            if (answer && (args.get("messageId") != null || args.get("beforeSeq") != null)
                    || !answer && (args.get("inputRequestId") != null || args.get("beforeInputId") != null
                    || args.get("sourceRunId") != null)) throw invalid();
            if ((args.getIntValue("offset", 0) > 0 || args.get("contentHash") != null)
                    && args.get(answer ? "inputRequestId" : "messageId") == null) throw invalid();
            for (String key : List.of("messageId", "beforeSeq", "sourceRunId", "inputRequestId", "beforeInputId")) {
                if (args.get(key) != null) {
                    try { Long.parseLong(args.getString(key)); }
                    catch (NumberFormatException overflow) { throw invalid(); }
                }
            }
            return args;
        }
        if (args.keySet().stream().anyMatch(key -> !CONTRACT_ARGUMENTS.contains(key) && args.get(key) != null))
            throw invalid();
        String reference = args.getString("contractRef");
        if (reference == null || !reference.matches("[a-f0-9]{64}")
                || args.getString("contentHash") != null && !reference.equals(args.getString("contentHash")))
            throw invalid();
        // pageSize is a published upper bound. A contract reference always selects exactly one item.
        return args;
    }

    @Override public RuntimeToolResult execute(RuntimeToolInvocation invocation) {
        try { return readAuthorizedSource(invocation, validatedArguments(invocation)); }
        catch (ContextContractReader.DocumentUnavailable missing) { return missingContractResult(); }
    }

    RuntimeToolResult readAuthorizedSource(RuntimeToolInvocation invocation, JSONObject args) {
        if (!"CONTRACT".equals(args.getString("operation"))) {
            JSONObject sourceArguments = new JSONObject(args);
            sourceArguments.remove("contractRef");
            return history.readHistory(invocation.getUserId(), invocation.getRunId(), sourceArguments);
        }
        return readContract(invocation, args);
    }

    private RuntimeToolResult readContract(RuntimeToolInvocation invocation, JSONObject args) {
        String reference = args.getString("contractRef");
        var scope = new ConversationContextMemoryStore.Key(invocation.getUserId(), "ASSISTANT_RUN",
                String.valueOf(invocation.getRunId()));
        var chunk = contracts.read(scope, reference, args.getIntValue("offset", 0),
                args.getIntValue("maxChars", 8192));
        JSONObject item = new JSONObject();
        item.put("id", reference);
        item.put("sourceId", "contract:" + reference);
        item.put("sequence", "0");
        item.put("role", "CONTRACT");
        item.put("text", chunk.text());
        item.put("contentHash", reference);
        item.put("offset", chunk.offset());
        item.put("nextOffset", chunk.nextOffset());
        item.put("hasMoreText", chunk.hasMore());
        JSONObject output = new JSONObject();
        output.put("operation", "CONTRACT");
        output.put("items", new JSONArray(List.of(item)));
        output.put("hasMore", false);
        output.put("nextBefore", null);
        if (!OUTPUT_SCHEMA.validate(output).isSuccess())
            throw new ServiceException("CONTEXT_READ_RESULT_INVALID");
        return RuntimeToolResult.succeeded(output.toJSONString(JSONWriter.Feature.WriteNulls));
    }

    private static String inputSchema() {
        JSONObject root = JSON.parseObject(ConversationHistoryRuntimeToolProvider.INPUT);
        JSONObject properties = root.getJSONObject("properties");
        properties.getJSONObject("operation").put("enum", List.of("MESSAGES", "ANSWERS", "CONTRACT"));
        properties.getJSONObject("maxChars").put("maximum", 8192);
        properties.getJSONObject("maxChars").put("description", "History: 1..3000; contract: 1..8192 UTF-16 characters.");
        properties.put("contractRef", JSON.parseObject("{\"type\":[\"string\",\"null\"],\"pattern\":\"^[a-f0-9]{64}$\"}"));
        return root.toJSONString();
    }

    private static String outputSchema() {
        JSONObject root = JSON.parseObject(ConversationHistoryRuntimeToolProvider.OUTPUT);
        JSONObject properties = root.getJSONObject("properties");
        properties.getJSONObject("operation").put("enum", List.of("MESSAGES", "ANSWERS", "CONTRACT"));
        properties.getJSONObject("items").getJSONObject("items").getJSONObject("properties")
                .getJSONObject("text").put("maxLength", 8193);
        return root.toJSONString();
    }

    private static ServiceException invalid() { return new ServiceException("CONTEXT_READ_ARGUMENTS_INVALID"); }
}
