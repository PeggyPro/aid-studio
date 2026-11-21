package com.aid.assistant.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.Objects;
import java.util.Set;

/** Exact reference fence with one exception: the implicit Canvas workspace baseline may advance. */
public final class AssistantReferenceContextFence {
    private AssistantReferenceContextFence() { }

    public static boolean matchesPinnedManifest(String approvedManifestJson, String currentManifestJson) {
        try {
            JSONObject approved = JSON.parseObject(approvedManifestJson);
            JSONObject current = JSON.parseObject(currentManifestJson);
            if (approved == null || current == null
                    || !Objects.equals(approved.getString("schemaVersion"), current.getString("schemaVersion"))
                    || !Objects.equals(approved.getJSONObject("sessionScope"), current.getJSONObject("sessionScope"))) {
                return false;
            }
            JSONArray approvedRefs = approved.getJSONArray("refs");
            JSONArray currentRefs = current.getJSONArray("refs");
            if (approvedRefs == null || currentRefs == null || approvedRefs.size() != currentRefs.size()) {
                return false;
            }
            for (int index = 0; index < approvedRefs.size(); index++) {
                if (!matchesPinnedRow(approvedRefs.getJSONObject(index), currentRefs.getJSONObject(index))) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    public static boolean matchesExpectedRevisions(JSONArray observed, String currentManifestJson) {
        try {
            JSONObject current = JSON.parseObject(currentManifestJson);
            JSONArray currentRefs = current == null ? null : current.getJSONArray("refs");
            if (observed == null || currentRefs == null || observed.size() != currentRefs.size()) return false;
            for (int index = 0; index < observed.size(); index++) {
                JSONObject expected = observed.getJSONObject(index);
                JSONObject currentRow = currentRefs.getJSONObject(index);
                if (expected == null || currentRow == null
                        || !Set.of("kind", "canonicalRef").equals(expected.keySet())) return false;
                JSONObject actual = new JSONObject();
                actual.put("kind", currentRow.getString("kind"));
                actual.put("canonicalRef", currentRow.getJSONObject("canonicalRef"));
                if (!isImplicitWorkspace(expected)) {
                    if (!Objects.equals(expected, actual)) return false;
                    continue;
                }
                JSONObject expectedCanonical = expected.getJSONObject("canonicalRef");
                JSONObject actualCanonical = actual.getJSONObject("canonicalRef");
                if (!isImplicitWorkspace(actual)
                        || !workspaceAdvanced(expectedCanonical, actualCanonical)) return false;
                JSONObject normalized = JSON.parseObject(actual.toJSONString());
                normalized.getJSONObject("canonicalRef")
                        .put("version", expectedCanonical.getString("version"));
                if (!Objects.equals(expected, normalized)) return false;
            }
            return true;
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    static boolean matchesPinnedRow(JSONObject expected, JSONObject actual) {
        if (expected == null || actual == null) return false;
        if (!isImplicitWorkspace(expected)) return Objects.equals(expected, actual);
        JSONObject expectedCanonical = expected.getJSONObject("canonicalRef");
        JSONObject actualCanonical = actual.getJSONObject("canonicalRef");
        if (!isImplicitWorkspace(actual)
                || !Objects.equals(expectedCanonical.getString("projectId"), actualCanonical.getString("projectId"))
                || !Objects.equals(expectedCanonical.getString("workspaceId"), actualCanonical.getString("workspaceId"))
                || !Objects.equals(expected.getString("access"), actual.getString("access"))
                || !workspaceAdvanced(expectedCanonical, actualCanonical)) {
            return false;
        }
        JSONObject normalized = JSON.parseObject(actual.toJSONString());
        JSONObject normalizedCanonical = JSON.parseObject(actualCanonical.toJSONString());
        normalizedCanonical.put("version", expectedCanonical.getString("version"));
        normalized.put("canonicalRef", normalizedCanonical);
        normalized.put("contentRef", expected.getString("contentRef"));
        return Objects.equals(expected, normalized);
    }

    static boolean isImplicitWorkspace(JSONObject row) {
        JSONObject canonical = row == null ? null : row.getJSONObject("canonicalRef");
        return "FREE_CANVAS".equals(row == null ? null : row.getString("kind"))
                && canonical != null && canonical.getString("objectId") == null;
    }

    private static boolean workspaceAdvanced(JSONObject expectedCanonical, JSONObject actualCanonical) {
        if (expectedCanonical == null || actualCanonical == null) return false;
        String expected = expectedCanonical.getString("version");
        String actual = actualCanonical.getString("version");
        if (expected == null || actual == null
                || !expected.matches("0|[1-9][0-9]{0,18}")
                || !actual.matches("0|[1-9][0-9]{0,18}")) return false;
        try {
            return Long.parseLong(actual) >= Long.parseLong(expected);
        } catch (NumberFormatException invalid) {
            return false;
        }
    }
}
