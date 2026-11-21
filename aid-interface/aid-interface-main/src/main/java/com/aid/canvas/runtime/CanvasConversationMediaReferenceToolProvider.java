package com.aid.canvas.runtime;

import cn.hutool.crypto.SecureUtil;
import com.aid.assistant.artifact.service.AssistantArtifactService;
import com.aid.assistant.service.AssistantCoreService;
import com.aid.assistant.error.AssistantServiceException;
import com.aid.canvas.domain.CanvasEntities.Workspace;
import com.aid.canvas.mapper.CanvasMapper;
import com.aid.canvas.service.CanvasAccessService;
import com.aid.common.aid.oss.util.MediaUrlResolver;
import com.aid.common.exception.ServiceException;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.mapper.AidRuntimeRunMapper;
import com.aid.runtime.service.RuntimeToolRegistry;
import com.aid.runtime.tool.*;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.schema.JSONSchema;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Objects;

/** Read an archived conversation output as an existing workspace reference, without mutation. */
@Component
@Slf4j
public class CanvasConversationMediaReferenceToolProvider implements RuntimeToolProvider {
    public static final String CODE = "canvas.conversation.media_reference";
    private static final String INPUT = """
        {"type":"object","required":["sourceRunId","artifactId"],"properties":{
          "sourceRunId":{"type":"string","pattern":"^[1-9][0-9]{0,18}$"},
          "artifactId":{"type":"string","pattern":"^[1-9][0-9]{0,18}$"}},"additionalProperties":false}
        """;
    private static final String OUTPUT = """
        {"type":"object","required":["artifactId","sourceRunId","resourceId","workspaceId","projectId","mediaType","contentHash","resourceIdentityHash","untrustedData"],
         "properties":{"artifactId":{"type":"string"},"sourceRunId":{"type":"string"},"resourceId":{"type":"string"},
         "workspaceId":{"type":"string"},"projectId":{"type":"string"},"mediaType":{"type":"string"},
         "contentHash":{"type":"string"},"resourceIdentityHash":{"type":"string"},"untrustedData":{"type":"boolean"}},"additionalProperties":false}
        """;
    private final AidRuntimeRunMapper runs;
    private final ObjectProvider<AssistantCoreService> core;
    private final ObjectProvider<AssistantArtifactService> artifacts;
    private final CanvasAccessService access;
    private final CanvasMapper canvas;
    private final MediaUrlResolver urls;

    public CanvasConversationMediaReferenceToolProvider(AidRuntimeRunMapper runs,
            ObjectProvider<AssistantCoreService> core, ObjectProvider<AssistantArtifactService> artifacts,
            CanvasAccessService access, CanvasMapper canvas, MediaUrlResolver urls) {
        this.runs = runs; this.core = core; this.artifacts = artifacts;
        this.access = access; this.canvas = canvas; this.urls = urls;
    }

    public static RuntimeToolRegistry.Descriptor descriptor() {
        return new RuntimeToolRegistry.Descriptor(CODE, "1", SecureUtil.sha256(INPUT + "\n" + OUTPUT),
                "Resolve a historical generated media reference",
                "Resolve one archived output artifact from this same conversation and workspace to an existing resourceId. "
                + "Use artifactId/sourceRunId from aid.conversation.result.references; never guess an input referenceOrdinal. "
                + "Pass the returned resourceId in a media generation tool's resourceIds when the user requests reuse. "
                + "Read-only: does not generate, adopt, change nodes, grant write permission, or prove visual quality. "
                + "Unavailable resources must not be regenerated without user intent.",
                INPUT, OUTPUT, "READ", false, "REQUEST_DIGEST", false, "REPLAY_SAFE", List.of("conversation.history"));
    }
    @Override public List<RuntimeToolRegistry.Descriptor> descriptors() { return List.of(descriptor()); }
    @Override public OutputReferenceKind planningOutputReferenceKind(RuntimeToolRegistry.Descriptor d) { return OutputReferenceKind.OBSERVATION; }

    private static JSONObject arguments(RuntimeToolInvocation invocation) {
        if (invocation == null || invocation.getRunId() == null || invocation.getUserId() == null
                || !CODE.equals(invocation.getToolCode()) || !descriptor().contractVersion().equals(invocation.getContractVersion())
                || !descriptor().schemaDigest().equals(invocation.getSchemaDigest())) throw invalid();
        JSONObject args;
        try {
            args = JSON.parseObject(invocation.getArgumentsJson());
            if (args == null || !JSONSchema.of(JSON.parseObject(INPUT)).validate(args).isSuccess()) throw invalid();
            if (Long.parseLong(args.getString("sourceRunId")) <= 0 || Long.parseLong(args.getString("artifactId")) <= 0) throw invalid();
        } catch (RuntimeException malformed) { throw invalid(); }
        return args;
    }

    @Override public RuntimeToolPreparation prepare(RuntimeToolInvocation invocation) {
        try { arguments(invocation); }
        catch (ServiceException rejected) {
            throw new RuntimeToolPreparationRejectedException(RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_ARGUMENTS_INVALID, rejected);
        }
        return RuntimeToolProvider.super.prepare(invocation);
    }

    @Override public RuntimeToolResult execute(RuntimeToolInvocation invocation) {
        try { return read(invocation); }
        catch (AssistantServiceException rejected) {
            if (rejected.getHttpStatus() != 403 && rejected.getHttpStatus() != 404 && rejected.getHttpStatus() != 422) throw rejected;
            String code = rejected.getErrorCode();
            log.info("Historical media reference unavailable, status={}, code={}", rejected.getHttpStatus(),
                    code != null && code.matches("[A-Z][A-Z0-9_]{0,79}") ? code : "UNCLASSIFIED");
            return unavailable();
        }
        catch (ServiceException rejected) {
            StackTraceElement[] trace = rejected.getStackTrace();
            log.warn("Historical media reference read rejected, origin={}, caller={}",
                    trace.length > 0 ? trace[0] : null, trace.length > 1 ? trace[1] : null);
            if (!"CONVERSATION_MEDIA_REFERENCE_UNAVAILABLE".equals(rejected.getMessage())) {
                throw rejected;
            }
            return unavailable();
        }
    }

    private RuntimeToolResult read(RuntimeToolInvocation invocation) {
        JSONObject args = arguments(invocation);
        Long sourceId = Long.valueOf(args.getString("sourceRunId"));
        AidRuntimeRun current = runs.selectConversationResultScope(invocation.getRunId(), invocation.getUserId());
        if (current == null || !Objects.equals(current.getId(), invocation.getRunId())
                || !Objects.equals(current.getUserId(), invocation.getUserId()) || !"0".equals(current.getDelFlag())
                || current.getSessionId() == null || current.getWorkspaceId() == null
                || !Objects.equals(current.getPlanRevision(), invocation.getPlanRevision())) throw invalid();
        var session = core.getObject().authorizeSession(invocation.getUserId(), current.getSessionId(), false);
        if (session == null || !Objects.equals(session.getId(), current.getSessionId())
                || !Objects.equals(session.getUserId(), current.getUserId())
                || !Objects.equals(session.getActiveRuntimeRunId(), current.getId())) throw invalid();
        AidRuntimeRun source = sourceId.equals(current.getId()) ? current
                : runs.selectConversationResultScope(sourceId, invocation.getUserId());
        if (!sameScope(current, source)) throw invalid();
        Workspace workspace;
        try { workspace = access.requireWorkspace(current.getWorkspaceId(), invocation.getUserId(), "OWNER", "EDITOR", "VIEWER"); }
        catch (ServiceException denied) { throw invalid(); }
        if (workspace == null || !Objects.equals(workspace.getId(), current.getWorkspaceId()) || workspace.getProjectId() == null
                || current.getProjectId() != null && !Objects.equals(workspace.getProjectId(), current.getProjectId())) throw invalid();
        var archived = artifacts.getObject().conversationMedia(invocation.getUserId(), Long.valueOf(args.getString("artifactId")), sourceId);
        if (archived.resultId() == null) throw invalid();
        var resource = canvas.selectCanvasResourceBySource(current.getWorkspaceId(), "MEDIA_RESULT", archived.resultId());
        var media = archived.media();
        if (resource == null || resource.getId() == null || !"ACTIVE".equals(resource.getStatus())
                || !Objects.equals(resource.getWorkspaceId(), current.getWorkspaceId())
                || !Objects.equals(resource.getProjectId(), workspace.getProjectId())
                || !"MEDIA_RESULT".equals(resource.getSourceType()) || !Objects.equals(resource.getSourceId(), archived.resultId())
                || !Objects.equals(resource.getMediaType(), media.getArtifactType())
                || !Objects.equals(urls.toFullUrl(resource.getStorageUrl()), media.getResourceUrl())) throw invalid();
        var output = JSONObject.of("artifactId", media.getArtifactId(), "sourceRunId", String.valueOf(sourceId),
                "resourceId", String.valueOf(resource.getId()), "workspaceId", String.valueOf(current.getWorkspaceId()),
                "projectId", String.valueOf(workspace.getProjectId()), "mediaType", resource.getMediaType(),
                "contentHash", media.getContentHash(), "resourceIdentityHash", CanvasResourceRuntimeScopeAccess.identityHash(resource), "untrustedData", true);
        RuntimeToolResult result = RuntimeToolResult.succeeded(output.toJSONString());
        result.setSafeSummary("已核验历史媒体归档与当前工作区素材引用；未生成、写入或进行视觉评审");
        return result;
    }

    static boolean sameScope(AidRuntimeRun current, AidRuntimeRun source) {
        return source != null && source.getId() != null && source.getId() <= current.getId()
                && "0".equals(source.getDelFlag()) && Objects.equals(current.getUserId(), source.getUserId())
                && Objects.equals(current.getSessionId(), source.getSessionId())
                && Objects.equals(current.getProjectId(), source.getProjectId())
                && Objects.equals(current.getWorkspaceId(), source.getWorkspaceId());
    }
    private static ServiceException invalid() { return new ServiceException("CONVERSATION_MEDIA_REFERENCE_UNAVAILABLE"); }
    private static RuntimeToolResult unavailable() {
        RuntimeToolResult result = new RuntimeToolResult();
        result.setStatus("FAILED"); result.setErrorCode("CONVERSATION_MEDIA_REFERENCE_UNAVAILABLE");
        result.setSafeSummary("历史媒体来源或当前素材身份无法核验；未生成、修改或重新选用结果");
        return result;
    }
}
