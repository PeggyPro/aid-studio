package com.aid.previz.service;

import com.aid.assistant.dto.AssistantRequests;
import com.aid.assistant.scope.ResolvedContextRef;
import com.aid.assistant.scope.RuntimeScopeAccess;
import com.aid.assistant.service.AssistantContextReferenceProjector;
import com.aid.assistant.service.AssistantReferenceMediaAdapter;
import com.aid.common.exception.ServiceException;
import com.aid.media.dto.MediaTextGenerateRequest;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "aid.previz.runtime", name = "enabled", havingValue = "true")
public final class PrevizImageReferenceAdapter implements RuntimeScopeAccess,
        AssistantContextReferenceProjector, AssistantReferenceMediaAdapter, com.aid.media.service.ManagedTextImageResolver {
    private final PrevizReferenceImageStore images;
    private final PrevizProjectService projects;
    @Override public boolean supports(String kind) { return "PREVIZ_IMAGE".equals(kind)
            || kind != null && kind.startsWith("aid-image://previz/"); }
    @Override public com.aid.media.service.ManagedTextImageResolver.Image resolve(Long actor, String reference) {
        if (!com.aid.media.service.ManagedTextImageService.isReference(reference) || !reference.startsWith("aid-image://previz/"))
            throw new ServiceException("三维参考图引用无效");
        String[] identity = reference.substring("aid-image://previz/".length()).split("/");
        var image = read(actor, identity[0], identity[1]);
        return new com.aid.media.service.ManagedTextImageResolver.Image(image.bytes(), image.mimeType(), image.width(), image.height());
    }
    @Override public ResolvedContextRef resolvePinned(Long actor, AssistantRequests.ContextRef ref) {
        if (ref == null || !"PREVIZ_IMAGE".equals(ref.getKind()) || !Objects.equals(ref.getContentHash(), ref.getObjectId()))
            throw new ServiceException("三维参考图引用无效");
        JSONObject raw = (JSONObject) JSON.toJSON(ref);
        if (raw.entrySet().stream().anyMatch(entry -> entry.getValue() != null
                && !Set.of("kind", "previzProjectId", "objectId", "contentHash", "reason").contains(entry.getKey())))
            throw new ServiceException("三维参考图引用字段无效");
        projects.requireWrite(actor, ref.getPrevizProjectId());
        var image = read(actor, ref.getPrevizProjectId(), ref.getObjectId());
        var canonical = new AssistantRequests.ContextRef();
        canonical.setKind("PREVIZ_IMAGE"); canonical.setPrevizProjectId(ref.getPrevizProjectId());
        canonical.setObjectId(image.id()); canonical.setContentHash(image.id());
        return new ResolvedContextRef(canonical, "previz-image:" + ref.getPrevizProjectId() + ":" + image.id(), "READ");
    }
    @Override public ResolvedContextRef resolveCurrent(Long actor, AssistantRequests.ContextRef ref) { return resolvePinned(actor, ref); }
    @Override public JSONObject project(Long actor, ResolvedContextRef resolved, int ordinal) {
        var ref = resolvePinned(actor, resolved.contextRef()).contextRef();
        var image = read(actor, ref.getPrevizProjectId(), ref.getObjectId());
        JSONObject result = new JSONObject();
        result.put("source", "PREVIZ_IMAGE"); result.put("ordinal", ordinal);
        result.put("previzProjectId", ref.getPrevizProjectId()); result.put("objectId", image.id());
        result.put("contentHash", image.id()); result.put("mimeType", image.mimeType());
        result.put("width", image.width()); result.put("height", image.height());
        result.put("sizeBytes", image.bytes().length);
        return result;
    }
    @Override public List<MediaTextGenerateRequest.TextContentPart> adapt(Long actor, JSONObject reference, Set<String> modalities) {
        if (!modalities.contains("IMAGE")) throw new ServiceException("当前文本模型不支持参考图，请选择支持图片输入的模型");
        var image = read(actor, reference.getString("previzProjectId"), reference.getString("objectId"));
        if (!image.id().equals(reference.getString("contentHash"))) throw new ServiceException("参考图内容已变化");
        var part = new MediaTextGenerateRequest.TextContentPart();
        part.setType("image"); part.setUrl("aid-image://previz/" + reference.getString("previzProjectId") + "/" + image.id()); part.setMimeType(image.mimeType());
        part.setWidth(image.width()); part.setHeight(image.height()); part.setSizeBytes((long) image.bytes().length);
        reference.put("modelDelivery", "PROVIDER_NATIVE_IMAGE"); reference.put("semanticSource", "PROVIDER_NATIVE_MEDIA");
        reference.put("semanticDescriptionRequired", false);
        return List.of(part);
    }
    private PrevizReferenceImageStore.Image read(Long actor, String project, String id) {
        try { return images.read(actor, project, id); }
        catch (IOException invalid) { throw new ServiceException("参考图不存在或校验失败，请重新上传"); }
    }
}
