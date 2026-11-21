package com.aid.media.provider.audio;

import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaAudioGenerateRequest;
import com.aid.media.service.VerifiedMediaMetadataService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 报价/提交共用的素材验证与可信计价输入，客户端不能指定计价用量。 */
@Component
@RequiredArgsConstructor
public final class OfficialAudioInputService {
    private final VerifiedMediaMetadataService metadata;
    private final OfficialAudioContractRegistry contracts;
    private final com.aid.common.aid.oss.util.MediaUrlResolver urls;

    public Map<String,Object> prepare(AiModelConfigVo config,MediaAudioGenerateRequest request,
                                      OfficialAudioContractRegistry.Plan plan,boolean verify) {
        Map<String,Object> input=new LinkedHashMap<>();
        Map<String,Object> body=plan.body();
        input.put("operation",plan.operation());
        if(plan.operation().equals("mureka.post.song_stem"))input.put("stemModel",plan.body().getOrDefault("model","audio-separation-1"));
        int count=number(body.get("n"),number(body.get("variants_num"),plan.vendor().equals("mureka")&&Set.of(
                "mureka.post.song_generate","mureka.post.song_easy_generate","mureka.post.instrumental_generate","mureka.post.soundtrack_generate","mureka.post.song_remix").contains(plan.operation())?2:1));
        input.put("outputCount",count);
        input.put("packageQuantity",count);
        input.put("n",count);input.put("variants_num",count);
        long chars=0;
        for(String key:List.of("text","text_prompt"))if(body.get(key) instanceof String text)chars+=text.codePointCount(0,text.length());
        if(body.get("inputs") instanceof List<?> lines)for(Object line:lines)if(line instanceof Map<?,?> m&&m.get("text") instanceof String text)chars+=text.codePointCount(0,text.length());
        if(body.get("messages") instanceof List<?> messages)for(Object message:messages)if(message instanceof Map<?,?> m&&m.get("inputs") instanceof List<?> lines)
            for(Object line:lines)if(line instanceof Map<?,?> inputLine&&inputLine.get("text") instanceof String text)chars+=text.codePointCount(0,text.length());
        input.put("chars",Math.toIntExact(chars));input.put("textLength",Math.toIntExact(chars));input.put("ttsChars",Math.toIntExact(chars));
        for(String field:List.of("stem_variation_id","stem_type","output_format","is_instrumental"))if(body.containsKey(field))input.put(field,body.get(field));
        BigDecimal duration=body.get("duration") instanceof Number n?new BigDecimal(n.toString()):null;
        if(body.get("music_length_ms") instanceof Number n)duration=new BigDecimal(n.toString()).movePointLeft(3);
        if(body.get("composition_plan") instanceof Map<?,?> composition&&composition.get("chunks") instanceof List<?> chunks) {
            duration=BigDecimal.ZERO;
            for(Object chunk:chunks)if(chunk instanceof Map<?,?> c) {
                if(c.get("duration_ms") instanceof Number n)duration=duration.add(new BigDecimal(n.toString()).movePointLeft(3));
                else if(c.get("range") instanceof Map<?,?> range && range.get("start_ms") instanceof Number start && range.get("end_ms") instanceof Number end)
                    duration=duration.add(new BigDecimal(end.toString()).subtract(new BigDecimal(start.toString())).movePointLeft(3));
            }
        }
        long totalBytes=0;BigDecimal videoSeconds=BigDecimal.ZERO,audioSeconds=BigDecimal.ZERO;int videos=0;
        JsonNode schema=contracts.contract(plan.operation()).path("requestSchema");
        for(var entry:body.entrySet()) {
            String field=entry.getKey();
            JsonNode fieldSchema=OfficialAudioTransport.dereference(schema.path("properties").path(field),schema);
            JsonNode itemSchema=fieldSchema.path("type").asText().equals("array")?OfficialAudioTransport.dereference(fieldSchema.path("items"),schema):fieldSchema;
            if(itemSchema.path("format").asText().equals("binary")||Set.of("url","video_url","voice_url","music_url").contains(field)) {
                String purpose=String.valueOf(body.get("purpose"));
                boolean soundtrack=plan.operation().equals("mureka.post.files_upload")&&Set.of("soundtrack","lyrics-video").contains(purpose);
                boolean video=field.startsWith("video") || soundtrack && String.valueOf(entry.getValue()).toLowerCase().matches(".*\\.(mp4|mov|avi|mkv|webm)(\\?.*)?");
                boolean image=soundtrack&&!video;
                List<?> sources=entry.getValue() instanceof List<?> list?list:List.of(entry.getValue());
                for(Object url:sources) {
                    if(!(url instanceof String text)||!text.startsWith("http"))throw new ServiceException("文件输入需要已上传的素材URL");
                    if(!verify)continue;
                    if(plan.operation().equals("elevenlabs.post.pronunciation_dictionaries_add_from_file")) {
                        validateLexicon(metadata.readTrustedBytes(text,10*1024*1024));continue;
                    }
                    if(plan.operation().equals("elevenlabs.post.voices_pvc_voice_id_verification")) {
                        byte[] document=metadata.readTrustedBytes(text,25*1024*1024);
                        if(document.length>=5&&new String(document,0,5,java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-"))continue;
                        metadata.inspect(text,"image");continue;
                    }
                    if(plan.operation().equals("mureka.post.files_upload")&&purpose.equals("melody")&&java.net.URI.create(text).getPath().toLowerCase().matches(".*\\.mid(i)?")) {
                        if(!urls.isSiteImageUrl(text))throw new ServiceException("请使用本站旋律素材");
                        byte[] data=com.aid.tokendance.security.TokenDancePublicMediaDownloader.download(text,10000,30000,10*1024*1024);
                        try {
                            var midi=javax.sound.midi.MidiSystem.getSequence(new java.io.ByteArrayInputStream(data));
                            if(midi.getTracks().length>128||midi.getMicrosecondLength()<5_000_000)throw new ServiceException("旋律MIDI时长或轨道无效");
                        }catch(ServiceException invalid){throw invalid;}catch(Exception invalid){throw new ServiceException("旋律MIDI格式无效");}
                        continue;
                    }
                    var actual=metadata.inspect(text,image?"image":video?"video":"audio");
                    if(soundtrack) {
                        if(actual.sizeBytes()>(video?100L:50L)*1024*1024)throw new ServiceException("Mureka配乐素材体积超过限制");
                        if(!video&&!(purpose.equals("lyrics-video")?Set.of("jpg","jpeg","png","webp","gif"):Set.of("jpg","jpeg","png","webp")).contains(actual.format()))throw new ServiceException("配乐图片格式不支持");
                        continue;
                    }
                    long limit=plan.vendor().equals("sonilo")?300L*1024*1024:200L*1024*1024;
                    if(actual.sizeBytes()>limit)throw new ServiceException("音频输入文件过大");
                    totalBytes=Math.addExact(totalBytes,actual.sizeBytes());
                    if(video) {
                        videos++;videoSeconds=videoSeconds.add(actual.durationSeconds());
                        int maximum=plan.vendor().equals("sonilo")?Set.of("sonilo.post.video_to_sound","sonilo.post.video_to_video_sound","sonilo.post.video_analysis").contains(plan.operation())?480:360:600;
                        if(actual.durationSeconds().compareTo(BigDecimal.valueOf(maximum))>0)throw new ServiceException("输入视频时长超过限制");
                    }else {
                        audioSeconds=audioSeconds.max(actual.durationSeconds());
                        if(plan.operation().equals("mureka.post.files_upload"))validateMurekaUpload(body,actual);
                        if(plan.operation().equals("mureka.post.song_describe")&&(actual.sizeBytes()>10L*1024*1024||!Set.of("mp3","m4a").contains(actual.format())||actual.durationSeconds().compareTo(BigDecimal.TEN)<0||actual.durationSeconds().compareTo(BigDecimal.valueOf(300))>0))throw new ServiceException("音乐理解素材须为10至300秒、10MB以内的mp3或m4a");
                        if(plan.operation().equals("mureka.post.song_transcribe")&&(actual.sizeBytes()>100L*1024*1024||!Set.of("mp3","wav","m4a").contains(actual.format())||actual.durationSeconds().compareTo(BigDecimal.TEN)<0||actual.durationSeconds().compareTo(BigDecimal.valueOf(600))>0))throw new ServiceException("转谱素材须为10至600秒、100MB以内的mp3/wav/m4a");
                        if(plan.operation().equals("mureka.post.song_vocal_clone")&&(actual.sizeBytes()>10L*1024*1024||!Set.of("mp3","m4a").contains(actual.format())||actual.durationSeconds().compareTo(BigDecimal.valueOf(15))<0))throw new ServiceException("音色克隆需要至少15秒、10MB以内的mp3或m4a素材");
                    }
                }
            }
        }
        if(plan.operation().equals("elevenlabs.post.music_video_to_music")&&(totalBytes>200L*1024*1024||videoSeconds.compareTo(BigDecimal.valueOf(600))>0||videos>10))throw new ServiceException("视频配乐总素材超过官方限制");
        if(videoSeconds.signum()>0)duration=videoSeconds;
        if(plan.operation().equals("sonilo.post.audio_ducking")&&audioSeconds.signum()>0)duration=audioSeconds;
        if(plan.vendor().equals("seed")) {
            duration=BigDecimal.valueOf(120);
            Object refs=body.get("references");
            if(refs instanceof List<?> references)for(Object value:references)if(value instanceof Map<?,?> ref) {
                boolean image=ref.containsKey("image_url");Object url=ref.get(image?"image_url":"audio_url");
                if(verify&&(ref.containsKey("audio_data")||ref.containsKey("image_data")))throw new ServiceException("参考素材尚未转存");
                if(url instanceof String text&&verify) {
                    var actual=metadata.inspect(text,image?"image":"audio");
                    if(actual.sizeBytes()>10L*1024*1024||image&&actual.sizeBytes()<1024)throw new ServiceException("参考素材体积不符合要求");
                    if(image&&!Set.of("jpg","jpeg","png","webp").contains(actual.format()))throw new ServiceException("参考图片格式不支持");
                    if(!image&&(!Set.of("wav","mp3","pcm","ogg_opus","ogg","opus").contains(actual.format())||actual.durationSeconds().compareTo(BigDecimal.valueOf(30))>0))throw new ServiceException("参考音频格式或时长不符合要求");
                }
            }
        }
        if(duration==null)duration=BigDecimal.valueOf(plan.vendor().equals("sonilo")?360:plan.vendor().equals("elevenlabs")?600:300);
        if(duration.signum()<=0)throw new ServiceException("音频计费时长无效");
        if(plan.vendor().equals("sonilo"))duration=duration.max(BigDecimal.TEN);
        input.put("duration",duration.multiply(BigDecimal.valueOf(count)).setScale(0,RoundingMode.CEILING).intValueExact());
        input.put("singleDuration",duration);input.put("inputVideoSeconds",videoSeconds);
        if(verify&&plan.vendor().equals("sonilo")&&body.containsKey("segments")&&videoSeconds.signum()>0) {
            try {
                JsonNode segments=new com.fasterxml.jackson.databind.ObjectMapper().readTree(String.valueOf(body.get("segments")));
                BigDecimal start=segments.get(segments.size()-1).path("start").decimalValue();
                if(start.add(BigDecimal.valueOf(5)).compareTo(videoSeconds)>0)throw new ServiceException("最后配乐段落距离视频结束不足5秒");
            }catch(ServiceException ex){throw ex;}catch(Exception ex){throw new ServiceException("音乐时间线无效");}
        }
        return input;
    }

    private static void validateMurekaUpload(Map<String,Object> body,VerifiedMediaMetadataService.Metadata data) {
        String purpose=String.valueOf(body.get("purpose"));
        if(data.sizeBytes()>10L*1024*1024)throw new ServiceException("参考音频不得超过10MB");
        if(!Set.of("mp3","m4a","mid","midi").contains(data.format()))throw new ServiceException("Mureka参考音频格式不支持");
        BigDecimal min=BigDecimal.ZERO,max=null;
        switch(purpose){
            case "reference","instrumental"->{min=BigDecimal.valueOf(30);max=min;}
            case "melody"->{min=BigDecimal.valueOf(5);max=BigDecimal.valueOf(60);}
            case "voice"->{min=BigDecimal.valueOf(5);max=BigDecimal.valueOf(15);}
            case "remix"->{min=BigDecimal.valueOf(10);max=BigDecimal.valueOf(350);}
            default->{ }
        }
        if(data.durationSeconds().compareTo(min)<0||"remix".equals(purpose)&&max!=null&&data.durationSeconds().compareTo(max)>0)throw new ServiceException("参考音频时长不符合用途要求");
    }
    private static int number(Object value,int fallback){return value instanceof Number n?n.intValue():fallback;}
    private static void validateLexicon(byte[] bytes) {
        try {
            var factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
            factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD,"");
            factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
            factory.setNamespaceAware(true);
            var root=factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(bytes)).getDocumentElement();
            if(!"lexicon".equals(root.getLocalName()))throw new IllegalArgumentException();
        }catch(Exception invalid){throw new ServiceException("发音字典必须是有效且不含外部实体的PLS文件");}
    }
}
