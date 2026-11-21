package com.aid.media.service;

import cn.hutool.core.bean.BeanUtil;
import com.aid.media.dto.MinimaxMusicGenerateRequest;
import com.alibaba.fastjson2.JSON;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Canonical music inputs mapped to existing official protocol fields, never Skill identities. */
public final class MusicInputContract {
    private MusicInputContract() { }
    private static final Map<String, Map<String,String>> FIELDS = Map.of(
        "minimax.post.music_generation", Map.of(),
        "mureka.post.song_generate", Map.of("prompt","prompt","lyrics","lyrics"),
        "mureka.post.song_easy_generate", Map.of("prompt","prompt"),
        "mureka.post.instrumental_generate", Map.of("prompt","prompt"),
        "seed.post.audio_generate", Map.of("prompt","text_prompt","audioFormat","audio_config.format",
                "sampleRate","audio_config.sample_rate","aigcWatermark","watermark.aigc_watermark"),
        "elevenlabs.post.music", Map.of("prompt","prompt","instrumental","force_instrumental",
                "audioFormat","queryParameters.output_format"),
        "sonilo.post.text_to_music", Map.of("prompt","prompt","audioFormat","output_format"));
    private static final Map<String,String> PROTOCOLS = Map.of("minimax","minimax-music",
        "mureka","mureka-audio","seed","seed-audio","elevenlabs","elevenlabs-audio","sonilo","sonilo-audio");

    public static String operation(String protocol, String capabilityJson) {
        String operation = null;
        try { var value=JSON.parseObject(capabilityJson); if(value!=null)operation=value.getString("officialAudioOperation"); }
        catch (RuntimeException invalid) { return null; }
        if(operation==null && "minimax-music".equalsIgnoreCase(protocol))operation="minimax.post.music_generation";
        if(operation==null || !FIELDS.containsKey(operation))return null;
        String vendor=operation.substring(0,operation.indexOf('.'));
        return PROTOCOLS.get(vendor).equalsIgnoreCase(protocol) ? operation : null;
    }

    public static boolean supports(String protocol, String capabilityJson) {
        return operation(protocol,capabilityJson)!=null;
    }

    public static Map<String,Object> inputSupport(String protocol,String capabilityJson) {
        String operation=operation(protocol,capabilityJson);
        if(operation==null)return Map.of();
        java.util.Set<String> fields=new java.util.TreeSet<>(FIELDS.get(operation).keySet());
        if("minimax.post.music_generation".equals(operation))fields.addAll(List.of("prompt","lyrics","instrumental",
                "lyricsOptimizer","audioFormat","sampleRate","bitrate","aigcWatermark","referenceAudioId","coverFeatureId"));
        Map<String,Object> fixed=Map.of();
        if(operation.startsWith("mureka.")) {
            fields.add("instrumental");fixed=Map.of("instrumental","mureka.post.instrumental_generate".equals(operation));
        }
        fields.add("modelCode");fields.add("capabilityCode");
        return Map.of("supportedFields",List.copyOf(fields),"fixedValuesWhenProvided",fixed,
                "_parameterAliases",FIELDS.get(operation),
                "unsupportedFieldPolicy","未列出的工具字段必须省略或为null。需求可写入prompt，但不能据此虚构模型不支持的布尔开关。官方参数名不是工具字段名，请使用此处声明的通用字段。所有实际参数仍由官方契约校验。");
    }

    public static Map<String,Object> normalize(MinimaxMusicGenerateRequest input, String operation) {
        Map<String,String> mapping=FIELDS.get(operation);
        if(mapping==null || "minimax.post.music_generation".equals(operation))
            throw MediaPreparationErrorCode.MODEL_CAPABILITY_INVALID.reject("当前音乐工具不支持所选模型能力");
        Map<String,Object> result=new LinkedHashMap<>();
        for(String field:List.of("prompt","lyrics","instrumental","lyricsOptimizer","audioFormat",
                "sampleRate","bitrate","aigcWatermark","referenceAudioId","coverFeatureId")) {
            Object value=BeanUtil.getProperty(input,field);
            if(value==null || value instanceof String text && text.isBlank())continue;
            if("instrumental".equals(field) && operation.startsWith("mureka.")) {
                boolean instrumental="mureka.post.instrumental_generate".equals(operation);
                if(!Boolean.valueOf(instrumental).equals(value))
                    throw MediaPreparationErrorCode.MODEL_PARAMETERS_INVALID.reject("instrumental与所选音乐能力不一致");
                continue;
            }
            String path=mapping.get(field);
            if(path==null)throw MediaPreparationErrorCode.MODEL_PARAMETERS_INVALID.reject("所选音乐能力不支持参数："+field);
            put(result,path,value);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static void put(Map<String,Object> target,String path,Object value) {
        String[] parts=path.split("\\.");
        for(int index=0;index<parts.length-1;index++)
            target=(Map<String,Object>)target.computeIfAbsent(parts[index],ignored->new LinkedHashMap<>());
        target.put(parts[parts.length-1],value);
    }
}
