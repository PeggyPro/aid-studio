package com.aid.assistant.creative.tool;

import cn.hutool.crypto.SecureUtil;
import com.alibaba.fastjson2.JSON;
import com.aid.assistant.budget.AssistantBudgetOperationScope;
import com.aid.assistant.creative.dto.CreativeAuditRequests;
import com.aid.assistant.creative.service.CreativeArtifactChunkReadService;
import com.aid.assistant.creative.service.CreativeCoveragePersistenceService;
import com.aid.billing.dto.BillingBudgetContext;
import com.aid.runtime.assistant.admission.RuntimeOperationSubmissionClaim;
import com.aid.runtime.service.RuntimeToolRegistry;
import com.aid.runtime.tool.RuntimeToolInvocation;
import com.aid.runtime.tool.RuntimeToolPreparation;
import com.aid.runtime.tool.RuntimeToolPreparedInvocation;
import com.aid.runtime.tool.RuntimeToolProvider;
import com.aid.runtime.tool.RuntimeToolResult;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.List;

/** Auditable coverage mutation and bounded artifact reading tools. */
@Component
public class CreativeAuditRuntimeToolProvider implements RuntimeToolProvider {
    public static final String SOURCE_COVERAGE_MARK = "creative.source.coverage.mark";
    public static final String ARTIFACT_CHUNK_READ = "creative.artifact.chunk.read";
    private static final String VERSION = "1.0.0";

    private static final String COVERAGE_INPUT = """
            {"type":"object","required":["sourceRevisionId","chunkIndex","purpose","coverageStatus","expectedRevision"],"properties":{
              "sourceRevisionId":{"type":"string","pattern":"^[1-9][0-9]{0,18}$"},
              "chunkIndex":{"type":"integer","minimum":0},
              "purpose":{"type":"string","minLength":1,"maxLength":40},
              "coverageStatus":{"enum":["PENDING","READ","USED","SKIPPED","FAILED"]},
              "evidenceArtifactId":{"anyOf":[{"type":"string","pattern":"^[1-9][0-9]{0,18}$"},{"type":"null"}]},
              "expectedRevision":{"type":"integer","minimum":0,"maximum":9223372036854775806},
              "notes":{"type":["string","null"],"maxLength":1000}
            },"additionalProperties":false}
            """;
    private static final String COVERAGE_OUTPUT = """
            {"type":"object","required":["coverageId","sourceRevisionId","chunkIndex","purpose","coverageStatus","evidenceArtifactId","revision","notes"],"properties":{
              "coverageId":{"type":"string","pattern":"^[1-9][0-9]{0,18}$"},
              "sourceRevisionId":{"type":"string","pattern":"^[1-9][0-9]{0,18}$"},
              "chunkIndex":{"type":"integer","minimum":0},
              "purpose":{"type":"string","minLength":1,"maxLength":40},
              "coverageStatus":{"enum":["PENDING","READ","USED","SKIPPED","FAILED"]},
              "evidenceArtifactId":{"anyOf":[{"type":"string","pattern":"^[1-9][0-9]{0,18}$"},{"type":"null"}]},
              "revision":{"type":"integer","minimum":0},
              "notes":{"type":["string","null"],"maxLength":1000}
            },"additionalProperties":false}
            """;
    private static final String ARTIFACT_READ_INPUT = """
            {"type":"object","required":["artifactId"],"properties":{
              "artifactId":{"type":"string","pattern":"^[1-9][0-9]{0,18}$"},
              "afterChunkIndex":{"type":["integer","null"],"minimum":-1},
              "limit":{"type":["integer","null"],"minimum":1,"maximum":100}
            },"additionalProperties":false}
            """;
    private static final String ARTIFACT_READ_OUTPUT = """
            {"type":"object","required":["artifactId","artifactRef","artifactType","revision","contentHash","chunkCount","nextAfterChunkIndex","complete","chunks"],"properties":{
              "artifactId":{"type":"string","pattern":"^[1-9][0-9]{0,18}$"},
              "artifactRef":{"type":"string","pattern":"^artifact:[A-Za-z0-9][A-Za-z0-9._:-]{0,63}:[1-9][0-9]{0,18}$"},
              "artifactType":{"enum":["CREATIVE_TEXT","OUTLINE","SCREENPLAY","REVIEW_REPORT"]},
              "revision":{"type":"integer","minimum":1},
              "contentHash":{"type":"string","pattern":"^[0-9a-f]{64}$"},
              "chunkCount":{"type":"integer","minimum":1},
              "nextAfterChunkIndex":{"type":"integer","minimum":-1},
              "complete":{"type":"boolean"},
              "chunks":{"type":"array","maxItems":100,"items":{"type":"object","required":["chunkIndex","charStart","charEnd","charCount","wordCount","contentHash","content"],"properties":{
                "chunkIndex":{"type":"integer","minimum":0},"charStart":{"type":"integer","minimum":0},
                "charEnd":{"type":"integer","minimum":0},"charCount":{"type":"integer","minimum":0},
                "wordCount":{"type":"integer","minimum":0},
                "contentHash":{"type":"string","pattern":"^[0-9a-f]{64}$"},
                "content":{"type":"string","maxLength":100000}
              },"additionalProperties":false}}
            },"additionalProperties":false}
            """;

    @Resource private CreativeCoveragePersistenceService coverageService;
    @Resource private CreativeArtifactChunkReadService artifactReadService;

    @Override
    public List<RuntimeToolRegistry.Descriptor> descriptors() {
        return List.of(
                descriptor(SOURCE_COVERAGE_MARK, "Mark creative source coverage",
                        "CAS-update one authorized source chunk coverage receipt.",
                        COVERAGE_INPUT, COVERAGE_OUTPUT, "DATABASE_WRITE", "IDEMPOTENCY_KEY", List.of()),
                descriptor(ARTIFACT_CHUNK_READ, "Read creative artifact chunks",
                        "Read a bounded verified page from an artifact frozen as a plan input.",
                        ARTIFACT_READ_INPUT, ARTIFACT_READ_OUTPUT, "READ", "REQUEST_DIGEST", List.of("TEXT"))
        );
    }

    @Override
    public RuntimeToolPreparation prepare(RuntimeToolInvocation invocation) {
        if (SOURCE_COVERAGE_MARK.equals(invocation.getToolCode())) {
            CreativeCoveragePersistenceService.PreparedCoverage prepared = coverageService.prepare(invocation,
                    JSON.parseObject(invocation.getArgumentsJson(), CreativeAuditRequests.CoverageMark.class));
            AssistantBudgetOperationScope scope = JSON.parseObject(
                    JSON.toJSONString(invocation.getOperationScope()), AssistantBudgetOperationScope.class);
            scope.setTargetRefs(List.of(prepared.targetRef()));
            return new RuntimeToolPreparation(JSON.toJSONString(prepared.payload()), null, scope);
        }
        if (ARTIFACT_CHUNK_READ.equals(invocation.getToolCode())) {
            return RuntimeToolProvider.super.prepare(invocation);
        }
        throw new IllegalArgumentException("Unsupported creative audit tool");
    }

    @Override
    public RuntimeToolResult execute(RuntimeToolInvocation invocation) {
        throw new IllegalStateException("Creative audit tools require prepared execution");
    }

    @Override
    public RuntimeToolResult executePrepared(RuntimeToolPreparedInvocation prepared,
                                             BillingBudgetContext billingBudgetContext) {
        if (billingBudgetContext != null) {
            throw new IllegalArgumentException("Creative audit tools are not billable");
        }
        if (SOURCE_COVERAGE_MARK.equals(prepared.getToolCode())) {
            try {
                return RuntimeToolResult.succeeded(JSON.toJSONString(coverageService.persist(prepared,
                        JSON.parseObject(prepared.getPreparedPayloadJson(),
                                CreativeAuditRequests.PreparedCoverageMark.class), submissionClaim(prepared))));
            } catch (CreativeCoveragePersistenceService.ReceiptMissingException missing) {
                return RuntimeToolResult.unknown("CREATIVE_COVERAGE_RECEIPT_MISSING",
                        "Creative source coverage receipt is unavailable");
            }
        }
        if (ARTIFACT_CHUNK_READ.equals(prepared.getToolCode())) {
            return RuntimeToolResult.succeeded(JSON.toJSONString(artifactReadService.read(prepared,
                    JSON.parseObject(prepared.getPreparedPayloadJson(),
                            CreativeAuditRequests.ArtifactChunkRead.class))));
        }
        throw new IllegalArgumentException("Unsupported creative audit tool");
    }

    @Override
    public RuntimeToolResult reconcilePrepared(RuntimeToolPreparedInvocation prepared) {
        if (SOURCE_COVERAGE_MARK.equals(prepared.getToolCode())) {
            return executePrepared(prepared, null);
        }
        return RuntimeToolProvider.super.reconcilePrepared(prepared);
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

    private RuntimeToolRegistry.Descriptor descriptor(String code, String name, String description,
                                                       String input, String output, String sideEffect,
                                                       String idempotency, List<String> capabilities) {
        return new RuntimeToolRegistry.Descriptor(code, VERSION, SecureUtil.sha256(input + "\n" + output),
                name, description, input, output, sideEffect, false, idempotency,
                false, "REPLAY_SAFE", capabilities);
    }
}
