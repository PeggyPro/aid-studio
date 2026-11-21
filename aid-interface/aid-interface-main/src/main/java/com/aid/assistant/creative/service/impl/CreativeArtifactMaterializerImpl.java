package com.aid.assistant.creative.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.domain.media.AidMediaResult;
import com.aid.aid.mapper.AidMediaResultMapper;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.assistant.artifact.domain.AidRuntimeArtifact;
import com.aid.assistant.artifact.domain.AidRuntimeSourceRevision;
import com.aid.assistant.artifact.dto.ArtifactWriteRequests;
import com.aid.assistant.artifact.mapper.AidRuntimeArtifactMapper;
import com.aid.assistant.artifact.mapper.AidRuntimeSourceRevisionMapper;
import com.aid.assistant.artifact.service.AssistantArtifactWriter;
import com.aid.assistant.artifact.service.MediaArtifactTypeResolver;
import com.aid.assistant.artifact.vo.ArtifactRef;
import com.aid.assistant.creative.dto.CreativeAuditRequests;
import com.aid.assistant.creative.dto.CreativeMaterializationRequests;
import com.aid.assistant.creative.service.CreativeArtifactMaterializer;
import com.aid.assistant.creative.service.CreativeAuditService;
import com.aid.assistant.creative.service.CreativeContextAuthorizer;
import com.aid.assistant.creative.service.CreativeMediaArtifactPersistence;
import com.aid.assistant.creative.service.CreativeTextChunker;
import com.aid.assistant.creative.vo.CreativeViews;
import com.aid.runtime.assistant.RuntimeLease;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.domain.AidRuntimeRunStep;
import com.aid.runtime.mapper.AidRuntimeRunMapper;
import com.aid.runtime.mapper.AidRuntimeRunStepMapper;
import com.aid.media.service.IMediaGenerationService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Converts a successful fenced creative text task into an immutable candidate revision. */
@Service
public class CreativeArtifactMaterializerImpl implements CreativeArtifactMaterializer {
    private static final Pattern LOGICAL_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}");
    private static final Set<String> ARTIFACT_TYPES = Set.of(
            "CREATIVE_TEXT", "OUTLINE", "SCREENPLAY", "REVIEW_REPORT");
    private static final Set<String> MEDIA_TYPES = Set.of("IMAGE", "VIDEO", "AUDIO", "MUSIC");
    private static final int MAX_RESULT_CHARS = 4_000_000;

    @Resource private AidMediaTaskMapper mediaTaskMapper;
    @Resource private AidMediaResultMapper mediaResultMapper;
    @Resource private AidRuntimeRunMapper runMapper;
    @Resource private AidRuntimeRunStepMapper stepMapper;
    @Resource private AidRuntimeSourceRevisionMapper sourceRevisionMapper;
    @Resource private AidRuntimeArtifactMapper artifactMapper;
    @Resource private CreativeContextAuthorizer contextAuthorizer;
    @Resource private AssistantArtifactWriter artifactWriter;
    @Resource private CreativeAuditService auditService;
    @Resource private IMediaGenerationService mediaGenerationService;
    @Resource private CreativeMediaArtifactPersistence mediaArtifactPersistenceService;
    @Resource private MediaArtifactTypeResolver mediaArtifactTypes;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ArtifactRef materializeText(CreativeMaterializationRequests.MaterializeText request,
                                       RuntimeLease runLease, RuntimeLease stepLease) {
        return materializeText(request, runLease, stepLease, null);
    }

    private ArtifactRef materializeText(CreativeMaterializationRequests.MaterializeText request,
                                        RuntimeLease runLease, RuntimeLease stepLease,
                                        String projectedContent) {
        require(request != null, "request");
        positive(request.getRunId(), "runId");
        positive(request.getStepId(), "stepId");
        positive(request.getMediaTaskId(), "mediaTaskId");
        require(StrUtil.isNotBlank(request.getLogicalArtifactId())
                && LOGICAL_ID.matcher(request.getLogicalArtifactId()).matches(), "logicalArtifactId");
        require(ARTIFACT_TYPES.contains(request.getArtifactType()), "artifactType");
        text(request.getSchemaVersion(), 20, "schemaVersion");
        int maxChunkChars = request.getMaxChunkChars() == null ? 12000 : request.getMaxChunkChars();
        require(maxChunkChars >= 1000 && maxChunkChars <= 50000, "maxChunkChars");
        if (request.getSummary() != null && request.getSummary().length() > 1000) invalid("summary");

        AidRuntimeRun run = runMapper.selectById(request.getRunId());
        AidRuntimeRunStep step = stepMapper.selectById(request.getStepId());
        AidMediaTask task = mediaTaskMapper.selectById(request.getMediaTaskId());
        requireTask(run, step, task, request);
        String content = projectedContent == null ? task.getResultText() : projectedContent;
        require(StrUtil.isNotBlank(content) && content.length() <= MAX_RESULT_CHARS, "media task result");

        List<CreativeTextChunker.Chunk> chunks = new ArrayList<>();
        CreativeTextChunker.Stats stats;
        try (StringReader reader = new StringReader(content)) {
            stats = CreativeTextChunker.scan(reader, maxChunkChars, chunks::add);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        require(Objects.equals(stats.contentHash(), SecureUtil.sha256(content)), "contentHash");
        String sourceRefsJson = sourceRefs(run.getUserId(), request);
        JSONObject metadata = metadata(request.getMetadataJson());
        metadata.put("mediaTaskId", String.valueOf(task.getId()));
        metadata.put("modelCode", task.getModelName());
        metadata.put("charCount", stats.charCount());
        metadata.put("wordCount", stats.wordCount());
        metadata.put("chunkCount", stats.chunkCount());
        metadata.put("sourceCount", request.getSources() == null ? 0 : request.getSources().size());

        ArtifactWriteRequests.CreateCandidate create = new ArtifactWriteRequests.CreateCandidate();
        create.setRunId(run.getId());
        create.setStepId(step.getId());
        create.setLogicalArtifactId(request.getLogicalArtifactId());
        create.setParentRevision(request.getParentRevision());
        create.setArtifactType(request.getArtifactType());
        create.setSchemaVersion(request.getSchemaVersion());
        create.setAgentVersionId(run.getVersionId());
        create.setPlanRevision(run.getPlanRevision());
        JSONObject resourceRef = new JSONObject();
        resourceRef.put("kind", "MEDIA_TASK");
        resourceRef.put("mediaTaskId", String.valueOf(task.getId()));
        create.setResourceRefJson(resourceRef.toJSONString());
        create.setSourceContextDigest(run.getContextDigest());
        create.setSourceContextRefsJson(sourceRefsJson);
        create.setContentHash(stats.contentHash());
        create.setExpectedChunkCount(stats.chunkCount());
        create.setSummary(request.getSummary());
        create.setMetadataJson(metadata.toJSONString());
        ArtifactRef artifact = artifactWriter.createCandidate(create, runLease, stepLease);
        if ("FINALIZED".equals(artifact.getStatus())) return artifact;

        for (CreativeTextChunker.Chunk chunk : chunks) {
            ArtifactWriteRequests.AppendChunk append = new ArtifactWriteRequests.AppendChunk();
            append.setRunId(run.getId());
            append.setStepId(step.getId());
            append.setArtifactId(artifact.getArtifactId());
            append.setChunkIndex(chunk.index());
            append.setCharStart(chunk.charStart());
            append.setContent(chunk.content());
            append.setContentHash(chunk.contentHash());
            artifactWriter.appendChunk(append, runLease, stepLease);
        }
        ArtifactWriteRequests.FinalizeCandidate finalize = new ArtifactWriteRequests.FinalizeCandidate();
        finalize.setRunId(run.getId());
        finalize.setStepId(step.getId());
        finalize.setArtifactId(artifact.getArtifactId());
        finalize.setExpectedChunkCount(stats.chunkCount());
        finalize.setContentHash(stats.contentHash());
        return artifactWriter.finalizeCandidate(finalize, runLease, stepLease);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CreativeViews.ReviewMaterialization materializeReview(
            CreativeMaterializationRequests.MaterializeReview request,
            RuntimeLease runLease, RuntimeLease stepLease) {
        require(request != null && request.getText() != null, "request");
        require("REVIEW_REPORT".equals(request.getText().getArtifactType()), "artifactType");
        positive(request.getTargetArtifactId(), "targetArtifactId");
        require(request.getSubjectArtifactRef() != null
                && request.getSubjectArtifactRef().length() <= 500, "subjectArtifactRef");
        require(request.getSubjectContentHash() != null
                && request.getSubjectContentHash().matches("[0-9a-f]{64}"), "subjectContentHash");
        require(request.getSubmissionPromptDigest() != null
                && request.getSubmissionPromptDigest().matches("[0-9a-f]{64}"), "submissionPromptDigest");
        AidRuntimeArtifact target = artifactMapper.selectById(request.getTargetArtifactId());
        String targetRef = target == null ? null
                : "artifact:" + target.getLogicalArtifactId() + ":" + target.getRevision();
        require(target != null && "0".equals(target.getDelFlag()) && "FINALIZED".equals(target.getStatus())
                && Objects.equals(target.getProducerRunId(), request.getText().getRunId())
                && !MEDIA_TYPES.contains(target.getArtifactType())
                && Objects.equals(targetRef, request.getSubjectArtifactRef())
                && Objects.equals(target.getContentHash(), request.getSubjectContentHash()), "review subject");
        AidMediaTask task = mediaTaskMapper.selectById(request.getText().getMediaTaskId());
        require(task != null && StrUtil.isNotBlank(task.getPrompt())
                && Objects.equals(SecureUtil.sha256(task.getPrompt()), request.getSubmissionPromptDigest()),
                "review prompt digest");
        JSONObject metadata = metadata(request.getText().getMetadataJson());
        metadata.put("reviewSubjectRef", targetRef);
        metadata.put("reviewSubjectContentHash", target.getContentHash());
        metadata.put("reviewSubmissionPromptDigest", request.getSubmissionPromptDigest());
        request.getText().setMetadataJson(metadata.toJSONString());
        ReviewPayload payload = reviewPayload(task == null ? null : task.getResultText());
        ArtifactRef review = materializeText(request.getText(), runLease, stepLease, payload.report());
        CreativeAuditRequests.ReviewRecord record = new CreativeAuditRequests.ReviewRecord();
        record.setRunId(request.getText().getRunId());
        record.setStepId(request.getText().getStepId());
        record.setArtifactId(request.getTargetArtifactId());
        record.setReviewArtifactId(review.getArtifactId());
        record.setVerdict(payload.verdict());
        record.setSeverityCountsJson(payload.severityCountsJson());
        Long reviewId = auditService.recordReview(record, runLease, stepLease);
        CreativeViews.ReviewMaterialization result = new CreativeViews.ReviewMaterialization();
        result.setArtifact(review);
        result.setReviewId(String.valueOf(reviewId));
        return result;
    }

    @Override
    public CreativeViews.MediaMaterialization materializeMedia(
            CreativeMaterializationRequests.MaterializeMedia request,
            RuntimeLease runLease, RuntimeLease stepLease) {
        require(request != null, "request");
        positive(request.getRunId(), "runId");
        positive(request.getStepId(), "stepId");
        positive(request.getMediaTaskId(), "mediaTaskId");
        AidRuntimeRun run = runMapper.selectById(request.getRunId());
        AidRuntimeRunStep step = stepMapper.selectById(request.getStepId());
        AidMediaTask task = mediaTaskMapper.selectById(request.getMediaTaskId());
        requireMediaTask(run, step, task, request);
        String resultType = mediaArtifactTypes.requireResultType(step.getToolCode(), task);
        if (!archiveReady(task, resultType)) {
            mediaGenerationService.ensureOssPersisted(task.getId());
            task = mediaTaskMapper.selectById(task.getId());
            requireMediaTask(run, step, task, request);
        }
        CreativeViews.MediaMaterialization result = new CreativeViews.MediaMaterialization();
        result.setMediaTaskId(String.valueOf(request.getMediaTaskId()));
        if (!archiveReady(task, resultType)) {
            result.setStatus("WAITING_TASK");
            return result;
        }
        result.setArtifacts(mediaArtifactPersistenceService.materialize(request, runLease, stepLease));
        result.setStatus("FINALIZED");
        return result;
    }

    private ReviewPayload reviewPayload(String value) {
        JSONObject result;
        try { result = JSON.parseObject(value); }
        catch (RuntimeException error) { invalid("review result"); return null; }
        if (result == null || !Set.of("verdict", "severityCounts", "report").equals(result.keySet())) {
            invalid("review result");
        }
        String verdict = result.getString("verdict");
        JSONObject severityCounts = result.getJSONObject("severityCounts");
        String report = result.getString("report");
        require(Set.of("PASS", "CHANGES_REQUESTED", "REJECT").contains(verdict)
                && severityCounts != null && severityCounts.size() <= 20
                && StrUtil.isNotBlank(report) && report.length() <= MAX_RESULT_CHARS, "review result");
        for (Map.Entry<String, Object> entry : severityCounts.entrySet()) {
            require(StrUtil.isNotBlank(entry.getKey()) && entry.getKey().length() <= 40
                    && entry.getValue() instanceof Number
                    && ((Number) entry.getValue()).longValue() >= 0, "review severityCounts");
        }
        return new ReviewPayload(verdict, severityCounts.toJSONString(), report);
    }

    private void requireTask(AidRuntimeRun run, AidRuntimeRunStep step, AidMediaTask task,
                             CreativeMaterializationRequests.MaterializeText request) {
        require(run != null && step != null && task != null, "runtime task identity");
        require("0".equals(run.getDelFlag()) && "0".equals(step.getDelFlag())
                && Objects.equals(step.getRunId(), run.getId())
                && Objects.equals(step.getId(), request.getStepId())
                && Objects.equals(step.getMediaTaskId(), task.getId())
                && "creative.text.submit".equals(step.getToolCode())
                && Objects.equals(task.getUserId(), run.getUserId())
                && Objects.equals(task.getBizTaskId(), step.getId())
                && "ASSISTANT_RUNTIME_STEP".equals(task.getBizTaskType())
                && Objects.equals(task.getIdempotencyKey(), step.getIdempotencyKey())
                && "TEXT".equals(task.getMediaType()) && "SUCCEEDED".equals(task.getStatus()),
                "runtime task identity");
    }

    private void requireMediaTask(AidRuntimeRun run, AidRuntimeRunStep step, AidMediaTask task,
                                  CreativeMaterializationRequests.MaterializeMedia request) {
        require(run != null && step != null && task != null, "runtime task identity");
        require("0".equals(run.getDelFlag()) && "0".equals(step.getDelFlag())
                && Objects.equals(step.getRunId(), run.getId())
                && Objects.equals(step.getId(), request.getStepId())
                && Objects.equals(step.getMediaTaskId(), task.getId())
                && StrUtil.isNotBlank(step.getToolCode())
                && Objects.equals(task.getUserId(), run.getUserId())
                && Objects.equals(task.getBizTaskId(), step.getId())
                && "ASSISTANT_RUNTIME_STEP".equals(task.getBizTaskType())
                && Objects.equals(task.getIdempotencyKey(), step.getIdempotencyKey())
                && "SUCCEEDED".equals(task.getStatus()),
                "runtime task identity");
    }

    private boolean archiveReady(AidMediaTask task, String resultType) {
        if (task == null || !"SUCCEEDED".equals(task.getStatus())) return false;
        List<AidMediaResult> results = mediaResultMapper.selectList(
                new LambdaQueryWrapper<AidMediaResult>()
                        .eq(AidMediaResult::getTaskId, task.getId())
                        .orderByAsc(AidMediaResult::getResultIndex));
        if (results == null || results.isEmpty()) return StrUtil.isNotBlank(task.getOssUrl());
        return results.stream().allMatch(result -> result != null
                && result.getResultIndex() != null && result.getResultIndex() >= 0
                && Objects.equals(task.getId(), result.getTaskId())
                && Objects.equals(resultType, result.getMediaType())
                && StrUtil.isNotBlank(result.getOssUrl()));
    }

    private String sourceRefs(Long userId, CreativeMaterializationRequests.MaterializeText request) {
        JSONArray refs = new JSONArray();
        Set<Long> seen = new HashSet<>();
        List<CreativeMaterializationRequests.SourceUse> sources = request.getSources() == null
                ? List.of() : request.getSources();
        require(sources.size() <= 1000, "sources");
        for (CreativeMaterializationRequests.SourceUse use : sources) {
            require(use != null && use.getSourceRevisionId() != null
                    && use.getSourceRevisionId() > 0 && seen.add(use.getSourceRevisionId()), "sourceRevisionId");
            text(use.getPurpose(), 40, "source purpose");
            AidRuntimeSourceRevision source = sourceRevisionMapper.selectAuthorized(userId, use.getSourceRevisionId());
            require(source != null && "ACTIVE".equals(source.getStatus()), "sourceRevisionId");
            contextAuthorizer.requireRead(request.getContextManifestJson(), source.getSourceKind(),
                    source.getSourceObjectId(), source.getContentHash());
            JSONObject ref = new JSONObject();
            ref.put("ref", "source:" + source.getId());
            ref.put("sourceRevisionId", String.valueOf(source.getId()));
            ref.put("sourceKind", source.getSourceKind());
            ref.put("sourceObjectId", source.getSourceObjectId());
            ref.put("sourceRevision", source.getSourceRevision());
            ref.put("contentHash", source.getContentHash());
            ref.put("purpose", use.getPurpose().trim());
            refs.add(ref);
        }
        return refs.toJSONString();
    }

    private JSONObject metadata(String value) {
        if (StrUtil.isBlank(value)) return new JSONObject();
        try {
            JSONObject parsed = JSON.parseObject(value);
            if (parsed == null) invalid("metadataJson");
            return parsed;
        }
        catch (RuntimeException error) { invalid("metadataJson"); return null; }
    }

    private void text(String value, int max, String field) {
        require(StrUtil.isNotBlank(value) && value.length() <= max, field);
    }

    private void positive(Long value, String field) {
        require(value != null && value > 0, field);
    }

    private void require(boolean condition, String field) {
        if (!condition) invalid(field);
    }

    private void invalid(String field) {
        throw new IllegalArgumentException("Invalid creative materialization " + field);
    }

    private record ReviewPayload(String verdict, String severityCountsJson, String report) {
    }
}
