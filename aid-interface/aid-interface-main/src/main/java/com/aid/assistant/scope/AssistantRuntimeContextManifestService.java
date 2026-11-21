package com.aid.assistant.scope;

import com.aid.assistant.domain.AidAssistantSession;
import com.aid.assistant.dto.AssistantRequests;
import com.aid.runtime.domain.AidRuntimeRun;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.List;
import java.util.Objects;

/** Single canonical builder for all assistant context identities. */
public interface AssistantRuntimeContextManifestService
{
    ResolvedManifest resolveForCreate(Long userId, AidAssistantSession session,
                                      List<AssistantRequests.ContextRef> requested);

    ResolvedManifest requirePinned(Long userId, AidAssistantSession session, AidRuntimeRun run);

    /** Call only after requirePinned has verified access and authoritative progression. */
    default boolean matchesExpectedRevisions(Long userId, AidAssistantSession session, AidRuntimeRun run,
                                             JSONArray observed, ResolvedManifest current) {
        if (observed == null || current == null) return false;
        try {
            JSONObject root = JSON.parseObject(current.executionManifestJson());
            JSONArray refs = root == null ? null : root.getJSONArray("refs");
            if (refs == null || refs.size() != observed.size()) return false;
            JSONArray expected = new JSONArray();
            for (Object raw : refs) {
                if (!(raw instanceof JSONObject row) || row.getString("kind") == null
                        || row.getJSONObject("canonicalRef") == null) return false;
                JSONObject revision = new JSONObject();
                revision.put("kind", row.getString("kind"));
                revision.put("canonicalRef", row.getJSONObject("canonicalRef"));
                expected.add(revision);
            }
            // Preserve the existing null-omitting canonical reference comparison.
            return Objects.equals(JSON.parseArray(JSON.toJSONString(expected)),
                    JSON.parseArray(JSON.toJSONString(observed)));
        } catch (RuntimeException invalid) { return false; }
    }

    ResolvedManifest resolveCurrentForRefresh(Long userId, AidAssistantSession session,
                                              List<AssistantRequests.ContextRef> identities);

    /** Authorization compares the same identity projection the quote returns. */
    static JSONObject canonicalTarget(AssistantRequests.ContextRef ref) {
        JSONObject target = JSONObject.of("kind", ref.getKind());
        target.putAll(canonicalRef(ref));
        return target;
    }

    static JSONObject canonicalRef(AssistantRequests.ContextRef ref) {
        JSONObject value = new JSONObject();
        put(value, "workspaceId", id(ref.getWorkspaceId()));
        put(value, "resourceId", id(ref.getResourceId()));
        put(value, "objectId", ref.getObjectId());
        put(value, "version", ref.getVersion());
        put(value, "projectId", id(ref.getProjectId()));
        put(value, "episodeId", id(ref.getEpisodeId()));
        put(value, "entityType", ref.getEntityType());
        put(value, "entityId", ref.getEntityId());
        put(value, "scriptId", id(ref.getScriptId()));
        put(value, "previzProjectId", ref.getPrevizProjectId());
        put(value, "sourceVersion", ref.getSourceVersion());
        put(value, "documentId", id(ref.getDocumentId()));
        put(value, "documentSessionId", id(ref.getDocumentSessionId()));
        put(value, "contentHash", ref.getContentHash());
        if (ref.getSelection() != null) {
            JSONObject selection = new JSONObject();
            selection.put("start", ref.getSelection().getStart());
            selection.put("end", ref.getSelection().getEnd());
            selection.put("selectedTextHash", ref.getSelection().getSelectedTextHash());
            value.put("selection", selection);
        }
        return value;
    }

    private static void put(JSONObject target, String key, Object value) {
        if (value != null) target.put(key, value);
    }

    private static String id(Long value) {
        return value == null ? null : String.valueOf(value);
    }

    record ResolvedManifest(String manifestJson, String digest,
                            List<AssistantRequests.ContextRef> contextRefs,
                            String targetRefsJson, String executionManifestJson,
                            String executionDigest, List<AssistantRequests.ContextRef> executionContextRefs,
                            String executionTargetRefsJson,
                            List<ResolvedContextRef> resolvedContextRefs,
                            List<ResolvedContextRef> executionResolvedContextRefs)
    {
        public ResolvedManifest {
            contextRefs = contextRefs == null ? List.of() : List.copyOf(contextRefs);
            executionContextRefs = executionContextRefs == null
                    ? List.of() : List.copyOf(executionContextRefs);
            resolvedContextRefs = resolvedContextRefs == null
                    ? List.of() : List.copyOf(resolvedContextRefs);
            executionResolvedContextRefs = executionResolvedContextRefs == null
                    ? List.of() : List.copyOf(executionResolvedContextRefs);
            requirePairs(manifestJson, contextRefs, resolvedContextRefs);
            requirePairs(executionManifestJson, executionContextRefs, executionResolvedContextRefs);
        }

        public ResolvedManifest(String manifestJson, String digest,
                                List<AssistantRequests.ContextRef> contextRefs, String targetRefsJson) {
            this(manifestJson, digest, contextRefs, targetRefsJson,
                    manifestJson, digest, contextRefs, targetRefsJson);
        }

        public ResolvedManifest(String manifestJson, String digest,
                                List<AssistantRequests.ContextRef> contextRefs,
                                String targetRefsJson, String executionManifestJson,
                                String executionDigest,
                                List<AssistantRequests.ContextRef> executionContextRefs,
                                String executionTargetRefsJson) {
            this(manifestJson, digest, contextRefs, targetRefsJson,
                    executionManifestJson, executionDigest, executionContextRefs,
                    executionTargetRefsJson, List.of(), List.of());
        }

        private static void requirePairs(String manifestJson,
                                         List<AssistantRequests.ContextRef> contextRefs,
                                         List<ResolvedContextRef> resolved) {
            // Legacy four/eight-argument records remain usable for non-execution
            // projections. They must not manufacture trusted contentRef/access values.
            if (resolved.isEmpty()) return;
            if (contextRefs.size() != resolved.size())
                throw new IllegalArgumentException("Resolved context references do not match raw references");
            JSONArray rows = manifestRows(manifestJson, contextRefs.size());
            for (int index = 0; index < contextRefs.size(); index++) {
                ResolvedContextRef value = resolved.get(index);
                JSONObject row = rows.getJSONObject(index);
                if (value == null || !Objects.equals(contextRefs.get(index), value.contextRef())
                        || row == null
                        || !Objects.equals(value.contextRef().getKind(), row.getString("kind"))
                        || !Objects.equals(canonicalRef(value.contextRef()), row.getJSONObject("canonicalRef"))
                        || !Objects.equals(value.contentRef(), row.getString("contentRef"))
                        || !Objects.equals(value.access(), row.getString("access"))
                        || value.contentRef() == null || value.contentRef().isBlank()
                        || value.access() == null || value.access().isBlank())
                    throw new IllegalArgumentException("Resolved context reference is incomplete");
            }
        }

        private static JSONArray manifestRows(String manifestJson, int expectedSize) {
            try {
                JSONObject manifest = JSON.parseObject(manifestJson);
                JSONArray rows = manifest == null ? null : manifest.getJSONArray("refs");
                if (rows == null || rows.size() != expectedSize)
                    throw new IllegalArgumentException(
                            "Resolved manifest references do not match context references");
                return rows;
            } catch (IllegalArgumentException invalid) {
                throw invalid;
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("Resolved manifest is invalid", invalid);
            }
        }

    }
}
