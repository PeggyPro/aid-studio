package com.aid.media.provider.audio;

import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 补充官方自然语言中未被OpenAPI表达的跨字段和版本约束。 */
public final class OfficialAudioConstraints {
    private OfficialAudioConstraints() { }

    public static void validate(String vendor, String operation, AiModelConfigVo config, JsonNode body) {
        switch (vendor) {
            case "mureka" -> mureka(operation, config, body);
            case "seed" -> seed(body);
            case "minimax" -> minimax(operation, config, body);
            case "elevenlabs" -> eleven(operation, config, body);
            case "sonilo" -> sonilo(operation, body);
            default -> reject("音频供应商不支持");
        }
    }

    private static void mureka(String operation,AiModelConfigVo config, JsonNode b) {
        if ("mureka-o2".equals(config.getRealModelCode()) && (present(b,"vocal_id") || present(b,"melody_id"))) reject("O2不支持音色或旋律ID");
        if (present(b,"melody_id") && any(b,"reference_id","vocal_id","gender","prompt")) reject("旋律参考不能与其他控制混用");
        if (b.has("n") && b.path("n").asInt() < 1) reject("生成数量必须为正整数");
        for(String field:List.of("extend_at","generate_start","generate_end","edit_start","edit_end"))if(b.hasNonNull(field)&&b.path(field).asLong()<0)reject("音频时间位置不能为负数");
        exclusive(b,"image_id","video_id");
        if(operation.endsWith("soundtrack_generate"))exclusiveRequired(b,"image_id","video_id");
        if (present(b,"instrumental_id") && present(b,"prompt")) reject("纯音乐参考与提示词互斥");
        if (b.has("audio_end") && !b.has("audio_start")) reject("指定结束位置时需要同时指定起点");
        if (b.has("audio_end") && b.path("audio_end").asLong()-b.path("audio_start").asLong()<3000) reject("配乐区间不得少于3秒");
        if (b.has("file") || b.has("url")) exclusiveRequired(b,"file","url");
        if(operation.endsWith("song_transcribe")) {
            if(count(b,"song_id","upload_audio_id","url")!=1)reject("转谱需要且只能指定一种音频来源");
        }
        if(Set.of("mureka.post.song_extend","mureka.post.track_generate","mureka.post.song_region_edit","mureka.post.song_remix","mureka.post.lyrics_video_generate").contains(operation))exclusiveRequired(b,"song_id","upload_audio_id");
        if(operation.endsWith("song_extend")&&"mureka-8".equals(config.getRealModelCode())&&!present(b,"extend_type"))reject("V8续写必须指定head或tail");
        if(operation.endsWith("song_region_edit")&&b.hasNonNull("edit_start")&&b.hasNonNull("edit_end")&&b.path("edit_end").asLong()-b.path("edit_start").asLong()<3000)reject("局部编辑区间至少3秒");
        if(operation.endsWith("track_generate")&&b.hasNonNull("generate_start")&&b.hasNonNull("generate_end")&&b.path("generate_end").asLong()<=b.path("generate_start").asLong())reject("单轨区间结束位置必须大于起点");
        if(operation.endsWith("lyrics_video_generate")) {
            if(present(b,"cover")&&b.path("layout").asText("layout_1").equals("layout_1"))reject("layout_1不支持歌词视频封面");
            for(String[] pair:List.of(new String[]{"lyrics_start_row","lyrics_end_row"},new String[]{"selection_start","selection_end"}))
                if(present(b,pair[0])!=present(b,pair[1]))reject("歌词行或时间选区必须成组提供");
            if(any(b,"lyrics_start_row","lyrics_end_row")&&any(b,"selection_start","selection_end"))reject("歌词行与时间选区不能混用");
            if(b.hasNonNull("lyrics_start_row")&&(b.path("lyrics_start_row").asLong()<1||b.path("lyrics_start_row").asLong()>b.path("lyrics_end_row").asLong()))reject("歌词行范围无效");
            if(b.hasNonNull("selection_start")&&(b.path("selection_start").asLong()<0||b.path("selection_start").asLong()>=b.path("selection_end").asLong()))reject("歌词视频时间范围无效");
        }
    }

    private static void seed(JsonNode b) {
        boolean image=false,audio=false;
        int images=0;
        for (JsonNode r:b.path("references")) {
            int a=count(r,"speaker","audio_data","audio_url"),i=count(r,"image_data","image_url");
            if (a+i!=1) reject("每条参考必须且只能指定一种素材");
            if (i>0) {image=true;images++;} else audio=true;
        }
        if (image&&audio || images>1) reject("参考图片与音频不能混用，最多一张图片");
        JsonNode c=b.path("audio_config");
        String format=c.path("format").asText("wav");
        int rate=c.path("sample_rate").asInt(format.equals("ogg_opus")?48000:format.equals("mp3")?44100:40000);
        if (format.equals("ogg_opus")&&rate!=48000 || format.equals("mp3")&&rate==40000) reject("音频格式与采样率不兼容");
        var matcher=java.util.regex.Pattern.compile("@音频(\\d+)").matcher(b.path("text_prompt").asText());
        while(matcher.find()) if(Integer.parseInt(matcher.group(1))<1||Integer.parseInt(matcher.group(1))>b.path("references").size()) reject("参考音频编号超出素材数量");
    }

    private static void minimax(String operation,AiModelConfigVo config, JsonNode b) {
        if (!operation.endsWith("music_generation")) return;
        if("music-3.0".equals(config.getRealModelCode())&&any(b,"audio_url","audio_base64","cover_feature_id"))reject("Music 3.0不支持翻唱参考参数");
        boolean instrumental=b.path("is_instrumental").asBoolean();
        if (instrumental&&!present(b,"prompt")) reject("纯音乐需要提示词");
        if (!instrumental&&!present(b,"lyrics")&&!(b.path("lyrics_optimizer").asBoolean()&&present(b,"prompt"))) reject("歌曲需要歌词或开启自动写词并填写提示词");
        if (b.path("stream").asBoolean() && (!b.path("output_format").asText("hex").equals("hex") || b.path("aigc_watermark").asBoolean())) reject("流式音乐仅支持hex且不能启用非流式水印");
    }

    private static void eleven(String operation,AiModelConfigVo config, JsonNode b) {
        if(operation.contains(".ws.")) {
            java.util.Map<String,Set<String>> contexts=new java.util.HashMap<>();
            boolean multi=operation.contains("multi_stream");boolean closed=false;int textLength=0;
            for(JsonNode message:b.path("messages")) {
                if(closed)reject("关闭连接后不能继续发送消息");
                if(message.path("close_socket").asBoolean()) {closed=true;continue;}
                String id=multi?message.path("context_id").asText(""):"default";
                if(id.isBlank()||id.length()>100)reject("多上下文对话需要有效的context_id");
                Set<String> voices=contexts.get(id);
                if(voices==null) {
                    voices=new HashSet<>();for(JsonNode voice:message.path("voices"))voices.add(voice.asText());
                    if(voices.isEmpty()||voices.size()>10||voices.contains(""))reject("对话上下文需要1至10个音色");
                    if("eleven_v4_turbo".equals(config.getRealModelCode())&&voices.size()!=1)reject("V4 Turbo每个上下文只支持一个注册音色");
                    if(contexts.size()>=5)reject("最多5个并行对话上下文");
                    contexts.put(id,voices);
                } else if(any(message,"voices","voice_settings","pronunciation_dictionary_locators"))reject("上下文初始化参数只能在首条消息中发送");
                for(JsonNode line:message.path("inputs")) {
                    if(!voices.contains(line.path("voice_id").asText()))reject("文本音色必须在所属上下文中注册");
                    String text=line.path("text").asText();textLength+=text.codePointCount(0,text.length());
                }
                if(message.path("close_context").asBoolean())contexts.remove(id);
            }
            if(!closed)reject("任务消息必须以close_socket结束");
            if(textLength<1||textLength>10000)reject("对话文字需要1至10000字符");
            return;
        }
        if (operation.contains("music") && !operation.contains("finetunes") && !operation.endsWith("upload") && !operation.endsWith("stem_separation") && !operation.endsWith("video_to_music") && !operation.endsWith("music_plan")) {
            exclusiveRequired(b,"prompt","composition_plan");
            if (present(b,"prompt")&&b.hasNonNull("seed")) reject("prompt作曲不支持seed");
            if (present(b,"composition_plan")&&(b.hasNonNull("music_length_ms")||b.path("force_instrumental").asBoolean())) reject("作曲计划模式不能传prompt专用参数");
        }
        if (b.path("sign_with_c2pa").asBoolean()&&!b.path("queryParameters").path("output_format").asText("mp3_48000_192").startsWith("mp3")) reject("C2PA仅支持mp3输出");
        JsonNode effects=b.path("audio_effects");
        String output=b.path("queryParameters").path("output_format").asText("mp3_44100_128");
        if((present(effects,"environment_id")&&effects.path("send_level").asDouble(1)>0||effects.path("pan").asDouble()!=0)
                &&(output.startsWith("pcm_")||output.startsWith("ulaw_")||output.startsWith("alaw_")))reject("立体声音效不支持所选原始音频格式");
        if (b.path("inputs").isArray()) {
            int chars=0;Set<String> voices=new HashSet<>();for(JsonNode line:b.path("inputs")){voices.add(line.path("voice_id").asText());String text=line.path("text").asText();chars+=text.codePointCount(0,text.length());}
            if(voices.size()>10)reject("对话最多10个不同音色");
            if(chars<1||chars>10000)reject("对话文字需要1至10000字符");
        }
        for(String[] fields:List.of(new String[]{"previous_text","previous_request_ids"},new String[]{"next_text","next_request_ids"},new String[]{"future_text","next_request_ids"}))
            if(present(b,fields[0])&&present(b,fields[1]))reject("同方向上下文文本与请求ID不能同时指定");
        for(String field:List.of("previous_request_ids","next_request_ids","pronunciation_dictionary_locators"))
            if(b.path(field).isArray()&&b.path(field).size()>3)reject("上下文请求或发音字典最多3项");
        if(b.hasNonNull("seed")&&(b.path("seed").asLong()<0||b.path("seed").asLong()>4294967295L))reject("随机种子超出官方范围");
        checkChunks(b.path("composition_plan").path("chunks"));
    }

    private static void checkChunks(JsonNode chunks) {
        long total=0;
        for(JsonNode chunk:chunks) {
            if(chunk.has("duration_ms"))total+=chunk.path("duration_ms").asLong();
            if(chunk.has("text")) {
                String[] lines=chunk.path("text").asText().split("\\R",-1);
                if(lines.length>30)reject("每个作曲片段最多30行");
                for(String line:lines)if(line.codePointCount(0,line.length())>200)reject("每行歌词最多200字符");
            }
            JsonNode range=chunk.path("range");
            if(range.isObject()) {
                if(range.path("start_ms").asLong()<0||range.path("end_ms").asLong()<=range.path("start_ms").asLong())reject("参考音频范围无效");
                total+=range.path("end_ms").asLong()-range.path("start_ms").asLong();
            }
            JsonNode conditioning=chunk.path("conditioning_ref").path("range");
            if(conditioning.isObject()&&(conditioning.path("start_ms").asLong()<0||conditioning.path("end_ms").asLong()<=conditioning.path("start_ms").asLong()))reject("条件参考音频范围无效");
        }
        if(total>600000)reject("音乐总时长超过10分钟");
    }

    private static void sonilo(String operation, JsonNode b) {
        if(b.has("video")||b.has("video_url"))exclusiveRequired(b,"video","video_url");
        if(operation.endsWith("audio_ducking")) {exclusiveRequired(b,"voice_file","voice_url");exclusiveRequired(b,"music_file","music_url");}
        boolean stream=b.path("mode").asText("stream").equals("stream")&&Set.of("sonilo.post.text_to_music","sonilo.post.video_to_music").contains(operation);
        if(stream && (b.path("variants_num").asInt(1)>1||b.path("stems").asBoolean()||b.path("ducking").asBoolean()||b.path("preserve_speech").asBoolean()||!b.path("output_format").asText("m4a").equals("m4a")))reject("此组合要求async模式");
        if(b.hasNonNull("segments")) {
            JsonNode segments;
            try{segments=new com.fasterxml.jackson.databind.ObjectMapper().readTree(b.path("segments").asText());}catch(Exception ex){throw new ServiceException("音乐时间线必须是JSON数组字符串");}
            if(!segments.isArray()||segments.size()<1||segments.size()>30)reject("音乐时间线需要1至30段");
            BigDecimal previous=null;
            for(JsonNode segment:segments) {
                if(!segment.path("start").isNumber()||!segment.path("prompt").isTextual())reject("音乐时间线需要start和prompt");
                BigDecimal start=segment.path("start").decimalValue();
                if(previous==null&&start.signum()!=0||previous!=null&&start.subtract(previous).compareTo(BigDecimal.valueOf(5))<0)reject("时间线首段从0开始且相邻至少5秒");
                int length=segment.path("prompt").asText().codePointCount(0,segment.path("prompt").asText().length());
                if(length<1||length>200)reject("每段音乐描述需要1至200字符");
                if(segment.has("label")&&!Set.of("intro","verse","pre-chorus","chorus","bridge","break","silence","outro","none").contains(segment.path("label").asText()))reject("音乐段落标签不支持");
                previous=start;
            }
            if(b.hasNonNull("duration")&&previous!=null&&previous.add(BigDecimal.valueOf(5)).compareTo(b.path("duration").decimalValue())>0)reject("最后段落起点须距结束至少5秒");
        }
    }

    private static boolean present(JsonNode b,String k){JsonNode v=b.get(k);return v!=null&&!v.isNull()&&(!v.isTextual()||!v.asText().isBlank())&&(!v.isArray()||!v.isEmpty());}
    private static boolean any(JsonNode b,String...keys){return count(b,keys)>0;}
    private static int count(JsonNode b,String...keys){int n=0;for(String k:keys)if(present(b,k))n++;return n;}
    private static void exclusive(JsonNode b,String a,String c){if(present(b,a)&&present(b,c))reject(a+"与"+c+"互斥");}
    private static void exclusiveRequired(JsonNode b,String a,String c){if(present(b,a)==present(b,c))reject(a+"与"+c+"必须且只能提供一个");}
    private static void reject(String message){throw com.aid.media.service.MediaPreparationErrorCode.MODEL_PARAMETERS_INVALID.reject(message);}
}
