package com.aid.runtime.assistant;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.aid.media.dto.MediaTextGenerateRequest;
import lombok.Data;

/** Persisted text-generation result for an assistant iteration. */
@Data
public class AssistantLoopModelResult
{
    private Long mediaTaskId;
    private String status;
    private String resultJson;
    private String resultDigest;
    private Long runId;
    private Long observedRunRevision;
    private Long observedPlanRevision;
    private String nativeToolBindingsJson;
    private MediaTextGenerateRequest.TextMessageItem toolMessage;

    /**
     * Canonical digest of the persisted visible text. Native tool-call responses may have no
     * visible text, and storage may represent that value as either null or the empty string.
     * Both representations therefore bind to SHA-256 of the same empty UTF-8 text.
     */
    public static String persistedTextDigest(String value)
    {
        return SecureUtil.sha256(StrUtil.nullToEmpty(value));
    }
}
