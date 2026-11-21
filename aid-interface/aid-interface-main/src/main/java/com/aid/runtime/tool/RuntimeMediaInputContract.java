package com.aid.runtime.tool;

import com.aid.media.dto.MediaAudioGenerateRequest;
import com.aid.media.dto.MediaImageGenerateRequest;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.alibaba.fastjson2.JSONObject;
import java.util.LinkedHashMap;
import java.util.Map;

/** Canonical assistant fields to existing media DTOs. Model defaults and validation remain in quoting. */
public final class RuntimeMediaInputContract {
    private RuntimeMediaInputContract() { }

    public static Map<String, String> aliases(String mediaType) {
        return switch (mediaType) {
            case "IMAGE" -> Map.of("aspectRatio", "options.aspectRatio");
            case "VIDEO" -> Map.of("resolution", "options.resolution");
            case "AUDIO" -> Map.of("text", "ttsText");
            default -> Map.of();
        };
    }

    public static MediaImageGenerateRequest image(JSONObject input) {
        var request = new MediaImageGenerateRequest();
        request.setModelName(input.getString("modelCode"));
        request.setCapabilityCode(input.getString("capabilityCode"));
        request.setPrompt(input.getString("prompt"));
        request.setNegativePrompt(input.getString("negativePrompt"));
        request.setSize(input.getString("size"));
        request.setExpectedImageCount(input.getInteger("expectedImageCount"));
        if (input.get("aspectRatio") != null)
            request.setOptions(new LinkedHashMap<>(Map.of("aspectRatio", input.getString("aspectRatio"))));
        return request;
    }

    public static MediaVideoGenerateRequest video(JSONObject input) {
        var request = new MediaVideoGenerateRequest();
        request.setModelName(input.getString("modelCode"));
        request.setCapabilityCode(input.getString("capabilityCode"));
        request.setPrompt(input.getString("prompt"));
        request.setDurationSeconds(input.getInteger("durationSeconds"));
        request.setAspectRatio(input.getString("aspectRatio"));
        request.setAudio(input.getBoolean("audio"));
        request.setBgm(input.getBoolean("bgm"));
        request.setAudioType(input.getString("audioType"));
        request.setVoiceId(input.getString("voiceId"));
        if (input.get("resolution") != null)
            request.setOptions(new LinkedHashMap<>(Map.of("resolution", input.getString("resolution"))));
        return request;
    }

    public static MediaAudioGenerateRequest audio(JSONObject input) {
        var request = new MediaAudioGenerateRequest();
        request.setModelName(input.getString("modelCode"));
        request.setCapabilityCode(input.getString("capabilityCode"));
        request.setTtsText(input.getString("text"));
        request.setVoiceCode(input.getString("voiceCode"));
        request.setLanguage(input.getString("language"));
        request.setEmotion(input.getString("emotion"));
        request.setEmotionScale(input.getInteger("emotionScale"));
        request.setSpeechRate(input.getInteger("speechRate"));
        request.setLoudnessRate(input.getInteger("loudnessRate"));
        request.setPitch(input.getInteger("pitch"));
        request.setAudioFormat(input.getString("audioFormat"));
        request.setSampleRate(input.getInteger("sampleRate"));
        request.setEnableTimestamp(input.getBoolean("enableTimestamp"));
        return request;
    }
}
