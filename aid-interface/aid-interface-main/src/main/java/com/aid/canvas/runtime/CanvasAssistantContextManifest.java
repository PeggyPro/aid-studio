package com.aid.canvas.runtime;

import com.aid.common.exception.ServiceException;
import com.aid.runtime.tool.RuntimeToolInvocation;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Strict reader for the coordinator-owned assistant-context-manifest/v1 projection. */
final class CanvasAssistantContextManifest {
    private static final String VERSION = "assistant-context-manifest/v1";
    private static final Set<String> ROOT_KEYS = Set.of("schemaVersion", "sessionScope", "refs");
    private static final Set<String> REF_KEYS = Set.of("kind", "canonicalRef", "contentRef", "access");
    private static final Set<String> REQUIRED_REF_KEYS = Set.of("kind", "canonicalRef", "access");
    private static final Set<String> CANVAS_KEYS = Set.of("projectId", "workspaceId", "objectId", "version");
    private static final Set<String> REQUIRED_CANVAS_KEYS = Set.of("projectId", "workspaceId");
    private static final int ABSOLUTE_MAX_REFS = 1_001;

    private CanvasAssistantContextManifest() {
    }

    static List<TargetRef> mutableTargets(RuntimeToolInvocation invocation) {
        return refs(invocation).stream().filter(TargetRef.class::isInstance).map(TargetRef.class::cast)
                .filter(ref -> "MUTATE".equals(ref.access())).toList();
    }

    static TargetRef requireTarget(RuntimeToolInvocation invocation, String objectId,
                                   String objectVersion, boolean mutate) {
        if (blank(objectId) || blank(objectVersion) || !objectId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw invalid("CANVAS_TARGET_INVALID");
        }
        long revision = positive(objectVersion, "CANVAS_REVISION_INVALID");
        List<TargetRef> matched = refs(invocation).stream()
                .filter(TargetRef.class::isInstance).map(TargetRef.class::cast)
                .filter(ref -> Objects.equals(ref.objectId(), objectId) && ref.objectVersion() == revision)
                .filter(ref -> !mutate || "MUTATE".equals(ref.access()))
                .toList();
        if (matched.size() != 1) throw invalid("CANVAS_TARGET_NOT_AUTHORIZED");
        return matched.get(0);
    }

    /** Resolve a business source against the frozen manifest, never against the latest database row. */
    static TargetRef requireObservedSource(String manifestJson, long projectId, long workspaceId,
                                           String objectId) {
        if (blank(objectId) || !objectId.matches("[A-Za-z0-9_-]{1,64}"))
            throw invalid("CANVAS_SOURCE_INVALID");
        List<TargetRef> matched = refs(manifestJson).stream()
                .filter(TargetRef.class::isInstance).map(TargetRef.class::cast)
                .filter(ref -> Objects.equals(ref.objectId(), objectId)).toList();
        if (matched.size() != 1 || matched.get(0).projectId() != projectId
                || matched.get(0).workspaceId() != workspaceId)
            throw invalid("CANVAS_SOURCE_NOT_AUTHORIZED");
        return matched.get(0);
    }

    static WorkspaceRef requireWorkspace(RuntimeToolInvocation invocation, String workspaceId, boolean mutate) {
        long selectedWorkspace = positive(workspaceId, "CANVAS_WORKSPACE_INVALID");
        List<WorkspaceRef> matched = refs(invocation).stream()
                .filter(WorkspaceRef.class::isInstance)
                .map(WorkspaceRef.class::cast)
                .filter(ref -> ref.workspaceId() == selectedWorkspace)
                .filter(ref -> !mutate || "MUTATE".equals(ref.access()))
                .toList();
        if (matched.size() != 1) throw invalid("CANVAS_WORKSPACE_NOT_AUTHORIZED");
        return matched.get(0);
    }

    static AvailableRefs availableRefs(String manifestJson, Long expectedProjectId, Long expectedWorkspaceId) {
        List<Ref> refs = refs(manifestJson);
        long readableTargets = refs.stream().filter(TargetRef.class::isInstance).count();
        long mutableTargets = refs.stream().filter(TargetRef.class::isInstance)
                .filter(ref -> "MUTATE".equals(ref.access())).count();
        boolean mutableWorkspace = refs.stream().filter(WorkspaceRef.class::isInstance)
                .anyMatch(ref -> "MUTATE".equals(ref.access()));
        boolean scopeMatches = refs.stream().allMatch(ref -> Objects.equals(ref.workspaceId(), expectedWorkspaceId)
                && (expectedProjectId == null || Objects.equals(ref.projectId(), expectedProjectId)));
        if (!scopeMatches) throw invalid("CANVAS_MANIFEST_SCOPE_MISMATCH");
        return new AvailableRefs(readableTargets, mutableTargets, mutableWorkspace);
    }

    static void requireScope(String manifestJson, Long projectId, Long workspaceId, boolean mutate) {
        List<Ref> refs = refs(manifestJson);
        if (refs.isEmpty() || refs.stream().anyMatch(ref -> !Objects.equals(ref.projectId(), projectId)
                || !Objects.equals(ref.workspaceId(), workspaceId))
                || mutate && refs.stream().noneMatch(ref -> "MUTATE".equals(ref.access()))) {
            throw invalid("CANVAS_MANIFEST_SCOPE_MISMATCH");
        }
    }

    private static List<Ref> refs(RuntimeToolInvocation invocation) {
        if (invocation == null || blank(invocation.getContextManifestJson())) {
            throw invalid("CANVAS_MANIFEST_MISSING");
        }
        return refs(invocation.getContextManifestJson());
    }

    private static List<Ref> refs(String manifestJson) {
        if (blank(manifestJson)) throw invalid("CANVAS_MANIFEST_MISSING");
        JSONObject root;
        try {
            root = JSON.parseObject(manifestJson);
        } catch (RuntimeException error) {
            throw invalid("CANVAS_MANIFEST_INVALID");
        }
        if (root == null || !ROOT_KEYS.equals(root.keySet())
                || !VERSION.equals(requiredString(root, "schemaVersion", "CANVAS_MANIFEST_INVALID"))) {
            throw invalid("CANVAS_MANIFEST_INVALID");
        }
        JSONArray values = root.getJSONArray("refs");
        if (values == null || values.isEmpty() || values.size() > ABSOLUTE_MAX_REFS) {
            throw invalid("CANVAS_MANIFEST_REFS_INVALID");
        }
        List<Ref> result = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof JSONObject ref) || !REF_KEYS.containsAll(ref.keySet())
                    || !ref.keySet().containsAll(REQUIRED_REF_KEYS)) {
                throw invalid("CANVAS_MANIFEST_REF_INVALID");
            }
            String kind = requiredString(ref, "kind", "CANVAS_MANIFEST_REF_INVALID");
            if (!"FREE_CANVAS".equals(kind)) continue;
            String access = requiredString(ref, "access", "CANVAS_ACCESS_INVALID");
            if (!Set.of("READ", "MUTATE").contains(access)) throw invalid("CANVAS_ACCESS_INVALID");
            JSONObject canonical = ref.getJSONObject("canonicalRef");
            if (canonical == null || !CANVAS_KEYS.containsAll(canonical.keySet())
                    || !canonical.keySet().containsAll(REQUIRED_CANVAS_KEYS)) {
                throw invalid("CANVAS_CANONICAL_REF_INVALID");
            }
            long projectId = positive(requiredString(canonical, "projectId", "CANVAS_PROJECT_INVALID"),
                    "CANVAS_PROJECT_INVALID");
            long workspaceId = positive(requiredString(canonical, "workspaceId", "CANVAS_WORKSPACE_INVALID"),
                    "CANVAS_WORKSPACE_INVALID");
            String objectId = optionalString(canonical, "objectId", "CANVAS_TARGET_REF_INVALID");
            String objectVersion = optionalString(canonical, "version", "CANVAS_TARGET_REF_INVALID");
            String contentRef = optionalString(ref, "contentRef", "CANVAS_CONTENT_REF_INVALID");
            boolean workspaceOnly = blank(objectId) && !blank(objectVersion)
                    && Objects.equals(contentRef, "canvas:workspace:" + workspaceId + ":" + objectVersion);
            if (workspaceOnly) {
                result.add(new WorkspaceRef(projectId, workspaceId, access,
                        workspaceRevision(objectVersion)));
                continue;
            }
            if (blank(objectId) || blank(objectVersion) || blank(contentRef)
                    || !objectId.matches("[A-Za-z0-9_-]{1,64}")) {
                throw invalid("CANVAS_TARGET_REF_INVALID");
            }
            long revision = positive(objectVersion, "CANVAS_REVISION_INVALID");
            if (!contentRef.equals("canvas:node:" + workspaceId + ":" + objectId + ":" + revision)) {
                throw invalid("CANVAS_CONTENT_REF_INVALID");
            }
            result.add(new TargetRef(projectId, workspaceId, access, objectId, revision, contentRef));
        }
        return List.copyOf(result);
    }

    private static long workspaceRevision(String value) {
        // A newly persisted workspace has revision zero; node versions and identifiers remain positive.
        if ("0".equals(value)) return 0L;
        return positive(value, "CANVAS_WORKSPACE_REVISION_INVALID");
    }

    private static long positive(String value, String code) {
        if (blank(value) || !value.matches("[1-9][0-9]{0,18}")) throw invalid(code);
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 1) throw invalid(code);
            return parsed;
        } catch (NumberFormatException error) {
            throw invalid(code);
        }
    }

    private static String requiredString(JSONObject value, String key, String code) {
        Object raw = value.get(key);
        if (!(raw instanceof String string) || string.isBlank()) throw invalid(code);
        return string;
    }

    private static String optionalString(JSONObject value, String key, String code) {
        Object raw = value.get(key);
        if (raw == null) return null;
        if (!(raw instanceof String string)) throw invalid(code);
        return string;
    }

    private static ServiceException invalid(String code) {
        ServiceException failure = new ServiceException("画布范围无效").setDetailMessage(code);
        failure.initCause(new Rejected(code));
        return failure;
    }

    /** A manifest rejection is a deterministic precondition failure, never an uncertain I/O result. */
    static final class Rejected extends RuntimeException {
        Rejected(String code) {
            super(code);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private interface Ref {
        long projectId();
        long workspaceId();
        String access();
    }

    record WorkspaceRef(long projectId, long workspaceId, String access,
                        long workspaceRevision) implements Ref {
    }

    record TargetRef(long projectId, long workspaceId, String access,
                     String objectId, long objectVersion, String contentRef) implements Ref {
    }

    record AvailableRefs(long readableTargets, long mutableTargets, boolean mutableWorkspace) {
    }
}
