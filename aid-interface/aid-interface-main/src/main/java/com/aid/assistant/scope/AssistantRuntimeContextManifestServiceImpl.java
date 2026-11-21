package com.aid.assistant.scope;

import cn.hutool.crypto.SecureUtil;
import com.aid.assistant.domain.AidAssistantSession;
import com.aid.assistant.dto.AssistantRequests;
import com.aid.assistant.error.AssistantServiceException;
import com.aid.runtime.domain.AidRuntimeRun;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class AssistantRuntimeContextManifestServiceImpl implements AssistantRuntimeContextManifestService
{
    private static final String SCHEMA = "assistant-context-manifest/v1";

    @Resource private RuntimeScopeAccessRegistry registry;
    @Resource private AssistantContextLimits contextLimits;
    @Autowired(required = false) private List<AssistantContextProgression> progressions = List.of();

    @Override
    public ResolvedManifest resolveForCreate(Long userId, AidAssistantSession session,
                                             List<AssistantRequests.ContextRef> requested)
    {
        contextLimits.checkRawRequestBudget(requested);
        return build(session, registry.resolvePinnedAll(userId, withWorkspaceScope(session, requested)));
    }

    @Override
    public ResolvedManifest requirePinned(Long userId, AidAssistantSession session, AidRuntimeRun run)
    {
        if (userId == null || userId <= 0 || run == null || session == null
                || run.getId() == null || session.getId() == null) stale("CONTEXT_AUTHORITY_INVALID");
        JSONObject request = parseRequest(run);
        if (request == null) stale("CONTEXT_MANIFEST_INVALID");
        JSONObject stored = request.getJSONObject("_contextManifest");
        if (stored == null || !SCHEMA.equals(stored.getString("schemaVersion"))) stale("CONTEXT_MANIFEST_MISSING");
        if (!Objects.equals(run.getUserId(), userId)
                || !Objects.equals(run.getSessionId(), session.getId())
                || !Objects.equals(session.getUserId(), userId)
                || !Objects.equals(run.getContextDigest(), SecureUtil.sha256(stored.toJSONString())))
            stale("CONTEXT_AUTHORITY_INVALID");
        List<AssistantContextProgression> matching = progressions.stream()
                .filter(adapter -> adapter.supports(session)).toList();
        if (matching.size() > 1) stale("CONTEXT_PROGRESSION_AMBIGUOUS");
        if (matching.size() == 1) {
            AssistantContextProgression.Progressed progressed = matching.get(0)
                    .resolvePinned(userId, session, run, stored.toJSONString());
            if (progressed == null || progressed.authorityRefs() == null
                    || progressed.executionRefs() == null
                    || exceedsResolvedLimit(progressed.authorityRefs().size())
                    || exceedsResolvedLimit(progressed.executionRefs().size())) stale("CONTEXT_PROGRESSION_INVALID");
            ResolvedManifest authority = build(session, progressed.authorityRefs());
            ResolvedManifest execution = build(session, progressed.executionRefs());
            if (!Objects.equals(run.getContextDigest(), authority.digest())
                    || !Objects.equals(stored.toJSONString(), authority.manifestJson()))
                stale("CONTEXT_AUTHORITY_CHANGED");
            return new ResolvedManifest(authority.manifestJson(), authority.digest(),
                    authority.contextRefs(), authority.targetRefsJson(), execution.manifestJson(),
                    execution.digest(), execution.contextRefs(), execution.targetRefsJson(),
                    authority.resolvedContextRefs(), execution.resolvedContextRefs());
        }
        List<AssistantRequests.ContextRef> refs = request.getList("contextRefs", AssistantRequests.ContextRef.class);
        ResolvedManifest current = build(session,
                registry.resolvePinnedAll(userId, withWorkspaceScope(session, refs)));
        if (!Objects.equals(run.getContextDigest(), current.digest())
                || !Objects.equals(stored.toJSONString(), current.manifestJson()))
            stale("CONTEXT_CHANGED");
        return current;
    }

    @Override
    public boolean matchesExpectedRevisions(Long userId, AidAssistantSession session, AidRuntimeRun run,
                                            JSONArray observed, ResolvedManifest current) {
        if (current != null && com.aid.assistant.service.AssistantReferenceContextFence
                .matchesExpectedRevisions(observed, current.executionManifestJson())) return true;
        if (current == null || session == null || run == null) return false;
        List<AssistantContextProgression> matching = progressions.stream()
                .filter(adapter -> adapter.supports(session)).toList();
        return matching.size() == 1 && matching.get(0).matchesExpectedRevisions(
                userId, session, run, observed, current.executionManifestJson());
    }

    @Override
    public ResolvedManifest resolveCurrentForRefresh(Long userId, AidAssistantSession session,
                                                     List<AssistantRequests.ContextRef> identities)
    {
        List<AssistantRequests.ContextRef> scoped = withWorkspaceScope(session, identities);
        if (session.getNodeObjectId() != null && scoped.stream().noneMatch(ref -> ref != null
                && "FREE_CANVAS".equals(ref.getKind()) && Objects.equals(ref.getWorkspaceId(), session.getWorkspaceId())
                && Objects.equals(ref.getObjectId(), session.getNodeObjectId())))
        {
            AssistantRequests.ContextRef node = new AssistantRequests.ContextRef();
            node.setKind("FREE_CANVAS"); node.setWorkspaceId(session.getWorkspaceId());
            node.setObjectId(session.getNodeObjectId()); scoped.add(node);
        }
        contextLimits.checkRawRequestBudget(scoped.stream().filter(value -> value != null
                && !("FREE_CANVAS".equals(value.getKind()) && value.getObjectId() == null)).toList());
        return build(session, registry.resolveCurrentAll(userId, scoped));
    }

    private ResolvedManifest build(AidAssistantSession session, List<ResolvedContextRef> resolved)
    {
        if (resolved == null || exceedsResolvedLimit(resolved.size())) stale("CONTEXT_REFERENCE_LIMIT");
        List<ResolvedContextRef> ordered = new ArrayList<>(resolved);
        Set<String> unique = new LinkedHashSet<>();
        JSONArray refs = new JSONArray();
        JSONArray targets = new JSONArray();
        List<AssistantRequests.ContextRef> contextRefs = new ArrayList<>();
        List<ResolvedContextRef> normalizedResolved = new ArrayList<>();
        for (ResolvedContextRef value : ordered)
        {
            if (value == null || value.contextRef() == null
                    || value.contentRef() == null || value.contentRef().isBlank())
                stale("CONTEXT_REFERENCE_INVALID");
            String access = value.access() == null || value.access().isBlank() ? "READ" : value.access();
            ResolvedContextRef normalized = new ResolvedContextRef(
                    value.contextRef(), value.contentRef(), access);
            requireSessionMatch(session, value.contextRef());
            JSONObject canonical = AssistantRuntimeContextManifestService.canonicalRef(value.contextRef());
            String identity = value.contextRef().getKind() + "\n" + canonical;
            if (!unique.add(identity)) stale("CONTEXT_DUPLICATE");
            JSONObject item = new JSONObject();
            item.put("kind", value.contextRef().getKind());
            item.put("canonicalRef", canonical);
            item.put("contentRef", value.contentRef());
            item.put("access", access);
            refs.add(item);
            targets.add(AssistantRuntimeContextManifestService.canonicalTarget(value.contextRef()));
            contextRefs.add(value.contextRef());
            normalizedResolved.add(normalized);
        }
        JSONObject scope = new JSONObject();
        scope.put("projectId", id(zeroToNull(session.getProjectId())));
        scope.put("episodeId", zeroToNull(session.getProjectId()) == null ? null : id(session.getEpisodeId()));
        scope.put("workspaceId", id(session.getWorkspaceId()));
        JSONObject manifest = new JSONObject();
        manifest.put("schemaVersion", SCHEMA);
        manifest.put("sessionScope", scope);
        manifest.put("refs", refs);
        String json = manifest.toJSONString();
        List<ResolvedContextRef> immutableResolved = List.copyOf(normalizedResolved);
        return new ResolvedManifest(json, SecureUtil.sha256(json), List.copyOf(contextRefs),
                targets.toJSONString(), json, SecureUtil.sha256(json), List.copyOf(contextRefs),
                targets.toJSONString(), immutableResolved, immutableResolved);
    }

    private List<AssistantRequests.ContextRef> withWorkspaceScope(AidAssistantSession session,
                                                                   List<AssistantRequests.ContextRef> values)
    {
        List<AssistantRequests.ContextRef> result = new ArrayList<>();
        if (values != null) result.addAll(values);
        if (session.getWorkspaceId() != null && result.stream().noneMatch(value -> value != null
                && "FREE_CANVAS".equals(value.getKind())
                && Objects.equals(value.getWorkspaceId(), session.getWorkspaceId())
                && value.getObjectId() == null))
        {
            AssistantRequests.ContextRef workspace = new AssistantRequests.ContextRef();
            workspace.setKind("FREE_CANVAS");
            workspace.setWorkspaceId(session.getWorkspaceId());
            result.add(workspace);
        }
        return result;
    }

    private void requireSessionMatch(AidAssistantSession session, AssistantRequests.ContextRef ref)
    {
        if ("PREVIZ_PROJECT".equals(ref.getKind()) || "PREVIZ_IMAGE".equals(ref.getKind()) || "PREVIZ_EDITOR".equals(ref.getKind())) {
            JSONObject scope = JSON.parseObject(session.getBaseInputJson());
            if (!"PREVIZ_3DBOX".equals(session.getModuleCode()) || scope == null
                    || !Objects.equals(ref.getPrevizProjectId(), scope.getString("previzProjectId")))
                stale("PREVIZ_PROJECT_SCOPE_CHANGED");
        }

        if (ref.getWorkspaceId() != null && !Objects.equals(ref.getWorkspaceId(), session.getWorkspaceId()))
            stale("WORKSPACE_SCOPE_CHANGED");
        if ("DOCUMENT".equals(ref.getKind()) && !Objects.equals(ref.getDocumentSessionId(), session.getId()))
            stale("DOCUMENT_SESSION_CHANGED");
        Long projectId = zeroToNull(session.getProjectId());
        if (ref.getProjectId() != null && projectId != null && !Objects.equals(ref.getProjectId(), projectId))
            stale("PROJECT_SCOPE_CHANGED");
        Long episodeId = zeroToNull(session.getEpisodeId());
        // episodeId=0 is the project-level screenwriter scope.  It may pin multiple scripts from
        // the same project, while an episode-scoped session remains an exact episode boundary.
        if (ref.getEpisodeId() != null && projectId != null && episodeId != null
                && !Objects.equals(ref.getEpisodeId(), episodeId))
            stale("EPISODE_SCOPE_CHANGED");
    }

    private JSONObject parseRequest(AidRuntimeRun run)
    {
        try { return JSON.parseObject(run.getRequestJson()); }
        catch (RuntimeException invalid) { stale("CONTEXT_MANIFEST_INVALID"); return null; }
    }

    private String id(Long value) { return value == null ? null : String.valueOf(value); }
    private Long zeroToNull(Long value) { return value == null || value == 0L ? null : value; }
    private boolean exceedsResolvedLimit(int size) { return size > contextLimits.maxReferences() + 1; }
    private void stale(String reason)
    {
        throw new AssistantServiceException(HttpStatus.CONFLICT.value(), "ASSISTANT_CONTEXT_STALE",
                "\u4e0a\u4e0b\u6587\u5df2\u53d8\u66f4", reason);
    }
}
