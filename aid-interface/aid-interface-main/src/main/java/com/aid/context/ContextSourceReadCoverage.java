package com.aid.context;

import cn.hutool.crypto.SecureUtil;
import com.aid.runtime.service.RuntimeToolRegistry;
import com.aid.runtime.tool.ConversationHistoryRuntimeToolProvider;
import com.aid.runtime.tool.SharedContextRuntimeToolProvider;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Evidence that original text was read in this run; never a new authorization or freshness claim. */
public final class ContextSourceReadCoverage {
    private ContextSourceReadCoverage() { }
    private record Identity(String sourceId, String operation, String role, String hash, String sequence) { }
    private record Chunk(int offset, String text, boolean more) {
        int end() { return offset + text.length(); }
    }
    private static final class Coverage {
        final Identity identity;
        final Set<Chunk> chunks = new HashSet<>();
        Coverage(Identity identity) { this.identity = identity; }
    }

    /** Called after receipt projection, before the assembled-context byte-budget check. */
    public static void attachToPolicy(JSONObject root, String runId) {
        if (root == null) throw invalid();
        JSONObject owner = com.aid.runtime.assistant.AssistantMainInputContract.serverContext(root);
        if (owner == null) owner = root;
        JSONObject previous = owner.getJSONObject("priorResultsPolicy");
        if (previous == null) throw invalid();
        JSONObject policy = new JSONObject(previous);
        policy.put("completedOriginalSources", completedSources(root.getJSONArray("priorResults"), runId));
        owner.put("priorResultsPolicy", policy);
    }

    /** Accept only host-assembled, contract-verified successful observations, not user-provided JSON. */
    public static JSONArray completedSources(JSONArray priorResults, String runId) {
        JSONArray result = new JSONArray();
        if (priorResults == null) return result;
        if (!positiveId(runId) || priorResults.size() > 10_000) throw invalid();
        Map<String, Coverage> sources = new TreeMap<>();
        for (Object raw : priorResults) {
            if (!(raw instanceof JSONObject row) || !"TOOL".equals(row.getString("executorKind"))
                    || !"SUCCEEDED".equals(row.getString("status"))) continue;
            RuntimeToolRegistry.Descriptor descriptor;
            if (SharedContextRuntimeToolProvider.isContextReaderCode(row.getString("toolCode")))
                descriptor = SharedContextRuntimeToolProvider.descriptorForCode(row.getString("toolCode"));
            else if (ConversationHistoryRuntimeToolProvider.CODE.equals(row.getString("toolCode")))
                descriptor = ConversationHistoryRuntimeToolProvider.descriptor();
            else continue;
            if (!(row.get("readHistory") instanceof JSONObject history)
                    || !descriptor.contractVersion().equals(history.getString("contractVersion"))
                    || !descriptor.schemaDigest().equals(history.getString("schemaDigest"))) continue;
            if (!(row.get("observation") instanceof JSONObject observation)
                    || !ContextReadObservationProjector.VERSION.equals(observation.getString("schemaVersion"))) continue;
            if (!runId.equals(observation.getString("sourceRunId")) || !positiveId(row.getString("stepId"))
                    || !row.getString("stepId").equals(observation.getString("sourceStepId"))
                    || !descriptor.code().equals(observation.getString("toolCode"))
                    || !descriptor.contractVersion().equals(observation.getString("contractVersion"))
                    || !descriptor.schemaDigest().equals(observation.getString("schemaDigest"))
                    || !Boolean.TRUE.equals(observation.getBoolean("untrustedData"))
                    || !"UNTRUSTED_SOURCE_DATA".equals(observation.getString("contentDisposition"))) throw invalid();
            JSONObject page = observation.getJSONObject("page");
            if (page == null) throw invalid();
            String operation = page.getString("operation");
            if ("CONTRACT".equals(operation)) continue;
            if (operation == null || !Set.of("MESSAGES", "ANSWERS").contains(operation)) throw invalid();
            JSONArray items = page.getJSONArray("items");
            if (items == null || items.size() > 3) throw invalid();
            for (Object value : items) {
                if (!(value instanceof JSONObject item)) throw invalid();
                String sourceId = item.getString("sourceId"), id = item.getString("id");
                String role = item.getString("role"), hash = item.getString("contentHash");
                String text = item.getString("text"), sequence = item.getString("sequence");
                Integer offset = item.getInteger("offset");
                Boolean more = item.getBoolean("hasMoreText");
                boolean answers = "ANSWERS".equals(operation);
                if (!positiveId(id) || sourceId == null || role == null
                        || answers && (!sourceId.matches("answer:[1-9][0-9]{0,18}:" + id)
                        || !"USER_CONFIRMED".equals(role) || !id.equals(sequence))
                        || !answers && (!("message:" + id).equals(sourceId)
                        || !Set.of("USER", "ASSISTANT").contains(role))
                        || sequence == null || !sequence.matches("0|[1-9][0-9]{0,18}")
                        || hash == null || !hash.matches("[a-f0-9]{64}")
                        || text == null || text.length() > 3001 || offset == null || offset < 0
                        || offset > 2_000_000 || more == null
                        || more && (text.isEmpty() || !Integer.valueOf(offset + text.length()).equals(item.getInteger("nextOffset")))
                        || !more && item.get("nextOffset") != null) throw invalid();
                requireUnicode(text);
                Identity identity = new Identity(sourceId, operation, role, hash, sequence);
                Coverage coverage = sources.computeIfAbsent(sourceId, ignored -> new Coverage(identity));
                if (!coverage.identity.equals(identity)) throw invalid();
                coverage.chunks.add(new Chunk(offset, text, more));
            }
        }
        for (Coverage coverage : sources.values()) {
            if (!complete(coverage)) continue;
            Identity identity = coverage.identity;
            JSONObject source = new JSONObject();
            source.put("sourceId", identity.sourceId());
            source.put("operation", identity.operation());
            source.put("role", identity.role());
            source.put("contentHash", identity.hash());
            source.put("sequence", identity.sequence());
            source.put("coverage", "COMPLETE_VERIFIED_ORIGINAL");
            source.put("authority", "READ_RECEIPT_ONLY");
            if ("ANSWERS".equals(identity.operation())) {
                String[] parts = identity.sourceId().split(":");
                source.put("sourceRunId", parts[1]);
                source.put("inputRequestId", parts[2]);
            } else source.put("messageId", identity.sourceId().substring("message:".length()));
            result.add(source);
        }
        return result;
    }

    private static boolean complete(Coverage coverage) {
        Integer terminal = null;
        for (Chunk chunk : coverage.chunks) if (!chunk.more()) {
            if (terminal != null && terminal != chunk.end()) throw invalid();
            terminal = chunk.end();
        }
        if (terminal == null) return false;
        var chunks = new ArrayList<>(coverage.chunks);
        chunks.sort(Comparator.comparingInt(Chunk::offset).thenComparing(
                Comparator.comparingInt(Chunk::end).reversed()));
        StringBuilder text = new StringBuilder();
        for (Chunk chunk : chunks) {
            if (chunk.end() > terminal) throw invalid();
            if (chunk.offset() > text.length()) return false;
            int overlap = Math.min(text.length() - chunk.offset(), chunk.text().length());
            for (int i = 0; i < overlap; i++)
                if (text.charAt(chunk.offset() + i) != chunk.text().charAt(i)) throw invalid();
            if (overlap < chunk.text().length()) text.append(chunk.text(), overlap, chunk.text().length());
        }
        if (text.length() != terminal) return false;
        if (!coverage.identity.hash().equals(SecureUtil.sha256(text.toString()))) throw invalid();
        return true;
    }

    private static boolean positiveId(String value) { return value != null && value.matches("[1-9][0-9]{0,18}"); }
    private static void requireUnicode(String text) {
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (Character.isHighSurrogate(value)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) throw invalid();
            } else if (Character.isLowSurrogate(value)) throw invalid();
        }
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("CONTEXT_SOURCE_READ_COVERAGE_INVALID");
    }
}
