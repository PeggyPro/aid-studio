package com.aid.assistant.creative.service.impl;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.aid.assistant.artifact.domain.AidRuntimeArtifact;
import com.aid.assistant.artifact.domain.AidRuntimeArtifactReview;
import com.aid.assistant.artifact.domain.AidRuntimeSourceChunk;
import com.aid.assistant.artifact.domain.AidRuntimeSourceCoverage;
import com.aid.assistant.artifact.domain.AidRuntimeSourceRevision;
import com.aid.assistant.artifact.mapper.AidRuntimeArtifactMapper;
import com.aid.assistant.artifact.mapper.AidRuntimeArtifactReviewMapper;
import com.aid.assistant.artifact.mapper.AidRuntimeSourceChunkMapper;
import com.aid.assistant.artifact.mapper.AidRuntimeSourceCoverageMapper;
import com.aid.assistant.artifact.mapper.AidRuntimeSourceRevisionMapper;
import com.aid.assistant.creative.dto.CreativeAuditRequests;
import com.aid.assistant.creative.service.CreativeAuditService;
import com.aid.runtime.assistant.RuntimeLease;
import com.aid.runtime.assistant.recovery.AssistantRuntimeLeaseService;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.domain.AidRuntimeRunStep;
import com.aid.runtime.mapper.AidRuntimeRunMapper;
import com.aid.runtime.mapper.AidRuntimeRunStepMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.Objects;
import java.util.Set;

/** CAS coverage and immutable review receipts written only by fenced runtime workers. */
@Service
public class CreativeAuditServiceImpl implements CreativeAuditService {
    private static final Set<String> COVERAGE = Set.of("PENDING", "READ", "USED", "SKIPPED", "FAILED");
    private static final Set<String> VERDICTS = Set.of("PASS", "CHANGES_REQUESTED", "REJECT");

    @Resource private AssistantRuntimeLeaseService leaseService;
    @Resource private AidRuntimeRunMapper runMapper;
    @Resource private AidRuntimeRunStepMapper stepMapper;
    @Resource private AidRuntimeSourceRevisionMapper sourceRevisionMapper;
    @Resource private AidRuntimeSourceChunkMapper sourceChunkMapper;
    @Resource private AidRuntimeSourceCoverageMapper coverageMapper;
    @Resource private AidRuntimeArtifactMapper artifactMapper;
    @Resource private AidRuntimeArtifactReviewMapper reviewMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long markCoverage(CreativeAuditRequests.CoverageMark request,
                             RuntimeLease runLease, RuntimeLease stepLease) {
        AidRuntimeRun run = requireFence(request == null ? null : request.getRunId(),
                request == null ? null : request.getStepId(), runLease, stepLease);
        positive(request.getSourceRevisionId(), "sourceRevisionId");
        if (request.getChunkIndex() == null || request.getChunkIndex() < 0) invalid("chunkIndex");
        text(request.getPurpose(), 40, "purpose");
        if (!COVERAGE.contains(request.getCoverageStatus())) invalid("coverageStatus");
        if (request.getExpectedRevision() == null || request.getExpectedRevision() < 0) invalid("expectedRevision");
        if (request.getNotes() != null && request.getNotes().length() > 1000) invalid("notes");
        AidRuntimeSourceRevision source = sourceRevisionMapper.selectById(request.getSourceRevisionId());
        AidRuntimeSourceChunk chunk = sourceChunkMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AidRuntimeSourceChunk>()
                .eq(AidRuntimeSourceChunk::getSourceRevisionId, request.getSourceRevisionId())
                .eq(AidRuntimeSourceChunk::getChunkIndex, request.getChunkIndex())
                .eq(AidRuntimeSourceChunk::getStatus, "ACTIVE"));
        if (source == null || chunk == null || !Objects.equals(source.getUserId(), run.getUserId())
                || !"0".equals(source.getDelFlag())) invalid("sourceRevisionId");
        if (request.getEvidenceArtifactId() != null) requireArtifactOwner(run, request.getEvidenceArtifactId());

        AidRuntimeSourceCoverage row = coverageMapper.selectForUpdate(request.getRunId(),
                request.getSourceRevisionId(), request.getChunkIndex(), request.getPurpose());
        Date now = new Date();
        if (row == null) {
            if (request.getExpectedRevision() != 0L) invalid("expectedRevision");
            row = new AidRuntimeSourceCoverage();
            row.setRunId(request.getRunId());
            row.setStepId(request.getStepId());
            row.setSourceRevisionId(request.getSourceRevisionId());
            row.setChunkIndex(request.getChunkIndex());
            row.setPurpose(request.getPurpose());
            row.setCoverageStatus(request.getCoverageStatus());
            row.setEvidenceArtifactId(request.getEvidenceArtifactId());
            row.setRevision(0L);
            row.setNotes(request.getNotes());
            row.setStatus("ACTIVE");
            row.setDelFlag("0");
            row.setCreateBy(stepLease.getOwner());
            row.setCreateTime(now);
            row.setUpdateBy(stepLease.getOwner());
            row.setUpdateTime(now);
            coverageMapper.insert(row);
            return row.getRevision();
        }
        if (!Objects.equals(row.getRevision(), request.getExpectedRevision())) invalid("expectedRevision");
        long next = row.getRevision() + 1L;
        int updated = coverageMapper.update(null, new LambdaUpdateWrapper<AidRuntimeSourceCoverage>()
                .set(AidRuntimeSourceCoverage::getStepId, request.getStepId())
                .set(AidRuntimeSourceCoverage::getCoverageStatus, request.getCoverageStatus())
                .set(AidRuntimeSourceCoverage::getEvidenceArtifactId, request.getEvidenceArtifactId())
                .set(AidRuntimeSourceCoverage::getNotes, request.getNotes())
                .set(AidRuntimeSourceCoverage::getRevision, next)
                .set(AidRuntimeSourceCoverage::getUpdateBy, stepLease.getOwner())
                .set(AidRuntimeSourceCoverage::getUpdateTime, now)
                .eq(AidRuntimeSourceCoverage::getId, row.getId())
                .eq(AidRuntimeSourceCoverage::getRevision, request.getExpectedRevision())
                .eq(AidRuntimeSourceCoverage::getDelFlag, "0"));
        if (updated != 1) invalid("expectedRevision");
        return next;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long recordReview(CreativeAuditRequests.ReviewRecord request,
                             RuntimeLease runLease, RuntimeLease stepLease) {
        AidRuntimeRun run = requireFence(request == null ? null : request.getRunId(),
                request == null ? null : request.getStepId(), runLease, stepLease);
        positive(request.getArtifactId(), "artifactId");
        positive(request.getReviewArtifactId(), "reviewArtifactId");
        if (!VERDICTS.contains(request.getVerdict())) invalid("verdict");
        try {
            if (!(JSON.parse(request.getSeverityCountsJson()) instanceof java.util.Map<?, ?>)) invalid("severityCountsJson");
        } catch (RuntimeException error) { invalid("severityCountsJson"); }
        AidRuntimeArtifact target = requireArtifactOwner(run, request.getArtifactId());
        AidRuntimeArtifact reviewArtifact = requireArtifactOwner(run, request.getReviewArtifactId());
        JSONObject reviewMetadata;
        try { reviewMetadata = JSON.parseObject(reviewArtifact.getMetadataJson()); }
        catch (RuntimeException invalid) { reviewMetadata = null; }
        String targetRef = "artifact:" + target.getLogicalArtifactId() + ":" + target.getRevision();
        if (!"FINALIZED".equals(target.getStatus()) || !"FINALIZED".equals(reviewArtifact.getStatus())
                || "REVIEW_REPORT".equals(target.getArtifactType())
                || !"REVIEW_REPORT".equals(reviewArtifact.getArtifactType())
                || !Objects.equals(reviewArtifact.getProducerRunId(), request.getRunId())
                || !Objects.equals(reviewArtifact.getProducerStepId(), request.getStepId())
                || reviewMetadata == null
                || !Objects.equals(reviewMetadata.getString("reviewSubjectRef"), targetRef)
                || !Objects.equals(reviewMetadata.getString("reviewSubjectContentHash"), target.getContentHash())
                || reviewMetadata.getString("reviewSubmissionPromptDigest") == null
                || !reviewMetadata.getString("reviewSubmissionPromptDigest").matches("[0-9a-f]{64}")) {
            invalid("review artifact");
        }
        AidRuntimeArtifactReview replay = reviewMapper.selectReceipt(request.getArtifactId(),
                request.getReviewArtifactId(), request.getRunId(), request.getStepId());
        if (replay != null) {
            if (!Objects.equals(replay.getVerdict(), request.getVerdict())
                    || !Objects.equals(replay.getSeverityCountsJson(), request.getSeverityCountsJson())) invalid("review receipt");
            return replay.getId();
        }
        Date now = new Date();
        AidRuntimeArtifactReview receipt = new AidRuntimeArtifactReview();
        receipt.setArtifactId(request.getArtifactId());
        receipt.setReviewArtifactId(request.getReviewArtifactId());
        receipt.setReviewerRunId(request.getRunId());
        receipt.setReviewerStepId(request.getStepId());
        receipt.setVerdict(request.getVerdict());
        receipt.setSeverityCountsJson(request.getSeverityCountsJson());
        receipt.setContentHash(reviewArtifact.getContentHash());
        receipt.setStatus("ACTIVE");
        receipt.setDelFlag("0");
        receipt.setCreateBy(stepLease.getOwner());
        receipt.setCreateTime(now);
        receipt.setUpdateBy(stepLease.getOwner());
        receipt.setUpdateTime(now);
        reviewMapper.insert(receipt);
        artifactMapper.update(null, new LambdaUpdateWrapper<AidRuntimeArtifact>()
                .set(AidRuntimeArtifact::getReviewStatus, request.getVerdict())
                .set(AidRuntimeArtifact::getUpdateBy, stepLease.getOwner())
                .set(AidRuntimeArtifact::getUpdateTime, now)
                .eq(AidRuntimeArtifact::getId, target.getId())
                .eq(AidRuntimeArtifact::getContentHash, target.getContentHash())
                .eq(AidRuntimeArtifact::getStatus, "FINALIZED")
                .eq(AidRuntimeArtifact::getDelFlag, "0"));
        return receipt.getId();
    }

    private AidRuntimeArtifact requireArtifactOwner(AidRuntimeRun callerRun, Long artifactId) {
        AidRuntimeArtifact artifact = artifactMapper.selectById(artifactId);
        AidRuntimeRun producer = artifact == null ? null : runMapper.selectById(artifact.getProducerRunId());
        if (artifact == null || producer == null || !Objects.equals(producer.getUserId(), callerRun.getUserId())
                || !"0".equals(artifact.getDelFlag())) invalid("artifactId");
        return artifact;
    }

    private AidRuntimeRun requireFence(Long runId, Long stepId, RuntimeLease runLease, RuntimeLease stepLease) {
        positive(runId, "runId"); positive(stepId, "stepId");
        Date now = new Date();
        if (!leaseService.isRunLeaseCurrent(runId, runLease, now)) invalid("run lease");
        AidRuntimeRun run = runMapper.selectById(runId);
        AidRuntimeRunStep step = stepMapper.selectById(stepId);
        if (run == null || step == null || !Objects.equals(step.getRunId(), runId)
                || stepLease == null || StrUtil.isBlank(stepLease.getOwner())
                || !Objects.equals(step.getLeaseOwner(), stepLease.getOwner())
                || !Objects.equals(step.getFencingToken(), stepLease.getFencingToken())
                || step.getLeaseUntil() == null || !step.getLeaseUntil().after(now)) invalid("step lease");
        return run;
    }

    private void positive(Long value, String field) { if (value == null || value < 1) invalid(field); }
    private void text(String value, int max, String field) { if (StrUtil.isBlank(value) || value.length() > max) invalid(field); }
    private void invalid(String field) { throw new IllegalArgumentException("Invalid creative audit " + field); }
}
