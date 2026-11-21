package com.aid.runtime.assistant;

import cn.hutool.crypto.SecureUtil;
import com.aid.runtime.domain.AidRuntimePlan;
import com.aid.runtime.domain.AidRuntimeCommand;
import com.aid.runtime.domain.AidRuntimeEvent;
import com.aid.assistant.domain.AidAssistantMessage;
import com.aid.runtime.domain.AidRuntimeRun;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/** A confirmed user replan retires old requirements, never converts their execution into success. */
public final class AssistantReadOnlyReplanCompletionPolicy {
    private AssistantReadOnlyReplanCompletionPolicy() { }

    public static boolean permitsReply(AidRuntimeRun run, AidRuntimePlan current,
            List<AidRuntimePlan> ancestors, AidRuntimeEvent event, AidRuntimeCommand command,
            AidAssistantMessage message,
            Predicate<JSONObject> readOnlyPlan) {
        if (run == null || current == null || event == null || command == null || message == null
                || readOnlyPlan == null || run.getId() == null || run.getUserId() == null
                || run.getSessionId() == null || run.getVersionId() == null || run.getContextDigest() == null
                || current.getRevision() == null || current.getRevision() < 1
                || current.getProtocolVersion() == null || current.getProtocolVersion().isBlank()
                || !Objects.equals(run.getId(), event.getRunId())
                || !Objects.equals(run.getSessionId(), event.getSessionId())
                || !"run.replanning_required".equals(event.getEventType())
                || !Objects.equals(run.getUserId(), command.getActorId())
                || !Objects.equals(run.getSessionId(), command.getSessionId())
                || !"MESSAGE_INTERJECTION".equals(command.getCommandType())
                || !"COMPLETED".equals(command.getStatus()) || !"0".equals(command.getDelFlag())
                || event.getCausationId() == null
                || !event.getCausationId().equals(command.getClientRequestId())
                || !event.getCausationId().equals(message.getClientMessageId())
                || !Objects.equals(run.getId(), message.getRuntimeRunId())
                || !Objects.equals(run.getSessionId(), message.getSessionId())
                || !"USER_INTERJECTION".equals(message.getMessageKind())
                || !"USER".equals(message.getRole()) || !"PERSISTED".equals(message.getStatus())
                || !"0".equals(message.getDelFlag()) || command.getPayloadDigest() == null
                || !command.getPayloadDigest().equals(message.getRequestHash())
                || event.getCreateTime() == null || current.getCreateTime() == null
                || event.getCreateTime().after(current.getCreateTime())
                || !Objects.equals(run.getPlanRevision(), current.getRevision())
                || !Objects.equals(run.getContextDigest(), current.getInputContextDigest())) return false;
        JSONObject boundary, receipt;
        try {
            boundary = JSON.parseObject(event.getPayloadJson());
            receipt = JSON.parseObject(command.getResultJson());
        } catch (RuntimeException malformed) { return false; }
        if (boundary == null || receipt == null || boundary.getIntValue("replanBoundaryVersion") != 1
                || !Objects.equals(run.getId(), boundary.getLong("runId"))
                || !Objects.equals(run.getId(), receipt.getLong("runId"))
                || !Objects.equals(message.getId(), receipt.getLong("messageId"))
                || !"REPLANNING_REQUIRED".equals(boundary.getString("nextAction"))
                || !"REPLANNING_REQUIRED".equals(receipt.getString("nextAction"))) return false;
        long retiredRevision = boundary.getLongValue("retiredPlanRevision");
        if (retiredRevision < 1 || retiredRevision >= current.getRevision()) return false;
        Map<Long, AidRuntimePlan> byRevision = new HashMap<>();
        byRevision.put(current.getRevision(), current);
        if (ancestors == null || ancestors.size() > 1000) return false;
        for (AidRuntimePlan row : ancestors)
            if (row == null || row.getRevision() == null
                    || byRevision.putIfAbsent(row.getRevision(), row) != null) return false;
        long revision = current.getRevision();
        while (revision >= retiredRevision) {
            AidRuntimePlan row = byRevision.get(revision);
            if (row == null || !Objects.equals(row.getRunId(), run.getId())
                    || !Objects.equals(row.getAgentVersionId(), run.getVersionId())
                    || !Objects.equals(row.getProtocolVersion(), current.getProtocolVersion())
                    || !(revision == current.getRevision() ? "APPROVED" : "SUPERSEDED").equals(row.getStatus())
                    || row.getDefinitionJson() == null
                    || !Objects.equals(row.getDefinitionDigest(), SecureUtil.sha256(row.getDefinitionJson()))) return false;
            JSONObject definition;
            try { definition = JSON.parseObject(row.getDefinitionJson()); }
            catch (RuntimeException malformed) { return false; }
            long parent = row.getParentRevision() == null ? 0 : row.getParentRevision();
            if (definition == null || parent < 0 || parent >= revision
                    || definition.getLongValue("parentRevision") != parent) return false;
            if (revision == retiredRevision)
                return Objects.equals(row.getInputContextDigest(), boundary.getString("retiredContextDigest"));
            if (!readOnlyPlan.test(definition)) return false;
            revision = parent;
        }
        return false;
    }
}
