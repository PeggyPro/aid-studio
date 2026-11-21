package com.aid.skill.executor;

import com.aid.media.dto.MediaTextGenerateRequest;
import com.aid.media.dto.MediaTaskResponse;
import com.aid.media.admission.MediaSubmissionNotEnteredException;
import com.aid.assistant.skill.AssistantSkillBillingBridge;
import com.aid.assistant.skill.AssistantSkillBudgetIdentity;
import com.aid.common.exception.ServiceException;
import com.aid.media.service.IMediaGenerationService;
import com.aid.media.service.MediaTextStreamSink;
import com.aid.media.provider.TextReasoningOptionsResolver;
import com.aid.media.provider.StructuredOutputSupport;
import com.aid.skill.domain.AidSkill;
import lombok.RequiredArgsConstructor;
import cn.hutool.crypto.SecureUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 使用统一文本媒体任务执行Prompt型Skill。 */
@Component
@RequiredArgsConstructor
public class PromptSkillExecutor implements SkillExecutor {

    private static final String BIZ_TASK_TYPE = "SKILL_RUNTIME_STEP";
    private final IMediaGenerationService mediaGenerationService;
    private final AssistantSkillBillingBridge assistantSkillBillingBridge;

    @Override
    public String executorType() {
        return "PROMPT";
    }

    @Override
    public void execute(SkillExecutionContext context, SkillExecutionCallbacks callbacks) {
        AidRequestBuilder builder = new AidRequestBuilder(context);
        MediaTextGenerateRequest request = builder.build();
        if (context.getAssistantBudgetEnvelope() != null) {
            request = assistantSkillBillingBridge.quoteReserveAndAttach(context, request);
        } else if (context.getRun() != null
                && AssistantSkillBudgetIdentity.INVOKE_SOURCE.equals(context.getRun().getInvokeSource())) {
            throw new ServiceException("Assistant Skill budget identity is required");
        }
        MediaTextStreamSink sink = new MediaTextStreamSink() {
            @Override
            public void onTaskPrepared(long taskId) {
                callbacks.onTaskPrepared(taskId);
            }

            @Override
            public void onDetached(long taskId) {
                callbacks.onDetached(taskId);
            }

            @Override
            public void onDelta(String content) {
                callbacks.onDelta(content);
            }

            @Override
            public void onProviderReasoningDelta(String content) { callbacks.onProviderReasoningDelta(content); }

            @Override
            public void onPublicReasoningSummaryDelta(String content) {
                callbacks.onPublicReasoningSummaryDelta(content);
            }

            @Override
            public void onDone(String fullText, String truncatedRawSnapshot) {
                callbacks.onDone(fullText);
            }

            @Override
            public void onFailed(String userMessage) {
                callbacks.onFailed(userMessage);
            }
        };
        try {
            if (Boolean.TRUE.equals(request.getStream())) {
                mediaGenerationService.generateTextStream(request, sink);
            } else {
                MediaTaskResponse task = mediaGenerationService.submitText(request, callbacks::onTaskPrepared);
                switch (task.getStatus()) {
                    case "SUCCEEDED" -> callbacks.onDone(task.getTextContent());
                    case "FAILED", "CANCELLED" -> callbacks.onFailed(task.getUserMessage());
                    default -> callbacks.onDetached(task.getTaskId());
                }
            }
        } catch (MediaSubmissionNotEnteredException notEntered) {
            boolean released;
            try {
                released = assistantSkillBillingBridge.releaseNotEntered(context, notEntered);
            } catch (RuntimeException releaseUnknown) {
                notEntered.addSuppressed(releaseUnknown);
                throw notEntered;
            }
            if (released) callbacks.onFailed(notEntered.getEvidenceCode());
        }
    }

    /** 只构造受控内部参数，调用方不能透传供应商原生options。 */
    private static class AidRequestBuilder {
        private final SkillExecutionContext context;

        AidRequestBuilder(SkillExecutionContext context) {
            this.context = context;
        }

        MediaTextGenerateRequest build() {
            boolean routing = "ROUTING".equals(context.getResponseMode());
            boolean preparingContext = "CONTEXT_PREPARATION".equals(context.getResponseMode());
            boolean structuredOutput = routing || preparingContext || "METHOD_JSON".equals(context.getResponseMode())
                    || structuredOutputEnabled(context.getSkill());
            boolean reasoningEnabled = !routing && !preparingContext
                    && Boolean.TRUE.equals(context.getRun().getEffectiveReasoningEnabled());
            Integer reasoningBudgetTokens = context.getRun().getReasoningBudgetTokens();
            MediaTextGenerateRequest request = new MediaTextGenerateRequest();
            request.setModelName(context.getSkill().getModelCode());
            request.setStrictModelSelection(true);
            boolean stream = routing || preparingContext || streamEnabled(context.getSkill());
            request.setStream(stream);
            request.setRequireNativeNonStream(!stream);
            request.setReasoningEnabled(reasoningEnabled);
            request.setReasoningLevel(reasoningEnabled
                    ? context.getRun().getEffectiveReasoningLevel() : null);
            request.setReasoningBudgetTokens(reasoningEnabled && reasoningBudgetTokens != null
                    && reasoningBudgetTokens > 0 ? reasoningBudgetTokens : null);
            request.setIncludeReasoning(reasoningEnabled
                    && Boolean.TRUE.equals(context.getRun().getShowReasoning()));
            request.setMessages(structuredOutput && !routing && !preparingContext
                    && !"METHOD_JSON".equals(context.getResponseMode())
                    ? withJsonOutputInstructions() : context.getMessages());
            request.setBizTaskType(context.getBizTaskType() == null ? BIZ_TASK_TYPE : context.getBizTaskType());
            request.setBizTaskId(context.getRun().getId());
            request.setBillingExempt(context.getAssistantBudgetEnvelope() == null && context.getCallIdentity() != null
                    && context.getCallIdentity().startsWith("step=format-repair,"));
            request.setUserId(context.getRun().getUserId());
            request.setProjectId(context.getProjectId());
            request.setEpisodeId(context.getEpisodeId());
            request.setCallId(context.getLogicalCallKey() != null ? context.getLogicalCallKey()
                    : SecureUtil.sha256("skill-runtime|" + context.getRun().getId() + "|main|0"));
            request.setCallIdentity(context.getCallIdentity());
            request.setTaskPromptDigest(request.getBizTaskType() + ":" + context.getRun().getId());
            Map<String, Object> options = new LinkedHashMap<>();
            options.put(StructuredOutputSupport.ENABLED_KEY, structuredOutput);
            if ("METHOD_JSON".equals(context.getResponseMode()) && context.getResponseSchemaJson() != null)
                options.put(StructuredOutputSupport.NATIVE_SCHEMA_KEY, JSON.parseObject(context.getResponseSchemaJson()));
            if (context.getSkill().getMaxOutputTokens() != null) {
                options.put(TextReasoningOptionsResolver.MAX_OUTPUT_TOKENS_KEY,
                        routing ? Math.min(context.getSkill().getMaxOutputTokens(), 2048)
                                : context.getSkill().getMaxOutputTokens());
            }
            request.setOptions(options);
            return request;
        }

        private java.util.List<MediaTextGenerateRequest.TextMessageItem> withJsonOutputInstructions() {
            var messages = new java.util.ArrayList<>(context.getMessages());
            MediaTextGenerateRequest.TextMessageItem instruction = new MediaTextGenerateRequest.TextMessageItem();
            instruction.setRole("system");
            String schema = context.getSkill().getOutputSchemaJson();
            instruction.setContent("只输出一个合法 JSON 对象，不要添加 Markdown 或解释。"
                    + (schema == null || "{}".equals(schema) ? "" : "\n输出字段规则：" + schema));
            messages.add(0, instruction);
            return messages;
        }

        private boolean streamEnabled(AidSkill skill) {
            try {
                JSONObject definition = JSON.parseObject(skill.getDefinitionJson());
                return definition == null || !Boolean.FALSE.equals(definition.getBoolean("stream"));
            } catch (RuntimeException ignored) {
                return true;
            }
        }

        private boolean structuredOutputEnabled(AidSkill skill) {
            if (skill == null || skill.getDefinitionJson() == null || skill.getDefinitionJson().isBlank()) {
                return false;
            }
            try {
                JSONObject definition = JSON.parseObject(skill.getDefinitionJson());
                return Boolean.TRUE.equals(definition.getBoolean("structuredOutputEnabled"));
            } catch (RuntimeException ignored) {
                // 历史异常定义按安全默认值处理，不能因此把普通文本请求切成 JSON Mode。
                return false;
            }
        }
    }
}
