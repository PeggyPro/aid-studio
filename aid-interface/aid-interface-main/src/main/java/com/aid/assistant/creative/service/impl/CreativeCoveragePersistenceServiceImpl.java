package com.aid.assistant.creative.service.impl;

import cn.hutool.core.util.StrUtil;
import com.aid.assistant.artifact.domain.AidRuntimeArtifact;
import com.aid.assistant.artifact.domain.AidRuntimeSourceChunk;
import com.aid.assistant.artifact.domain.AidRuntimeSourceCoverage;
import com.aid.assistant.artifact.domain.AidRuntimeSourceRevision;
import com.aid.assistant.artifact.mapper.AidRuntimeSourceChunkMapper;
import com.aid.assistant.artifact.mapper.AidRuntimeSourceCoverageMapper;
import com.aid.assistant.artifact.mapper.AidRuntimeSourceRevisionMapper;
import com.aid.assistant.creative.dto.CreativeAuditRequests;
import com.aid.assistant.creative.service.CreativeArtifactInputAuthorizer;
import com.aid.assistant.creative.service.CreativeAuditService;
import com.aid.assistant.creative.service.CreativeContextAuthorizer;
import com.aid.assistant.creative.service.CreativeCoveragePersistenceService;
import com.aid.assistant.creative.vo.CreativeViews;
import com.aid.runtime.assistant.admission.RuntimeOperationSubmissionClaim;
import com.aid.runtime.assistant.admission.RuntimeOperationSubmissionDecision;
import com.aid.runtime.assistant.admission.RuntimeOperationSubmissionGuard;
import com.aid.runtime.tool.RuntimeToolInvocation;
import com.aid.runtime.tool.RuntimeToolPreparedInvocation;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Keeps coverage idempotency, fencing and the coverage CAS in one transaction. */
@Service
public class CreativeCoveragePersistenceServiceImpl implements CreativeCoveragePersistenceService {
    private static final Set<String> COVERAGE = Set.of("PENDING", "READ", "USED", "SKIPPED", "FAILED");

    @Resource private RuntimeOperationSubmissionGuard submissionGuard;
    @Resource private CreativeAuditService auditService;
    @Resource private CreativeContextAuthorizer contextAuthorizer;
    @Resource private CreativeArtifactInputAuthorizer artifactInputAuthorizer;
    @Resource private AidRuntimeSourceRevisionMapper sourceRevisionMapper;
    @Resource private AidRuntimeSourceChunkMapper sourceChunkMapper;
    @Resource private AidRuntimeSourceCoverageMapper coverageMapper;

    @Override
    public PreparedCoverage prepare(RuntimeToolInvocation invocation, CreativeAuditRequests.CoverageMark raw) {
        if (invocation == null || invocation.getUserId() == null || invocation.getUserId() < 1
                || invocation.getRunId() == null || invocation.getRuntimeStepId() == null || raw == null) {
            invalid("request");
        }
        validate(raw);
        AidRuntimeSourceRevision source = sourceRevisionMapper.selectAuthorized(
                invocation.getUserId(), raw.getSourceRevisionId());
        AidRuntimeSourceChunk chunk = sourceChunkMapper.selectAuthorizedChunk(
                invocation.getUserId(), raw.getSourceRevisionId(), raw.getChunkIndex());
        if (source == null || chunk == null || !"ACTIVE".equals(source.getStatus())) invalid("sourceRevisionId");
        CreativeContextAuthorizer.AuthorizedSourceRef authorized = contextAuthorizer.requireRead(
                invocation.getContextManifestJson(), source.getSourceKind(),
                source.getSourceObjectId(), source.getContentHash());

        AidRuntimeArtifact evidence = raw.getEvidenceArtifactId() == null ? null
                : artifactInputAuthorizer.requireTextInput(invocation, raw.getEvidenceArtifactId());
        CreativeAuditRequests.CoverageMark request = copy(raw);
        request.setRunId(invocation.getRunId());
        request.setStepId(invocation.getRuntimeStepId());

        List<AidRuntimeSourceCoverage> rows = find(request);
        if (rows.size() > 1) invalid("coverage identity");
        long expectedResult;
        boolean alreadyApplied = false;
        if (rows.isEmpty()) {
            if (request.getExpectedRevision() != 0L) invalid("expectedRevision");
            expectedResult = 0L;
        } else {
            AidRuntimeSourceCoverage current = rows.get(0);
            if (Objects.equals(current.getRevision(), request.getExpectedRevision())) {
                expectedResult = Math.addExact(request.getExpectedRevision(), 1L);
            } else if (Objects.equals(current.getRevision(), Math.addExact(request.getExpectedRevision(), 1L))
                    && sameDesiredState(current, request)) {
                expectedResult = current.getRevision();
                alreadyApplied = true;
            } else {
                invalid("expectedRevision");
                return null;
            }
        }
        CreativeAuditRequests.PreparedCoverageMark payload = new CreativeAuditRequests.PreparedCoverageMark();
        payload.setRequest(request);
        payload.setSourceKind(source.getSourceKind());
        payload.setSourceObjectId(source.getSourceObjectId());
        payload.setSourceContentHash(source.getContentHash());
        payload.setEvidenceArtifactRef(evidence == null ? null : ref(evidence));
        payload.setEvidenceContentHash(evidence == null ? null : evidence.getContentHash());
        payload.setExpectedResultRevision(expectedResult);
        payload.setAlreadyApplied(alreadyApplied);
        return new PreparedCoverage(payload, authorized.targetRef());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CreativeViews.CoverageReceipt persist(RuntimeToolPreparedInvocation invocation,
                                                 CreativeAuditRequests.PreparedCoverageMark prepared,
                                                 RuntimeOperationSubmissionClaim claim) {
        // The claim is deliberately the first database action in this mutation transaction.
        RuntimeOperationSubmissionDecision decision = submissionGuard.claim(claim);
        requirePrepared(invocation, prepared);
        if (decision == RuntimeOperationSubmissionDecision.RECONCILE_ONLY || prepared.isAlreadyApplied()) {
            return receiptOrMissing(prepared);
        }
        Long revision = auditService.markCoverage(prepared.getRequest(),
                invocation.getRunLease(), invocation.getStepLease());
        if (!Objects.equals(revision, prepared.getExpectedResultRevision())) {
            throw new IllegalStateException("Creative coverage CAS returned an unexpected revision");
        }
        return receiptOrMissing(prepared);
    }

    private void requirePrepared(RuntimeToolPreparedInvocation invocation,
                                 CreativeAuditRequests.PreparedCoverageMark prepared) {
        if (invocation == null || prepared == null || prepared.getRequest() == null
                || !Objects.equals(prepared.getRequest().getRunId(), invocation.getRunId())
                || !Objects.equals(prepared.getRequest().getStepId(), invocation.getRuntimeStepId())
                || prepared.getExpectedResultRevision() == null || prepared.getExpectedResultRevision() < 0) {
            throw new IllegalArgumentException("Creative coverage prepared identity is invalid");
        }
        validate(prepared.getRequest());
        AidRuntimeSourceRevision source = sourceRevisionMapper.selectAuthorized(
                invocation.getUserId(), prepared.getRequest().getSourceRevisionId());
        AidRuntimeSourceChunk chunk = sourceChunkMapper.selectAuthorizedChunk(invocation.getUserId(),
                prepared.getRequest().getSourceRevisionId(), prepared.getRequest().getChunkIndex());
        if (source == null || chunk == null || !"ACTIVE".equals(source.getStatus())
                || !Objects.equals(source.getSourceKind(), prepared.getSourceKind())
                || !Objects.equals(source.getSourceObjectId(), prepared.getSourceObjectId())
                || !Objects.equals(source.getContentHash(), prepared.getSourceContentHash())) {
            throw new IllegalArgumentException("Creative coverage source identity changed");
        }
        if (prepared.getRequest().getEvidenceArtifactId() == null) {
            if (prepared.getEvidenceArtifactRef() != null || prepared.getEvidenceContentHash() != null) {
                throw new IllegalArgumentException("Creative coverage evidence identity changed");
            }
        } else {
            AidRuntimeArtifact evidence = artifactInputAuthorizer.requireTextInput(
                    invocation, prepared.getRequest().getEvidenceArtifactId());
            if (!Objects.equals(ref(evidence), prepared.getEvidenceArtifactRef())
                    || !Objects.equals(evidence.getContentHash(), prepared.getEvidenceContentHash())) {
                throw new IllegalArgumentException("Creative coverage evidence identity changed");
            }
        }
    }

    private CreativeViews.CoverageReceipt receiptOrMissing(CreativeAuditRequests.PreparedCoverageMark prepared) {
        List<AidRuntimeSourceCoverage> rows = find(prepared.getRequest());
        if (rows.size() != 1 || !matchesReceipt(rows.get(0), prepared)) {
            throw new CreativeCoveragePersistenceService.ReceiptMissingException();
        }
        AidRuntimeSourceCoverage row = rows.get(0);
        CreativeViews.CoverageReceipt result = new CreativeViews.CoverageReceipt();
        result.setCoverageId(String.valueOf(row.getId()));
        result.setSourceRevisionId(String.valueOf(row.getSourceRevisionId()));
        result.setChunkIndex(row.getChunkIndex());
        result.setPurpose(row.getPurpose());
        result.setCoverageStatus(row.getCoverageStatus());
        result.setEvidenceArtifactId(row.getEvidenceArtifactId() == null
                ? null : String.valueOf(row.getEvidenceArtifactId()));
        result.setRevision(row.getRevision());
        result.setNotes(row.getNotes());
        return result;
    }

    private List<AidRuntimeSourceCoverage> find(CreativeAuditRequests.CoverageMark request) {
        return coverageMapper.selectList(new LambdaQueryWrapper<AidRuntimeSourceCoverage>()
                .eq(AidRuntimeSourceCoverage::getRunId, request.getRunId())
                .eq(AidRuntimeSourceCoverage::getSourceRevisionId, request.getSourceRevisionId())
                .eq(AidRuntimeSourceCoverage::getChunkIndex, request.getChunkIndex())
                .eq(AidRuntimeSourceCoverage::getPurpose, request.getPurpose())
                .eq(AidRuntimeSourceCoverage::getDelFlag, "0"));
    }

    private boolean matchesReceipt(AidRuntimeSourceCoverage row,
                                   CreativeAuditRequests.PreparedCoverageMark prepared) {
        return row != null && row.getId() != null && row.getId() > 0
                && "ACTIVE".equals(row.getStatus()) && "0".equals(row.getDelFlag())
                && Objects.equals(row.getRevision(), prepared.getExpectedResultRevision())
                && sameDesiredState(row, prepared.getRequest());
    }

    private boolean sameDesiredState(AidRuntimeSourceCoverage row, CreativeAuditRequests.CoverageMark request) {
        return Objects.equals(row.getRunId(), request.getRunId())
                && Objects.equals(row.getStepId(), request.getStepId())
                && Objects.equals(row.getSourceRevisionId(), request.getSourceRevisionId())
                && Objects.equals(row.getChunkIndex(), request.getChunkIndex())
                && Objects.equals(row.getPurpose(), request.getPurpose())
                && Objects.equals(row.getCoverageStatus(), request.getCoverageStatus())
                && Objects.equals(row.getEvidenceArtifactId(), request.getEvidenceArtifactId())
                && Objects.equals(row.getNotes(), request.getNotes());
    }

    private CreativeAuditRequests.CoverageMark copy(CreativeAuditRequests.CoverageMark source) {
        CreativeAuditRequests.CoverageMark result = new CreativeAuditRequests.CoverageMark();
        result.setSourceRevisionId(source.getSourceRevisionId());
        result.setChunkIndex(source.getChunkIndex());
        result.setPurpose(source.getPurpose().trim());
        result.setCoverageStatus(source.getCoverageStatus());
        result.setEvidenceArtifactId(source.getEvidenceArtifactId());
        result.setExpectedRevision(source.getExpectedRevision());
        result.setNotes(source.getNotes());
        return result;
    }

    private void validate(CreativeAuditRequests.CoverageMark request) {
        if (request.getSourceRevisionId() == null || request.getSourceRevisionId() < 1
                || request.getChunkIndex() == null || request.getChunkIndex() < 0
                || StrUtil.isBlank(request.getPurpose()) || request.getPurpose().length() > 40
                || !COVERAGE.contains(request.getCoverageStatus())
                || request.getExpectedRevision() == null || request.getExpectedRevision() < 0
                || request.getNotes() != null && request.getNotes().length() > 1000) invalid("request");
    }

    private String ref(AidRuntimeArtifact artifact) {
        return "artifact:" + artifact.getLogicalArtifactId() + ":" + artifact.getRevision();
    }

    private void invalid(String field) { throw new IllegalArgumentException("Invalid creative coverage " + field); }
}
