package com.aid.assistant.story.tool;

import cn.hutool.crypto.SecureUtil;
import com.aid.assistant.story.service.StoryWorkspaceService;
import com.aid.assistant.story.service.impl.StoryWorkspaceServiceImpl;
import com.aid.runtime.service.RuntimeToolRegistry;
import com.aid.runtime.tool.RuntimeToolAvailability;
import com.aid.runtime.tool.RuntimeToolAvailabilityContext;
import com.aid.runtime.tool.RuntimeToolInvocation;
import com.aid.runtime.tool.RuntimeToolPreparedInvocation;
import com.aid.runtime.tool.RuntimeToolProvider;
import com.aid.runtime.tool.RuntimeToolResult;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/** Explicit state query; the original inspection identity and frozen schema stay unchanged. */
@Component
public class StoryWorkspaceStateToolProvider implements RuntimeToolProvider {
    public static final String CODE = "canvas.story.project.inspect-state";
    public static final String VERSION = "1";
    private static final String INPUT = "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}";
    private static final String OUTPUT = """
            {"anyOf":[
              {"type":"object","required":["initialized","workspace"],"properties":{
                "initialized":{"type":"boolean","const":false},"workspace":{"type":"null"}
              },"additionalProperties":false},
              {"type":"object","required":["initialized","workspace"],"properties":{
                "initialized":{"type":"boolean","const":true},"workspace":%s
              },"additionalProperties":false}
            ]}
            """.formatted(StoryWorkspaceRuntimeToolProvider.WORKSPACE_OUTPUT);
    private static final RuntimeToolRegistry.Descriptor DESCRIPTOR = new RuntimeToolRegistry.Descriptor(
            CODE, VERSION, SecureUtil.sha256(INPUT + "\n" + OUTPUT), "Inspect screenplay initialization state",
            "Read the current authorized project's state without initializing or writing it. "
                    + "initialized=false with workspace=null is a successful authoritative absence result, "
                    + "not a permission error or an initialization receipt. "
                    + "initialized=true returns the existing workspace and immutable file receipts. "
                    + "A new conversation or node does not itself create a different project scope.",
            INPUT, OUTPUT, "READ", false, "REQUEST_DIGEST", false, "REPLAY_SAFE", List.of());

    private final StoryWorkspaceService service;
    private final StoryWorkspaceRuntimeToolProvider original;

    public StoryWorkspaceStateToolProvider(StoryWorkspaceService service, StoryWorkspaceRuntimeToolProvider original) {
        this.service = Objects.requireNonNull(service);
        this.original = Objects.requireNonNull(original);
    }

    @Override
    public List<RuntimeToolRegistry.Descriptor> descriptors() {
        return List.of(DESCRIPTOR);
    }

    @Override
    public OutputReferenceKind planningOutputReferenceKind(RuntimeToolRegistry.Descriptor descriptor) {
        return OutputReferenceKind.OBSERVATION;
    }

    @Override
    public RuntimeToolAvailability availability(RuntimeToolAvailabilityContext context,
                                                RuntimeToolRegistry.Descriptor descriptor) {
        return original.availability(context, descriptor);
    }

    @Override
    public RuntimeToolResult execute(RuntimeToolInvocation invocation) {
        JSONObject state = new JSONObject();
        try {
            // inspect validates runtime ownership and the pinned/live scope before reporting absence.
            state.put("workspace", Objects.requireNonNull(service.inspect(invocation),
                    "Screenplay inspection returned no state"));
            state.put("initialized", true);
        } catch (StoryWorkspaceServiceImpl.WorkspaceNotInitializedException absent) {
            state.put("workspace", null);
            state.put("initialized", false);
        }
        RuntimeToolResult result = RuntimeToolResult.succeeded(
                JSON.toJSONString(state, JSONWriter.Feature.WriteNulls));
        result.setSafeSummary(state.getBooleanValue("initialized")
                ? "已读取现有故事工程状态" : "已确认当前故事工程尚未初始化");
        return result;
    }

    @Override
    public RuntimeToolResult reconcilePrepared(RuntimeToolPreparedInvocation prepared) {
        if (prepared == null || !DESCRIPTOR.equals(prepared.getDescriptor())) {
            return RuntimeToolResult.unknown("STORY_STATE_READ_IDENTITY_INVALID", "故事状态读取身份无法核验");
        }
        return RuntimeToolProvider.super.executePrepared(prepared, null);
    }
}
