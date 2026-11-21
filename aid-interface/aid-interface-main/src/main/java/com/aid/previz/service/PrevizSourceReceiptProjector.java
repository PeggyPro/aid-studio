package com.aid.previz.service;

import com.aid.assistant.effect.VerifiedRuntimeToolEffect;
import com.aid.assistant.service.AssistantToolReceiptProjector;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Bounded continuation receipt: never copies the full authored source into every model turn. */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "aid.previz.runtime", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class PrevizSourceReceiptProjector implements AssistantToolReceiptProjector {
    private final PrevizSourceWriteCompletionVerifier writes;
    @Override public Set<String> supportedToolCodes() { return Set.of(PrevizRuntimeToolProvider.WRITE,PrevizRuntimeToolProvider.EDIT); }
    @Override public JSONObject projectCurrentReceipt(VerifiedRuntimeToolEffect effect) {
        writes.requirePersistedWrite(effect);
        JSONObject saved = JSON.parseObject(effect.outputJson());
        JSONObject input = JSON.parseObject(effect.preparedPayloadJson() == null
                ? effect.argumentsJson() : effect.preparedPayloadJson());
        JSONObject receipt = new JSONObject();
        receipt.put("kind", "PREVIZ_SOURCE_VERSION");
        receipt.put("projectId", saved.getString("projectId"));
        receipt.put("sourceVersion", saved.getString("sourceVersion"));
        if(PrevizRuntimeToolProvider.EDIT.equals(effect.toolCode())) {
            receipt.put("changedPaths",java.util.List.of(input.getString("path")));
            receipt.put("replacements",saved.getJSONArray("replacements"));return receipt;
        }
        receipt.put("fileCount", input.getJSONObject("files").size());
        var paths = new com.alibaba.fastjson2.JSONArray();
        for (String path : input.getJSONObject("files").keySet().stream().sorted().toList()) {
            paths.add(path);
            if (paths.toJSONString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 6000) {
                paths.remove(paths.size() - 1); break;
            }
        }
        receipt.put("paths", paths);
        receipt.put("pathsTruncated", paths.size() < input.getJSONObject("files").size());
        return receipt;
    }
}
