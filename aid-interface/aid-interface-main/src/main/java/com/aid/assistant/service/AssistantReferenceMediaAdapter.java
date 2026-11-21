package com.aid.assistant.service;

import com.alibaba.fastjson2.JSONObject;
import com.aid.media.dto.MediaTextGenerateRequest;
import java.util.List;
import java.util.Set;

/** Module-owned media delivery. Implementations must reauthorize immutable references on every call. */
public interface AssistantReferenceMediaAdapter {
    boolean supports(String source);
    List<MediaTextGenerateRequest.TextContentPart> adapt(Long actor, JSONObject reference, Set<String> modalities);
}
