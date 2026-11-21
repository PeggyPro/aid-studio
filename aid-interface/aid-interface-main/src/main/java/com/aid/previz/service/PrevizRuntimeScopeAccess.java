package com.aid.previz.service;

import com.aid.assistant.dto.AssistantRequests;
import com.aid.assistant.scope.ResolvedContextRef;
import com.aid.assistant.scope.RuntimeScopeAccess;
import com.aid.common.exception.ServiceException;
import com.aid.previz.workspace.PrevizSourceStore;
import java.io.IOException;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "aid.previz.runtime", name = "enabled", havingValue = "true")
public final class PrevizRuntimeScopeAccess implements RuntimeScopeAccess
{
    private final PrevizProjectService projects;
    private final PrevizSourceStore sources;
    @Override public boolean supports(String kind) { return "PREVIZ_PROJECT".equals(kind); }
    @Override public ResolvedContextRef resolvePinned(Long actor, AssistantRequests.ContextRef ref)
    {
        return resolve(actor, ref, true);
    }
    @Override public ResolvedContextRef resolveCurrent(Long actor, AssistantRequests.ContextRef ref)
    {
        return resolve(actor, ref, false);
    }
    private ResolvedContextRef resolve(Long actor, AssistantRequests.ContextRef ref, boolean pinned)
    {
        if (ref == null || !supports(ref.getKind()) || ref.getPrevizProjectId() == null
                || !ref.getPrevizProjectId().matches("[A-Za-z0-9_-]{1,100}")
                || ref.getWorkspaceId() != null || ref.getProjectId() != null || ref.getResourceId() != null
                || ref.getObjectId() != null || ref.getVersion() != null || ref.getEpisodeId() != null
                || ref.getEntityId() != null || ref.getEntityType() != null || ref.getScriptId() != null
                || ref.getDocumentId() != null || ref.getDocumentSessionId() != null
                || ref.getContentHash() != null || ref.getSelection() != null)
            throw new ServiceException("独立三维项目引用无效");
        projects.requireWritableScope(actor, ref.getPrevizProjectId());
        try
        {
            // Source versions are immutable input snapshots. Later edits do not
            // rewrite this input; write tools separately enforce expectedVersion CAS.
            String current = pinned ? ref.getSourceVersion() : sources.currentVersion(actor, ref.getPrevizProjectId());
            if (current != null) sources.read(actor, ref.getPrevizProjectId(), current);
            AssistantRequests.ContextRef result = new AssistantRequests.ContextRef();
            result.setKind("PREVIZ_PROJECT");
            result.setPrevizProjectId(ref.getPrevizProjectId());
            result.setSourceVersion(current);
            return new ResolvedContextRef(result, "previz:" + ref.getPrevizProjectId() + ":" + (current == null ? "empty" : current), "WRITE");
        }
        catch (IOException invalid) { throw new ServiceException("工程源码无法读取，请核对本地存储"); }
    }
}
