package com.aid.assistant.service;
import com.aid.media.dto.MediaTextGenerateRequest;
import com.alibaba.fastjson2.JSONObject;
import java.util.List;
import java.util.Set;
/** Optional module extension. Every generated media part must be reauthorized against persisted execution evidence. */
public interface AssistantExecutionMediaAdapter {
    List<MediaTextGenerateRequest.TextContentPart> adapt(Long actor, JSONObject context, Set<String> modalities);
}
