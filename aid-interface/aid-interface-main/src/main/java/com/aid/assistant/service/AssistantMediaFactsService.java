package com.aid.assistant.service;

import cn.hutool.cache.impl.LRUCache;
import cn.hutool.crypto.SecureUtil;
import com.aid.assistant.artifact.domain.AidRuntimeArtifact;
import com.aid.assistant.artifact.dto.ArtifactRequests;
import com.aid.assistant.artifact.service.AssistantArtifactService;
import com.aid.media.service.VerifiedMediaMetadataService;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Measured properties of authorized output bytes, separate from immutable archive receipts. */
@Service
public class AssistantMediaFactsService {
    @Resource private AssistantArtifactService artifacts;
    @Resource private VerifiedMediaMetadataService metadata;
    private final LRUCache<String, JSONObject> measurements =
            new LRUCache<>(256, TimeUnit.MINUTES.toMillis(5));

    public JSONObject imageFacts(Long userId, AidRuntimeArtifact artifact) {
        if (artifact == null || !"IMAGE".equals(artifact.getArtifactType())) return null;
        var request = new ArtifactRequests.Media();
        request.setArtifactId(artifact.getId());
        // Access, current source identity and archive digest are checked even on a cache hit.
        var media = artifacts.media(userId, request);
        if (media == null || !Objects.equals(String.valueOf(artifact.getId()), media.getArtifactId())
                || !Objects.equals(artifact.getContentHash(), media.getContentHash())
                || !"IMAGE".equals(media.getArtifactType()))
            throw new IllegalStateException("ASSISTANT_MEDIA_FACT_IDENTITY_INVALID");
        String key = userId + ":" + artifact.getId() + ":" + artifact.getContentHash()
                + ":" + SecureUtil.sha256(media.getResourceUrl());
        try {
            JSONObject facts = measurements.get(key, false, () -> inspect(media.getResourceUrl()));
            return JSON.parseObject(facts.toJSONString());
        } catch (RuntimeException unavailable) {
            if (Thread.currentThread().isInterrupted()) throw unavailable;
            // A failed technical read is not a failed generation and must never trigger a refund or retry.
            return JSONObject.of("verificationStatus", "UNAVAILABLE",
                    "reasonCode", "MEDIA_TECHNICAL_FACTS_UNAVAILABLE");
        }
    }

    private JSONObject inspect(String source) {
        var measured = metadata.inspect(source, "image");
        if (measured == null || measured.width() <= 0 || measured.height() <= 0
                || measured.sizeBytes() <= 0)
            throw new IllegalStateException("MEDIA_DIMENSIONS_UNAVAILABLE");
        return JSONObject.of("verificationStatus", "VERIFIED", "measurementKind", "STORED_MEDIA_BYTES",
                "width", measured.width(), "height", measured.height(), "fileSizeBytes", measured.sizeBytes());
    }
}
