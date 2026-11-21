package com.aid.assistant.creative.tool;

import cn.hutool.crypto.SecureUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.aid.assistant.creative.dto.CreativeRequests;
import com.aid.assistant.budget.AssistantBudgetOperationScope;
import com.aid.assistant.creative.service.CreativePlanningService;
import com.aid.assistant.creative.service.CreativeSourceService;
import com.aid.assistant.creative.service.CreativeTextGenerationService;
import com.aid.assistant.creative.service.CreativePreparedTextIdentity;
import com.aid.billing.dto.BillingBudgetContext;
import com.aid.billing.dto.BillingBudgetViews;
import com.aid.billing.service.AssistantMediaBillingQuoteService;
import com.aid.billing.util.AssistantMediaQuoteDigest;
import com.aid.media.dto.MediaTextGenerateRequest;
import com.aid.media.util.MediaTaskPayloadSanitizer;
import com.aid.model.service.IAiModelBusinessService;
import com.aid.runtime.service.RuntimeToolRegistry;
import com.aid.runtime.assistant.admission.RuntimeOperationSubmissionClaim;
import com.aid.runtime.tool.RuntimeToolAvailabilityContext;
import com.aid.runtime.tool.RuntimeToolInvocation;
import com.aid.runtime.tool.RuntimeToolBillingIntent;
import com.aid.runtime.tool.RuntimeToolBudgetOffer;
import com.aid.runtime.tool.RuntimeToolPreparation;
import com.aid.runtime.tool.RuntimeToolPreparedInvocation;
import com.aid.runtime.tool.RuntimeToolProvider;
import com.aid.runtime.tool.RuntimeToolResult;
import com.aid.assistant.creative.service.impl.CreativeSourcePersistenceService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.List;

/** Public server-owned creative tools. The main Agent selects them dynamically from its frozen catalog. */
@Component
public class CreativeRuntimeToolProvider implements RuntimeToolProvider {
    public static final String SOURCE_INGEST = "creative.source.ingest";
    public static final String SOURCE_CHUNK_READ = "creative.source.chunk.read";
    public static final String STRATEGY_PLAN = "creative.strategy.plan";
    public static final String TEXT_SUBMIT = "creative.text.submit";
    private static final String VERSION = "1.3.0";

    private static final String SOURCE_INPUT = """
            {"type":"object","required":["sourceKind","sourceObjectId","contentHash"],"properties":{
              "sourceKind":{"const":"SCRIPT"},
              "sourceObjectId":{"type":"string","minLength":1,"maxLength":128},
              "sourceRevision":{"type":["string","null"],"maxLength":128},
              "contentHash":{"type":"string","pattern":"^[0-9a-f]{64}$"},
              "maxChunkChars":{"type":["integer","null"],"minimum":1000,"maximum":50000}
            },"additionalProperties":false}
            """;
    private static final String SOURCE_OUTPUT = """
            {"type":"object","required":["sourceRevisionId","sourceKind","sourceObjectId","sourceRevision","contentHash","charCount","wordCount","chunkCount","status"],"properties":{
              "sourceRevisionId":{"type":"string"},"sourceKind":{"type":"string"},
              "sourceObjectId":{"type":"string"},"sourceRevision":{"type":"string"},
              "contentHash":{"type":"string","pattern":"^[0-9a-f]{64}$"},
              "charCount":{"type":"integer"},"wordCount":{"type":"integer"},
              "chunkCount":{"type":"integer"},"status":{"type":"string"}
            },"additionalProperties":false}
            """;
    private static final String SOURCE_READ_INPUT = """
            {"type":"object","required":["sourceRevisionId"],"properties":{
              "sourceRevisionId":{"type":"string","pattern":"^[1-9][0-9]{0,18}$"},
              "afterChunkIndex":{"type":["integer","null"],"minimum":-1},
              "limit":{"type":["integer","null"],"minimum":1,"maximum":100}
            },"additionalProperties":false}
            """;
    private static final String SOURCE_READ_OUTPUT = """
            {"type":"object","required":["sourceRevisionId","sourceKind","sourceObjectId","sourceRevision","contentHash","chunkCount","nextAfterChunkIndex","complete","chunks"],"properties":{
              "sourceRevisionId":{"type":"string"},"sourceKind":{"type":"string"},
              "sourceObjectId":{"type":"string"},"sourceRevision":{"type":"string"},
              "contentHash":{"type":"string","pattern":"^[0-9a-f]{64}$"},
              "chunkCount":{"type":"integer","minimum":0},
              "nextAfterChunkIndex":{"type":"integer","minimum":-1},
              "complete":{"type":"boolean"},
              "chunks":{"type":"array","items":{"type":"object","required":["chunkIndex","charStart","charEnd","charCount","wordCount","contentHash","content"],"properties":{
                "chunkIndex":{"type":"integer","minimum":0},"charStart":{"type":"integer","minimum":0},
                "charEnd":{"type":"integer","minimum":0},"charCount":{"type":"integer","minimum":0},
                "wordCount":{"type":"integer","minimum":0},"locator":{"type":["object","null"]},
                "contentHash":{"type":"string","pattern":"^[0-9a-f]{64}$"},"content":{"type":"string"}
              },"additionalProperties":false}}
            },"additionalProperties":false}
            """;
    private static final String STRATEGY_INPUT = """
            {"type":"object","required":["targetWordCount"],"properties":{
              "mode":{"type":["string","null"],"enum":["CRAFT","ADAPT","REVIEW",null]},
              "targetFormat":{"type":["string","null"],"maxLength":40},
              "targetWordCount":{"type":"integer","minimum":1,"maximum":10000000},
              "episodeCount":{"type":["integer","null"],"minimum":1,"maximum":10000},
              "sourceCharCount":{"type":["integer","null"],"minimum":0},
              "constraints":{"type":"array","maxItems":100,"items":{"type":"string","maxLength":1000}}
            },"additionalProperties":false}
            """;
    private static final String STRATEGY_OUTPUT = """
            {"type":"object","required":["mode","targetFormat","targetWordCount","episodeCount","wordsPerEpisode","episodeRanges","checkpoints"],"properties":{
              "mode":{"type":"string"},"targetFormat":{"type":"string"},
              "targetWordCount":{"type":"integer"},"episodeCount":{"type":"integer"},
              "wordsPerEpisode":{"type":"integer"},"episodeRanges":{"type":"array"},
              "checkpoints":{"type":"array","items":{"type":"string"}}
            },"additionalProperties":false}
            """;
    private static final String TEXT_INPUT = """
            {"type":"object","required":["modelCode","prompt","logicalArtifactId","artifactType","sources"],"properties":{
              "modelCode":{"type":"string","minLength":1,"maxLength":100},
              "prompt":{"type":"string","minLength":1,"maxLength":100000},
              "logicalArtifactId":{"type":"string","pattern":"^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$"},
              "artifactType":{"enum":["CREATIVE_TEXT","OUTLINE","SCREENPLAY","REVIEW_REPORT"]},
              "targetArtifactId":{"anyOf":[{"type":"string","pattern":"^[1-9][0-9]{0,18}$"},{"type":"null"}]},
              "sources":{"type":"array","maxItems":1000,"items":{"type":"object","required":["sourceRevisionId","purpose"],"properties":{
                "sourceRevisionId":{"type":"string","pattern":"^[1-9][0-9]{0,18}$"},"purpose":{"type":"string","minLength":1,"maxLength":40}
              },"additionalProperties":false}},
              "maxOutputTokens":{"type":["integer","null"],"minimum":1,"maximum":200000},
              "temperature":{"type":["number","null"],"minimum":0,"maximum":2}
            },"additionalProperties":false}
            """;
    private static final String TEXT_OUTPUT = """
            {"type":"object","required":["mediaTaskId","status","modelCode","logicalArtifactId","artifactType"],"properties":{
              "mediaTaskId":{"type":"string"},"status":{"type":"string"},"modelCode":{"type":"string"},
              "logicalArtifactId":{"type":"string"},"artifactType":{"type":"string"},
              "targetArtifactId":{"type":["string","null"]}
            },"additionalProperties":false}
            """;

    @Resource private CreativeSourceService sourceService;
    @Resource private CreativePlanningService planningService;
    @Resource private CreativeTextGenerationService textGenerationService;
    @Resource private AssistantMediaBillingQuoteService mediaBillingQuoteService;
    @Resource private IAiModelBusinessService modelBusinessService;

    @Override
    public List<RuntimeToolRegistry.Descriptor> descriptors() {
        return List.of(
                descriptor(SOURCE_INGEST, "Ingest creative source",
                        "Authorize and snapshot an immutable long-form source revision.",
                        SOURCE_INPUT, SOURCE_OUTPUT, "DATABASE_WRITE", false, "REQUEST_DIGEST", "REPLAY_SAFE"),
                descriptor(SOURCE_CHUNK_READ, "Read creative source chunks",
                        "Read a bounded page from an authorized immutable source revision.",
                        SOURCE_READ_INPUT, SOURCE_READ_OUTPUT, "READ", false, "REQUEST_DIGEST", "REPLAY_SAFE"),
                descriptor(STRATEGY_PLAN, "Plan creative length",
                        "Calculate optional word and episode allocation hints without forcing a workflow.",
                        STRATEGY_INPUT, STRATEGY_OUTPUT, "PURE", false, "REQUEST_DIGEST", "REPLAY_SAFE"),
                descriptor(TEXT_SUBMIT, "Submit creative text",
                        "Submit one authorized creative text step through unified media billing.",
                        TEXT_INPUT, TEXT_OUTPUT, "BILLABLE", true, "IDEMPOTENCY_KEY", "RECONCILE_TASK")
        );
    }

    @Override
    public RuntimeToolBudgetOffer budgetOffer(RuntimeToolAvailabilityContext context,
                                              RuntimeToolRegistry.Descriptor descriptor) {
        if (context == null || context.getUserId() == null || context.getUserId() <= 0
                || descriptor == null || !TEXT_SUBMIT.equals(descriptor.code())) {
            return RuntimeToolProvider.super.budgetOffer(context, descriptor);
        }
        List<String> modelCodes;
        try {
            modelCodes = modelBusinessService.listAvailableModelsByFuncCode(
                            CreativeTextGenerationService.BUSINESS_FUNC_CODE).stream()
                    .filter(java.util.Objects::nonNull)
                    .filter(model -> model.getBilling() != null)
                    .filter(model -> "text".equalsIgnoreCase(model.getModelType()))
                    .filter(model -> model.getCapabilityCode() != null
                            && model.getCapabilityCode().matches("[a-z][a-z0-9_-]{0,95}"))
                    .map(com.aid.model.vo.AiModelVO::getModelCode)
                    .filter(code -> code != null && !code.isBlank())
                    .distinct().toList();
        } catch (RuntimeException unavailable) {
            modelCodes = List.of();
        }
        return new RuntimeToolBudgetOffer(modelCodes, List.of("text.chat"), List.of("TEXT"));
    }

    @Override
    public RuntimeToolResult execute(RuntimeToolInvocation invocation) {
        return switch (invocation.getToolCode()) {
            case SOURCE_INGEST -> throw new IllegalStateException(
                    "Creative source ingestion requires prepared execution");
            case SOURCE_CHUNK_READ -> RuntimeToolResult.succeeded(JSON.toJSONString(sourceService.read(
                    invocation.getUserId(),
                    JSON.parseObject(invocation.getArgumentsJson(), CreativeRequests.SourceChunkRead.class),
                    invocation.getContextManifestJson())));
            case STRATEGY_PLAN -> RuntimeToolResult.succeeded(JSON.toJSONString(planningService.plan(
                    JSON.parseObject(invocation.getArgumentsJson(), CreativeRequests.Strategy.class))));
            case TEXT_SUBMIT -> throw new IllegalStateException(
                    "Billable creative text requires prepared execution");
            default -> throw new IllegalArgumentException("Unsupported creative tool");
        };
    }

    @Override
    public RuntimeToolPreparation prepare(RuntimeToolInvocation invocation) {
        if (!TEXT_SUBMIT.equals(invocation.getToolCode())) {
            return RuntimeToolProvider.super.prepare(invocation);
        }
        CreativeRequests.TextSubmit request = JSON.parseObject(
                invocation.getArgumentsJson(), CreativeRequests.TextSubmit.class);
        request.setResolvedCapabilityCode(resolveCatalogTextCapability(request.getModelCode()));
        CreativeRequests.PreparedTextSubmit payload = textGenerationService.prepareRequest(invocation, request);
        MediaTextGenerateRequest mediaRequest = payload.getMediaRequest();
        BillingBudgetViews.TextQuoteView quote = mediaBillingQuoteService.quoteText(mediaRequest);
        if (quote == null || !quote.isHardUpperBoundAvailable() || quote.getNormalizedRequest() == null
                || quote.getQuotedUpperBound() == null || quote.getQuoteId() == null
                || quote.getRequestDigest() == null || quote.getQuoteDigest() == null
                || quote.getExpiresAt() == null) {
            throw new IllegalStateException("Creative text billing quote is unavailable");
        }
       String normalizedRequestJson = MediaTaskPayloadSanitizer.serializeRequest(quote.getNormalizedRequest());
       String modelInvocationIdentity = quote.getNormalizedRequest().getInvocationIdentity();
       if (!java.util.Objects.equals(mediaRequest.getPrompt(), quote.getNormalizedRequest().getPrompt())
                || !java.util.Objects.equals(payload.getSubmissionPromptDigest(),
                SecureUtil.sha256(quote.getNormalizedRequest().getPrompt()))
               || !java.util.Objects.equals(request.getResolvedCapabilityCode(),
               quote.getNormalizedRequest().getCapabilityCode())
               || modelInvocationIdentity == null || !modelInvocationIdentity.matches("[0-9a-f]{64}")
               || !java.util.Objects.equals(quote.getNormalizedRequest().getUserId(), invocation.getUserId())
               || !java.util.Objects.equals(quote.getNormalizedRequest().getBizTaskId(),
               invocation.getRuntimeStepId())
               || !BillingBudgetContext.ASSISTANT_BIZ_TASK_TYPE.equals(
               quote.getNormalizedRequest().getBizTaskType())
               || !CreativeTextGenerationService.BUSINESS_FUNC_CODE.equals(
               quote.getNormalizedRequest().getBusinessFuncCode())
               || !java.util.Objects.equals(quote.getNormalizedRequest().getCallId(),
               invocation.getIdempotencyKey())
               || !java.util.Objects.equals(quote.getNormalizedRequest().getCallIdentity(),
               "creative:" + invocation.getRunId() + ":" + invocation.getRuntimeStepId()
                       + ":" + invocation.getAttemptNo())
               || !java.util.Objects.equals(quote.getRequestDigest(),
               AssistantMediaQuoteDigest.requestDigestFromJson(normalizedRequestJson))) {
            throw new IllegalStateException("Creative text quote changed the frozen request");
        }
        requirePreparationScope(invocation.getOperationScope());
        AssistantBudgetOperationScope scope = JSON.parseObject(
                JSON.toJSONString(invocation.getOperationScope()), AssistantBudgetOperationScope.class);
        scope.setModelCode(quote.getModelCode());
        scope.setCapabilityCodes(List.of("text.chat"));
        scope.setMediaTypes(List.of("TEXT"));
        scope.setTargetRefs(payload.getTargetRefs());
        scope.setQuotedUpperBound(quote.getQuotedUpperBound());

        payload.setMediaRequest(quote.getNormalizedRequest());
        payload.setModelInvocationIdentity(modelInvocationIdentity);
        payload.setNormalizedRequestDigest(quote.getRequestDigest());
        RuntimeToolBillingIntent intent = new RuntimeToolBillingIntent();
        intent.setMediaType("TEXT");
        intent.setNormalizedRequestJson(normalizedRequestJson);
        intent.setRequestDigest(quote.getRequestDigest());
        intent.setQuoteId(quote.getQuoteId());
        intent.setQuoteDigest(quote.getQuoteDigest());
        intent.setQuoteExpiresAt(quote.getExpiresAt());
        intent.setQuotedUpperBound(quote.getQuotedUpperBound());
        payload.setServerIdentityDigest(CreativePreparedTextIdentity.digest(
                invocation.getUserId(), invocation.getRunId(), invocation.getRuntimeStepId(),
                invocation.getStableStepId(), invocation.getAttemptNo(), invocation.getIdempotencyKey(),
                modelInvocationIdentity, payload.getSubmissionPromptDigest(), intent, scope));
        return new RuntimeToolPreparation(JSON.toJSONString(payload), intent, scope);
    }

    @Override
    public RuntimeToolResult executePrepared(RuntimeToolPreparedInvocation prepared,
                                             BillingBudgetContext billingBudgetContext) {
        if (SOURCE_INGEST.equals(prepared.getToolCode())) {
            try {
                return RuntimeToolResult.succeeded(JSON.toJSONString(sourceService.ingest(
                        prepared.getUserId(),
                        JSON.parseObject(prepared.getPreparedPayloadJson(), CreativeRequests.SourceIngest.class),
                        prepared.getContextManifestJson(), submissionClaim(prepared))));
            } catch (CreativeSourcePersistenceService.ReceiptMissingException missing) {
                return RuntimeToolResult.unknown("CREATIVE_SOURCE_RECEIPT_MISSING",
                        "Creative source ingestion receipt is unavailable");
            }
        }
        if (!TEXT_SUBMIT.equals(prepared.getToolCode())) {
            return RuntimeToolProvider.super.executePrepared(prepared, billingBudgetContext);
        }
        CreativeRequests.PreparedTextSubmit payload = JSON.parseObject(
                prepared.getPreparedPayloadJson(), CreativeRequests.PreparedTextSubmit.class);
        return textGenerationService.submitPrepared(prepared, payload, billingBudgetContext);
    }

    private RuntimeOperationSubmissionClaim submissionClaim(RuntimeToolPreparedInvocation prepared) {
        RuntimeOperationSubmissionClaim claim = new RuntimeOperationSubmissionClaim();
        claim.setRunId(prepared.getRunId());
        claim.setUserId(prepared.getUserId());
        claim.setStepId(prepared.getRuntimeStepId());
        claim.setApprovalId(prepared.getApprovalId());
        // 提交身份匹配运行步骤类型；具体创作操作仍由冻结审批范围校验。
        claim.setOperationKind("ASSISTANT_TOOL");
        claim.setIdempotencyKey(prepared.getIdempotencyKey());
        claim.setRunLease(prepared.getRunLease());
        claim.setStepLease(prepared.getStepLease());
        return claim;
    }

    private void requirePreparationScope(AssistantBudgetOperationScope scope) {
        if (scope == null || !TEXT_SUBMIT.equals(scope.getToolCode())) {
            throw new IllegalArgumentException("Creative text operation scope is invalid");
        }
    }

    private String resolveCatalogTextCapability(String modelCode) {
        if (StrUtil.isBlank(modelCode)) {
            throw new com.aid.runtime.tool.RuntimeToolPreparationRejectedException(
                    com.aid.runtime.tool.RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_MODEL_REQUIRED);
        }
        List<com.aid.model.vo.AiModelVO> available = modelBusinessService.listAvailableModelsByFuncCode(
                CreativeTextGenerationService.BUSINESS_FUNC_CODE);
        if (available == null) available = List.of();
        List<com.aid.model.vo.AiModelVO> matches = available.stream()
                .filter(java.util.Objects::nonNull)
                .filter(model -> modelCode.trim().equals(model.getModelCode()))
                .toList();
        if (matches.size() != 1) {
            throw new com.aid.runtime.tool.RuntimeToolPreparationRejectedException(
                    com.aid.runtime.tool.RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_MODEL_UNAVAILABLE);
        }
        com.aid.model.vo.AiModelVO match = matches.get(0);
        String capabilityCode = match.getCapabilityCode();
        if (match.getBilling() == null || !"text".equalsIgnoreCase(match.getModelType())
                || capabilityCode == null || !capabilityCode.matches("[a-z][a-z0-9_-]{0,95}")) {
            throw new com.aid.runtime.tool.RuntimeToolPreparationRejectedException(
                    com.aid.runtime.tool.RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_MODEL_CAPABILITY_INVALID);
        }
        return capabilityCode;
    }

    private RuntimeToolRegistry.Descriptor descriptor(String code, String name, String description,
                                                       String input, String output, String sideEffect,
                                                       boolean billable, String idempotency, String recovery) {
        return new RuntimeToolRegistry.Descriptor(code, VERSION, SecureUtil.sha256(input + "\n" + output),
                name, description, input, output, sideEffect, billable, idempotency,
                false, recovery, billable ? List.of("text.chat", "TEXT") : List.of());
    }
}
