package com.aid.context;

import cn.hutool.crypto.SecureUtil;
import com.aid.runtime.tool.SharedContextRuntimeToolProvider;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reconstructs complete immutable reads from server-verified step observations, never from user messages. */
public final class ContextContractReadCoverage {
    private ContextContractReadCoverage() { }
    private record Chunk(int offset, String text, boolean more) {
        int end() { return offset + text.length(); }
    }

    public static Set<String> completedReferences(JSONArray priorResults, String runId) {
        if (priorResults == null) return Set.of();
        if (runId == null || !runId.matches("[1-9][0-9]{0,18}") || priorResults.size() > 10_000) throw invalid();
        Map<String, Set<Chunk>> chunks = new HashMap<>();
        for (Object raw : priorResults) {
            if (!(raw instanceof JSONObject row) || !"TOOL".equals(row.getString("executorKind"))
                    || !"SUCCEEDED".equals(row.getString("status"))
                    || !SharedContextRuntimeToolProvider.isContextReaderCode(row.getString("toolCode"))) continue;
            var descriptor = SharedContextRuntimeToolProvider.descriptorForCode(row.getString("toolCode"));
            if (!(row.get("readHistory") instanceof JSONObject history)
                    || !descriptor.contractVersion().equals(history.getString("contractVersion"))
                    || !descriptor.schemaDigest().equals(history.getString("schemaDigest"))) continue;
            if (!(row.get("observation") instanceof JSONObject observation)
                    || !ContextReadObservationProjector.VERSION.equals(observation.getString("schemaVersion"))) continue;
            if (!runId.equals(observation.getString("sourceRunId"))
                    || row.getString("stepId") == null
                    || !row.getString("stepId").equals(observation.getString("sourceStepId"))
                    || !descriptor.code().equals(observation.getString("toolCode"))
                    || !descriptor.contractVersion().equals(observation.getString("contractVersion"))
                    || !descriptor.schemaDigest().equals(observation.getString("schemaDigest"))
                    || !Boolean.TRUE.equals(observation.getBoolean("untrustedData"))
                    || !"UNTRUSTED_SOURCE_DATA".equals(observation.getString("contentDisposition"))) throw invalid();
            JSONObject page = observation.getJSONObject("page");
            if (page == null || !"CONTRACT".equals(page.getString("operation"))) continue;
            JSONArray items = page.getJSONArray("items");
            if (items == null || items.size() != 1 || !Boolean.FALSE.equals(page.getBoolean("hasMore"))
                    || page.get("nextBefore") != null) throw invalid();
            JSONObject item = items.getJSONObject(0);
            String reference = item.getString("contentHash");
            String text = item.getString("text");
            Integer offset = item.getInteger("offset");
            Boolean more = item.getBoolean("hasMoreText");
            if (reference == null || !reference.matches("[a-f0-9]{64}") || text == null
                    || text.length() > 8193 || offset == null || offset < 0 || offset > 2_000_000 || more == null
                    || !reference.equals(item.getString("id"))
                    || !("contract:" + reference).equals(item.getString("sourceId"))
                    || !"CONTRACT".equals(item.getString("role")) || !"0".equals(item.getString("sequence"))
                    || more && (text.isEmpty() || !Integer.valueOf(offset + text.length()).equals(item.getInteger("nextOffset")))
                    || !more && item.get("nextOffset") != null) throw invalid();
            chunks.computeIfAbsent(reference, key -> new HashSet<>()).add(new Chunk(offset, text, more));
        }
        Set<String> complete = new HashSet<>();
        for (var entry : chunks.entrySet()) if (complete(entry.getKey(), entry.getValue())) complete.add(entry.getKey());
        return Set.copyOf(complete);
    }

    private static boolean complete(String reference, Set<Chunk> chunks) {
        Integer terminalEnd = null;
        for (Chunk chunk : chunks) if (!chunk.more()) {
            if (terminalEnd != null && terminalEnd != chunk.end()) throw invalid();
            terminalEnd = chunk.end();
        }
        if (terminalEnd == null) return false;
        List<Chunk> ordered = new ArrayList<>(chunks);
        ordered.sort(Comparator.comparingInt(Chunk::offset).thenComparing(Comparator.comparingInt(Chunk::end).reversed()));
        StringBuilder text = new StringBuilder();
        for (Chunk chunk : ordered) {
            if (chunk.end() > terminalEnd) throw invalid();
            if (chunk.offset() > text.length()) return false;
            int overlap = Math.min(text.length() - chunk.offset(), chunk.text().length());
            for (int i = 0; i < overlap; i++)
                if (text.charAt(chunk.offset() + i) != chunk.text().charAt(i)) throw invalid();
            if (overlap < chunk.text().length()) text.append(chunk.text(), overlap, chunk.text().length());
        }
        if (text.length() != terminalEnd) return false;
        if (!reference.equals(SecureUtil.sha256(text.toString()))) throw invalid();
        JSONObject document;
        try { document = JSON.parseObject(text.toString()); }
        catch (RuntimeException malformed) { throw invalid(); }
        if (document == null || !ContextContractProjection.VERSION.equals(document.getString("schemaVersion"))
                || !(document.get("entry") instanceof JSONObject original)
                || !reference.equals(ContextContractProjection.referenceFor(document.getString("kind"), original))) throw invalid();
        return true;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("CONTEXT_CONTRACT_READ_COVERAGE_INVALID");
    }
}
