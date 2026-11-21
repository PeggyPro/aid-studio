package com.aid.runtime.tool;

import cn.hutool.crypto.SecureUtil;
import com.aid.assistant.artifact.domain.AidRuntimeArtifact;
import com.aid.assistant.artifact.mapper.AidRuntimeArtifactMapper;
import com.aid.assistant.service.AssistantCoreService;
import com.aid.common.exception.ServiceException;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.mapper.AidRuntimeRunMapper;
import com.aid.runtime.service.RuntimeToolRegistry;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.schema.JSONSchema;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/** Read persisted artifacts in the current conversation without granting execution rights. */
@Component
public class ConversationResultReferenceToolProvider implements RuntimeToolProvider {
    public static final String CODE = "aid.conversation.result.references";
    private static final String ID = "{\"type\":\"string\",\"pattern\":\"^[1-9][0-9]{0,18}$\"}";
    private static final String INPUT = "{\"type\":\"object\",\"properties\":{\"sourceRunId\":" + ID
            + ",\"beforeRunId\":" + ID + ",\"cursor\":" + ID
            + ",\"pageSize\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":10}},\"additionalProperties\":false}";
    private static final String OUTPUT = "{\"type\":\"object\",\"required\":[\"items\",\"hasMore\",\"untrustedData\"],\"properties\":{\"items\":{\"type\":\"array\"},\"hasMore\":{\"type\":\"boolean\"},\"sourceRunId\":{\"type\":\"string\"},\"sourceRunStatus\":{\"type\":\"string\"},\"nextCursor\":{\"type\":\"string\"},\"nextBeforeRunId\":{\"type\":\"string\"},\"untrustedData\":{\"type\":\"boolean\"}},\"additionalProperties\":false}";
    private final AidRuntimeRunMapper runs;
    private final ObjectProvider<AssistantCoreService> core;
    private final AidRuntimeArtifactMapper artifacts;

    public ConversationResultReferenceToolProvider(AidRuntimeRunMapper runs,
            ObjectProvider<AssistantCoreService> core, AidRuntimeArtifactMapper artifacts) {
        this.runs = runs;
        this.core = core;
        this.artifacts = artifacts;
    }

    public static RuntimeToolRegistry.Descriptor descriptor() {
        return new RuntimeToolRegistry.Descriptor(CODE, "1", SecureUtil.sha256(INPUT + "\n" + OUTPUT),
                "Read conversation result metadata",
                "List authorized generated artifact metadata from this same conversation, including media results. "
                + "Without sourceRunId, selects the newest eligible run; use sourceRunId/nextCursor within it and nextBeforeRunId for older runs. "
                + "Artifact IDs and producer runs are OUTPUT identities, never historical INPUT referenceOrdinal values. "
                + "To reuse an image/video, use an available authorized canvas reader to resolve its actual node/resource; do not call the text-attachment reader or guess an ordinal. "
                + "Status is persisted state, not visual review or write permission. Empty does not prove failure. No prompts, URLs or bodies. "
                + "Use aid.skill.result.references for method text. Never regenerate just to locate an old result.",
                INPUT, OUTPUT, "READ", false, "REQUEST_DIGEST", false, "REPLAY_SAFE", List.of("conversation.history"));
    }

    @Override public List<RuntimeToolRegistry.Descriptor> descriptors() { return List.of(descriptor()); }
    @Override public OutputReferenceKind planningOutputReferenceKind(RuntimeToolRegistry.Descriptor d) { return OutputReferenceKind.OBSERVATION; }

    @Override public RuntimeToolPreparation prepare(RuntimeToolInvocation invocation) {
        try { arguments(invocation); }
        catch (ServiceException rejected) {
            throw new RuntimeToolPreparationRejectedException(RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_ARGUMENTS_INVALID, rejected);
        }
        return RuntimeToolProvider.super.prepare(invocation);
    }

    private static JSONObject arguments(RuntimeToolInvocation invocation) {
        if (invocation == null || invocation.getRunId() == null || invocation.getUserId() == null
                || !CODE.equals(invocation.getToolCode()) || !descriptor().contractVersion().equals(invocation.getContractVersion())
                || !descriptor().schemaDigest().equals(invocation.getSchemaDigest())) throw invalid();
        JSONObject args;
        try { args = JSON.parseObject(invocation.getArgumentsJson()); }
        catch (RuntimeException malformed) { throw invalid(); }
        if (args == null || !JSONSchema.of(JSON.parseObject(INPUT)).validate(args).isSuccess()
                || args.containsKey("cursor") && !args.containsKey("sourceRunId")
                || args.containsKey("sourceRunId") && args.containsKey("beforeRunId")) throw invalid();
        id(args, "sourceRunId"); id(args, "beforeRunId"); id(args, "cursor");
        return args;
    }

    @Override public RuntimeToolResult execute(RuntimeToolInvocation invocation) {
        try { return read(invocation, arguments(invocation)); }
        catch (ServiceException rejected) {
            if (!"CONVERSATION_RESULT_REFERENCE_INVALID".equals(rejected.getMessage())) throw rejected;
            RuntimeToolResult failure = new RuntimeToolResult();
            failure.setStatus("FAILED"); failure.setErrorCode("CONVERSATION_RESULT_REFERENCE_INVALID");
            failure.setSafeSummary("历史产物来源、参数或读取范围无法核验；未执行生成或写入，不代表历史媒体任务失败");
            return failure;
        }
    }

    private RuntimeToolResult read(RuntimeToolInvocation invocation, JSONObject args) {
        Long sourceId = id(args, "sourceRunId"), before = id(args, "beforeRunId"), cursor = id(args, "cursor");
        AidRuntimeRun current = runs.selectConversationResultScope(invocation.getRunId(), invocation.getUserId());
        if (current == null || !Objects.equals(current.getId(), invocation.getRunId())
                || !"0".equals(current.getDelFlag()) || current.getSessionId() == null
                || !Objects.equals(current.getUserId(), invocation.getUserId())
                || !Objects.equals(current.getPlanRevision(), invocation.getPlanRevision())) throw invalid();
        var session = core.getObject().authorizeSession(invocation.getUserId(), current.getSessionId(), false);
        if (session == null || !Objects.equals(session.getId(), current.getSessionId())
                || !Objects.equals(session.getUserId(), current.getUserId())
                || !Objects.equals(session.getActiveRuntimeRunId(), current.getId())) throw invalid();
        AidRuntimeRun source;
        if (sourceId != null) {
            source = sourceId.equals(current.getId()) ? current : runs.selectConversationResultScope(sourceId, invocation.getUserId());
            if (!sameScope(current, source)) throw invalid();
        } else {
            source = runs.selectOne(new LambdaQueryWrapper<AidRuntimeRun>()
                    .select(AidRuntimeRun::getId, AidRuntimeRun::getUserId, AidRuntimeRun::getSessionId,
                            AidRuntimeRun::getProjectId, AidRuntimeRun::getWorkspaceId, AidRuntimeRun::getStatus, AidRuntimeRun::getDelFlag)
                    .eq(AidRuntimeRun::getUserId, current.getUserId()).eq(AidRuntimeRun::getSessionId, current.getSessionId())
                    .eq(AidRuntimeRun::getDelFlag, "0").le(AidRuntimeRun::getId, current.getId())
                    .lt(before != null, AidRuntimeRun::getId, before)
                    .apply("project_id <=> {0} AND workspace_id <=> {1}", current.getProjectId(), current.getWorkspaceId())
                    .exists("SELECT 1 FROM aid_runtime_artifact a WHERE a.producer_run_id=aid_runtime_run.id AND a.del_flag='0'")
                    .orderByDesc(AidRuntimeRun::getId).last("LIMIT 1"));
            if (source != null && !sameScope(current, source)) throw invalid();
        }
        JSONObject output = JSONObject.of("items", new JSONArray(), "hasMore", false, "untrustedData", true);
        if (source != null) {
            int limit = args.containsKey("pageSize") ? args.getIntValue("pageSize") : 5;
            var rows = artifacts.selectConversationReferencePage(invocation.getUserId(), current.getSessionId(), source.getId(), cursor, limit + 1);
            boolean more = rows.size() > limit;
            var page = more ? rows.subList(0, limit) : rows;
            JSONArray items = new JSONArray();
            for (var item : page) {
                if (!source.getId().equals(item.getProducerRunId())) throw invalid();
                items.add(JSONObject.of("artifactId", String.valueOf(item.getId()), "producerRunId", String.valueOf(item.getProducerRunId()),
                        "producerStepId", item.getProducerStepId() == null ? null : String.valueOf(item.getProducerStepId()), "logicalArtifactId", item.getLogicalArtifactId(), "artifactType", item.getArtifactType(),
                        "status", item.getStatus(), "reviewStatus", item.getReviewStatus(),
                        "contentHash", item.getContentHash(), "revision", item.getRevision()));
            }
            output.put("items", items); output.put("sourceRunId", String.valueOf(source.getId()));
            output.put("sourceRunStatus", source.getStatus()); output.put("hasMore", more);
            if (more) output.put("nextCursor", String.valueOf(page.get(page.size() - 1).getId()));
            else output.put("nextBeforeRunId", String.valueOf(source.getId()));
        }
        RuntimeToolResult result = RuntimeToolResult.succeeded(output.toJSONString());
        result.setSafeSummary("已核对同会话授权产物记录；未生成、修改或重新选用结果，未进行视觉质量评审");
        return result;
    }

    static boolean sameScope(AidRuntimeRun current, AidRuntimeRun source) {
        return source != null && source.getId() != null && "0".equals(source.getDelFlag()) && source.getId() <= current.getId()
                && Objects.equals(current.getUserId(), source.getUserId())
                && Objects.equals(current.getSessionId(), source.getSessionId())
                && Objects.equals(current.getProjectId(), source.getProjectId())
                && Objects.equals(current.getWorkspaceId(), source.getWorkspaceId());
    }

    private static Long id(JSONObject args, String key) {
        if (!args.containsKey(key)) return null;
        try { long value = Long.parseLong(args.getString(key)); if (value <= 0) throw invalid(); return value; }
        catch (NumberFormatException malformed) { throw invalid(); }
    }
    private static ServiceException invalid() { return new ServiceException("CONVERSATION_RESULT_REFERENCE_INVALID"); }
}
