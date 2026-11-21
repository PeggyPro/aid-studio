package com.aid.runtime.tool;

import cn.hutool.crypto.SecureUtil;
import com.aid.common.exception.ServiceException;
import com.aid.context.*;
import com.aid.runtime.service.RuntimeToolRegistry;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.schema.JSONSchema;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Validates derived notes against a frozen batch and freshly authorized original text. No writes. */
@Component
public class ContextPreparationRuntimeToolProvider implements RuntimeToolProvider {
    @org.springframework.beans.factory.annotation.Value("${conversation.context.paid-preparation-enabled:false}")
    private boolean paidPreparationEnabled;
    public static final String CODE = "aid.context.prepare";
    public static final String VERSION = "1";
    private static final String NOTES = """
        {"type":"array","maxItems":128,"items":{"type":"object","additionalProperties":false,
         "required":["kind","text","citations"],"properties":{
          "kind":{"type":"string","enum":["USER_REQUIREMENT","CONFIRMED_INPUT","SOURCE_NOTE","ASSISTANT_NOTE"]},
          "text":{"type":"string","minLength":1,"maxLength":65536},
          "citations":{"type":"array","minItems":1,"maxItems":16,"items":{"type":"object","additionalProperties":false,
           "required":["sourceId","quote"],"properties":{
            "sourceId":{"type":"string","maxLength":128},"quote":{"type":"string","minLength":1,"maxLength":65536},
            "startOffset":{"type":["integer","null"],"minimum":0,"maximum":2000000},
            "endOffset":{"type":["integer","null"],"minimum":1,"maximum":2000000}}}}}}}
        """;
    public static final String INPUT = "{\"type\":\"object\",\"additionalProperties\":false,"
            + "\"required\":[\"batchReference\",\"notes\"],\"properties\":{"
            + "\"batchReference\":{\"type\":\"string\",\"pattern\":\"^[a-f0-9]{64}$\"},\"notes\":" + NOTES + "}}";
    public static final String OUTPUT = "{\"type\":\"object\",\"additionalProperties\":false,"
            + "\"required\":[\"batchReference\",\"notes\",\"semanticAuthority\",\"notesDigest\"],\"properties\":{"
            + "\"batchReference\":{\"type\":\"string\",\"pattern\":\"^[a-f0-9]{64}$\"},"
            + "\"semanticAuthority\":{\"type\":\"string\",\"enum\":[\"DERIVED_ONLY\"]},"
            + "\"notesDigest\":{\"type\":\"string\",\"pattern\":\"^[a-f0-9]{64}$\"},\"notes\":" + NOTES + "}}";
    private static final String DIGEST = SecureUtil.sha256(INPUT + "\n" + OUTPUT);
    private static final JSONSchema INPUT_SCHEMA = JSONSchema.of(JSON.parseObject(INPUT));
    private static final JSONSchema OUTPUT_SCHEMA = JSONSchema.of(JSON.parseObject(OUTPUT));
    private final ConversationMemoryBatchStore batches;
    private final ConversationHistoryRuntimeToolProvider history;
    private final ConversationMemoryPreparationService preparation;

    public ContextPreparationRuntimeToolProvider(ConversationMemoryBatchStore batches,
                                                 ConversationHistoryRuntimeToolProvider history,
                                                 ConversationMemoryPreparationService preparation) {
        this.batches = Objects.requireNonNull(batches);
        this.history = Objects.requireNonNull(history);
        this.preparation = Objects.requireNonNull(preparation);
    }

    public static RuntimeToolRegistry.Descriptor descriptor() {
        return new RuntimeToolRegistry.Descriptor(CODE, VERSION, DIGEST, "整理对话上下文",
                "Validate notes for the exact host preparation batch. Preserve every user constraint, negation, date, quantity and uncertainty with exact quotes. "
                        + "USER_REQUIREMENT and CONFIRMED_INPUT text must equal their quotes joined by a newline. "
                        + "Quote offsets are optional when the quote is unique. This reads original sources and returns validation only; it does not execute a Skill or change business state. "
                        + "The model call still uses the existing text budget.",
                INPUT, OUTPUT, "READ", false, "REQUEST_DIGEST", false, "REPLAY_SAFE",
                List.of("conversation.history", "context.memory.prepare"));
    }
    @Override public List<RuntimeToolRegistry.Descriptor> descriptors() { return List.of(descriptor()); }
    @Override public RuntimeToolAvailability availability(RuntimeToolAvailabilityContext context,
                                                          RuntimeToolRegistry.Descriptor descriptor) {
        return paidPreparationEnabled ? RuntimeToolAvailability.enabled()
                : RuntimeToolAvailability.unavailable("PAID_CONTEXT_PREPARATION_DISABLED");
    }
    @Override public OutputReferenceKind planningOutputReferenceKind(RuntimeToolRegistry.Descriptor value) {
        return OutputReferenceKind.OBSERVATION;
    }
    @Override public RuntimeToolPreparation prepare(RuntimeToolInvocation invocation) {
        try { arguments(invocation); }
        catch (ServiceException invalid) {
            throw new RuntimeToolPreparationRejectedException(
                    RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_ARGUMENTS_INVALID, invalid);
        }
        return RuntimeToolProvider.super.prepare(invocation);
    }

    /** This provider performs only reads and pure validation; an exception cannot hide a submitted generation. */
    @Override public RuntimeToolResult executePrepared(RuntimeToolPreparedInvocation prepared,
                                                       com.aid.billing.dto.BillingBudgetContext budget) {
        try { return RuntimeToolProvider.super.executePrepared(prepared, budget); }
        catch (RuntimeException rejected) {
            String code = rejected.getMessage();
            if (code == null || !code.matches("CONTEXT_(?:CHECKPOINT|PREPARATION|SOURCE|MEMORY)_[A-Z_]{1,80}"))
                code = "CONTEXT_PREPARATION_READ_FAILED";
            RuntimeToolResult failure = new RuntimeToolResult();
            failure.setStatus("FAILED");
            failure.setErrorCode(code);
            failure.setSafeSummary("历史内容准备未通过校验；原始对话已保留，可按当前批次继续。");
            failure.setRetryable(!"CONTEXT_PREPARATION_BATCH_STALE".equals(code));
            return failure;
        }
    }

    /** Rebuild a lost read receipt from its frozen batch and live source checks; never repeat a model call. */
    @Override public RuntimeToolResult reconcilePrepared(RuntimeToolPreparedInvocation prepared) {
        if (prepared == null || !descriptor().equals(prepared.getDescriptor()))
            return RuntimeToolResult.unknown("CONTEXT_PREPARATION_IDENTITY_INVALID", "上下文准备身份无法核验");
        return executePrepared(prepared, null);
    }
    @Override public RuntimeToolResult execute(RuntimeToolInvocation invocation) {
        JSONObject args = arguments(invocation);
        var scope = new ConversationContextMemoryStore.Key(invocation.getUserId(), "ASSISTANT_RUN",
                String.valueOf(invocation.getRunId()));
        var stored = batches.load(scope, args.getString("batchReference"));
        if (stored == null || !"ASSISTANT_SESSION".equals(stored.contextScope().scopeType())
                || stored.batch().slices().isEmpty() || stored.batch().utf8TextBytes() > 65536
                || stored.maxNotesBytes() > 65536) throw invalid();
        var current = preparation.checkpoint(stored.contextScope());
        var anchor = current == null ? ConversationMemoryBatchStore.CheckpointAnchor.initial() : current.checkpoint();
        if (!anchor.equals(stored.checkpoint()))
            throw new ServiceException("CONTEXT_PREPARATION_BATCH_STALE");
        var candidates = inputCandidates(stored, args);
        var validated = ConversationMemoryCheckpointValidator.validate(stored.batch(), stored.batch().batchDigest(),
                candidates, stored.maxNotesBytes());
        for (var slice : stored.batch().slices()) verifySource(invocation, slice);
        JSONObject receipt = new JSONObject();
        receipt.put("batchReference", stored.reference());
        receipt.put("semanticAuthority", "DERIVED_ONLY");
        receipt.put("notesDigest", notesDigest(validated));
        receipt.put("notes", JSON.parseArray(JSON.toJSONString(candidates)));
        if (!OUTPUT_SCHEMA.validate(receipt).isSuccess()) throw invalid();
        return RuntimeToolResult.succeeded(receipt.toJSONString());
    }

    /** Only the host may consume this after checking the durable successful tool receipt identity. */
    public static List<ConversationMemoryCheckpointValidator.Candidate> inputCandidates(
            ConversationMemoryBatchStore.Stored stored, JSONObject arguments) {
        if (stored == null || arguments == null || !INPUT_SCHEMA.validate(arguments).isSuccess()
                || !stored.reference().equals(arguments.getString("batchReference"))) throw invalid();
        return candidates(stored, arguments);
    }

    /** The approved arguments and actual receipt must describe the same validated notes. */
    public static List<ConversationMemoryCheckpointValidator.Candidate> receiptCandidates(
            ConversationMemoryBatchStore.Stored stored, JSONObject arguments, JSONObject receipt) {
        var actual = receiptCandidates(stored, receipt);
        if (!actual.equals(inputCandidates(stored, arguments))) throw invalid();
        return actual;
    }

    public static List<ConversationMemoryCheckpointValidator.Candidate> receiptCandidates(
            ConversationMemoryBatchStore.Stored stored, JSONObject receipt) {
        if (receipt == null || !OUTPUT_SCHEMA.validate(receipt).isSuccess()
                || !stored.reference().equals(receipt.getString("batchReference"))) throw invalid();
        var candidates = candidates(stored, receipt);
        var checked = ConversationMemoryCheckpointValidator.validate(stored.batch(), stored.batch().batchDigest(),
                candidates, stored.maxNotesBytes());
        if (!notesDigest(checked).equals(receipt.getString("notesDigest"))) throw invalid();
        return candidates;
    }

    private static List<ConversationMemoryCheckpointValidator.Candidate> candidates(
            ConversationMemoryBatchStore.Stored stored, JSONObject value) {
        var notes = new ArrayList<ConversationMemoryCheckpointValidator.Candidate>();
        for (Object raw : value.getJSONArray("notes")) {
            JSONObject note = (JSONObject) raw;
            var citations = new ArrayList<ConversationMemoryCheckpointValidator.Citation>();
            for (Object entry : note.getJSONArray("citations")) {
                JSONObject citation = (JSONObject) entry;
                var resolved = ConversationMemoryCheckpointValidator.resolveCitation(stored.batch(),
                        citation.getString("sourceId"), citation.getString("quote"), citation.getInteger("startOffset"));
                if (citation.get("endOffset") != null && citation.getIntValue("endOffset") != resolved.endOffset())
                    throw invalid();
                citations.add(resolved);
            }
            notes.add(new ConversationMemoryCheckpointValidator.Candidate(
                    ConversationMemoryCheckpointValidator.NoteKind.valueOf(note.getString("kind")),
                    note.getString("text"), citations));
        }
        return List.copyOf(notes);
    }

    private void verifySource(RuntimeToolInvocation invocation, ConversationMemoryPreparationPlanner.Slice slice) {
        String id = slice.source().sourceId();
        JSONObject args = new JSONObject();
        if (id.matches("message:[1-9][0-9]{0,18}")) {
            args.put("operation", "MESSAGES"); args.put("messageId", id.substring(8));
        } else if (id.matches("answer:[1-9][0-9]{0,18}:[1-9][0-9]{0,18}")) {
            String[] ids = id.split(":");
            args.put("operation", "ANSWERS"); args.put("sourceRunId", ids[1]); args.put("inputRequestId", ids[2]);
        } else throw invalid();
        args.put("contentHash", slice.source().contentHash());
        args.put("pageSize", 1);
        int offset = slice.startOffset();
        while (offset < slice.endOffset()) {
            args.put("offset", offset); args.put("maxChars", Math.min(3000, slice.endOffset() - offset));
            RuntimeToolResult result = history.readHistory(invocation.getUserId(), invocation.getRunId(), args);
            if (!"SUCCEEDED".equals(result.getStatus())) throw invalid();
            JSONArray items = JSON.parseObject(result.getOutputJson()).getJSONArray("items");
            if (items == null || items.size() != 1) throw invalid();
            JSONObject item = items.getJSONObject(0);
            String expectedRole = switch (slice.source().kind()) {
                case USER -> "USER";
                case CONFIRMED_INPUT -> "USER_CONFIRMED";
                case ASSISTANT -> "ASSISTANT";
                default -> throw invalid();
            };
            String text = item.getString("text");
            if (!id.equals(item.getString("sourceId")) || !expectedRole.equals(item.getString("role"))
                    || !slice.source().contentHash().equals(item.getString("contentHash"))
                    || !Integer.valueOf(offset).equals(item.getInteger("offset"))
                    || text == null || text.isEmpty() || text.length() > 3001) throw invalid();
            int length = Math.min(text.length(), slice.endOffset() - offset);
            if (!slice.text().regionMatches(offset - slice.startOffset(), text, 0, length)) throw invalid();
            offset += length;
        }
    }
    private static JSONObject arguments(RuntimeToolInvocation invocation) {
        if (invocation == null || invocation.getUserId() == null || invocation.getRunId() == null
                || !CODE.equals(invocation.getToolCode()) || !VERSION.equals(invocation.getContractVersion())
                || !DIGEST.equals(invocation.getSchemaDigest()) || invocation.getArgumentsJson() == null
                || invocation.getArgumentsJson().getBytes(StandardCharsets.UTF_8).length > 262144) throw invalid();
        try {
            JSONObject args = JSON.parseObject(invocation.getArgumentsJson());
            if (args == null || !INPUT_SCHEMA.validate(args).isSuccess()) throw invalid();
            return args;
        } catch (RuntimeException invalid) { throw invalid(); }
    }
    private static String notesDigest(ConversationMemoryCheckpointValidator.Validated result) {
        return SecureUtil.sha256(ContextContractProjection.canonical(JSON.parseObject(result.notesJson())));
    }
    private static ServiceException invalid() { return new ServiceException("CONTEXT_PREPARATION_VALIDATION_INVALID"); }
}
