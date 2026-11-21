package com.aid.media.provider.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.aid.common.constant.HttpConstants;
import com.aid.common.utils.ProviderEndpointUtils;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.provider.GeminiOmniInteractionsCodec;
import com.aid.media.provider.ProviderResponseHelper;
import com.aid.media.provider.ProviderSubmissionFailures;
import com.aid.media.provider.ProviderSubmitResult;
import com.aid.media.provider.ProviderTaskResult;
import com.aid.media.provider.SubmitTimeoutResolver;
import com.aid.media.provider.VideoProviderClient;
import com.aid.media.provider.ReferencePromptSanitizer;
import com.alibaba.fastjson2.JSON;
import com.aid.media.util.TrustedMediaProbe;
import com.aid.media.util.MediaBytesFetcher;
import com.aid.common.oss.factory.OssFactory;
import com.aid.common.exception.ServiceException;
import com.aid.model.definition.ModelConfiguredRequestBody;
import java.util.Base64;
import java.util.LinkedHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Gemini Omni asynchronous Interactions adapter, isolated from generateContent image/text protocols. */
@Slf4j
@Component
@RequiredArgsConstructor
public class GeminiOmniVideoProviderClient implements VideoProviderClient {
    private final TrustedMediaProbe mediaProbe;
    @Override public String protocol() { return GeminiOmniInteractionsCodec.PROTOCOL; }

    @Override public Integer fallbackMaxReferenceImages(AiModelConfigVo model) {
        return GeminiOmniInteractionsCodec.capability(model).path("maxReferenceImages").asInt(0);
    }

    @Override public Integer fallbackMaxReferenceVideos(AiModelConfigVo model) {
        return GeminiOmniInteractionsCodec.capability(model).path("maxReferenceVideos").asInt(0);
    }

    @Override public boolean requiresVerifiedMetadataForQuote() { return true; }

    @Override public boolean hasTrustedMetadataForQuote(MediaVideoGenerateRequest request) {
        if (request.sourceVideoIds() == null || request.sourceVideoIds().isEmpty()) return true;
        return request.getResolvedReferenceVideos() != null
                && request.getResolvedReferenceVideos().size() == request.sourceVideoIds().size()
                && request.getResolvedReferenceVideos().stream().allMatch(video -> video.getDurationMs() != null
                    && video.getDurationMs() > 0 && video.getWidth() != null && video.getWidth() > 0
                    && video.getHeight() != null && video.getHeight() > 0);
    }

    @Override public void normalizeRequest(AiModelConfigVo model, MediaVideoGenerateRequest request) {
        var capability = GeminiOmniInteractionsCodec.capability(model);
        if (request.getDurationSeconds() == null && capability.path("supportsDuration").asBoolean(false) && capability.has("defaultDurationSeconds"))
            request.setDurationSeconds(capability.path("defaultDurationSeconds").intValue());
        if (StrUtil.isBlank(request.getAspectRatio()) && capability.path("supportsAspectRatio").asBoolean(false))
            request.setAspectRatio(capability.path("defaultAspectRatio").asText(""));
        if (request.getOptions() == null) request.setOptions(new LinkedHashMap<>());
        request.getOptions().putIfAbsent("resolution", request.getOptions().getOrDefault("targetResolution", capability.path("defaultSizeCode").asText()));
    }

    @Override public void validateRequest(AiModelConfigVo model, MediaVideoGenerateRequest request) {
        validateRequest(model, request, false);
    }

    @Override public void validateRequest(AiModelConfigVo model, MediaVideoGenerateRequest request, boolean planned) {
        if (StrUtil.isBlank(model.getApiKey())) throw ProviderSubmissionFailures.notSent("请配置 Omni 调用密钥");
        ProviderEndpointUtils.buildSubmitUrl(model.getBaseUrl(), model.getApiSuffix());
        ProviderEndpointUtils.normalizeTaskQueryTemplate(model.getTaskQuerySuffix());
        GeminiOmniInteractionsCodec.validate(model, request, planned);
    }

    @Override public void prepareSubmitRequest(AiModelConfigVo model, MediaVideoGenerateRequest request,
                                                Long mediaTaskId, String submissionTraceId) {
        if (GeminiOmniInteractionsCodec.capability(model).path("interactionsIdempotency").asBoolean(false))
            request.setProviderIdempotencyKey(StrUtil.blankToDefault(submissionTraceId, "aid-media-" + mediaTaskId));
    }

    @Override public ProviderSubmitResult submit(AiModelConfigVo model, MediaVideoGenerateRequest request) {
        validateRequest(model, request);
        ReferencePromptSanitizer.sanitizeInPlaceForIndexedMedia(request, GeminiOmniInteractionsCodec.images(request).size(),
                request.getResolvedReferenceVideos() == null ? 0 : request.getResolvedReferenceVideos().size(), 0);
        var mapped = ModelConfiguredRequestBody.apply(model, GeminiOmniInteractionsCodec.build(model, request), request);
        String body = JSON.toJSONString(mapped);
        org.slf4j.LoggerFactory.getLogger("media-image-input").info("Omni 请求凭证, requestKey={}, audit={}", request.getProviderIdempotencyKey(),
                JSON.toJSONString(GeminiOmniInteractionsCodec.requestAudit(mapped, body)));
        HttpRequest http = authenticate(HttpRequest.post(ProviderEndpointUtils.buildSubmitUrl(
                model.getBaseUrl(), model.getApiSuffix())), model).body(body);
        if (GeminiOmniInteractionsCodec.capability(model).path("interactionsIdempotency").asBoolean(false)
                && StrUtil.isNotBlank(request.getProviderIdempotencyKey()))
            http.header("Idempotency-Key", request.getProviderIdempotencyKey());
        try (HttpResponse response = http.execute()) {
            String raw = response.body();
            if (response.getStatus() < 200 || response.getStatus() >= 300)
                return ProviderSubmissionFailures.httpResponse(response.getStatus(), raw, model.getModelCode());
            var root = ProviderResponseHelper.readTree(raw);
            String id = root == null ? null : root.path("id").asText();
            // Even an already-completed response is reconciled through its durable interaction id.
            if (StrUtil.isBlank(id)) return ProviderSubmissionFailures.unknownResponse("上游未返回 Interaction ID");
            return ProviderSubmitResult.builder().providerTaskId(id).rawResponse(GeminiOmniInteractionsCodec.audit(root)).build();
        } catch (Exception failure) {
            log.warn("Omni 提交连接异常, model={}, type={}", model.getModelCode(), failure.getClass().getSimpleName());
            return ProviderSubmissionFailures.unknownResponse("Omni 提交连接异常");
        }
    }

    @Override public ProviderTaskResult query(AiModelConfigVo model, String interactionId) {
        String url = ProviderEndpointUtils.buildTaskQueryUrl(model.getBaseUrl(), model.getTaskQuerySuffix(), interactionId);
        try (HttpResponse response = authenticate(HttpRequest.get(url), model).execute()) {
            if (response.getStatus() < 200 || response.getStatus() >= 300) return GeminiOmniInteractionsCodec.anomaly(null);
            String raw = response.body();
            try { return GeminiOmniInteractionsCodec.parse(raw, content -> storeVideo(model, content)); }
            catch (ServiceException persistenceFailure) {
                var root = ProviderResponseHelper.readTree(raw);
                if (root != null && "completed".equals(root.path("status").asText())) {
                    log.warn("Omni 结果待保存, interactionId={}, reason={}", interactionId, persistenceFailure.getMessage());
                    return GeminiOmniInteractionsCodec.pendingPersistence(root);
                }
                throw persistenceFailure;
            }
        } catch (Exception failure) {
            log.warn("Omni 查询异常, interactionId={}, type={}", interactionId, failure.getClass().getSimpleName());
            return GeminiOmniInteractionsCodec.anomaly(null);
        }
    }

    private GeminiOmniInteractionsCodec.StoredVideo storeVideo(AiModelConfigVo model, com.fasterxml.jackson.databind.JsonNode content) {
        if (!"video/mp4".equals(content.path("mime_type").asText())) throw new TrustedMediaProbe.InvalidVideoException("上游视频格式不支持");
        byte[] bytes;
        if (content.path("data").isTextual()) {
            String data = content.path("data").asText();
            if (data.length() > (256L * 1024 * 1024 + 2) / 3 * 4) throw new TrustedMediaProbe.InvalidVideoException("上游视频文件过大");
            try { bytes = Base64.getDecoder().decode(data); }
            catch (IllegalArgumentException invalid) { throw new TrustedMediaProbe.InvalidVideoException("上游视频编码无效"); }
        } else if (content.path("uri").asText().startsWith("gs://")) {
            bytes = readGcsVideo(model, content.path("uri").asText());
        } else {
            var downloaded = MediaBytesFetcher.fetch(content.path("uri").asText(), 256 * 1024 * 1024, 60_000);
            if (downloaded.isEmpty() || downloaded.truncated()) throw new ServiceException("上游视频读取失败");
            bytes = downloaded.bytes();
        }
        TrustedMediaProbe.VideoMetadata metadata = mediaProbe.video(bytes);
        try {
            String url = OssFactory.instance().uploadSuffix(bytes, ".mp4", "video/mp4").getUrl();
            return new GeminiOmniInteractionsCodec.StoredVideo(url, metadata);
        } catch (java.io.IOException failure) { throw new ServiceException("视频存储失败"); }
    }

    /** Cloud GCS is an authenticated Google storage API, never an arbitrary credential-bearing media GET. */
    private static byte[] readGcsVideo(AiModelConfigVo model, String location) {
        if (!"array".equals(GeminiOmniInteractionsCodec.capability(model).path("interactionsResponseFormat").asText())
                || !"Authorization".equalsIgnoreCase(model.getAuthHeader())
                || !"Bearer ".equals(model.getAuthPrefix())) throw new ServiceException("Cloud 资源读取需配置 Google Bearer 凭证");
        try {
            java.net.URI source = java.net.URI.create(location);
            if (source.getHost() == null || source.getUserInfo() != null || source.getRawQuery() != null
                    || source.getRawFragment() != null || source.getPath() == null || source.getPath().isBlank())
                throw new TrustedMediaProbe.InvalidVideoException("上游 Cloud 资源地址无效");
            String url = new java.net.URI("https", null, "storage.googleapis.com", -1,
                    "/" + source.getHost() + source.getPath(), null, null).toASCIIString();
            try (HttpResponse response = authenticate(HttpRequest.get(url), model).executeAsync()) {
                if (response.getStatus() != 200) throw new ServiceException("Cloud 视频读取暂不可用");
                byte[] bytes = response.bodyStream().readNBytes(256 * 1024 * 1024 + 1);
                if (bytes.length > 256 * 1024 * 1024) throw new TrustedMediaProbe.InvalidVideoException("上游视频文件过大");
                return bytes;
            }
        } catch (TrustedMediaProbe.InvalidVideoException failure) { throw failure; }
        catch (ServiceException failure) { throw failure; }
        catch (Exception failure) { throw new ServiceException("Cloud 视频读取暂不可用"); }
    }

    private static HttpRequest authenticate(HttpRequest request, AiModelConfigVo model) {
        String header = StrUtil.blankToDefault(model.getAuthHeader(), HttpConstants.HEADER_AUTHORIZATION);
        String prefix = model.getAuthPrefix() == null ? HttpConstants.AUTH_BEARER_PREFIX : model.getAuthPrefix();
        return request.header(header, prefix + model.getApiKey(), true)
                .header(HttpConstants.HEADER_CONTENT_TYPE, HttpConstants.CONTENT_TYPE_JSON, true)
                .setFollowRedirects(false).timeout(SubmitTimeoutResolver.resolveMs(model, 60_000));
    }
}
