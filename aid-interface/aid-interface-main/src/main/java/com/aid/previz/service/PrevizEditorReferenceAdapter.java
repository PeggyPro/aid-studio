package com.aid.previz.service;

import com.aid.assistant.dto.AssistantRequests;
import com.aid.assistant.scope.ResolvedContextRef;
import com.aid.assistant.scope.RuntimeScopeAccess;
import com.aid.assistant.service.AssistantContextReferenceProjector;
import com.aid.common.exception.ServiceException;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix="aid.previz.runtime", name="enabled", havingValue="true")
public final class PrevizEditorReferenceAdapter implements RuntimeScopeAccess, AssistantContextReferenceProjector {
    private final PrevizEditorStore editors;
    private final PrevizProjectService projects;
    @Override public boolean supports(String kind) { return "PREVIZ_EDITOR".equals(kind); }
    @Override public ResolvedContextRef resolvePinned(Long actor, AssistantRequests.ContextRef ref) {
        if (ref == null || !supports(ref.getKind()) || !Objects.equals(ref.getObjectId(),ref.getContentHash())) invalid();
        JSONObject raw=(JSONObject)JSON.toJSON(ref);
        if(raw.entrySet().stream().anyMatch(e->e.getValue()!=null && !Set.of("kind","previzProjectId","objectId","contentHash","reason").contains(e.getKey()))) invalid();
        projects.requireWrite(actor,ref.getPrevizProjectId());
        read(actor,ref.getPrevizProjectId(),ref.getObjectId());
        var canonical=new AssistantRequests.ContextRef();canonical.setKind("PREVIZ_EDITOR");
        canonical.setPrevizProjectId(ref.getPrevizProjectId());canonical.setObjectId(ref.getObjectId());canonical.setContentHash(ref.getObjectId());
        return new ResolvedContextRef(canonical,"previz-editor:"+ref.getPrevizProjectId()+":"+ref.getObjectId(),"READ");
    }
    @Override public ResolvedContextRef resolveCurrent(Long actor, AssistantRequests.ContextRef ref) { return resolvePinned(actor,ref); }
    @Override public JSONObject project(Long actor, ResolvedContextRef resolved, int ordinal) {
        var ref=resolvePinned(actor,resolved.contextRef()).contextRef();
        JSONObject result=new JSONObject();result.put("source","PREVIZ_EDITOR");result.put("ordinal",ordinal);
        result.put("previzProjectId",ref.getPrevizProjectId());result.put("editorVersion",ref.getObjectId());
        result.put("editorState",read(actor,ref.getPrevizProjectId(),ref.getObjectId()));
        result.put("editingContract","Use aid.previz.scene-inspect to read real object identities; use aid.previz.scene-edit for transform, color, visibility and keyframe edits without rebuilding geometry. For new or changed geometry use aid.previz.build-scoped with an explicit operation. Editor offsets, object tracks and colors are applied by the host after source construction. Preserve semantic groups and hf_id; mark scene containers aid_role=scene, independently movable groups aid_role=object, and their components aid_role=part in Three.js userData or Blender custom properties; do not bake overlays twice. Declare build operation: ADD outputs only new object roots listed in outputObjectIds; MODIFY names one existing GLB targetNodeId; REPLACE_SCOPE names explicit observed targetNodeIds; REBUILD_SCENE replaces observed geometry only. The host binds editor version and baseline, rejects locked or missing targets, and retains independent manual edits. Never reconstruct existing people or environment for an ADD. Render success alone is not visual review.");
        result.put("buildScopeContract", "ADD and REBUILD_SCENE must omit targetNodeIds or send an empty array. For REBUILD_SCENE the host derives every replaceable geometry root from this pinned editor state and rejects locked roots. MODIFY requires exactly one observed GLB node; REPLACE_SCOPE requires observed targetNodeIds. ADD and REPLACE_SCOPE require nonempty outputObjectIds. Never repeat editor IDs in targetNodeIds merely to describe a whole-scene rebuild.");
        return result;
    }
    private JSONObject read(Long actor,String project,String version) {
        try{return editors.read(actor,project,version);}catch(java.io.IOException|IllegalArgumentException e){throw new ServiceException("三维编辑版本不可读取");}
    }
    private static void invalid(){throw new ServiceException("三维编辑引用无效");}
}
