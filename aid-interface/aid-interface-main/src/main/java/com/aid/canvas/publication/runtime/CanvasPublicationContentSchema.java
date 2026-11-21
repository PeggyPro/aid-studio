package com.aid.canvas.publication.runtime;

import com.aid.common.exception.ServiceException;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.util.*;

/** 发布格式的显式创作字段表。未知字段拒绝，不能删掉后继续宣称完整复制。 */
final class CanvasPublicationContentSchema {
    private CanvasPublicationContentSchema() {}
    private static final Map<String,String> SHAPES = Map.ofEntries(
        Map.entry("NODE", "nodeType title position@point size@size prompt negativePrompt textContent parameters@parameters resourceIds[] inputPorts[]@port metadata@nodeMetadata"),
        Map.entry("EDGE", "sourceObjectId targetObjectId sourceHandle targetHandle label style@edgeStyle metadata@edgeMetadata"),
        Map.entry("GROUP", "title position@point size@size childObjectIds[] style@groupStyle metadata@groupMetadata"),
        Map.entry("point", "x y"), Map.entry("size", "width height"), Map.entry("rect", "x y w h"),
        Map.entry("edgeStyle", "stroke strokeWidth strokeDasharray opacity"),
        Map.entry("groupStyle", "colorTag"), Map.entry("groupMetadata", "layoutMode"),
        Map.entry("nodeMetadata", "studioData@studio hidden draggable"),
        Map.entry("edgeMetadata", "studioData@edgeData type"),
        Map.entry("edgeData", "kind label inputRole"),
        Map.entry("port", "id name type role resourceId sourceNodeId"),
        Map.entry("studio", "kind title subtitle zoneId prompt documentContent negativePrompt model modelName mediaKind mediaId mediaAspectRatio resultSummary composerHidden labelColor emptyNextStepConsumed textEditingEnabled displayWidth displayHeight textBackground imagePurpose videoPurpose composerAgentOpen composerSkillId composerAgentCode composerAgentName storyboardCompositionId highlightRefNodeId recommendedDurationSeconds recommendedDurationSource recommendedDurationDescription canvasResourceIds[] canvasPrimaryResourceId directorSceneId previzOfSceneId previzOfNodeId previzRecordMode operationOrigin previzFps previzWidth previzHeight previzBurnedNameplates groupId generationOptions@parameters imageSplitOrigin@imageSplit imageDescribe@sourceNode composerRefs[]@composer frameBreakdown@frameBreakdown smartEdit@smartEdit smartEditOrigin@smartEditOrigin layerSeparation@layers gridStoryboard@grid gridStoryboardOrigin@gridOrigin imageToolOptions@imageTool videoToolOptions@videoTool audioEdit@audioEdit videoSubjectEdit@subjectEdit mediaDerivation@derivation storyboardRows[]@storyboardRow storyboardAssets[]@storyboardAsset storyboardWorkflowStep storyboardPromptMode canvasGenerationRetry@retry"),
        Map.entry("parameters", "aspectRatio duration audioEnabled motion shootingTechnique voice quality count videoMode videoCapability endFrameEnabled styleId styleName stylePrompt benchPreset autoLink audioCapability audioPurpose audioLanguage sampleRate audioFormat lyricsMode lyrics texture@texture narrative@narrative audioParameters@audioParameters audioFiles[]@audioFile audioResourceIds[]"),
        Map.entry("audioFile", "field resourceId"),
        Map.entry("texture", "preset sharpness contrast grain"), Map.entry("narrative", "shotSize cameraHeight moods[]"),
        Map.entry("imageSplit", "sourceNodeId sourceResourceId row col x y width height"),
        Map.entry("sourceNode", "sourceNodeId"),
        Map.entry("composer", "id kind nodeId resourceId title mediaKind textContent sourceKey channel inputRole"),
        Map.entry("frameBreakdown", "dimensions[] sourceNodeId sourceTitle"),
        Map.entry("smartEditOrigin", "nodeId kind sourceNodeId"),
        Map.entry("smartEdit", "playbook chrome timelineZoom snapping hiddenTracks[] dismissedSourceIds[] timelineInitialized timeline@editTimeline"),
        Map.entry("editTimeline", "durationSeconds videoClips[]@editClip overlayClips[]@editClip audioClips[]@editClip subtitles[]@cue texts[]@text"),
        Map.entry("editClip", "id sourceNodeId sourceResourceId sourceDurationSeconds timelineStartSeconds range@range volume track overlayRect@rect transitionOut@transition"),
        Map.entry("range", "startSeconds endSeconds durationSeconds"), Map.entry("transition", "type durationSeconds"),
        Map.entry("cue", "id timelineStartSeconds durationSeconds text"),
        Map.entry("text", "id timelineStartSeconds durationSeconds text fontSize color rect@rect animation"),
        Map.entry("layers", "sourceNodeId backgroundResourceId canvasWidth canvasHeight backgroundLocked layers[]@layer activeLayerId dirty"),
        Map.entry("layer", "id resourceId label transform@layerTransform"),
        Map.entry("layerTransform", "rect@rect rotationDeg flipX flipY opacity visible"),
        Map.entry("grid", "rows columns aspectRatio showSerial cells[]@cell sourceNodeId"),
        Map.entry("cell", "id row col resourceId mediaKind mediaAspectRatio canvasWidth canvasHeight title"),
        Map.entry("gridOrigin", "gridNodeId cellId sourceNodeId canvasWidth canvasHeight"),
        Map.entry("imageTool", "provider modelCode scale fineness aspectRatio quality count sourceNodeId"),
        Map.entry("videoTool", "sourceNodeId sourceResourceId promptContext depthResolution clipRange@range cropRect@rect resolution interpolationMode frameRate slowMotionFactor subtitleMode subtitleRegions[]@subtitleRegion"),
        Map.entry("subtitleRegion", "id rect@rect"),
        Map.entry("audioEdit", "operation sourceNodeId sourceResourceId sourceTitle durationSeconds startSeconds endSeconds speed instruction"),
        Map.entry("derivation", "groupId sourceNodeId sourceResourceId operation outputRole pairedNodeId"),
        Map.entry("subjectEdit", "mode sourceNodeId sourceResourceId sourceTitle range@range marks[]@subjectMark"),
        Map.entry("subjectMark", "id frameSeconds selection@selection candidates[]@candidate candidateId instruction replacementNodeId replacementResourceId confirmed"),
        Map.entry("selection", "tool point@point rect@rect strokes[]@stroke"), Map.entry("stroke", "tool size points[]@point"),
        Map.entry("candidate", "id label rect@rect thumbnailResourceId maskResourceId"),
        Map.entry("storyboardRow", "id shot duration description shotSize lighting dialogue[]@dialogue soundEffect cameraMovement finalPrompt motionPrompt rowColor"),
        Map.entry("dialogue", "id kind speaker content"), Map.entry("storyboardAsset", "kind nodeId title resourceId"),
        Map.entry("retry", "kind prompt model negativePrompt generationOptions@parameters")
    );
    static void validate(String type, JSONObject content) { validateShape(type,content,"content",0); }
    private static void validateShape(String type,JSONObject object,String path,int depth) {
        if(depth>25) fail(path,"层级过深");
        // 音频参数由能力目录声明为嵌套 JSON；发布只保存创作数据，实际调用仍按当前能力契约校验。
        if(type.equals("audioParameters")) { validateParameterValue(object,path,depth); return; }
        String specification=SHAPES.get(type); if(specification==null) fail(path,"未知对象类型");
        Map<String,String> allowed=new HashMap<>();
        for(String token:specification.split(" ")) { String name=token.split("@",2)[0].replace("[]",""); allowed.put(name,token); }
        for(var e:object.entrySet()) {
            String token=allowed.get(e.getKey()), at=path+"."+e.getKey(); if(token==null) fail(at,"尚不支持完整发布此字段");
            Object value=e.getValue();
            boolean array=token.contains("[]"); String child=token.contains("@")?token.substring(token.indexOf('@')+1):null;
            if(value==null) { if(array||child!=null) fail(at,"不能为null"); continue; }
            if(array) {
                if(!(value instanceof JSONArray list)||list.size()>10000) fail(at,"必须为有界数组");
                for(Object row:(JSONArray)value) if(child!=null) validateShape(child,asObject(row,at),at,depth+1); else scalar(row,at);
            } else if(child!=null) validateShape(child,asObject(value,at),at,depth+1); else scalar(value,at);
        }
        if(type.equals("composer")) {
            if(!Set.of("node","upload","frame").contains(Objects.toString(object.getString("kind"),""))) fail(path,"素材引用须转换为nodeId或resourceId");
            if(!object.containsKey("nodeId")&&!object.containsKey("resourceId")) fail(path,"缺少持久引用");
            String source=object.getString("sourceKey");
            if(source!=null&&!source.equals("node:"+object.getString("nodeId"))&&!source.equals("resource:"+object.getString("resourceId"))) fail(path,"sourceKey未转换为持久引用");
        }
        if(type.equals("editClip")&&!object.containsKey("sourceResourceId")) fail(path,"剪辑须提供冻结的sourceResourceId");
        if(type.equals("layer")&&!object.containsKey("resourceId")) fail(path,"图层缺少resourceId");
    }
    private static void validateParameterValue(Object value,String path,int depth) {
        if(depth>25) fail(path,"层级过深");
        if(value instanceof JSONObject object) {
            if(object.size()>1000) fail(path,"参数字段过多");
            for(var entry:object.entrySet()) {
                if(entry.getKey().isBlank()||entry.getKey().length()>128) fail(path,"参数字段名无效");
                validateParameterValue(entry.getValue(),path+"."+entry.getKey(),depth+1);
            }
        } else if(value instanceof JSONArray array) {
            if(array.size()>10000) fail(path,"必须为有界数组");
            for(Object item:array) validateParameterValue(item,path,depth+1);
        } else scalar(value,path);
    }
    private static JSONObject asObject(Object value,String path) { if(!(value instanceof JSONObject)) fail(path,"必须为对象"); return (JSONObject)value; }
    private static void scalar(Object value,String path) {
        if(value==null||value instanceof Boolean) return;
        if(value instanceof Number n&&Double.isFinite(n.doubleValue())) return;
        if(value instanceof String s&&s.length()<=65536) return;
        fail(path,"只允许明确的文本、数字或布尔值");
    }
    private static void fail(String path,String reason) { throw new ServiceException(path+"："+reason,400); }
}
