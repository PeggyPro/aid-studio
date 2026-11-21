package com.aid.media.provider.audio;

import com.aid.aid.domain.AidAiModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 将已核验的音频输入契约投影为不含凭据和路由的客户端表单。 */
@Component
@RequiredArgsConstructor
public class OfficialAudioClientContract {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNode MODEL_METADATA = loadModelMetadata();
    private final OfficialAudioContractRegistry contracts;

    public Map<String, Object> describe(AidAiModel model) {
        if (model == null || !"audio".equals(model.getModelType())) return null;
        JsonNode configured;
        try { configured = JSON.readTree(model.getCapabilityJson()); }
        catch (Exception invalid) { return null; }
        String operation = configured.path("officialAudioOperation").asText("");
        if (operation.isBlank()) return null;
        JsonNode contract = contracts.contract(operation);
        if (contract.path("adminOnly").asBoolean()) return null;
        ObjectNode schema = contract.path("requestSchema").deepCopy();
        removeCredentialFields(schema);
        if ("mureka-o2".equals(model.getRealModelCode())) schema.withObject("/properties").remove(List.of("vocal_id", "melody_id"));
        if ("music-3.0".equals(model.getRealModelCode())) schema.withObject("/properties").remove(List.of("audio_url", "audio_base64", "cover_feature_id"));
        JsonNode languages = MODEL_METADATA.path("models").path(model.getRealModelCode() == null ? "" : model.getRealModelCode()).path("languages");
        Map<String, String> languageLabels = new LinkedHashMap<>();
        ObjectNode languageProperties = schema.withObject("/properties");
        if (!languageProperties.has("language_code") && languageProperties.path("queryParameters").path("properties").has("language_code"))
            languageProperties = languageProperties.withObject("/queryParameters").withObject("/properties");
        if (languageProperties.has("language_code") && languages.isArray()) {
            var choices = JSON.createArrayNode();
            for (JsonNode language : languages) {
                String code = language.path("language_id").asText();
                choices.add(code); languageLabels.put(code, language.path("name").asText() + " (" + code + ")");
            }
            languageProperties.withObject("/language_code").set("enum", choices);
        }
        String modelField = contract.path("modelField").asText("");
        if (!modelField.isBlank() && schema.path("properties").has(modelField)) {
            ObjectNode field = schema.withObject("/properties").withObject("/" + modelField);
            field.put("const", model.getRealModelCode());
            field.put("default", model.getRealModelCode());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", JSON.convertValue(schema, Map.class));
        result.put("resultKind", contract.path("resultKind").asText("audio"));
        result.put("managedFields", modelField.isBlank() ? List.of("queryParameters.model_id")
                : List.of(modelField, "queryParameters.model_id"));
        result.put("promptPath", promptPath(operation, schema));
        result.put("files", files(operation, schema));
        result.put("sharedChoices", sharedChoices(configured));
        result.put("rules", rules(operation, schema));
        result.put("choiceLabels", Map.of("language_code", languageLabels));
        if (operation.equals("elevenlabs.ws.text_to_dialogue_stream_input")) {
            String voice = configured.path("officialSharedVoiceIds").path(0).asText("");
            result.put("initialParameters", Map.of("messages", List.of(Map.of("voices", List.of(voice)),
                    Map.of("inputs", List.of(Map.of("text", "", "voice_id", voice)), "flush", true), Map.of("close_socket", true))));
        }
        result.put("minimumBillableSeconds", operation.startsWith("sonilo.") ? 10 : 0);
        return result;
    }

    private List<Map<String, Object>> rules(String operation, JsonNode schema) {
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        if (operation.startsWith("mureka.")) {
            for (String target : List.of("reference_id", "vocal_id", "gender", "prompt"))
                result.add(rule("melody_id", "present", null, target, "forbidden", null));
            result.add(rule("instrumental_id", "present", null, "prompt", "forbidden", null));
            result.add(rule("song_id", "present", null, "upload_audio_id", "forbidden", null));
            result.add(rule("upload_audio_id", "present", null, "song_id", "forbidden", null));
            if (Set.of("mureka.post.song_extend", "mureka.post.track_generate", "mureka.post.song_region_edit", "mureka.post.song_remix").contains(operation))
                result.add(rule("song_id", "absent", null, "upload_audio_id", "required", null));
        }
        if (operation.equals("seed.post.audio_generate")) {
            result.add(rule("audio_config.format", "eq", "ogg_opus", "audio_config.sample_rate", "choices", List.of(48000)));
            var rate = schema.path("properties").path("audio_config").path("properties").path("sample_rate").path("enum");
            List<Integer> mp3 = new java.util.ArrayList<>();
            for (JsonNode item : rate) if (item.asInt() != 40000) mp3.add(item.asInt());
            result.add(rule("audio_config.format", "eq", "mp3", "audio_config.sample_rate", "choices", mp3));
        }
        if (operation.startsWith("sonilo.")) {
            for (String toggle : List.of("stems", "ducking", "preserve_speech")) result.add(rule(toggle, "eq", true, "mode", "choices", List.of("async")));
            result.add(rule("variants_num", "gt", 1, "mode", "choices", List.of("async")));
            result.add(rule("video", "present", null, "video_url", "forbidden", null));
            result.add(rule("video_url", "present", null, "video", "forbidden", null));
        }
        if (operation.equals("elevenlabs.post.music")) {
            result.add(rule("composition_plan", "present", null, "prompt", "forbidden", null));
            result.add(rule("composition_plan", "present", null, "music_length_ms", "forbidden", null));
            result.add(rule("prompt", "present", null, "composition_plan", "forbidden", null));
        }
        return result;
    }

    private Map<String, Object> rule(String field, String operator, Object expected, String target, String action, Object value) {
        Map<String, Object> condition = new LinkedHashMap<>();
        condition.put("field", "options." + field); condition.put("operator", operator);
        if (expected != null) condition.put("value", expected);
        Map<String, Object> effect = new LinkedHashMap<>();
        effect.put("field", "options." + target); effect.put("operator", action);
        if (value != null) effect.put("value", value);
        return Map.of("match", "all", "conditions", List.of(condition), "actions", List.of(effect));
    }

    private String promptPath(String operation, JsonNode schema) {
        if (schema.path("properties").has("text_prompt")) return "text_prompt";
        if (schema.path("properties").has("prompt")) return "prompt";
        if (schema.path("properties").has("description")) return "description";
        if (operation.equals("elevenlabs.post.text_to_dialogue")) return "inputs.0.text";
        if (operation.equals("elevenlabs.ws.text_to_dialogue_stream_input")) return "messages.1.inputs.0.text";
        return "";
    }

    private Map<String, Object> sharedChoices(JsonNode config) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, String> fields = Map.of("voice_id", "officialSharedVoiceIds", "vocal_id", "officialSharedVocalIds",
                "speaker", "officialSharedSeedSpeakerIds", "pronunciation_dictionary_id", "officialSharedDictionaryIds");
        fields.forEach((field, key) -> {
            JsonNode values = config.path(key);
            if (values.isArray()) result.put(field, JSON.convertValue(values, List.class));
        });
        return result;
    }

    private Map<String, Object> files(String operation, JsonNode schema) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (operation.startsWith("mureka.")) {
            for (String field : List.of("reference_id", "melody_id", "instrumental_id", "upload_audio_id", "image_id", "video_id")) {
                if (!schema.path("properties").has(field)) continue;
                String purpose = switch (field) {
                    case "reference_id" -> "reference";
                    case "melody_id" -> "melody";
                    case "instrumental_id" -> "instrumental";
                    case "image_id", "video_id" -> "soundtrack";
                    default -> operation.endsWith("song_remix") ? "remix" : "audio";
                };
                boolean video = field.equals("video_id"), image = field.equals("image_id");
                Map<String, Object> file = new LinkedHashMap<>();
                file.put("mediaType", video ? "VIDEO" : image ? "IMAGE" : "AUDIO");
                file.put("formats", video ? List.of("mp4", "mov", "avi", "mkv", "webm")
                        : image ? List.of("jpg", "jpeg", "png", "webp")
                        : purpose.equals("melody") ? List.of("mp3", "m4a", "mid", "midi") : List.of("mp3", "m4a"));
                file.put("maxFileSizeMb", video ? 100 : image ? 50 : 10);
                file.put("prepareUpload", true);
                file.put("purpose", purpose);
                if (Set.of("reference", "instrumental").contains(purpose)) { file.put("minDurationSeconds", 30); file.put("clipDurationSeconds", 30); }
                if (purpose.equals("melody")) { file.put("minDurationSeconds", 5); file.put("maxDurationSeconds", 60); }
                if (purpose.equals("remix")) { file.put("minDurationSeconds", 10); file.put("maxDurationSeconds", 350); }
                result.put(field, file);
            }
            if (schema.path("properties").has("url")) result.put("url", Map.of("mediaType", "AUDIO", "formats", List.of("mp3", "wav", "m4a"), "maxFileSizeMb", 200));
        }
        if (operation.equals("seed.post.audio_generate")) {
            result.put("references.*.audio_url", Map.of("mediaType", "AUDIO", "formats", List.of("wav", "mp3", "pcm", "ogg", "opus", "ogg_opus"), "maxFileSizeMb", 10, "maxDurationSeconds", 30));
            result.put("references.*.image_url", Map.of("mediaType", "IMAGE", "formats", List.of("jpg", "jpeg", "png", "webp"), "maxFileSizeMb", 10));
        }
        if (operation.equals("elevenlabs.post.music_video_to_music")) result.put("videos.*", Map.of("mediaType", "VIDEO", "maxFileSizeMb", 200, "maxDurationSeconds", 600, "maxTotalDurationSeconds", 600, "maxItems", 10));
        if (operation.equals("sonilo.post.video_to_music")) {
            Map<String, Object> file = Map.of("mediaType", "VIDEO", "maxFileSizeMb", 300, "maxDurationSeconds", 360);
            result.put("video", file); result.put("video_url", file);
        }
        if (operation.equals("minimax.post.music_generation") && schema.path("properties").has("audio_url")) result.put("audio_url", Map.of("mediaType", "AUDIO"));
        return result;
    }

    /** Credentials belong to provider configuration and are never editable node parameters. */
    private void removeCredentialFields(JsonNode node) {
        if (node.isObject()) {
            if (node.path("properties") instanceof ObjectNode properties)
                properties.remove(List.of("xi_api_key", "api_key", "authorization", "single_use_token"));
            if (node.path("required").isArray()) {
                var safe = JSON.createArrayNode();
                for (JsonNode field : node.path("required")) if (!Set.of("xi_api_key", "api_key", "authorization", "single_use_token").contains(field.asText())) safe.add(field);
                ((ObjectNode) node).set("required", safe);
            }
            node.elements().forEachRemaining(this::removeCredentialFields);
        } else if (node.isArray()) node.elements().forEachRemaining(this::removeCredentialFields);
    }

    private static JsonNode loadModelMetadata() {
        try (var stream = new org.springframework.core.io.ClassPathResource("model/audio/official-audio-model-metadata.json").getInputStream()) {
            return JSON.readTree(stream);
        } catch (Exception invalid) { throw new IllegalStateException("音频模型资料缺失", invalid); }
    }
}
