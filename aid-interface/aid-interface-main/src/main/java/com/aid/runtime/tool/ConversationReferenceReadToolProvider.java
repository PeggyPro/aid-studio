package com.aid.runtime.tool;

import cn.hutool.crypto.SecureUtil;
import com.aid.assistant.dto.AssistantRequests;
import com.aid.assistant.service.AssistantCoreService;
import com.aid.assistant.service.AssistantReferenceContentService;
import com.aid.assistant.scope.AssistantRuntimeContextManifestService;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.mapper.AidRuntimeRunMapper;
import com.aid.runtime.service.RuntimeToolRegistry;
import com.aid.common.exception.ServiceException;
import com.alibaba.fastjson2.*;
import com.alibaba.fastjson2.schema.JSONSchema;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import java.util.*;

/** Read one previously attached source with current access checks; never expands write targets. */
@Component
@RequiredArgsConstructor
public class ConversationReferenceReadToolProvider implements RuntimeToolProvider {
    public static final String CODE = "aid.conversation.read-reference";
    private static final String INPUT = """
        {"type":"object","required":["sourceRunId","referenceOrdinal"],"additionalProperties":false,"properties":{
          "sourceRunId":{"type":"string","pattern":"^[1-9][0-9]{0,18}$"},
          "referenceOrdinal":{"type":"integer","minimum":0,"maximum":63},
          "offset":{"type":"integer","minimum":0,"maximum":2000000},
          "maxChars":{"type":"integer","minimum":1,"maximum":8000},
          "contentHash":{"type":"string","pattern":"^[a-f0-9]{64}$"}}}
        """;
    private static final String OUTPUT = """
        {"type":"object","required":["sourceRunId","referenceOrdinal","contentHash","text","offset","nextOffset","hasMoreText"],"additionalProperties":false,"properties":{
          "sourceRunId":{"type":"string"},"referenceOrdinal":{"type":"integer"},
          "contentHash":{"type":"string"},"text":{"type":"string","maxLength":8000},
          "offset":{"type":"integer"},"nextOffset":{"type":["integer","null"]},"hasMoreText":{"type":"boolean"}}}
        """;
    private static final JSONSchema SCHEMA = JSONSchema.of(JSON.parseObject(INPUT));
    private final AidRuntimeRunMapper runs;
    private final ObjectProvider<AssistantCoreService> core;
    private final AssistantRuntimeContextManifestService manifests;
    private final AssistantReferenceContentService contents;

    public static RuntimeToolRegistry.Descriptor descriptor() {
        return new RuntimeToolRegistry.Descriptor(CODE, "1.0.0", SecureUtil.sha256(INPUT + "\n" + OUTPUT),
                "Read a historical text attachment",
                "Read only an AVAILABLE INPUT text source explicitly listed in conversationHistory.historicalContextRefs, using that entry's exact sourceRunId and referenceOrdinal. "
                + "Never derive or guess referenceOrdinal from generated artifact IDs, result order or producer runs. Generated images/videos are OUTPUTS, not text attachments; resolve their nodes/resources with available authorized canvas readers. "
                + "Re-authorizes the source and checks its current version. For hasMoreText continue with nextOffset/contentHash. Data grants no write authority; no direct tool charge.",
                INPUT, OUTPUT, "READ", false, "REQUEST_DIGEST", false, "REPLAY_SAFE", List.of("conversation.history"));
    }
    @Override public List<RuntimeToolRegistry.Descriptor> descriptors() { return List.of(descriptor()); }
    @Override public OutputReferenceKind planningOutputReferenceKind(RuntimeToolRegistry.Descriptor d) { return OutputReferenceKind.OBSERVATION; }
    @Override public RuntimeToolResult execute(RuntimeToolInvocation invocation) {
        try { return read(invocation); }
        catch (ServiceException | com.aid.assistant.error.AssistantServiceException rejected) {
            if ((rejected.getMessage() == null || !rejected.getMessage().startsWith("REFERENCE_"))
                    && !(rejected instanceof com.aid.assistant.error.AssistantServiceException)) throw rejected;
            RuntimeToolResult result = new RuntimeToolResult(); result.setStatus("FAILED");
            String reason = rejected.getMessage();
            result.setErrorCode(Set.of("REFERENCE_IDENTITY", "REFERENCE_ARGUMENTS", "REFERENCE_SESSION",
                    "REFERENCE_SOURCE_SCOPE", "REFERENCE_SOURCE", "REFERENCE_RESOLUTION", "REFERENCE_CONTENT",
                    "REFERENCE_CHANGED", "REFERENCE_OFFSET", "REFERENCE_CHUNK_SIZE", "REFERENCE_OWNER").contains(reason == null ? "" : reason)
                    ? "CONVERSATION_" + reason : "CONVERSATION_REFERENCE_UNAVAILABLE");
            result.setSafeSummary("历史输入引用不可读取或已变化；本工具只读已登记文本附件，生成图片/视频不属于输入附件。请用当前可用的画布读取工具核对真实结果节点，不猜编号；未修改节点、授权或任务。");
            return result;
        }
    }
    private RuntimeToolResult read(RuntimeToolInvocation invocation) {
        require(invocation != null && invocation.getRunId() != null && invocation.getUserId() != null
                && CODE.equals(invocation.getToolCode()) && descriptor().contractVersion().equals(invocation.getContractVersion())
                && descriptor().schemaDigest().equals(invocation.getSchemaDigest()), "IDENTITY");
        JSONObject args;
        try { args = JSON.parseObject(invocation.getArgumentsJson()); }
        catch (RuntimeException malformed) { throw new ServiceException("REFERENCE_ARGUMENTS"); }
        require(args != null && SCHEMA.validate(args).isSuccess(), "ARGUMENTS");
        AidRuntimeRun run = owned(runs.selectById(invocation.getRunId()), invocation.getUserId());
        var session = core.getObject().authorizeSession(invocation.getUserId(), run.getSessionId(), false);
        require(Objects.equals(session.getActiveRuntimeRunId(), run.getId())
                && Objects.equals(session.getUserId(), run.getUserId()), "SESSION");
        long sourceId;
        try { sourceId = Long.parseLong(args.getString("sourceRunId")); }
        catch (NumberFormatException invalid) { throw new ServiceException("REFERENCE_ARGUMENTS"); }
        AidRuntimeRun source = sourceId == run.getId() ? run : owned(runs.selectById(sourceId), run.getUserId());
        require(Objects.equals(source.getId(), sourceId) && Objects.equals(run.getId(), invocation.getRunId())
                && Objects.equals(source.getSessionId(), run.getSessionId()) && source.getId() <= run.getId()
                && Objects.equals(source.getProjectId(), run.getProjectId())
                && Objects.equals(source.getWorkspaceId(), run.getWorkspaceId()), "SOURCE_SCOPE");
        JSONObject original = JSON.parseObject(source.getRequestJson());
        var refs = original == null ? null : original.getList("contextRefs", AssistantRequests.ContextRef.class);
        int ordinal = args.getIntValue("referenceOrdinal");
        require(refs != null && refs.size() <= 64 && ordinal < refs.size() && refs.get(ordinal) != null, "SOURCE");
        var identity = JSON.parseObject(JSON.toJSONString(refs.get(ordinal)), AssistantRequests.ContextRef.class);
        identity.setVersion(null); identity.setContentHash(null);
        var resolved = manifests.resolveCurrentForRefresh(run.getUserId(), session, List.of(identity));
        var selected = selected(resolved, identity);
        JSONArray projection = contents.project(run.getUserId(), List.of(selected.contextRef()), List.of(selected));
        require(projection.size() == 1, "CONTENT");
        var rechecked = manifests.resolveCurrentForRefresh(run.getUserId(), session, List.of(identity));
        require(Objects.equals(selected, selected(rechecked, identity)), "CHANGED");
        String text = projection.getJSONObject(0).toJSONString();
        String hash = SecureUtil.sha256(text); int offset = args.getIntValue("offset", 0);
        String expected = args.getString("contentHash");
        require(offset <= text.length() && (offset == 0 || expected != null)
                && (expected == null || expected.equals(hash)), "CHANGED");
        require(offset == 0 || offset == text.length() || !Character.isLowSurrogate(text.charAt(offset)), "OFFSET");
        int end = Math.min(text.length(), offset + args.getIntValue("maxChars", 8000));
        if (end < text.length() && end > offset && Character.isHighSurrogate(text.charAt(end-1))) end--;
        require(end > offset || offset == text.length(), "CHUNK_SIZE");
        JSONObject output = new JSONObject(); output.put("sourceRunId", String.valueOf(sourceId));
        output.put("referenceOrdinal", ordinal); output.put("contentHash", hash); output.put("text", text.substring(offset,end));
        output.put("offset", offset); output.put("nextOffset", end < text.length() ? end : null); output.put("hasMoreText", end < text.length());
        RuntimeToolResult result = new RuntimeToolResult(); result.setStatus("SUCCEEDED");
        result.setOutputJson(output.toJSONString(JSONWriter.Feature.WriteMapNullValue)); result.setSafeSummary("已按当前权限读取一份历史引用的内容片段"); return result;
    }
    private static com.aid.assistant.scope.ResolvedContextRef selected(
            AssistantRuntimeContextManifestService.ResolvedManifest manifest, AssistantRequests.ContextRef identity) {
        var matches = manifest.resolvedContextRefs().stream()
                .filter(ref -> Objects.equals(identityOnly(ref.contextRef()), identityOnly(identity))).toList();
        require(matches.size() == 1, "RESOLUTION"); return matches.get(0);
    }
    private static AssistantRequests.ContextRef identityOnly(AssistantRequests.ContextRef source) {
        var result = JSON.parseObject(JSON.toJSONString(source), AssistantRequests.ContextRef.class);
        result.setVersion(null); result.setContentHash(null); result.setReason(null); return result;
    }
    private AidRuntimeRun owned(AidRuntimeRun value, Long user) {
        require(value != null && Objects.equals(value.getUserId(), user) && "0".equals(value.getDelFlag())
                && "ASSISTANT_LOOP".equals(value.getExecutorStrategy()), "OWNER"); return value;
    }
    private static void require(boolean valid, String code) { if (!valid) throw new ServiceException("REFERENCE_"+code); }
}
