package com.aid.assistant.service;

import com.aid.assistant.creative.series.FlowScreenplaySourcePolicy;
import com.aid.assistant.domain.AidAssistantSession;
import com.aid.runtime.assistant.SpatialPrevisGuidancePolicy;
import com.aid.runtime.domain.AidRuntimeRun;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/** Marks turns whose message submission authorizes bounded text execution on the server. */
public final class AssistantAutomaticTextExecution {
    public static final String FIELD = "_automaticTextExecution";
    public static final String VERSION = "server-v1";
    public static final String WALLET_APPROVAL_MODE = "WALLET_TEXT";
    public static final List<String> ACTIONS = List.of(
            "INITIAL_PLANNING_REQUIRED", "REPLANNING_REQUIRED", "CONTINUE_AGENT_REQUIRED");

    private AssistantAutomaticTextExecution() { }

    public static boolean supports(AidAssistantSession session) {
        if (session == null) return false;
        if (supportsSpatialGuidance(session)) return true;
        String transport = AssistantEntryTransport.of(session);
        if ("CANVAS".equals(transport)) {
            return session.getWorkspaceId() != null && session.getWorkspaceId() > 0;
        }
        return supportsFlow(session);
    }

    public static boolean supportsFlow(AidAssistantSession session) {
        return session != null
                && FlowScreenplaySourcePolicy.supportsEntry(session.getModuleCode(), AssistantEntryTransport.of(session))
                && ("CRAFT".equals(session.getMode()) || "ADAPT".equals(session.getMode()))
                && session.getProjectId() != null && session.getProjectId() > 0
                && session.getEpisodeId() != null && session.getEpisodeId() >= 0;
    }

    public static boolean supportsSpatialGuidance(AidAssistantSession session) {
        return session != null && SpatialPrevisGuidancePolicy.appliesToModule(session.getModuleCode());
    }

    public static boolean enabled(JSONObject request) {
        return request != null && VERSION.equals(request.getString(FIELD));
    }

    /** Only registered unbilled reads and one explicitly selected text Skill may run automatically. */
    public static boolean authorizesTextPlan(JSONObject request, JSONObject plan, Predicate<String> unbilledRead) {
        if (!enabled(request) || plan == null || !(plan.get("steps") instanceof JSONArray steps) || steps.isEmpty())
            return false;
        JSONObject selected = request.get("_selectedSkill") instanceof JSONObject value ? value : null;
        boolean hasSkill = false;
        for (Object entry : steps) {
            if (!(entry instanceof JSONObject step)) return false;
            String code = step.getString("bindingCode");
            if (code != null && "TOOL".equals(step.getString("executorKind"))
                    && "READ_ASSISTANT_CONTEXT".equals(step.getString("operationKind"))
                    && "READ_ASSISTANT_CONTEXT".equals(step.getString("approvalScope"))
                    && unbilledRead.test(code)) continue;
            if (hasSkill || !matchesSelectedTextSkill(selected, step)) return false;
            hasSkill = true;
        }
        return true;
    }

    private static boolean matchesSelectedTextSkill(JSONObject selected, JSONObject step) {
        if (selected == null || !"TEXT".equals(selected.getString("outputKind"))
                || !"SKILL".equals(selected.getString("executionRoute"))) return false;
        String code = selected.getString("code");
        String version = selected.getString("versionId");
        return code != null && !code.isBlank() && version != null && !version.isBlank()
                && "SKILL".equals(step.getString("executorKind"))
                && "SKILL_INVOKE".equals(step.getString("operationKind"))
                && "SKILL_INVOKE".equals(step.getString("approvalScope"))
                && Objects.equals(code, step.getString("bindingCode"))
                && Objects.equals(version, step.getString("bindingVersion"));
    }

    public static boolean isDue(AidRuntimeRun run) {
        return isPending(run)
                && (run.getNextRetryAt() == null || !run.getNextRetryAt().after(new java.util.Date()));
    }

    /** Eligible future retries still need a wakeup; the fenced claim enforces the due time. */
    public static boolean isPending(AidRuntimeRun run) {
        return run != null && "WAITING_APPROVAL".equals(run.getStatus())
                && run.getNextAction() != null && ACTIONS.contains(run.getNextAction())
                && !Boolean.TRUE.equals(run.getStopRequested())
                && enabled(JSON.parseObject(run.getRequestJson()));
    }
}
