package com.aid.media.provider.audio;

import com.aid.common.exception.ServiceException;
import com.aid.common.error.TaskErrorCode;
import com.aid.common.error.TaskErrorResult;
import com.aid.media.provider.AudioOutputItem;
import com.aid.media.provider.ProviderSubmitResult;
import com.aid.media.provider.ProviderTaskResult;
import com.aid.media.provider.ProviderSubmissionOutcomeUnknownException;
import com.aid.media.util.MediaTaskPayloadSanitizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipInputStream;

/** 官方音频响应编解码；HTTP、业务错误、预览片段和权威终态分别处理。 */
@Component
@RequiredArgsConstructor
public class OfficialAudioResponseCodec {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final OfficialAudioArtifacts artifacts;
    private final OfficialAudioErrorMapper errors;

    public ProviderSubmitResult submit(OfficialAudioTransport.Response response,OfficialAudioContractRegistry.Plan plan) {
        ProviderSubmitResult result=decodeResponse(response,plan);
        if (plan.operation().contains("lyrics") && "json".equals(plan.resultKind()) && result.getDirectText() != null) {
            result.setDirectText(lyricsText(result.getDirectText()));
        }
        if("sonilo".equals(plan.vendor()))applySoniloBillingFloor(result);
        if(plan.vendor().equals("elevenlabs")) {
            Map<String,Object> usage=result.getUsage()==null?new LinkedHashMap<>():new LinkedHashMap<>(result.getUsage());
            String song=null,request=null;
            for(var header:response.headers().entrySet()) {
                if(Set.of("song-id","song_id").contains(header.getKey().toLowerCase()))song=header.getValue().get(0);
                if(header.getKey().equalsIgnoreCase("request-id"))request=header.getValue().get(0);
            }
            if(request!=null)usage.put("requestId",request);
            if(result.getAudioOutputs()!=null)for(var item:result.getAudioOutputs()) {
                if(song!=null)item.setUpstreamArtifactId(song);
                Map<String,Object> details=item.getMetadata()==null?new LinkedHashMap<>():new LinkedHashMap<>(item.getMetadata());
                if(request!=null)details.put("requestId",request);item.setMetadata(details);
            }
            result.setUsage(usage);
        }
        return result;
    }

    /** 歌词结果显示官方正文，不将响应对象序列化字符串当作用户内容。 */
    static String lyricsText(String raw) {
        JsonNode value;
        try { value = JSON.readTree(raw); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            if (raw == null || raw.isBlank() || raw.stripLeading().startsWith("{") || raw.stripLeading().startsWith("["))
                throw new ProviderSubmissionOutcomeUnknownException();
            return raw;
        }
        if (value != null && value.isTextual() && !value.asText().isBlank()) return value.asText();
        List<String> parts = new ArrayList<>();
        collectLyrics(value, parts);
        if (parts.isEmpty()) throw new ProviderSubmissionOutcomeUnknownException();
        return String.join("\n\n", parts);
    }

    private static void collectLyrics(JsonNode node, List<String> parts) {
        if (node == null) return;
        if (node.isArray()) { for (JsonNode child : node) collectLyrics(child, parts); return; }
        if (!node.isObject()) return;
        for (String field : List.of("title", "lyrics", "text", "lyrics_content")) {
            JsonNode content = node.path(field);
            if (content.isTextual() && !content.asText().isBlank()) parts.add(content.asText());
            else if (content.isArray() || content.isObject()) collectLyrics(content, parts);
        }
        for (String field : List.of("data", "choices", "results")) collectLyrics(node.path(field), parts);
    }

    private ProviderSubmitResult decodeResponse(OfficialAudioTransport.Response response,OfficialAudioContractRegistry.Plan plan) {
        JsonNode root=parseJson(response);
        if(!response.successful()) {
            if(response.status()>=500&&OfficialAudioErrorMapper.code(plan.vendor(),root)==null)throw new ProviderSubmissionOutcomeUnknownException();
            return errors.rejected(plan.vendor(),response.status(),root,response.requestId());
        }
        if(response.bytes().length==0&&plan.resultKind().equals("json"))return json(JSON.createObjectNode().put("httpStatus",response.status()));
        if(response.contentType().contains("text/event-stream")||response.contentType().contains("x-ndjson"))return stream(response,plan);
        if(response.contentType().startsWith("multipart/mixed"))return multipart(response,plan);
        if(root==null) return binary(response,plan);
        String code=OfficialAudioErrorMapper.code(plan.vendor(),root);
        if(code!=null&&!Set.of("0","20000000").contains(code))return errors.rejected(plan.vendor(),response.status(),root,response.requestId());
        if(Set.of("failed","timeouted","cancelled","canceled").contains(root.path("status").asText()))return errors.rejected(plan.vendor(),400,root,response.requestId());
        return switch(plan.vendor()) {
            case "mureka"->mureka(root,plan.resultKind());
            case "sonilo"->sonilo(root,plan.resultKind());
            case "seed"->seed(root,plan);
            case "minimax"->minimax(root,plan);
            case "elevenlabs"->eleven(root,plan,response);
            default->throw new ServiceException("音频响应协议未实现");
        };
    }

    public ProviderTaskResult query(String vendor,OfficialAudioTransport.Response response,String kind) {
        if(!response.successful())return processing(false,null);
        JsonNode root=parseJson(response);if(root==null)return processing(false,null);
        String status=root.path("status").asText("");
        if(Set.of("preparing","queued","running","streaming","reviewing","processing","pending").contains(status))return processing(true,status);
        if(Set.of("failed","timeouted","cancelled","canceled").contains(status)) {
            TaskErrorResult error=errors.classify(vendor,200,root);
            if(status.equals("timeouted"))error=TaskErrorResult.of(TaskErrorCode.UPSTREAM_TIMEOUT);
            if(status.equals("cancelled")||status.equals("canceled"))error=TaskErrorResult.of(TaskErrorCode.TASK_INTERRUPTED);
            return ProviderTaskResult.builder().status("FAILED").querySuccessful(true).terminalConfirmed(true)
                    .providerStatus(status).taskError(error).errorMessage(error.getUserMessage()).build();
        }
        if(!Set.of("succeeded","completed").contains(status))return processing(false,status);
        ProviderSubmitResult result=vendor.equals("mureka")?mureka(root,kind):sonilo(root,kind);
        if(result.getDirectUrl()==null&&result.getOssUrl()==null&&result.getDirectText()==null)return processing(false,status);
        return ProviderTaskResult.builder().status("SUCCEEDED").querySuccessful(true).terminalConfirmed(true)
                .providerStatus(status).resultUrl(result.getOssUrl()!=null?result.getOssUrl():result.getDirectUrl())
                .audioOutputs(result.getAudioOutputs()).usage(result.getUsage()).resultText(result.getDirectText())
                .rawResponse(result.getRawResponse()).build();
    }

    private ProviderSubmitResult mureka(JsonNode root,String kind) {
        String state=root.path("status").asText();
        if(kind.equals("json")&&!root.hasNonNull("zip_url"))return json(root);
        if(root.hasNonNull("id")&&!root.has("choices")&&!Set.of("succeeded","failed","timeouted","cancelled").contains(state))
            return ProviderSubmitResult.builder().providerTaskId(root.path("id").asText()).rawResponse(safe(root)).build();
        List<AudioOutputItem> outputs=new ArrayList<>();
        if(kind.equals("video")&&root.path("url").isTextual()) {
            var item=artifacts.remote(root.path("url").asText(),"video",0,null,Map.of());item.setMediaType("VIDEO");outputs.add(item);
        }
        for(JsonNode song:root.path("choices")) {
            int variant=song.path("index").asInt(outputs.size());long duration=song.path("duration").asLong();
            Map<String,Object> data=map(song);data.remove("url");data.remove("flac_url");data.remove("wav_url");data.remove("stream_url");
            data.put("watermarked",root.path("watermarked").asBoolean());
            for(String field:List.of("url","flac_url","wav_url"))if(song.path(field).isTextual()&&!song.path(field).asText().isBlank()) {
                AudioOutputItem item=artifacts.remote(song.path(field).asText(),field.equals("url")?"main":"format_copy",variant,duration,data);
                item.setUpstreamArtifactId(song.path("id").asText(null));outputs.add(item);
            }
        }
        for(String field:List.of("zip_url","midi_zip_url"))if(root.path(field).isTextual())outputs.add(artifacts.remote(root.path(field).asText(),field,0,null,Map.of()));
        if(outputs.isEmpty()&&root.hasNonNull("id"))return json(root);
        return outputs(outputs,Map.of("actualOutputCount",Math.max(1,root.path("choices").size())),root);
    }

    private ProviderSubmitResult sonilo(JsonNode root,String kind) {
        String state=root.path("status").asText();
        if(root.hasNonNull("task_id")&&!Set.of("succeeded","completed","failed","canceled").contains(state))
            return ProviderSubmitResult.builder().providerTaskId(root.path("task_id").asText()).rawResponse(safe(root)).build();
        List<AudioOutputItem> outputs=new ArrayList<>();
        if(root.path("outputs").isArray())for(JsonNode variant:root.path("outputs"))combined(variant,outputs,variant.path("variant_index").asInt(outputs.size()));
        else if(root.hasNonNull("output_url"))combined(root,outputs,0);
        for(String field:kind.equals("video")?List.of("videos","audio"):List.of("audio","videos")) {
            for(JsonNode media:root.path(field)) {
                AudioOutputItem item=media(media,field.equals("videos")?"video":"main",media.path("stream_index").asInt(outputs.size()));
                if(field.equals("videos"))item.setMediaType("VIDEO");outputs.add(item);
            }
        }
        if(root.path("videos").isEmpty()&&root.path("video").isObject()) {AudioOutputItem item=media(root.path("video"),"video",0);item.setMediaType("VIDEO");outputs.add(item);}
        for(String field:List.of("vocals","mux","ducked")) {
            JsonNode value=root.path(field);
            if(value.isArray())for(JsonNode media:value)outputs.add(media(media,field,media.path("stream_index").asInt()));
            else if(value.isObject())outputs.add(media(value,field,0));
        }
        for(JsonNode group:root.path("stems"))for(String stem:List.of("drums","bass","vocals","other"))
            if(group.path(stem).isObject())outputs.add(media(group.path(stem),"stem_"+stem,group.path("stream_index").asInt()));
        if(outputs.isEmpty()&&kind.equals("json"))return json(root);
        int count=root.path("audio").size();if(count==0)count=root.path("videos").size();if(count==0&&root.path("outputs").isArray())count=root.path("outputs").size();
        if(!outputs.isEmpty())for(String key:List.of("stems_error","trial_preview"))if(root.hasNonNull(key)) {
            Map<String,Object> details=new LinkedHashMap<>(outputs.get(0).getMetadata());details.put(key,JSON.convertValue(root.get(key),Object.class));outputs.get(0).setMetadata(details);
        }
        ProviderSubmitResult result=outputs(outputs,Map.of("actualOutputCount",Math.max(1,count)),root);
        applySoniloBillingFloor(result);
        return result;
    }

    /** 官方音乐接口每个变体最低计费10秒，附属分轨、混音和格式副本不另计变体。 */
    static void applySoniloBillingFloor(ProviderSubmitResult result) {
        if(result.getAudioOutputs()==null)return;
        Map<Integer,Long> durations=new LinkedHashMap<>();
        for(AudioOutputItem item:result.getAudioOutputs()) {
            if(!Set.of("main","video").contains(item.getRole()))continue;
            if(item.getDurationMs()==null||item.getDurationMs()<=0)return;
            durations.merge(item.getVariantIndex()==null?0:item.getVariantIndex(),item.getDurationMs(),Math::max);
        }
        if(durations.isEmpty())return;
        long billed=0,original=0;
        for(long duration:durations.values()) {billed=Math.addExact(billed,Math.max(10000,duration));original=Math.addExact(original,duration);}
        Map<String,Object> usage=result.getUsage()==null?new LinkedHashMap<>():new LinkedHashMap<>(result.getUsage());
        usage.put("actualDuration",BigDecimal.valueOf(billed,3));
        usage.put("originalOutputDuration",BigDecimal.valueOf(original,3));
        usage.put("minimumBillableSecondsPerVariant",10);
        result.setUsage(usage);
    }

    private void combined(JsonNode variant,List<AudioOutputItem> outputs,int index) {
        if(variant.path("output_url").isTextual()) {
            boolean video=variant.path("output_type").asText().equals("video");
            var item=artifacts.remote(variant.path("output_url").asText(),video?"video":"main",index,null,Map.of());
            if(video)item.setMediaType("VIDEO");outputs.add(item);
        }
        for(String field:List.of("music","sfx","music_processed"))if(variant.path(field).isObject())outputs.add(media(variant.path(field),field,index));
    }

    private AudioOutputItem media(JsonNode media,String role,int index) {
        String url=media.path("url").asText(null);
        if(url==null)throw new ServiceException("上游音频结果缺少URL");
        Long duration=media.hasNonNull("duration")?media.path("duration").decimalValue().movePointRight(3).longValue():null;
        AudioOutputItem item=artifacts.remote(url,role,index,duration,Map.of());
        item.setContentType(media.path("content_type").asText(null));item.setSizeBytes(media.hasNonNull("file_size")?media.path("file_size").asLong():null);
        return item;
    }

    private ProviderSubmitResult seed(JsonNode root,OfficialAudioContractRegistry.Plan plan) {
        Map<String,Object> details=map(root.path("subtitle"));
        AudioOutputItem item;
        String format=stringNested(plan.body(),"audio_config","format","wav");
        if(root.path("audio").isTextual())item=artifacts.store(decode(root.path("audio").asText()),format,"main",0,details);
        else item=artifacts.remote(root.path("url").asText(),"main",0,null,details);
        long original=milliseconds(root.path("original_duration")),duration=milliseconds(root.path("duration"));
        if(original<=0||duration<=0)throw new ServiceException("音频原始时长或输出时长缺失");
        item.setOriginalDurationMs(original);item.setDurationMs(duration);
        return outputs(List.of(item),Map.of("actualDuration",BigDecimal.valueOf(original,3),"originalDurationMs",original),root);
    }

    private ProviderSubmitResult minimax(JsonNode root,OfficialAudioContractRegistry.Plan plan) {
        if(plan.resultKind().equals("json"))return json(root);
        if(root.path("data").path("status").asInt()!=2)throw new ProviderSubmissionOutcomeUnknownException();
        String audio=root.path("data").path("audio").asText();
        AudioOutputItem item;
        if("url".equals(plan.body().get("output_format")))item=artifacts.remote(audio,"main",0,null,Map.of());
        else item=artifacts.store(HexFormat.of().parseHex(audio),stringNested(plan.body(),"audio_setting","format","mp3"),"main",0,Map.of());
        long duration=root.path("extra_info").path("music_duration").asLong();
        if(duration>0)item.setDurationMs(duration);
        return outputs(List.of(item),Map.of("actualOutputCount",1),root);
    }

    private ProviderSubmitResult eleven(JsonNode root,OfficialAudioContractRegistry.Plan plan,OfficialAudioTransport.Response response) {
        if(root.path("previews").isArray()) {
            List<AudioOutputItem> previews=new ArrayList<>();
            for(JsonNode preview:root.path("previews"))if(preview.path("audio_base_64").isTextual()||preview.path("audio_base64").isTextual()) {
                String data=preview.path("audio_base_64").asText(preview.path("audio_base64").asText());
                Map<String,Object> details=map(preview);details.remove("audio_base_64");details.remove("audio_base64");
                previews.add(artifacts.store(decode(data),format(plan,response.contentType()),"voice_preview",previews.size(),details));
            }
            if(!previews.isEmpty()){var result=outputs(previews,characterUsage(response),root);result.setDirectText(safe(root));return result;}
        }
        if(root.hasNonNull("audio_base64")) {
            Map<String,Object> details=map(root);details.remove("audio_base64");
            AudioOutputItem item=artifacts.store(decode(root.path("audio_base64").asText()),format(plan,response.contentType()),"main",0,details);
            return outputs(List.of(item),characterUsage(response),root);
        }
        if(plan.resultKind().equals("json"))return json(root);
        throw new ProviderSubmissionOutcomeUnknownException();
    }

    private ProviderSubmitResult binary(OfficialAudioTransport.Response response,OfficialAudioContractRegistry.Plan plan) {
        byte[] data=response.bytes();
        if(data.length>=4&&data[0]=='P'&&data[1]=='K')return zip(data,plan,response);
        String type=response.contentType().toLowerCase();
        String format=type.contains("png")?"png":type.contains("jpeg")?"jpg":type.contains("webp")?"webp":type.contains("xml")?"xml":format(plan,response.contentType());
        AudioOutputItem item=artifacts.store(data,format,"main",0,Map.of());
        Map<String,Object> usage=characterUsage(response);
        if(item.getDurationMs()!=null)usage.put("actualDuration",BigDecimal.valueOf(item.getDurationMs(),3));
        return outputs(List.of(item),usage,null);
    }

    private ProviderSubmitResult zip(byte[] data,OfficialAudioContractRegistry.Plan plan,OfficialAudioTransport.Response response) {
        List<AudioOutputItem> outputs=new ArrayList<>();long total=0;int count=0;
        try(ZipInputStream in=new ZipInputStream(new ByteArrayInputStream(data))) {
            java.util.zip.ZipEntry entry;
            while((entry=in.getNextEntry())!=null) {
                if(entry.isDirectory())continue;
                if(++count>32)throw new ServiceException("音频压缩包文件过多");
                String name=entry.getName();
                if(name.startsWith("/")||name.contains("..")||name.contains("\\"))throw new ServiceException("音频压缩包路径无效");
                byte[] bytes=in.readNBytes(OfficialAudioTransport.MAX_RESPONSE_BYTES+1);total+=bytes.length;
                if(total>OfficialAudioTransport.MAX_RESPONSE_BYTES)throw new ServiceException("音频压缩包展开过大");
                String ext=name.lastIndexOf('.')>=0?name.substring(name.lastIndexOf('.')+1).toLowerCase():"";
                if(!Set.of("mp3","wav","flac","m4a","ogg","opus","aac","pcm","json","srt","vtt","txt").contains(ext))continue;
                String role=plan.operation().endsWith("stem_separation")?"stem_"+name.substring(name.lastIndexOf('/')+1,name.lastIndexOf('.')):ext.equals("json")?"metadata":"main";
                outputs.add(artifacts.store(bytes,ext,role,0,Map.of("name",name)));
            }
        }catch(IOException ex){throw new ServiceException("音频压缩包无法解析");}
        outputs.add(artifacts.store(data,"zip","package",0,Map.of()));
        return outputs(outputs,characterUsage(response),null);
    }

    private ProviderSubmitResult multipart(OfficialAudioTransport.Response response,OfficialAudioContractRegistry.Plan plan) {
        var matcher=java.util.regex.Pattern.compile("boundary=\"?([^\"; ]+)").matcher(response.contentType());
        if(!matcher.find())throw new ProviderSubmissionOutcomeUnknownException();
        String boundary="--"+matcher.group(1),all=new String(response.bytes(),StandardCharsets.ISO_8859_1);
        List<AudioOutputItem> outputs=new ArrayList<>();Map<String,Object> details=new LinkedHashMap<>();
        for(String part:all.split(java.util.regex.Pattern.quote(boundary))) {
            int start=part.indexOf("\r\n\r\n");if(start<0)continue;
            String header=part.substring(0,start),payload=part.substring(start+4);if(payload.endsWith("\r\n"))payload=payload.substring(0,payload.length()-2);
            byte[] bytes=payload.getBytes(StandardCharsets.ISO_8859_1);
            if(header.contains("application/json")){try{details.putAll(map(JSON.readTree(bytes)));}catch(Exception ex){throw new ServiceException("音乐元数据无法解析");}}
            else outputs.add(artifacts.store(bytes,format(plan,header),"main",0,details));
        }
        return outputs(outputs,characterUsage(response),null);
    }

    private ProviderSubmitResult stream(OfficialAudioTransport.Response response,OfficialAudioContractRegistry.Plan plan) {
        Map<Integer,ByteArrayOutputStream> tracks=new LinkedHashMap<>();Map<String,Object> metadata=new LinkedHashMap<>();
        boolean finished=false;String event="";JsonNode last=null;
        Map<String,Integer> contexts=new LinkedHashMap<>();java.util.List<Object> alignments=new ArrayList<>();
        Set<String> expectedContexts=new java.util.HashSet<>(), finalContexts=new java.util.HashSet<>();
        if(plan.operation().contains(".ws.")) {
            JsonNode messages=JSON.valueToTree(plan.body().get("messages"));
            for(JsonNode message:messages)if(message.path("inputs").isArray()&&!message.path("inputs").isEmpty())
                expectedContexts.add(message.path("context_id").asText("default"));
        }
        for(String line:response.text().split("\\r?\\n")) {
            if(line.startsWith("event:")){event=line.substring(6).trim();continue;}
            if(line.isBlank()||line.startsWith(":"))continue;
            String text=line.startsWith("data:")?line.substring(5).trim():line;
            if(text.equals("[DONE]")){finished=true;continue;}
            JsonNode chunk;try{chunk=JSON.readTree(text);}catch(Exception ex){throw new ProviderSubmissionOutcomeUnknownException();}
            if(chunk==null)continue;last=chunk;
            String type=chunk.path("type").asText(event);
            if(type.equals("error")||chunk.has("error"))return errors.rejected(plan.vendor(),400,chunk,response.requestId());
            String code=OfficialAudioErrorMapper.code(plan.vendor(),chunk);
            if(code!=null&&!Set.of("0","20000000").contains(code))return errors.rejected(plan.vendor(),400,chunk,response.requestId());
            int index=chunk.path("stream_index").asInt();
            if(chunk.hasNonNull("context_id"))index=contexts.computeIfAbsent(chunk.path("context_id").asText(),ignored->contexts.size());
            String audio=null;
            if(plan.vendor().equals("minimax"))audio=chunk.path("data").path("audio").asText(null);
            else if(type.equals("audio_chunk"))audio=chunk.path("data").asText(null);
            else if(chunk.path("audio_base64").isTextual())audio=chunk.path("audio_base64").asText();
            else if(chunk.path("audio").isTextual())audio=chunk.path("audio").asText();
            if(chunk.hasNonNull("alignment")) {if(alignments.size()>20000)throw new ServiceException("音频时间戳过多");alignments.add(Map.of("variantIndex",index,"alignment",map(chunk.path("alignment"))));}
            if(audio!=null&&!audio.isEmpty()) {
                byte[] bytes=plan.vendor().equals("minimax")?HexFormat.of().parseHex(audio):decode(audio);
                ByteArrayOutputStream stream=tracks.computeIfAbsent(index,ignored->new ByteArrayOutputStream());
                if((long)stream.size()+bytes.length>OfficialAudioTransport.MAX_RESPONSE_BYTES)throw new ServiceException("流式音频超过安全上限");
                stream.writeBytes(bytes);
            }else if(!type.isBlank())metadata.put(type,JSON.convertValue(chunk,Object.class));
            if(chunk.path("is_final").asBoolean())finalContexts.add(chunk.path("context_id").asText("default"));
            if(Set.of("complete","done","final","completion").contains(type)||chunk.path("is_final").asBoolean()
                    ||plan.vendor().equals("minimax")&&chunk.path("data").path("status").asInt()==2)finished=true;
        }
        if(!finished||tracks.isEmpty()||!finalContexts.containsAll(expectedContexts))throw new ProviderSubmissionOutcomeUnknownException();
        List<AudioOutputItem> outputs=new ArrayList<>();
        if(!alignments.isEmpty())metadata.put("alignmentChunks",alignments);
        if(!contexts.isEmpty())metadata.put("contexts",contexts);
        for(var entry:tracks.entrySet())outputs.add(artifacts.store(entry.getValue().toByteArray(),format(plan,response.contentType()),"main",entry.getKey(),metadata));
        Map<String,Object> usage=characterUsage(response);usage.put("actualOutputCount",outputs.size());
        return outputs(outputs,usage,last);
    }

    private ProviderSubmitResult outputs(List<AudioOutputItem> outputs,Map<String,Object> usage,JsonNode root) {
        if(outputs.isEmpty())throw new ProviderSubmissionOutcomeUnknownException();
        outputs.forEach(artifacts::persist);
        usage=new LinkedHashMap<>(usage);
        if(!usage.containsKey("actualDuration")) {
            long duration=0;Set<Integer> variants=new java.util.HashSet<>();
            for(AudioOutputItem item:outputs)if((item.getRole().equals("main")||item.getRole().equals("video"))&&variants.add(item.getVariantIndex())) {
                artifacts.ensureMetadata(item);
                if(item.getDurationMs()!=null)duration=Math.addExact(duration,item.getDurationMs());
            }
            if(duration>0)usage.put("actualDuration",BigDecimal.valueOf(duration,3));
        }
        AudioOutputItem first=outputs.stream().filter(x->x.getRole().equals("main")||x.getRole().equals("video")).findFirst().orElse(outputs.get(0));
        return ProviderSubmitResult.builder().directUrl(Boolean.TRUE.equals(first.getPersisted())?null:first.getUrl())
                .ossUrl(Boolean.TRUE.equals(first.getPersisted())?first.getUrl():null).audioOutputs(outputs)
                .audioDurationMs(first.getDurationMs()).usage(usage).rawResponse(root==null?null:safe(root)).build();
    }
    private ProviderSubmitResult json(JsonNode root){return ProviderSubmitResult.builder().directText(safe(root)).build();}
    private static ProviderTaskResult processing(boolean recognized,String state){return ProviderTaskResult.builder().status("PROCESSING").querySuccessful(recognized).terminalConfirmed(false).providerStatus(state).build();}
    private static JsonNode parseJson(OfficialAudioTransport.Response response){if(response.bytes().length==0)return null;try{return JSON.readTree(response.bytes());}catch(Exception ignored){return null;}}
    private static String safe(JsonNode root){JsonNode copy=root.deepCopy();redactUrls(copy,0);return MediaTaskPayloadSanitizer.sanitizeForStorage(copy.toString());}
    private static Map<String,Object> map(JsonNode root){try{return root!=null&&root.isObject()?JSON.readValue(safe(root),LinkedHashMap.class):new LinkedHashMap<>();}catch(IOException invalid){throw new ServiceException("音频元数据无法归档");}}
    private static void redactUrls(JsonNode node,int depth) {
        if(depth>64)throw new ServiceException("音频元数据层级过深");
        if(node.isObject()) {
            var entries=node.fields();List<String> keys=new ArrayList<>();while(entries.hasNext())keys.add(entries.next().getKey());
            for(String key:keys) {
                JsonNode value=node.get(key);
                if(value.isTextual()&&(Set.of("audio_base64","audio_base_64","content_base64").contains(key.toLowerCase())
                        ||key.equals("audio")&&!value.asText().matches("^https?://.*")))((com.fasterxml.jackson.databind.node.ObjectNode)node).put(key,"[binary omitted]");
                else if(Set.of("api_key","apikey","authorization","access_token","secret").contains(key.toLowerCase()))((com.fasterxml.jackson.databind.node.ObjectNode)node).put(key,"[redacted]");
                else if(value.isTextual()&&value.asText().matches("^https?://.*")) {
                    try{var uri=java.net.URI.create(value.asText());if(uri.getRawQuery()!=null||uri.getUserInfo()!=null)((com.fasterxml.jackson.databind.node.ObjectNode)node).put(key,new java.net.URI(uri.getScheme(),null,uri.getHost(),uri.getPort(),uri.getPath(),null,null).toString());}
                    catch(Exception invalid){((com.fasterxml.jackson.databind.node.ObjectNode)node).put(key,"[invalid URL]");}
                }else redactUrls(value,depth+1);
            }
        }else if(node.isArray())for(JsonNode child:node)redactUrls(child,depth+1);
    }
    private static byte[] decode(String value){try{return Base64.getDecoder().decode(value);}catch(Exception ex){throw new ServiceException("音频Base64响应无效");}}
    private static long milliseconds(JsonNode value){return value.isNumber()?value.decimalValue().movePointRight(3).longValue():0;}
    private static String format(OfficialAudioContractRegistry.Plan plan,String type){Object f=plan.query().get("output_format");if(f instanceof String text&&!text.equals("auto"))return text.split("_")[0];if(plan.vendor().equals("sonilo"))return String.valueOf(plan.body().getOrDefault("output_format","m4a"));if(plan.vendor().equals("minimax"))return stringNested(plan.body(),"audio_setting","format","mp3");return type!=null&&type.contains("wav")?"wav":"mp3";}
    private static String stringNested(Map<String,Object> body,String group,String key,String fallback){Object nested=body.get(group);return nested instanceof Map<?,?> m&&m.get(key) instanceof String text?text:fallback;}
    private static Map<String,Object> characterUsage(OfficialAudioTransport.Response response){Map<String,Object> result=new LinkedHashMap<>();for(var e:response.headers().entrySet())if(e.getKey().equalsIgnoreCase("character-cost")){try{result.put("actualChars",new BigDecimal(e.getValue().get(0)));}catch(Exception ignored){}}return result;}
}
