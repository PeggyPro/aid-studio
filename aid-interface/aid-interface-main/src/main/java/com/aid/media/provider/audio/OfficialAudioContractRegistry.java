package com.aid.media.provider.audio;

import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaAudioGenerateRequest;
import com.aid.model.definition.ModelConfiguredRequestBody;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.dialect.Dialects;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 官方音频契约固定快照；不远程加载Schema，不以配置扩大上游实际能力。 */
@Component
public final class OfficialAudioContractRegistry {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SchemaRegistry SCHEMAS = SchemaRegistry.withDialects(List.of(Dialects.getDraft202012()),
            builder -> builder.defaultDialectId("https://json-schema.org/draft/2020-12/schema")
                    .schemaRegistryConfig(SchemaRegistryConfig.builder().typeLoose(false).losslessNarrowing(false).build())
                    .schemaLoader(loader -> loader.fetchRemoteResources(false)
                            .resourceLoaders(loaders -> loaders.values(List::clear))));
    private final JsonNode contracts;
    private final JsonNode templates;
    private final Map<String, Schema> compiled = new ConcurrentHashMap<>();

    public OfficialAudioContractRegistry() {
        try (var stream = new ClassPathResource("model/audio/official-audio-contracts.json").getInputStream()) {
            contracts = JSON.readTree(stream);
            try(var catalog=new ClassPathResource("model/audio/official-audio-catalog.json").getInputStream()) {templates=JSON.readTree(catalog);}
        } catch (Exception ex) { throw new IllegalStateException("官方音频契约无法加载", ex); }
    }

    public JsonNode contract(String code) {
        JsonNode result = code == null ? null : contracts.get(code);
        if (result == null || !result.isObject()) throw new ServiceException("音频操作未实现");
        return result.deepCopy();
    }

    public List<Map<String, Object>> catalog(String vendor) {
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> enabledOperations=new java.util.HashSet<>();
        for(JsonNode model:templates)for(JsonNode capability:model.path("capabilities"))
            for(JsonNode binding:capability.path("bindings"))
                enabledOperations.add(binding.path("capability").path("officialAudioOperation").asText());
        contracts.fields().forEachRemaining(entry -> {
            if (enabledOperations.contains(entry.getKey())&&(vendor == null || vendor.equals(entry.getValue().path("vendor").asText()))) {
                Map<String, Object> item = JSON.convertValue(entry.getValue(), LinkedHashMap.class);
                item.remove("parameters");
                item.put("code", entry.getKey());
                result.add(item);
            }
        });
        return result;
    }

    public JsonNode template(String model) {
        for(JsonNode item:templates)if(item.path("realModelCode").asText().equals(model))return item.deepCopy();
        throw new ServiceException("当前真实模型尚无官方音频模板");
    }

    public static String operation(AiModelConfigVo config) {
        try {
            JsonNode capability = JSON.readTree(config.getCapabilityJson());
            String value = capability.path("officialAudioOperation").asText("");
            if (!value.isBlank()) return value;
        } catch (Exception ignored) { }
        if ("minimax-music".equalsIgnoreCase(config.getProtocol())) return "minimax.post.music_generation";
        throw new ServiceException("请配置音频操作");
    }

    public Plan prepare(String vendor, AiModelConfigVo config, MediaAudioGenerateRequest request) {
        String code = operation(config);
        JsonNode contract = contract(code);
        if (!vendor.equals(contract.path("vendor").asText())) throw new ServiceException("音频操作与供应商不匹配");
        Map<String, Object> values = request.getOptions() == null ? new LinkedHashMap<>()
                : JSON.convertValue(request.getOptions(), LinkedHashMap.class);
        // 历史MiniMax调用继续保留原DTO别名；新目录使用完整官方字段名。
        if ("minimax".equals(vendor) && (!hasExplicitOperation(config)||request.getTtsText()!=null||values.containsKey("isInstrumental"))) values = legacyMinimax(request, values);
        values = new LinkedHashMap<>(ModelConfiguredRequestBody.apply(config, values, request));
        if("WS".equals(contract.path("method").asText())) {
            Map<String,Object> query=values.get("queryParameters") instanceof Map<?,?> q?JSON.convertValue(q,LinkedHashMap.class):new LinkedHashMap<>();
            Object selected=query.get("model_id");
            if(selected!=null&&!config.getRealModelCode().equals(selected))throw new ServiceException("上游模型参数与所选模型不一致");
            query.put("model_id",config.getRealModelCode());values.put("queryParameters",query);
        }
        String field = contract.path("modelField").asText("");
        if (!field.isBlank()) {
            Object supplied = values.get(field);
            if (supplied != null && !config.getRealModelCode().equals(supplied)) throw new ServiceException("上游模型参数与所选模型不一致");
            values.put(field, config.getRealModelCode());
        }
        validateModel(vendor, contract, config.getRealModelCode());
        if(!Set.of("music-2.6","music-cover").contains(config.getRealModelCode())) {
            JsonNode profile=template(config.getRealModelCode());boolean allowed=false;
            for(JsonNode capability:profile.path("capabilities"))for(JsonNode binding:capability.path("bindings"))
                if(code.equals(binding.path("capability").path("officialAudioOperation").asText()))allowed=true;
            if(!allowed)throw com.aid.media.service.MediaPreparationErrorCode.MODEL_CAPABILITY_INVALID.reject("所选版本不支持此音频能力");
        }
        ObjectNode body = JSON.valueToTree(values);
        Schema schema = compiled.computeIfAbsent(code, ignored -> SCHEMAS.getSchema(contract.path("requestSchema")));
        var failures=schema.validate(body);
        if (!failures.isEmpty()) {
            String locations=failures.stream().limit(3).map(error->error.getInstanceLocation()+" ("+error.getKeyword()+")")
                    .collect(java.util.stream.Collectors.joining("、"));
            throw com.aid.media.service.MediaPreparationErrorCode.MODEL_PARAMETERS_INVALID.reject("音频参数不符合官方契约："+locations);
        }
        OfficialAudioConstraints.validate(vendor, code, config, body);
        Map<String, Object> path = removeObject(values, "pathParameters");
        Map<String, Object> query = removeObject(values, "queryParameters");
        return new Plan(code, vendor, contract.path("method").asText(), contract.path("contentType").asText(),
                contract.path("resultKind").asText(), contract.path("adminOnly").asBoolean(), values, path, query);
    }

    public static boolean hasExplicitOperation(AiModelConfigVo config) {
        try { return JSON.readTree(config.getCapabilityJson()).hasNonNull("officialAudioOperation"); }
        catch (Exception ignored) { return false; }
    }

    private static void validateModel(String vendor, JsonNode contract, String model) {
        boolean valid = switch (vendor) {
            case "mureka" -> Set.of("mureka-9.5", "mureka-o2", "mureka-8").contains(model);
            case "seed" -> "seed-audio-1.0".equals(model);
            case "minimax" -> Set.of("music-3.0", "music-2.6", "music-cover").contains(model);
            case "elevenlabs" -> Set.of("eleven_v4", "eleven_v4_turbo", "music_v2_5").contains(model);
            case "sonilo" -> model != null && !model.isBlank(); // 公开API无model字段，不向上游伪造版本选择。
            default -> false;
        };
        if (!valid) throw new ServiceException("音频模型版本未支持");
        String path = contract.path("path").asText();
        if ("mureka-o2".equals(model) && (path.contains("instrumental") || path.contains("soundtrack") || path.contains("vocal-clone")))
            throw new ServiceException("O2不支持此音频操作");
        if ("elevenlabs".equals(vendor) && ("music_v2_5".equals(model) != path.startsWith("/v1/music")))
            throw new ServiceException("音乐与语音模型能力不匹配");
    }

    private static Map<String, Object> legacyMinimax(MediaAudioGenerateRequest request, Map<String, Object> values) {
        for (String[] alias : List.of(new String[]{"isInstrumental", "is_instrumental"},
                new String[]{"lyricsOptimizer", "lyrics_optimizer"}, new String[]{"aigcWatermark", "aigc_watermark"},
                new String[]{"coverFeatureId", "cover_feature_id"}, new String[]{"audioUrl", "audio_url"})) {
            if (values.containsKey(alias[0])) {
                Object value = values.remove(alias[0]);
                if (values.containsKey(alias[1]) && !java.util.Objects.equals(values.get(alias[1]), value)) throw new ServiceException("音乐参数别名冲突");
                values.put(alias[1], value);
            }
        }
        if (!values.containsKey("prompt") && request.getTtsText() != null) values.put("prompt", request.getTtsText());
        for(String optional:List.of("prompt","lyrics"))if(values.get(optional) instanceof String text&&text.isBlank())values.remove(optional);
        Map<String, Object> audio = values.get("audio_setting") instanceof Map<?, ?> existing
                ? JSON.convertValue(existing, LinkedHashMap.class) : new LinkedHashMap<>();
        if (request.getAudioFormat() != null) audio.putIfAbsent("format", request.getAudioFormat());
        if (request.getSampleRate() != null) audio.putIfAbsent("sample_rate", request.getSampleRate());
        if (values.containsKey("bitrate")) audio.put("bitrate", values.remove("bitrate"));
        if (!audio.isEmpty()) values.put("audio_setting", audio);
        values.putIfAbsent("output_format", Boolean.TRUE.equals(values.get("stream"))?"hex":"url");
        return values;
    }

    private static Map<String, Object> removeObject(Map<String, Object> values, String field) {
        Object result = values.remove(field);
        return result == null ? Map.of() : JSON.convertValue(result, LinkedHashMap.class);
    }

    public record Plan(String operation, String vendor, String method, String contentType, String resultKind,
                       boolean adminOnly, Map<String, Object> body, Map<String, Object> path, Map<String, Object> query) { }
}
