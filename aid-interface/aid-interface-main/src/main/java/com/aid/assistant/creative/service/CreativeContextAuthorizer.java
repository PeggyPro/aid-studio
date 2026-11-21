package com.aid.assistant.creative.service;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.aid.assistant.dto.AssistantRequests;
import com.aid.assistant.error.AssistantServiceException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Objects;

/** Resolves one exact read grant from the runtime-frozen context manifest. */
@Component
public class CreativeContextAuthorizer {
    private static final String SCHEMA_VERSION = "assistant-context-manifest/v1";

    public AuthorizedSourceRef requireRead(String contextManifestJson, String sourceKind,
                                           String sourceObjectId, String contentHash) {
        String kind = normalize(sourceKind);
        if (StrUtil.isBlank(kind) || StrUtil.isBlank(sourceObjectId) || contentHash == null
                || !contentHash.matches("[0-9a-f]{64}")) {
            invalid("来源引用无效");
        }
        JSONObject manifest;
        try {
            manifest = JSON.parseObject(contextManifestJson);
        } catch (RuntimeException error) {
            invalid("运行上下文清单无效");
            return null;
        }
        if (manifest == null || !SCHEMA_VERSION.equals(manifest.getString("schemaVersion"))) {
            invalid("运行上下文清单版本无效");
        }
        JSONArray refs = manifest.getJSONArray("refs");
        if (refs == null || refs.size() > 1000) invalid("运行上下文清单缺少来源授权");
        for (Object item : refs) {
            if (!(item instanceof JSONObject ref)
                    || !kind.equals(normalize(ref.getString("kind")))
                    || !"READ".equals(ref.getString("access"))) {
                continue;
            }
            JSONObject canonical = ref.getJSONObject("canonicalRef");
            if (canonical == null) continue;
            String objectId = objectId(kind, canonical);
            String expectedHash = canonical.getString("contentHash");
            String contentRef = ref.getString("contentRef");
            if (Objects.equals(sourceObjectId, objectId)
                    && Objects.equals(contentHash, expectedHash)
                    && StrUtil.isNotBlank(contentRef)) {
                AssistantRequests.ContextRef targetRef = JSON.parseObject(
                        canonical.toJSONString(), AssistantRequests.ContextRef.class);
                targetRef.setKind(kind);
                return new AuthorizedSourceRef(kind, objectId, expectedHash, contentRef, targetRef);
            }
        }
        throw new SourceAuthorizationRejectedException(HttpStatus.FORBIDDEN.value(),
                "CREATIVE_SOURCE_SCOPE_REQUIRED", "当前运行未获准读取该来源，请先更新上下文授权", sourceObjectId);
    }

    private String objectId(String kind, JSONObject canonical) {
        if ("SCRIPT".equals(kind)) return canonical.getString("scriptId");
        if ("DOCUMENT".equals(kind)) return canonical.getString("documentId");
        return canonical.getString("sourceObjectId");
    }

    private String normalize(String value) {
        return StrUtil.isBlank(value) ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private void invalid(String message) {
        throw new SourceAuthorizationRejectedException(HttpStatus.UNPROCESSABLE_ENTITY.value(),
                "CREATIVE_CONTEXT_INVALID", message, null);
    }

    public record AuthorizedSourceRef(String sourceKind, String sourceObjectId,
                                      String contentHash, String contentRef,
                                      AssistantRequests.ContextRef targetRef) {
    }

    /** A local manifest guard rejected access before any source read or mutation. */
    public static final class SourceAuthorizationRejectedException extends AssistantServiceException {
        private SourceAuthorizationRejectedException(int status, String code, String message, Object details) {
            super(status, code, message, details);
        }
    }
}
