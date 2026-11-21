package com.aid.runtime.assistant;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.aid.common.exception.ServiceException;
import com.aid.assistant.scope.AssistantContextLimits;
import com.aid.runtime.service.RuntimeToolRegistry;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict parser for one main-agent decision. It never executes model-selected work. */
@Component
public class DefaultAssistantLoopExecutor implements AssistantLoopExecutor
{
    public static final String PROTOCOL_VERSION = "assistant-loop/v1";
    private static final Set<String> ACTIONS = Set.of(
            "CHAT", "ASK", "PLAN", "TOOL", "SKILL", "CHECKPOINT", "COMPLETE", "FAIL");
    private static final Set<String> TOP_LEVEL_FIELDS = Set.of(
            "protocolVersion", "decisionId", "observedRunRevision", "observedPlanRevision", "action",
            "chat", "ask", "plan", "tool", "skill", "checkpoint", "complete", "fail");
    private static final Set<String> PAYLOAD_FIELDS = Set.of(
            "chat", "ask", "plan", "tool", "skill", "checkpoint", "complete", "fail");
    private static final Set<String> FORBIDDEN_FIELDS = Set.of(
            "chainofthought", "reasoning", "reasoningcontent", "thinking", "thinkingblocks",
            "credential", "credentials", "authorization", "billing", "billingsnapshot",
            "billingbudgetcontext", "payer", "lease", "fencingtoken", "toollallowlist",
            "toolallowlist", "systemprompt", "instruction", "providerroutesnapshot");

    @Resource private RuntimeToolRegistry toolRegistry;
    @Resource private AssistantContextLimits contextLimits;
    static final String OUTPUT_SCHEMA = """
            {
              "type":"object",
              "required":["protocolVersion","decisionId","observedRunRevision","observedPlanRevision","action","chat","ask","plan","tool","skill","checkpoint","complete","fail"],
              "properties":{
                "protocolVersion":{"const":"assistant-loop/v1"},
                "decisionId":{"type":"string","minLength":1,"maxLength":100},
                "observedRunRevision":{"type":"integer","minimum":0},
                "observedPlanRevision":{"type":"integer","minimum":0},
                "action":{"enum":["CHAT","ASK","PLAN","TOOL","SKILL","CHECKPOINT","COMPLETE","FAIL"]},
                "chat":{"oneOf":[{"type":"null"},{"$ref":"#/$defs/chat"}]},
                "ask":{"oneOf":[{"type":"null"},{"$ref":"#/$defs/ask"}]},
                "plan":{"oneOf":[{"type":"null"},{"$ref":"#/$defs/plan"}]},
                "tool":{"oneOf":[{"type":"null"},{"$ref":"#/$defs/tool"}]},
                "skill":{"oneOf":[{"type":"null"},{"$ref":"#/$defs/skill"}]},
                "checkpoint":{"oneOf":[{"type":"null"},{"$ref":"#/$defs/checkpoint"}]},
                "complete":{"oneOf":[{"type":"null"},{"$ref":"#/$defs/complete"}]},
                "fail":{"oneOf":[{"type":"null"},{"$ref":"#/$defs/fail"}]}
              },
              "oneOf":[
                {"properties":{"action":{"const":"CHAT"},"chat":{"$ref":"#/$defs/chat"},"ask":{"type":"null"},"plan":{"type":"null"},"tool":{"type":"null"},"skill":{"type":"null"},"checkpoint":{"type":"null"},"complete":{"type":"null"},"fail":{"type":"null"}}},
                {"properties":{"action":{"const":"ASK"},"chat":{"type":"null"},"ask":{"$ref":"#/$defs/ask"},"plan":{"type":"null"},"tool":{"type":"null"},"skill":{"type":"null"},"checkpoint":{"type":"null"},"complete":{"type":"null"},"fail":{"type":"null"}}},
                {"properties":{"action":{"const":"PLAN"},"chat":{"type":"null"},"ask":{"type":"null"},"plan":{"$ref":"#/$defs/plan"},"tool":{"type":"null"},"skill":{"type":"null"},"checkpoint":{"type":"null"},"complete":{"type":"null"},"fail":{"type":"null"}}},
                {"properties":{"action":{"const":"TOOL"},"chat":{"type":"null"},"ask":{"type":"null"},"plan":{"type":"null"},"tool":{"$ref":"#/$defs/tool"},"skill":{"type":"null"},"checkpoint":{"type":"null"},"complete":{"type":"null"},"fail":{"type":"null"}}},
                {"properties":{"action":{"const":"SKILL"},"chat":{"type":"null"},"ask":{"type":"null"},"plan":{"type":"null"},"tool":{"type":"null"},"skill":{"$ref":"#/$defs/skill"},"checkpoint":{"type":"null"},"complete":{"type":"null"},"fail":{"type":"null"}}},
                {"properties":{"action":{"const":"CHECKPOINT"},"chat":{"type":"null"},"ask":{"type":"null"},"plan":{"type":"null"},"tool":{"type":"null"},"skill":{"type":"null"},"checkpoint":{"$ref":"#/$defs/checkpoint"},"complete":{"type":"null"},"fail":{"type":"null"}}},
                {"properties":{"action":{"const":"COMPLETE"},"chat":{"type":"null"},"ask":{"type":"null"},"plan":{"type":"null"},"tool":{"type":"null"},"skill":{"type":"null"},"checkpoint":{"type":"null"},"complete":{"$ref":"#/$defs/complete"},"fail":{"type":"null"}}},
                {"properties":{"action":{"const":"FAIL"},"chat":{"type":"null"},"ask":{"type":"null"},"plan":{"type":"null"},"tool":{"type":"null"},"skill":{"type":"null"},"checkpoint":{"type":"null"},"complete":{"type":"null"},"fail":{"$ref":"#/$defs/fail"}}}
              ],
              "$defs":{
                "message":{"type":"object","required":["format","content"],"properties":{"format":{"const":"MARKDOWN"},"content":{"type":"string","minLength":1,"maxLength":100000}},"additionalProperties":false},
                "chat":{"type":"object","required":["message","suggestedNextActions"],"properties":{"message":{"$ref":"#/$defs/message"},"suggestedNextActions":{"type":"array","maxItems":8}},"additionalProperties":false},
                "option":{"type":"object","required":["optionId","label","description","exclusive","controlAction"],"properties":{"optionId":{"type":"string","minLength":1,"maxLength":64},"label":{"type":"string","minLength":1,"maxLength":200},"description":{"type":["string","null"],"maxLength":500},"exclusive":{"type":"boolean"},"controlAction":{"type":"null","description":"Business options never grant run control. The server appends the only pause option after validation."}},"additionalProperties":false},
                "question":{"type":"object","required":["questionId","type","title","description","required","allowFreeText","minSelections","maxSelections","options"],"properties":{"questionId":{"type":"string","minLength":1,"maxLength":64},"type":{"enum":["SINGLE","MULTI","TEXT"]},"title":{"type":"string","minLength":1,"maxLength":300},"description":{"type":["string","null"],"maxLength":1000},"required":{"type":"boolean"},"allowFreeText":{"type":"boolean"},"minSelections":{"type":["integer","null"],"minimum":0},"maxSelections":{"type":["integer","null"],"minimum":0},"options":{"type":"array","maxItems":99,"items":{"$ref":"#/$defs/option"}}},"additionalProperties":false},
                "ask":{"type":"object","required":["requestKey","contextDigest","reason","questions"],"properties":{"requestKey":{"type":"string","minLength":1,"maxLength":100},"contextDigest":{"type":"string","minLength":1,"maxLength":64},"reason":{"type":"string","minLength":1,"maxLength":1000},"questions":{"type":"array","minItems":1,"maxItems":20,"items":{"$ref":"#/$defs/question"}}},"additionalProperties":false},
                "planCriterion":{"type":"object","required":["criterionId","criterionKind","description"],"properties":{"criterionId":{"type":"string","minLength":1,"maxLength":64},"criterionKind":{"enum":["TECHNICAL_ARCHIVE","CREATIVE_QUALITY","CONTENT_REQUIREMENT","TARGET_WRITE"]},"description":{"type":"string","minLength":1,"maxLength":1000}},"additionalProperties":false},
                "planInputBinding":{"type":"object","required":["sourceStableStepId","logicalArtifactId","artifactType"],"properties":{"sourceStableStepId":{"type":"string","minLength":1,"maxLength":64},"logicalArtifactId":{"type":"string","minLength":1,"maxLength":100},"artifactType":{"type":"string","pattern":"^[A-Z][A-Z0-9_]{0,63}$"}},"additionalProperties":false},
                "planStep":{"type":"object","required":["stableStepId","position","operationKind","executorKind","bindingCode","bindingVersion","dependsOn","inputArtifactRefs","inputArtifactBindings","outputLogicalArtifactIds","retryClass","maxAttempts","approvalScope"],"properties":{"stableStepId":{"type":"string","minLength":1,"maxLength":64},"position":{"type":"integer","minimum":1},"operationKind":{"type":"string","minLength":1,"maxLength":64},"executorKind":{"enum":["TOOL","SKILL"]},"bindingCode":{"type":"string","minLength":1,"maxLength":100,"description":"Exact toolCode or skillCode from the available catalog"},"bindingVersion":{"type":"string","minLength":1,"maxLength":100,"description":"Exact tool contractVersion or decimal skillVersionId from the available catalog"},"dependsOn":{"type":"array","maxItems":100,"uniqueItems":true,"items":{"type":"string","minLength":1,"maxLength":64}},"inputArtifactRefs":{"type":"array","maxItems":100},"inputArtifactBindings":{"type":"array","maxItems":100,"items":{"$ref":"#/$defs/planInputBinding"}},"outputLogicalArtifactIds":{"type":"array","maxItems":100,"uniqueItems":true,"items":{"type":"string","minLength":1,"maxLength":100},"minItems":0,"description":"Only actual RuntimeArtifact outputs. Use [] for OBSERVATION_ONLY and TARGET_REFERENCE steps; depend on their completed step and use priorResults.observation, never invent artifact IDs for method text or canvas node receipts."},"retryClass":{"type":"string","minLength":1,"maxLength":64},"maxAttempts":{"type":"integer","minimum":1,"maximum":10},"approvalScope":{"type":"string","minLength":1,"maxLength":64,"description":"Short approval scope label only (for example CREATE_IMAGE_NODE or GENERATE_IMAGE), at most 64 characters. Put detailed requirements and model/size explanations in goal or assumptions. This label never grants execution or billing authority."}},"additionalProperties":false},
                "plan":{"type":"object","required":["parentRevision","goal","assumptions","deliverables","steps","completionCriteria"],"properties":{"parentRevision":{"type":"integer","minimum":0},"goal":{"type":"string","minLength":1,"maxLength":4000},"assumptions":{"type":"array","maxItems":50},"deliverables":{"type":"array","minItems":1,"maxItems":100},"steps":{"type":"array","minItems":1,"maxItems":100,"items":{"$ref":"#/$defs/planStep"}},"completionCriteria":{"type":"array","minItems":1,"maxItems":100,"items":{"$ref":"#/$defs/planCriterion"}}},"additionalProperties":false},
                "tool":{"type":"object","required":["invocationId","stableStepId","attemptNo","toolCode","contractVersion","operationKind","arguments","inputArtifactRefs","expectedContextRevisions"],"properties":{"invocationId":{"type":"string"},"stableStepId":{"type":"string","minLength":1,"maxLength":64},"attemptNo":{"type":"integer","minimum":1},"toolCode":{"type":"string"},"contractVersion":{"type":"string"},"operationKind":{"type":"string"},"arguments":{"type":"object"},"inputArtifactRefs":{"type":"array"},"expectedContextRevisions":{"type":"array"}},"additionalProperties":false},
                "skill":{"type":"object","required":["invocationId","stableStepId","attemptNo","skillCode","skillVersionId","packageDigest","input"],"properties":{"invocationId":{"type":"string"},"stableStepId":{"type":"string","minLength":1,"maxLength":64},"attemptNo":{"type":"integer","minimum":1},"skillCode":{"type":"string"},"skillVersionId":{"type":"string","pattern":"^[1-9][0-9]{0,18}$"},"packageDigest":{"type":"string","pattern":"^[0-9a-f]{64}$"},"input":{"type":"object"}},"additionalProperties":false},
                "checkpoint":{"type":"object","required":["checkpointId","summary","completedStableStepIds","artifactRefs","nextIntent"],"properties":{"checkpointId":{"type":"string"},"summary":{"type":"string"},"completedStableStepIds":{"type":"array","maxItems":100,"uniqueItems":true,"items":{"type":"string","minLength":1,"maxLength":64}},"artifactRefs":{"type":"array"},"nextIntent":{"type":"string"}},"additionalProperties":false},
                "completionCriterion":{"type":"object","required":["criterionId","criterionKind","passed","evidenceRefs"],"properties":{"criterionId":{"type":"string","minLength":1,"maxLength":64},"criterionKind":{"enum":["TECHNICAL_ARCHIVE","CREATIVE_QUALITY","CONTENT_REQUIREMENT","TARGET_WRITE"]},"passed":{"const":true},"evidenceRefs":{"type":"array","minItems":1,"maxItems":100,"uniqueItems":true,"items":{"type":"string","minLength":1,"maxLength":100}}},"additionalProperties":false},
                "complete":{"type":"object","required":["message","artifactRefs","completionCriteriaResults"],"properties":{"message":{"$ref":"#/$defs/message"},"artifactRefs":{"type":"array","minItems":0,"maxItems":100,"uniqueItems":true,"items":{"type":"string","minLength":1,"maxLength":100}},"targetWriteEvidenceRefs":{"type":"array","maxItems":100,"uniqueItems":true,"items":{"type":"string","pattern":"^runtime-step:[1-9][0-9]{0,18}$"},"description":"Exact completed own current-plan text-node write receipts. Technical persistence only, never media archival or creative review."},"completionCriteriaResults":{"type":"array","minItems":1,"maxItems":100,"items":{"$ref":"#/$defs/completionCriterion"}}},"additionalProperties":false},
                "fail":{"type":"object","required":["code","userMessage","retryable","recoveryAction"],"properties":{"code":{"type":"string"},"userMessage":{"type":"string"},"retryable":{"type":"boolean"},"recoveryAction":{"enum":["ASK_USER","REPLAN","RETRY_LATER","NONE"]}},"additionalProperties":false}
              },
              "additionalProperties":false
            }
            """;
    private static final String VALIDATION_CONTRACT_DIGEST = SecureUtil.sha256(OUTPUT_SCHEMA);

    public static String validationContractDigest()
    {
        return VALIDATION_CONTRACT_DIGEST;
    }

    @Override
    public String supportedProtocolVersion()
    {
        return PROTOCOL_VERSION;
    }

    @Override
    public AssistantLoopSubmission prepareIteration(AssistantLoopExecutionContext context)
    {
        if (context == null || context.getRunId() == null || context.getStateRevision() == null
                || context.getPlanRevision() == null || context.getIteration() == null
                || context.getIteration() < 1 || !PROTOCOL_VERSION.equals(context.getProtocolVersion())
                || StrUtil.isBlank(context.getRequestJson()))
        {
            fail("\u5faa\u73af\u8f93\u5165\u65e0\u6548");
        }
        requireBinding(context.getBinding());
        requireResponsePolicy(context.getBinding());
        JSONObject request = object(context.getRequestJson(), "\u5faa\u73af\u8f93\u5165\u65e0\u6548");
        if (!PROTOCOL_VERSION.equals(request.getString("protocolVersion")))
        {
            fail("\u534f\u8bae\u7248\u672c\u9519\u8bef");
        }
        AssistantMainInputContract.validate(request, context.getBinding().getInputSchemaJson());
        JSONObject run = request.getJSONObject("run");
        JSONObject turn = request.getJSONObject("turn");
        if (run == null || turn == null
                || !context.getRunId().equals(run.getLong("runId"))
                || !context.getStateRevision().equals(run.getLong("stateRevision"))
                || !context.getPlanRevision().equals(run.getLong("planRevision"))
                || !context.getIteration().equals(turn.getInteger("iteration")))
        {
            fail("\u5faa\u73af\u7248\u672c\u51b2\u7a81");
        }
        AssistantLoopSubmission submission = new AssistantLoopSubmission();
        submission.setProtocolVersion(PROTOCOL_VERSION);
        submission.setOperationKind("ASSISTANT_DECISION");
        submission.setSystemPrompt(context.getBinding().getInstruction()
                + com.aid.skill.service.SkillContractExamples.instruction(
                context.getBinding().getInputSchemaJson(), context.getBinding().getOutputSchemaJson()));
        submission.setModelConfigJson(context.getBinding().getModelConfigJson());
        submission.setInputSchemaJson(context.getBinding().getInputSchemaJson());
        submission.setRequestJson(AssistantMainInputContract.validatedJson(request,
                context.getBinding().getInputSchemaJson()));
        submission.setOutputSchemaJson(OUTPUT_SCHEMA);
        return submission;
    }

    @Override
    public AssistantLoopDecision parseDecision(AssistantLoopModelResult persistedResult,
                                                AssistantLoopBinding binding)
    {
        requireBinding(binding);
        requireResponsePolicy(binding);
        if (persistedResult == null || persistedResult.getMediaTaskId() == null
                || !"SUCCEEDED".equals(persistedResult.getStatus())
                || StrUtil.isBlank(persistedResult.getResultJson()))
        {
            fail("\u6a21\u578b\u7ed3\u679c\u65e0\u6548");
        }
        if (StrUtil.isNotBlank(persistedResult.getResultDigest())
                && !persistedResult.getResultDigest().equals(SecureUtil.sha256(persistedResult.getResultJson())))
        {
            fail("\u7ed3\u679c\u6458\u8981\u51b2\u7a81");
        }
        JSONObject value = object(persistedResult.getResultJson(), "\u51b3\u7b56\u7ed3\u6784\u65e0\u6548");
        AssistantLoopDecision decision = validateEnvelope(value, PROTOCOL_VERSION);
        if (!AssistantMainInputContract.matchesConfiguredOutput(value, binding.getOutputSchemaJson()))
            throw new AssistantOutputContractException();
        return decision;
    }

    AssistantLoopDecision validateEnvelope(JSONObject value, String protocolVersion)
    {
        requireExactFields(value, TOP_LEVEL_FIELDS);
        rejectForbiddenDecisionFields(value);
        requireProtocol(value, protocolVersion);
        String decisionId = requiredText(value, "decisionId", 100);
        Long observedRunRevision = value.getLong("observedRunRevision");
        Long observedPlanRevision = value.getLong("observedPlanRevision");
        String action = requiredText(value, "action", 20);
        if (observedRunRevision == null || observedRunRevision < 0
                || observedPlanRevision == null || observedPlanRevision < 0 || !ACTIONS.contains(action))
        {
            fail("\u51b3\u7b56\u7248\u672c\u65e0\u6548");
        }
        String payloadName = action.toLowerCase();
        int payloadCount = 0;
        for (String field : PAYLOAD_FIELDS)
        {
            if (value.get(field) != null) payloadCount++;
        }
        if (payloadCount != 1 || value.getJSONObject(payloadName) == null)
        {
            fail("\u51b3\u7b56\u8f7d\u8377\u65e0\u6548");
        }
        validatePayload(action, value.getJSONObject(payloadName),
                NativeAssistantLoopExecutor.PROTOCOL_VERSION.equals(protocolVersion));

        AssistantLoopDecision decision = new AssistantLoopDecision();
        decision.setProtocolVersion(protocolVersion);
        decision.setDecisionId(decisionId);
        decision.setObservedRunRevision(observedRunRevision);
        decision.setObservedPlanRevision(observedPlanRevision);
        decision.setAction(action);
        decision.setDecisionJson(value.toJSONString(JSONWriter.Feature.WriteMapNullValue));
        return decision;
    }

    static JSONObject nativePayloadSchema(String action)
    {
        if (StrUtil.isBlank(action) || !ACTIONS.contains(action))
            throw new ServiceException("Assistant native action is invalid");
        JSONObject full = JSON.parseObject(OUTPUT_SCHEMA);
        JSONObject definitions = full.getJSONObject("$defs");
        if ("ASK".equals(action))
        {
            JSONObject option = definitions.getJSONObject("option");
            removeRequired(option, "controlAction");
            option.getJSONObject("properties").remove("controlAction");
            JSONObject ask = definitions.getJSONObject("ask");
            removeRequired(ask, "requestKey", "contextDigest");
            ask.getJSONObject("properties").remove("requestKey");
            ask.getJSONObject("properties").remove("contextDigest");
            ask.put("description", "Provide only the business reason and questions. Request identity, context identity and run-control options are server-owned and injected from the frozen native binding.");
            definitions.getJSONObject("question").getJSONObject("properties")
                    .getJSONObject("options").put("maxItems", 99);
        }
        if ("PLAN".equals(action))
        {
            JSONObject plan = definitions.getJSONObject("plan");
            removeRequired(plan, "parentRevision");
            plan.getJSONObject("properties").remove("parentRevision");
            plan.put("description",
                    "Propose an execution plan containing steps that can produce real evidence for every completion criterion. "
                            + "Use exact registered runtime tools or immutable method-only TEXT Skills from the current catalogs. "
                            + "A Skill produces a completed observation for dependent Tool steps, not a RuntimeArtifact or target write. "
                            + "Read method files, model catalogs or project state through the currently available discovery tools directly; "
                            + "do not propose a read-only plan just to gather information. The server-owned discovery plan is not an execution-plan template. "
                            + "The parent plan revision is server-owned and injected from the frozen native binding.");
            JSONObject executorKind = definitions.getJSONObject("planStep").getJSONObject("properties")
                    .getJSONObject("executorKind");
            executorKind.put("description", "Use TOOL for an exact runtime tool or SKILL for an exact immutable method-only TEXT Skill.");
            definitions.getJSONObject("planCriterion").getJSONObject("properties")
                    .getJSONObject("criterionKind").put("description",
                            "Use TARGET_WRITE for persisted text-node writes backed by current-plan runtime-step receipts. "
                                    + "Other kinds require a finalized Runtime artifact; CONTENT_REQUIREMENT and "
                                    + "CREATIVE_QUALITY also require a real PASS review report covering that artifact.");
        }
        if ("COMPLETE".equals(action))
        {
            definitions.getJSONObject("complete").getJSONObject("properties")
                    .getJSONObject("artifactRefs").put("description",
                            "Only finalized current-run, current-plan artifact:* references. Never put canvas:node:* "
                                    + "or canvas resource refs here.");
            definitions.getJSONObject("completionCriterion").getJSONObject("properties")
                    .getJSONObject("evidenceRefs").put("description",
                            "TARGET_WRITE uses runtime-step refs also listed in targetWriteEvidenceRefs. Other kinds "
                                    + "use finalized artifact refs; canvas node or resource refs are invalid.");
        }
        JSONObject result = new JSONObject();
        result.putAll(definitions.getJSONObject(action.toLowerCase()));
        result.put("$defs", definitions);
        return result;
    }

    private static void removeRequired(JSONObject schema, String... fields)
    {
        JSONArray required = schema == null ? null : schema.getJSONArray("required");
        if (required == null) throw new ServiceException("Assistant native schema is invalid");
        for (String field : fields) required.remove(field);
    }

    private void requireBinding(AssistantLoopBinding binding)
    {
        if (binding == null || binding.getAgentId() == null || binding.getAgentVersionId() == null
                || !PROTOCOL_VERSION.equals(binding.getProtocolVersion())
                || StrUtil.isBlank(binding.getVersionCode()) || StrUtil.isBlank(binding.getPackageDigest())
                || StrUtil.isBlank(binding.getInstruction()) || StrUtil.isBlank(binding.getDefinitionJson()))
        {
            fail("Agent\u7ed1\u5b9a\u65e0\u6548");
        }
    }

    private void requireResponsePolicy(AssistantLoopBinding binding)
    {
        JSONObject definition = object(binding.getDefinitionJson(), "Agent\u5b9a\u4e49\u65e0\u6548");
        JSONObject policy = definition.getJSONObject("responsePolicy");
        String mode = policy == null ? null : policy.getString("mode");
        Integer repairs = policy == null ? null : policy.getInteger("maxRepairAttempts");
        if (repairs == null || repairs < 0 || repairs > 2) fail("Agent\u54cd\u5e94\u7b56\u7565\u65e0\u6548");
        if ("STRICT_LOCAL_JSON".equals(mode))
        {
            if (repairs != 0) fail("Agent\u4fee\u590d\u8c03\u7528\u5c1a\u672a\u6388\u6743");
            return;
        }
        if ("NATIVE_JSON_SCHEMA".equals(mode))
        {
            JSONObject model = object(binding.getModelConfigJson(), "Agent\u6a21\u578b\u914d\u7f6e\u65e0\u6548");
            if (model.getBooleanValue("nativeJsonSchemaSupported")) return;
        }
        fail("Agent\u54cd\u5e94\u7b56\u7565\u4e0d\u53ef\u7528");
    }

    private void requireProtocol(JSONObject value, String expectedProtocol)
    {
        if (StrUtil.isBlank(expectedProtocol)
                || !expectedProtocol.equals(value.getString("protocolVersion")))
        {
            fail("\u534f\u8bae\u7248\u672c\u9519\u8bef");
        }
    }

    private void validatePayload(String action, JSONObject payload, boolean nativeProtocol)
    {
        switch (action)
        {
            case "CHAT" -> validateChat(payload);
            case "ASK" -> validateAsk(payload, nativeProtocol);
            case "PLAN" -> validatePlan(payload);
            case "TOOL" -> validateTool(payload);
            case "SKILL" -> validateSkill(payload);
            case "CHECKPOINT" -> validateCheckpoint(payload);
            case "COMPLETE" -> validateComplete(payload);
            case "FAIL" -> validateFail(payload);
            default -> fail("\u51b3\u7b56\u7c7b\u578b\u65e0\u6548");
        }
    }

    private void validateChat(JSONObject payload)
    {
        rejectUnknownFields(payload, Set.of("message", "suggestedNextActions"));
        Object rawMessage = payload.get("message");
        if (!(rawMessage instanceof JSONObject message))
            throw new AssistantDecisionValidationRejectedException(
                    AssistantDecisionValidationFeedback.chatMessageObjectRequired(
                            VALIDATION_CONTRACT_DIGEST,
                            AssistantDecisionValidationFeedback.ObservedType.fromJsonValue(rawMessage)));
        validateMessage(message);
        optionalArray(payload, "suggestedNextActions", 8);
    }

    private void validateAsk(JSONObject payload, boolean nativeProtocol)
    {
        rejectUnknownFields(payload, Set.of("requestKey", "contextDigest", "reason", "questions"));
        requiredText(payload, "requestKey", 100);
        requiredText(payload, "contextDigest", 64);
        requiredText(payload, "reason", 1000);
        JSONArray questions = requiredArray(payload, "questions", 1, 20);
        Set<String> questionIds = new HashSet<>();
        for (int i = 0; i < questions.size(); i++)
        {
            JSONObject question = questions.getJSONObject(i);
            if (question == null) fail("\u95ee\u9898\u7ed3\u6784\u65e0\u6548");
            rejectUnknownFields(question, Set.of("questionId", "type", "title", "description", "required",
                    "allowFreeText", "minSelections", "maxSelections", "options"));
            String questionId = requiredText(question, "questionId", 64);
            if (!questionIds.add(questionId)) fail("\u95ee\u9898\u6807\u8bc6\u91cd\u590d");
            String type = requiredText(question, "type", 10);
            if (!Set.of("SINGLE", "MULTI", "TEXT").contains(type)) fail("\u95ee\u9898\u7c7b\u578b\u65e0\u6548");
            requiredText(question, "title", 300);
            optionalText(question, "description", 1000);
            if (question.getBoolean("required") == null) fail("\u95ee\u9898\u5fc5\u586b\u65e0\u6548");
            Boolean allowFreeText = question.getBoolean("allowFreeText");
            if (allowFreeText == null) fail("\u95ee\u9898\u81ea\u7531\u8f93\u5165\u8bbe\u7f6e\u65e0\u6548");
            JSONArray options = question.getJSONArray("options");
      if ("TEXT".equals(type))
      {
          if (!Boolean.TRUE.equals(allowFreeText)) fail("\u6587\u672c\u95ee\u9898\u5fc5\u987b\u5141\u8bb8\u81ea\u7531\u8f93\u5165");
          if (options != null && !options.isEmpty()) fail("\u6587\u672c\u95ee\u9898\u65e0\u9009\u9879");
          Integer min = question.getInteger("minSelections");
          Integer max = question.getInteger("maxSelections");
          if (min != null && min != 0 || max != null && max != 0) fail("\u6587\u672c\u95ee\u9898\u4e0d\u652f\u6301\u9009\u62e9\u6570\u91cf\u8fb9\u754c");
          continue;
      }
            int optionLimit = nativeProtocol ? 100 : 99;
            if (options == null || options.isEmpty() || options.size() > optionLimit) fail("\u95ee\u9898\u9009\u9879\u65e0\u6548");
            Integer min = question.getInteger("minSelections");
            Integer max = question.getInteger("maxSelections");
            if (min == null || max == null || min < 0 || max < 1 || min > max || max > options.size()
                    || ("SINGLE".equals(type) && (min > 1 || max != 1)))
            {
                fail("\u9009\u9879\u6570\u91cf\u65e0\u6548");
            }
            Set<String> optionIds = new HashSet<>();
            int pauseOptions = 0;
            for (int j = 0; j < options.size(); j++)
            {
                JSONObject option = options.getJSONObject(j);
                if (option == null) fail("\u95ee\u9898\u9009\u9879\u65e0\u6548");
                rejectUnknownFields(option, Set.of("optionId", "label", "description", "exclusive", "controlAction"));
                if (!optionIds.add(requiredText(option, "optionId", 64))) fail("\u9009\u9879\u6807\u8bc6\u91cd\u590d");
                requiredText(option, "label", 200);
                optionalText(option, "description", 500);
                if (option.getBoolean("exclusive") == null) fail("\u9009\u9879\u6392\u4ed6\u65e0\u6548");
                Object control = option.get("controlAction");
                if (nativeProtocol && (!(control instanceof String)
                        || !Set.of("CONTINUE_RUN", "PAUSE_RUN").contains(control)))
                    fail("原生选项动作无效");
                if (!nativeProtocol && control != null)
                    fail("选项动作无效");
                if (nativeProtocol && "PAUSE_RUN".equals(control))
                {
                    pauseOptions++;
                    if (!NativeAssistantLoopExecutor.PLATFORM_PAUSE_OPTION_ID.equals(
                            option.getString("optionId"))
                            || !NativeAssistantLoopExecutor.PLATFORM_PAUSE_LABEL.equals(
                            option.getString("label"))
                            || !NativeAssistantLoopExecutor.PLATFORM_PAUSE_DESCRIPTION.equals(
                            option.getString("description"))
                            || !option.getBooleanValue("exclusive"))
                        fail("原生暂停选项无效");
                }
                if ("PAUSE_RUN".equals(control)
                        && "MULTI".equals(type) && !option.getBooleanValue("exclusive"))
                    fail("暂停选项必须是单选或排他选项");
            }
            if (nativeProtocol && pauseOptions != 1) fail("原生暂停选项缺失");
        }
    }

    private void validatePlan(JSONObject payload)
    {
        rejectUnknownFields(payload, Set.of("parentRevision", "goal", "assumptions", "deliverables", "steps",
                "completionCriteria"));
        Long parentRevision = payload.getLong("parentRevision");
        if (parentRevision == null || parentRevision < 0) fail("\u8ba1\u5212\u7248\u672c\u65e0\u6548");
        requiredText(payload, "goal", 4000);
        optionalArray(payload, "assumptions", 50);
        requiredArray(payload, "deliverables", 1, 100);
        Object rawSteps = payload.get("steps");
        if (!(rawSteps instanceof JSONArray))
            reject(AssistantDecisionValidationFeedback.planStepsArray(
                    VALIDATION_CONTRACT_DIGEST,
                    AssistantDecisionValidationFeedback.ObservedType.fromJsonValue(rawSteps)));
        JSONArray steps = requiredArray(payload, "steps", 1, 100);
        JSONArray completionCriteria = requiredArray(payload, "completionCriteria", 1, 100);
        Set<String> criterionIds = new HashSet<>();
        for (Object item : completionCriteria)
        {
            if (!(item instanceof JSONObject)) fail("\u5b8c\u6210\u6761\u4ef6\u65e0\u6548");
            JSONObject criterion = (JSONObject) item;
            rejectUnknownFields(criterion, Set.of("criterionId", "criterionKind", "description"));
            if (!criterionIds.add(requiredText(criterion, "criterionId", 64))) fail("\u5b8c\u6210\u6761\u4ef6\u6807\u8bc6\u91cd\u590d");
            String kind = requiredText(criterion, "criterionKind", 40);
            if (!Set.of("TECHNICAL_ARCHIVE", "CREATIVE_QUALITY", "CONTENT_REQUIREMENT", "TARGET_WRITE").contains(kind))
                fail("\u5b8c\u6210\u6761\u4ef6\u7c7b\u578b\u65e0\u6548");
            requiredText(criterion, "description", 1000);
        }
        Set<String> ids = new HashSet<>();
        Map<String, List<String>> dependencies = new HashMap<>();
        Map<String, JSONArray> inputBindings = new HashMap<>();
        Map<String, List<String>> declaredOutputs = new HashMap<>();
        Set<Integer> positions = new HashSet<>();
        for (int i = 0; i < steps.size(); i++)
        {
            JSONObject step = steps.getJSONObject(i);
            if (step == null) fail("\u8ba1\u5212\u6b65\u9aa4\u65e0\u6548");
            Set<String> allowedStepFields = Set.of("stableStepId", "position", "operationKind", "executorKind",
                    "bindingCode", "bindingVersion", "dependsOn", "inputArtifactRefs", "inputArtifactBindings",
                    "outputLogicalArtifactIds", "retryClass", "maxAttempts", "approvalScope");
            Set<String> unknownStepFields = new HashSet<>(step.keySet());
            unknownStepFields.removeAll(allowedStepFields);
            if (!unknownStepFields.isEmpty())
                reject(AssistantDecisionValidationFeedback.planStepUnknownFields(
                        VALIDATION_CONTRACT_DIGEST, unknownStepFields));
            String stableId = requiredText(step, "stableStepId", 64);
            if (!ids.add(stableId)) fail("\u6b65\u9aa4\u6807\u8bc6\u91cd\u590d");
            Integer position = step.getInteger("position");
            if (position == null || position < 1 || !positions.add(position)) fail("\u6b65\u9aa4\u987a\u5e8f\u65e0\u6548");
            String executorKind = requiredText(step, "executorKind", 10);
            if (!Set.of("TOOL", "SKILL").contains(executorKind)) fail("\u6b65\u9aa4\u6267\u884c\u65e0\u6548");
            requiredText(step, "operationKind", 64);
            requiredText(step, "bindingCode", 100);
            requiredText(step, "bindingVersion", 100);
            requiredText(step, "retryClass", 64);
            Integer maxAttempts = step.getInteger("maxAttempts");
            if (maxAttempts == null || maxAttempts < 1 || maxAttempts > 10) fail("\u6b65\u9aa4\u91cd\u8bd5\u4e0a\u9650\u65e0\u6548");
            requiredText(step, "approvalScope", 64);
            JSONArray deps = optionalArray(step, "dependsOn", 100);
            List<String> dependencyIds = stringValues(deps, 64);
            dependencies.put(stableId, dependencyIds);
            optionalArray(step, "inputArtifactRefs", 100);
            JSONArray bindings = optionalArray(step, "inputArtifactBindings", 100);
            inputBindings.put(stableId, bindings);
            for (Object rawBinding : bindings)
            {
                if (!(rawBinding instanceof JSONObject)) fail("\u8ba1\u5212\u8f93\u5165\u7ed1\u5b9a\u65e0\u6548");
                JSONObject binding = (JSONObject) rawBinding;
                rejectUnknownFields(binding, Set.of("sourceStableStepId", "logicalArtifactId", "artifactType"));
                String sourceStableStepId = requiredText(binding, "sourceStableStepId", 64);
                requiredText(binding, "logicalArtifactId", 100);
                String artifactType = requiredText(binding, "artifactType", 64);
                if (!artifactType.matches("^[A-Z][A-Z0-9_]{0,63}$")) fail("\u8ba1\u5212\u8f93\u5165\u4ea7\u7269\u7c7b\u578b\u65e0\u6548");
                if (!dependencyIds.contains(sourceStableStepId)) fail("\u8ba1\u5212\u8f93\u5165\u7ed1\u5b9a\u4f9d\u8d56\u65e0\u6548");
            }
            declaredOutputs.put(stableId, stringValues(
                    requiredArray(step, "outputLogicalArtifactIds", 0, 100), 100));
        }
        for (Map.Entry<String, List<String>> entry : dependencies.entrySet())
        {
            for (String dependency : entry.getValue())
            {
                if (!ids.contains(dependency) || dependency.equals(entry.getKey())) fail("\u6b65\u9aa4\u4f9d\u8d56\u65e0\u6548");
            }
        }
        for (Map.Entry<String, JSONArray> entry : inputBindings.entrySet())
        {
            for (Object rawBinding : entry.getValue())
            {
                JSONObject binding = (JSONObject) rawBinding;
                String source = binding.getString("sourceStableStepId");
                String logicalId = binding.getString("logicalArtifactId");
                List<String> outputs = declaredOutputs.get(source);
                if (outputs == null || outputs.stream().filter(logicalId::equals).count() != 1)
                    reject(AssistantDecisionValidationFeedback.planInputOutputNotDeclared(
                            VALIDATION_CONTRACT_DIGEST));
            }
        }
        rejectCycles(dependencies);
    }

    private void validateTool(JSONObject payload)
    {
        rejectUnknownFields(payload, Set.of("invocationId", "stableStepId", "attemptNo", "toolCode",
                "contractVersion", "operationKind", "arguments", "inputArtifactRefs", "expectedContextRevisions"));
        validateInvocation(payload, "toolCode", "contractVersion");
        JSONObject arguments = payload.getJSONObject("arguments");
        if (arguments == null) fail("Tool\u53c2\u6570\u65e0\u6548");
        toolRegistry.validateDecisionArguments(payload.getString("toolCode"),
                payload.getString("contractVersion"), arguments);
        optionalArray(payload, "inputArtifactRefs", 100);
        optionalArray(payload, "expectedContextRevisions", contextLimits.maxReferences() + 1);
    }

    private void validateSkill(JSONObject payload)
    {
        rejectUnknownFields(payload, Set.of("invocationId", "stableStepId", "attemptNo", "skillCode",
                "skillVersionId", "packageDigest", "input"));
        validateInvocation(payload, "skillCode", null);
        String skillVersionId = payload.getString("skillVersionId");
        boolean validVersion;
        try
        {
            validVersion = skillVersionId != null && skillVersionId.matches("[1-9][0-9]{0,18}")
                    && Long.parseLong(skillVersionId) > 0;
        }
        catch (NumberFormatException invalid)
        {
            validVersion = false;
        }
        if (!validVersion || !isSha256(payload.getString("packageDigest"))
                || payload.getJSONObject("input") == null)
        {
            fail("Skill\u8c03\u7528\u65e0\u6548");
        }
    }

    private void validateInvocation(JSONObject payload, String bindingField, String versionField)
    {
        requiredText(payload, "invocationId", 100);
        requiredText(payload, "stableStepId", 64);
        Integer attemptNo = payload.getInteger("attemptNo");
        if (attemptNo == null || attemptNo < 1 || attemptNo > 100) fail("\u8c03\u7528\u6b21\u6570\u65e0\u6548");
        requiredText(payload, bindingField, 100);
        if (versionField != null) requiredText(payload, versionField, 100);
        if (payload.containsKey("operationKind")) requiredText(payload, "operationKind", 64);
    }

    private void validateCheckpoint(JSONObject payload)
    {
        rejectUnknownFields(payload, Set.of("checkpointId", "summary", "completedStableStepIds",
                "artifactRefs", "nextIntent"));
        requiredText(payload, "checkpointId", 100);
        requiredText(payload, "summary", 10000);
        stringValues(optionalArray(payload, "completedStableStepIds", 100), 64);
        optionalArray(payload, "artifactRefs", 100);
        requiredText(payload, "nextIntent", 4000);
    }

    private void validateComplete(JSONObject payload)
    {
        rejectUnknownFields(payload, Set.of("message", "artifactRefs", "targetWriteEvidenceRefs", "completionCriteriaResults"));
        validateMessage(payload.getJSONObject("message"));
        stringValues(requiredArray(payload, "artifactRefs", 0, 100), 100);
        stringValues(optionalArray(payload, "targetWriteEvidenceRefs", 100), 100);
        JSONArray criteria = requiredArray(payload, "completionCriteriaResults", 1, 100);
        for (Object item : criteria)
        {
            if (!(item instanceof JSONObject)) fail("\u5b8c\u6210\u6761\u4ef6\u65e0\u6548");
            JSONObject result = (JSONObject) item;
            rejectUnknownFields(result, Set.of("criterionId", "criterionKind", "passed", "evidenceRefs"));
            requiredText(result, "criterionId", 64);
            String kind = requiredText(result, "criterionKind", 40);
            if (!Set.of("TECHNICAL_ARCHIVE", "CREATIVE_QUALITY", "CONTENT_REQUIREMENT", "TARGET_WRITE").contains(kind))
                fail("\u5b8c\u6210\u6761\u4ef6\u7c7b\u578b\u65e0\u6548");
            if (!Boolean.TRUE.equals(result.getBoolean("passed"))) fail("\u5b8c\u6210\u6761\u4ef6\u672a\u901a\u8fc7");
            stringValues(requiredArray(result, "evidenceRefs", 1, 100), 100);
        }
        AssistantCompletionEvidenceReferences.requireDeclared(payload, VALIDATION_CONTRACT_DIGEST);
    }

    private void validateFail(JSONObject payload)
    {
        rejectUnknownFields(payload, Set.of("code", "userMessage", "retryable", "recoveryAction"));
        requiredText(payload, "code", 64);
        requiredText(payload, "userMessage", 500);
        if (payload.getBoolean("retryable") == null
                || !Set.of("ASK_USER", "REPLAN", "RETRY_LATER", "NONE")
                .contains(requiredText(payload, "recoveryAction", 20)))
        {
            fail("\u5931\u8d25\u6062\u590d\u65e0\u6548");
        }
    }

    private void validateMessage(JSONObject message)
    {
        if (message == null) fail("\u6d88\u606f\u7ed3\u6784\u65e0\u6548");
        rejectUnknownFields(message, Set.of("format", "content"));
        if (!"MARKDOWN".equals(message.getString("format"))) fail("\u6d88\u606f\u683c\u5f0f\u65e0\u6548");
        requiredText(message, "content", 100000);
    }

    private void rejectCycles(Map<String, List<String>> dependencies)
    {
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> children = new HashMap<>();
        dependencies.keySet().forEach(id -> indegree.put(id, 0));
        dependencies.forEach((id, deps) -> deps.forEach(dep -> {
            indegree.put(id, indegree.get(id) + 1);
            children.computeIfAbsent(dep, key -> new ArrayList<>()).add(id);
        }));
        Deque<String> ready = new ArrayDeque<>();
        indegree.forEach((id, degree) -> { if (degree == 0) ready.add(id); });
        int visited = 0;
        while (!ready.isEmpty())
        {
            String id = ready.removeFirst();
            visited++;
            for (String child : children.getOrDefault(id, List.of()))
            {
                int degree = indegree.compute(child, (key, value) -> value - 1);
                if (degree == 0) ready.add(child);
            }
        }
        if (visited != dependencies.size()) fail("\u8ba1\u5212\u4f9d\u8d56\u6210\u73af");
    }

    private void rejectUnknownFields(JSONObject object, Set<String> allowed)
    {
        if (!allowed.containsAll(object.keySet())) fail("\u7ed3\u6784\u5b57\u6bb5\u672a\u77e5");
    }

    private void requireExactFields(JSONObject object, Set<String> expected)
    {
        if (object.size() != expected.size() || !expected.containsAll(object.keySet()))
            fail("\u51b3\u7b56\u7ed3\u6784\u5b57\u6bb5\u65e0\u6548");
    }

    private void rejectForbiddenDecisionFields(JSONObject envelope)
    {
        Set<String> businessPromptFields = Set.of();
        if ("TOOL".equals(envelope.get("action"))
                && envelope.get("tool") instanceof JSONObject tool
                && tool.get("arguments") instanceof JSONObject
                && tool.get("toolCode") instanceof String toolCode
                && tool.get("contractVersion") instanceof String contractVersion)
        {
            businessPromptFields = toolRegistry.decisionBusinessPromptTextFields(toolCode, contractVersion);
        }
        rejectForbiddenFields(envelope, "$", businessPromptFields);
    }

    private void rejectForbiddenFields(Object value, String path, Set<String> businessPromptFields)
    {
        if (value instanceof JSONObject object)
        {
            for (String key : object.keySet())
            {
                String normalized = key.replace("_", "").replace("-", "").toLowerCase(java.util.Locale.ROOT);
                // A declared prompt string is authored business content, not the agent's own
                // instruction. Only this exact Tool argument path may carry it. The complete
                // registered input schema is still validated before any decision is persisted.
                boolean businessText = "$.tool.arguments".equals(path)
                        && businessPromptFields.contains(key) && object.get(key) instanceof String;
                if (FORBIDDEN_FIELDS.contains(normalized) && !businessText)
                    fail("\u5305\u542b\u7981\u6b62\u5b57\u6bb5");
                rejectForbiddenFields(object.get(key), path + "." + key, businessPromptFields);
            }
        }
        else if (value instanceof JSONArray array)
        {
            array.forEach(item -> rejectForbiddenFields(item, path + "[]", businessPromptFields));
        }
    }

    private JSONObject object(String json, String message)
    {
        try
        {
            JSONObject result = JSON.parseObject(json);
            if (result == null) fail(message);
            return result;
        }
        catch (RuntimeException error)
        {
            fail(message);
            return null;
        }
    }

    private String requiredText(JSONObject object, String field, int maxLength)
    {
        String value = object.getString(field);
        if (StrUtil.isBlank(value) || value.length() > maxLength) fail("\u6587\u672c\u5b57\u6bb5\u65e0\u6548");
        return value;
    }

    private void optionalText(JSONObject object, String field, int maxLength)
    {
        String value = object.getString(field);
        if (value != null && value.length() > maxLength) fail("\u6587\u672c\u5b57\u6bb5\u65e0\u6548");
    }

    private JSONArray requiredArray(JSONObject object, String field, int minSize, int maxSize)
    {
        JSONArray value = object.getJSONArray(field);
        if (value == null || value.size() < minSize || value.size() > maxSize) fail("\u5217\u8868\u5b57\u6bb5\u65e0\u6548");
        return value;
    }

    private JSONArray optionalArray(JSONObject object, String field, int maxSize)
    {
        JSONArray value = object.getJSONArray(field);
        if (value != null && value.size() > maxSize) fail("\u5217\u8868\u5b57\u6bb5\u65e0\u6548");
        return value == null ? new JSONArray() : value;
    }

    private List<String> stringValues(JSONArray array, int maxLength)
    {
        List<String> values = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (Object item : array)
        {
            if (!(item instanceof String))
            {
                fail("\u5217\u8868\u5185\u5bb9\u65e0\u6548");
            }
            String text = (String) item;
            if (StrUtil.isBlank(text) || text.length() > maxLength || !unique.add(text))
            {
                fail("\u5217\u8868\u5185\u5bb9\u65e0\u6548");
            }
            values.add(text);
        }
        return values;
    }

    private boolean isSha256(String value)
    {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private void fail(String message)
    {
        throw new ServiceException(message);
    }

    private void reject(AssistantDecisionValidationFeedback feedback)
    {
        throw new AssistantDecisionValidationRejectedException(feedback);
    }

}
