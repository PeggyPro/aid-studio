package com.aid.media.provider;

import cn.hutool.core.util.StrUtil;
import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.dto.ReferenceVideoInput;
import com.aid.media.util.MediaBytesFetcher;
import com.aid.media.util.ModelCapabilityResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import com.aid.media.util.TrustedMediaProbe;

/** Interactions wire format. Transport variants are configured explicitly, never inferred from a host. */
public final class GeminiOmniInteractionsCodec {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    public static final String PROTOCOL = "gemini-omni-interactions-video";
    private static final Map<String, String> TASKS = Map.of(
            "text_to_video", "text_to_video", "image_to_video", "image_to_video",
            "start_end_to_video", "image_to_video", "reference_to_video", "reference_to_video",
            "video_edit", "edit", "video_extend", "extend", "video_enhance", "upscale");

    private GeminiOmniInteractionsCodec() { }

    public static JsonNode capability(AiModelConfigVo model) {
        JsonNode value = ModelCapabilityResolver.parseCapability(model.getCapabilityJson());
        if (value == null || !value.isObject()) throw new ServiceException("Omni 能力配置不完整");
        return value;
    }

    public static String mode(AiModelConfigVo model, MediaVideoGenerateRequest request) {
        String code = StrUtil.blankToDefault(request.getCapabilityCode(), model.getCapabilityCode());
        if (StrUtil.isBlank(code)) code = model.getGenerateMode();
        if (!TASKS.containsKey(code)) throw new ServiceException("请选择 Omni 视频能力");
        return code;
    }

    public static List<String> images(MediaVideoGenerateRequest request) {
        List<String> result = new ArrayList<>();
        addImage(result, request.getImageUrl());
        Object last = null;
        for (String key : List.of("lastFrameImageUrl", "endImageUrl", "end_image_url")) {
            Object value = option(request, key);
            if (value == null) continue;
            if (last != null && !last.equals(value)) throw new ServiceException("尾帧参数冲突");
            last = value;
        }
        addImage(result, last);
        Object references = option(request, "referenceImages");
        if (references instanceof List<?> list) for (Object image : list) if (!result.contains(image)) addImage(result, image);
        Object alternate = option(request, "images");
        if (alternate instanceof List<?> list) for (Object image : list) if (!result.contains(image)) addImage(result, image);
        return result;
    }

    private static void addImage(List<String> result, Object source) {
        if (source == null) return;
        if (!(source instanceof String text) || text.isBlank()) throw new ServiceException("参考图参数无效");
        // A first/last pair may deliberately use the same image, so do not deduplicate roles.
        result.add(text);
    }

    public static Object option(MediaVideoGenerateRequest request, String name) {
        return request.getOptions() == null ? null : request.getOptions().get(name);
    }

    public static String resolution(AiModelConfigVo model, MediaVideoGenerateRequest request) {
        Object requested = option(request, "resolution");
        if (requested == null) requested = option(request, "targetResolution");
        return ModelCapabilityResolver.resolveSize(model, requested == null ? null : requested.toString());
    }

    /** Pure preflight, also used for quotes: no downloads, uploads, network calls or task writes. */
    public static void validate(AiModelConfigVo model, MediaVideoGenerateRequest request, boolean planned) {
        JsonNode cap = capability(model);
        String mode = mode(model, request);
        String shape = cap.path("interactionsResponseFormat").asText();
        if (!List.of("array", "object").contains(shape)) throw new ServiceException("请配置 Interactions 响应格式类型");
        if (shape.equals("array") && cap.path("interactionsDelivery").asText("inline").equals("uri")) {
            String gcs = cap.path("interactionsOutputGcsUri").asText();
            if (!gcs.startsWith("gs://") || gcs.length() < 8) throw new ServiceException("Cloud 地址交付需配置输出 GCS 路径");
        }
        JsonNode tasks = cap.path("interactionsTasks");
        if (!tasks.isArray() || !contains(tasks, TASKS.get(mode))) throw new ServiceException("当前调用通道不支持此视频能力");
        List<String> images = images(request);
        int imageCount = images.size();
        int min = switch (mode) { case "image_to_video", "reference_to_video" -> 1; case "start_end_to_video" -> 2; default -> 0; };
        int max = switch (mode) { case "text_to_video", "video_extend", "video_enhance" -> 0; case "image_to_video" -> 1; case "start_end_to_video" -> 2; default -> cap.path("maxReferenceImages").asInt(); };
        int videoCount = request.sourceVideoIds() == null ? 0 : request.sourceVideoIds().size();
        if (mode.equals("reference_to_video") && videoCount > 0) min = 0;
        if ((!planned && imageCount < min) || imageCount > max) throw new ServiceException("参考图数量不符合所选视频能力");
        boolean requiresVideo = List.of("video_edit", "video_extend", "video_enhance").contains(mode);
        if ((!planned && requiresVideo && videoCount != 1) || videoCount > cap.path("maxReferenceVideos").asInt(0))
            throw new ServiceException("参考视频数量不符合所选视频能力");
        if (request.getReferenceAudios() != null && !request.getReferenceAudios().isEmpty())
            throw new ServiceException("Omni 不支持独立参考音频");
        if (!planned && !mode.equals("video_enhance") && StrUtil.isBlank(request.getPrompt())) throw new ServiceException("请输入视频描述");
        String resolution = resolution(model, request);
        if (!contains(cap.path("sizeOptions"), resolution)) throw new ServiceException("当前调用通道不支持此视频分辨率");
        if (!mode.equals("video_enhance")) {
            Integer duration = request.getDurationSeconds();
            int maxDuration = List.of("start_end_to_video", "video_extend").contains(mode) ? 10 : 15;
            if (duration == null || duration < 3 || duration > maxDuration || !contains(cap.path("durationOptions"), duration.toString()))
                throw new ServiceException("视频时长不在所选能力支持范围内");
        }
        String ratio = request.getAspectRatio();
        if (StrUtil.isNotBlank(ratio) && !contains(cap.path("aspectRatioOptions"), ratio))
            throw new ServiceException("视频比例不支持");
        for (String unsupported : List.of("fps", "targetFps", "slowMotion", "interpolation")) {
            Object value = option(request, unsupported);
            if (value != null && !Boolean.FALSE.equals(value)) throw new ServiceException("此模型不支持补帧、慢动作或自定义帧率");
        }
        Object interpolation = option(request, "interpolationMode");
        if (interpolation != null && !"NONE".equalsIgnoreCase(interpolation.toString())) throw new ServiceException("此模型不支持补帧");
        Object slowMotion = option(request, "slowMotionFactor");
        if (slowMotion != null && !"1".equals(slowMotion.toString())) throw new ServiceException("此模型不支持慢动作");
        if (!planned && videoCount > 0 && (request.getResolvedReferenceVideos() == null
                || request.getResolvedReferenceVideos().size() != videoCount))
            throw com.aid.media.service.MediaPreparationErrorCode.MEDIA_INPUT_METADATA_REQUIRED.reject("请先补齐参考视频元数据");
        if (request.getResolvedReferenceVideos() != null) for (ReferenceVideoInput video : request.getResolvedReferenceVideos()) {
            if (video.getDurationMs() == null || video.getWidth() == null || video.getHeight() == null)
                throw new ServiceException("请先补齐参考视频元数据");
            long maximum = mode.equals("video_extend") ? 30_000 : 15_000;
            if (video.getDurationMs() <= 0 || video.getDurationMs() > maximum) throw new ServiceException("参考视频时长超出能力限制");
            if (mode.equals("video_enhance")) validateUpscale(video, resolution, cap);
        }
    }

    private static void validateUpscale(ReferenceVideoInput source, String target, JsonNode capability) {
        int height = Math.min(source.getWidth(), source.getHeight());
        String from = height == 360 ? "360p" : height == 720 ? "720p" : "unsupported";
        if (!List.of("360p:720p", "360p:1080p", "720p:1080p", "720p:4k").contains(from + ":" + target.toLowerCase())
                || !contains(capability.path("upscalePairs").path(from), target))
            throw new ServiceException("此源视频与目标高清分辨率组合不支持");
    }

    private static boolean contains(JsonNode values, String expected) {
        if (!values.isArray() || expected == null) return false;
        for (JsonNode value : values) if (expected.equalsIgnoreCase(value.asText())) return true;
        return false;
    }

    /** Reads only previously authorized media through the standard storage reader at dispatch time. */
    public static Map<String, Object> build(AiModelConfigVo model, MediaVideoGenerateRequest request) {
        validate(model, request, false);
        JsonNode cap = capability(model);
        List<Map<String, Object>> input = new ArrayList<>();
        if (!mode(model, request).equals("video_enhance") && StrUtil.isNotBlank(request.getPrompt()))
            input.add(Map.of("type", "text", "text", request.getPrompt()));
        for (String source : images(request)) {
            var bytes = MediaBytesFetcher.fetchAuthorized(source, 20 * 1024 * 1024, 30_000);
            if (bytes.isEmpty() || bytes.truncated()) throw ProviderSubmissionFailures.notSent("已授权参考图读取失败或超过20MB");
            input.add(Map.of("type", "image", "mime_type", imageMime(bytes.bytes()), "data", Base64.getEncoder().encodeToString(bytes.bytes())));
        }
        if (request.getResolvedReferenceVideos() != null) for (ReferenceVideoInput video : request.getResolvedReferenceVideos()) {
            var content = MediaBytesFetcher.fetchAuthorized(video.getVideoUrl(), 20 * 1024 * 1024, 30_000);
            if (content.isEmpty() || content.truncated()) throw ProviderSubmissionFailures.notSent("参考视频读取失败或超过20MB内联限制");
            input.add(Map.of("type", "video", "mime_type", "video/mp4", "data", Base64.getEncoder().encodeToString(content.bytes())));
        }
        Map<String, Object> format = new LinkedHashMap<>();
        format.put("type", "video");
        format.put("delivery", cap.path("interactionsDelivery").asText("inline"));
        if (cap.path("interactionsResponseFormat").asText().equals("array") && Objects.equals("uri", format.get("delivery")))
            format.put("gcs_uri", cap.path("interactionsOutputGcsUri").asText());
        format.put("resolution", resolution(model, request));
        if (!mode(model, request).equals("video_enhance")) {
            format.put("duration", request.getDurationSeconds() + "s");
            if (StrUtil.isNotBlank(request.getAspectRatio())) format.put("aspect_ratio", request.getAspectRatio());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", ModelCodeResolver.resolveUpstreamModel(model, request.getModelName()));
        body.put("input", input);
        body.put("response_format", cap.path("interactionsResponseFormat").asText().equals("array") ? List.of(format) : format);
        body.put("generation_config", Map.of("video_config", Map.of("task", TASKS.get(mode(model, request)))));
        if (cap.path("interactionsBackground").asBoolean(false)) { body.put("background", true); body.put("store", true); }
        return body;
    }

    /** Preserve original reference bytes; MIME follows the verified file, never the URL extension. */
    private static String imageMime(byte[] bytes) {
        if (bytes.length >= 8 && bytes[0] == (byte) 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') return "image/png";
        if (bytes.length >= 3 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xd8 && bytes[2] == (byte) 0xff) return "image/jpeg";
        if (bytes.length >= 12) {
            String signature = new String(bytes, 0, 12, java.nio.charset.StandardCharsets.ISO_8859_1);
            if (signature.startsWith("RIFF") && signature.endsWith("WEBP")) return "image/webp";
            if (signature.substring(4, 8).equals("ftyp")) {
                String brand = signature.substring(8, 12);
                if (List.of("heic", "heix", "hevc", "hevx").contains(brand)) return "image/heic";
                if (List.of("mif1", "msf1").contains(brand)) return "image/heif";
            }
        }
        throw ProviderSubmissionFailures.notSent("参考图格式不在 Omni 支持范围内");
    }

    public record StoredVideo(String url, TrustedMediaProbe.VideoMetadata metadata) { }

    /** Audit the exact mapped payload without retaining prompts, signed URLs or inline media. */
    public static Map<String, Object> requestAudit(Map<String, Object> body, String serialized) {
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("requestSha256", cn.hutool.crypto.SecureUtil.sha256(serialized));
        audit.put("model", body.get("model"));
        Object formats = body.get("response_format");
        audit.put("responseFormat", formats instanceof List<?> list
                ? list.stream().map(GeminiOmniInteractionsCodec::auditFormat).toList() : auditFormat(formats));
        if (body.get("generation_config") instanceof Map<?, ?> generation
                && generation.get("video_config") instanceof Map<?, ?> video)
            audit.put("task", video.get("task"));
        List<Map<String, Object>> inputs = new ArrayList<>();
        if (body.get("input") instanceof List<?> values) for (Object value : values) {
            if (!(value instanceof Map<?, ?> item)) continue;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", item.get("type"));
            if (item.get("mime_type") != null) entry.put("mimeType", item.get("mime_type"));
            if (item.get("text") instanceof String text) {
                entry.put("textChars", text.length());
                entry.put("textSha256", cn.hutool.crypto.SecureUtil.sha256(text));
            }
            if (item.get("data") instanceof String data) {
                entry.put("encodedDataChars", data.length());
                entry.put("encodedDataSha256", cn.hutool.crypto.SecureUtil.sha256(data));
            }
            if (item.get("uri") instanceof String uri)
                entry.put("uriSha256", cn.hutool.crypto.SecureUtil.sha256(uri));
            inputs.add(entry);
        }
        audit.put("input", inputs);
        return audit;
    }

    private static Map<String, Object> auditFormat(Object format) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (format instanceof Map<?, ?> values) for (String key : List.of("type", "delivery", "resolution", "duration", "aspect_ratio"))
            if (values.containsKey(key)) result.put(key, values.get(key));
        return result;
    }

    public static ProviderTaskResult parse(String raw, Function<JsonNode, StoredVideo> store) {
        JsonNode root = ProviderResponseHelper.readTree(raw);
        String status = root == null ? "" : root.path("status").asText();
        if (!List.of("in_progress", "completed", "failed", "cancelled", "incomplete").contains(status)) return anomaly(null);
        Map<String, Object> usage = usage(root.path("usage"));
        List<String> urls = new ArrayList<>();
        List<StoredVideo> videos = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        if (status.equals("completed")) for (JsonNode step : root.path("steps")) {
            if (!step.path("type").asText().equals("model_output")) continue;
            for (JsonNode content : step.path("content")) {
                if (content.path("type").asText().equals("text")) texts.add(content.path("text").asText());
                if (!content.path("type").asText().equals("video")) continue;
                StoredVideo video;
                try { video = store.apply(content); }
                catch (TrustedMediaProbe.InvalidVideoException invalid) {
                    ObjectNode rejected = (ObjectNode) content;
                    rejected.remove(List.of("uri", "data"));
                    rejected.put("validation_error", invalid.getMessage());
                    continue;
                }
                videos.add(video);
                urls.add(video.url());
                ObjectNode stored = (ObjectNode) content;
                stored.remove("data");
                stored.put("uri", video.url());
                stored.set("metadata", MAPPER.valueToTree(video.metadata()));
            }
        }
        boolean complete = status.equals("completed");
        if (!videos.isEmpty()) usage.put("media_duration_ms", videos.get(0).metadata().durationMs());
        boolean failed = List.of("failed", "cancelled", "incomplete").contains(status) || complete && urls.isEmpty();
        if (failed) usage.put("generation_failed", true);
        return ProviderTaskResult.builder().status(failed ? "FAILED" : complete ? "SUCCEEDED" : "PROCESSING")
                .providerStatus(status).querySuccessful(true).terminalConfirmed(!status.equals("in_progress"))
                .resultUrl(urls.isEmpty() ? null : urls.get(0)).resultUrls(urls).resultCount(urls.size())
                .persistedResultUrls(urls).persistedResultMimeTypes(videos.stream().map(video -> "video/mp4").toList())
                .persistedResultFileSizes(videos.stream().map(video -> video.metadata().sizeBytes()).toList())
                .outputDurationSeconds(videos.isEmpty() ? null : (videos.get(0).metadata().durationMs() + 999) / 1000)
                .resultText(texts.isEmpty() ? null : String.join("\n\n", texts)).usage(usage)
                .errorMessage(failed ? complete ? "模型未返回有效视频" : "视频生成未完成，请查看任务详情" : null)
                .rawResponse(audit(root)).build();
    }

    public static ProviderTaskResult anomaly(String raw) {
        return ProviderTaskResult.builder().status("PROCESSING").querySuccessful(false).terminalConfirmed(false)
                .errorMessage("上游任务查询暂不可用").rawResponse(raw).build();
    }

    /** Upstream completed, but local persistence is pending; this is not an upstream query failure. */
    public static ProviderTaskResult pendingPersistence(JsonNode root) {
        ObjectNode receipt = root.deepCopy();
        receipt.remove("steps");
        return ProviderTaskResult.builder().status("PROCESSING").providerStatus("completed")
                .querySuccessful(true).terminalConfirmed(false).usage(usage(root.path("usage")))
                .errorMessage("视频已生成，保存暂未完成").rawResponse(receipt.toString()).build();
    }

    /** Keeps native usage; explicit modality totals are mutually exclusive, thought tokens are separate. */
    private static Map<String, Object> usage(JsonNode usage) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!usage.isObject()) return result;
        result.put("interactions_usage", com.alibaba.fastjson2.JSON.parseObject(usage.toString()));
        if (usage.path("total_input_tokens").isIntegralNumber()) result.put("input_tokens", usage.path("total_input_tokens").longValue());
        if (usage.path("total_thought_tokens").isIntegralNumber()) result.put("reasoning_tokens", usage.path("total_thought_tokens").longValue());
        List<String> unpriced = new ArrayList<>();
        long outputTotal = 0;
        for (JsonNode bucket : usage.path("output_tokens_by_modality")) {
            String type = bucket.path("modality").asText();
            if (List.of("video", "text").contains(type) && bucket.path("tokens").isIntegralNumber())
                result.merge(type + "_output_tokens", bucket.path("tokens").longValue(),
                        (previous, next) -> (Long) previous + (Long) next);
            else if (bucket.path("tokens").asLong() > 0) unpriced.add(type);
            outputTotal += bucket.path("tokens").asLong();
        }
        if (usage.has("total_output_tokens") && usage.path("total_output_tokens").asLong() != outputTotal)
            unpriced.add("UNATTRIBUTED_OUTPUT");
        if (usage.path("output_tokens_by_modality").isArray() && unpriced.isEmpty()
                && (!usage.has("total_output_tokens") || usage.path("total_output_tokens").asLong() == outputTotal)) {
            result.putIfAbsent("text_output_tokens", 0L);
            result.putIfAbsent("video_output_tokens", 0L);
            result.put("output_usage_complete", true);
        } else result.put("output_usage_complete", false);
        if (!unpriced.isEmpty()) result.put("unpriced_output_modalities", unpriced);
        return result;
    }

    /** Thought content and inline bytes never enter task response_json. */
    public static String audit(JsonNode root) {
        if (root == null) return null;
        ObjectNode copy = root.deepCopy();
        List<JsonNode> outputs = new ArrayList<>();
        for (JsonNode step : copy.path("steps")) if (Objects.equals("model_output", step.path("type").asText())) {
            for (JsonNode content : step.path("content")) if (content instanceof ObjectNode object) object.remove("data");
            outputs.add(step);
        }
        copy.putArray("steps").addAll(outputs);
        return copy.toString();
    }
}
