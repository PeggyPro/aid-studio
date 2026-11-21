package com.aid.runtime.assistant;

import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.domain.AidRuntimeRunStep;
import com.aid.media.dto.MediaTextGenerateRequest.TextMessageItem;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import cn.hutool.crypto.SecureUtil;
import org.springframework.stereotype.Component;
import java.util.*;

/** Read-only recovery of API-returned thinking from the original verified native turn. */
@Component
public final class NativeAssistantReasoningHistory {
    private final NativeAssistantTurnArchive turns;
    public NativeAssistantReasoningHistory(NativeAssistantTurnArchive turns) { this.turns = turns; }

    public Map<String,Object> read(AidRuntimeRun run, AidRuntimeRunStep step, String stream, int offset, int limit) {
        if (!"MAIN_AGENT".equals(step.getStepKind()) || step.getMediaTaskId() == null) return null;
        TextMessageItem turn = turns.readIfPresent(run, step.getId(), step.getMediaTaskId());
        if (turn == null) return null;
        JSONObject value = JSON.parseObject(JSON.toJSONString(turn));
        String text = value.getString("reasoningContent"), kind = "PROVIDER_REASONING";
        if (text == null || text.isBlank()) {
            StringBuilder thinking = new StringBuilder();
            var blocks = value.getJSONArray("thinkingBlocks");
            if (blocks != null) for (int i = 0; i < blocks.size(); i++) {
                var block = blocks.getJSONObject(i);
                if ("thinking".equals(block.getString("type"))) thinking.append(Objects.toString(block.getString("thinking"), ""));
            }
            text = thinking.toString();
        }
        if (text.isBlank()) {
            StringBuilder summaries = new StringBuilder();
            var items = value.getJSONArray("responseItems");
            if (items != null) for (int i = 0; i < items.size(); i++) {
                var item = items.getJSONObject(i);
                if (!"reasoning".equals(item.getString("type"))) continue;
                var summary = item.getJSONArray("summary");
                if (summary != null) for (int j = 0; j < summary.size(); j++) {
                    var part = summary.getJSONObject(j);
                    if ("summary_text".equals(part.getString("type"))) summaries.append(Objects.toString(part.getString("text"), ""));
                }
            }
            text = summaries.toString(); kind = "PUBLIC_SUMMARY";
        }
        if (text.isBlank()) return null;
        String identity = SecureUtil.sha256("native-reasoning:" + step.getId() + ":" + kind + ":" + text);
        if (stream != null && !identity.equals(stream)) return null;
        List<String> chunks = new ArrayList<>();
        for (int start = 0; start < text.length();) {
            int end = Math.min(text.length(), start + 8192);
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
            chunks.add(text.substring(start, end)); start = end;
        }
        if (offset < 0 || offset > chunks.size() || limit < 1 || limit > 16) throw new IllegalArgumentException("Invalid native reasoning page");
        int end = Math.min(chunks.size(), offset + limit);
        List<Map<String,Object>> page = new ArrayList<>();
        for (int i = offset; i < end; i++) page.add(Map.of("content", chunks.get(i), "reasoningKind", kind));
        return Map.of("status", end < chunks.size() ? "AVAILABLE" : "COMPLETE", "stream", identity,
                "streams", List.of(identity), "chunks", page, "nextOffset", end, "hasMore", end < chunks.size());
    }
}
