package com.aid.assistant.creative.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.aid.aid.domain.media.AidMediaResult;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaResultMapper;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.assistant.artifact.domain.AidRuntimeSourceRevision;
import com.aid.assistant.artifact.dto.ArtifactWriteRequests;
import com.aid.assistant.artifact.mapper.AidRuntimeSourceRevisionMapper;
import com.aid.assistant.artifact.service.AssistantArtifactWriter;
import com.aid.assistant.artifact.service.MediaArtifactTypeResolver;
import com.aid.assistant.artifact.vo.ArtifactRef;
import com.aid.assistant.creative.dto.CreativeMaterializationRequests;
import com.aid.assistant.creative.service.CreativeContextAuthorizer;
import com.aid.assistant.creative.service.CreativeMediaArtifactPersistence;
import com.aid.runtime.assistant.RuntimeLease;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.domain.AidRuntimeRunStep;
import com.aid.runtime.mapper.AidRuntimeRunMapper;
import com.aid.runtime.mapper.AidRuntimeRunStepMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Persists archived media task outputs as immutable resource artifacts. */
@Service
public class CreativeMediaArtifactPersistenceService implements CreativeMediaArtifactPersistence {
    private static final Pattern LOGICAL_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,47}");
    /** Fits the persisted aid_runtime_artifact.schema_version varchar(20) contract. */
    private static final String SCHEMA_VERSION = "aid-media/v1";

    @Resource private AidMediaTaskMapper mediaTaskMapper;
    @Resource private AidMediaResultMapper mediaResultMapper;
    @Resource private AidRuntimeRunMapper runMapper;
    @Resource private AidRuntimeRunStepMapper stepMapper;
    @Resource private AidRuntimeSourceRevisionMapper sourceRevisionMapper;
    @Resource private CreativeContextAuthorizer contextAuthorizer;
    @Resource private AssistantArtifactWriter artifactWriter;
    @Resource private MediaArtifactTypeResolver mediaArtifactTypes;

    @Transactional(rollbackFor = Exception.class)
    @Override
    public List<ArtifactRef> materialize(CreativeMaterializationRequests.MaterializeMedia request,
                                         RuntimeLease runLease, RuntimeLease stepLease) {
        require(request != null, "request");
        positive(request.getRunId(), "runId");
        positive(request.getStepId(), "stepId");
        positive(request.getMediaTaskId(), "mediaTaskId");
        require(StrUtil.isNotBlank(request.getLogicalArtifactId())
                && LOGICAL_ID.matcher(request.getLogicalArtifactId()).matches(), "logicalArtifactId");
        if (request.getSummary() != null && request.getSummary().length() > 1000) invalid("summary");

        AidRuntimeRun run = runMapper.selectById(request.getRunId());
        AidRuntimeRunStep step = stepMapper.selectById(request.getStepId());
        require(run != null && step != null && "0".equals(run.getDelFlag()) && "0".equals(step.getDelFlag())
                && Objects.equals(step.getRunId(), run.getId())
                && Objects.equals(step.getMediaTaskId(), request.getMediaTaskId())
                && StrUtil.isNotBlank(step.getToolCode()) && StrUtil.isNotBlank(step.getIdempotencyKey()),
                "runtime task identity");
        AidMediaTask task = mediaTaskMapper.selectByAssistantOperationForUpdate(run.getUserId(),
                "ASSISTANT_RUNTIME_STEP", step.getId(), step.getIdempotencyKey());
        requireTask(run, step, task, request.getMediaTaskId());
        String logicalMediaType = mediaArtifactTypes.requireForProduction(run, step, task);
        List<AidMediaResult> results = mediaResultMapper.selectList(
                new LambdaQueryWrapper<AidMediaResult>()
                        .eq(AidMediaResult::getTaskId, task.getId())
                        .orderByAsc(AidMediaResult::getResultIndex)
                        .last("FOR UPDATE"));
        List<MediaOutput> outputs = outputs(task, results, mediaArtifactTypes.resultMediaType(logicalMediaType));
        String sourceRefsJson = sourceRefs(run.getUserId(), request);
        JSONObject suppliedMetadata = optionalObject(request.getMetadataJson(), "metadataJson");
        List<ArtifactRef> artifacts = new ArrayList<>();
        for (MediaOutput output : outputs) {
            artifacts.add(persistOne(run, step, task, logicalMediaType, output, request, sourceRefsJson,
                    suppliedMetadata, runLease, stepLease));
        }
        return artifacts;
    }

    private ArtifactRef persistOne(AidRuntimeRun run, AidRuntimeRunStep step, AidMediaTask task,
                                   String logicalMediaType,
                                   MediaOutput output,
                                   CreativeMaterializationRequests.MaterializeMedia request,
                                   String sourceRefsJson, JSONObject suppliedMetadata,
                                   RuntimeLease runLease, RuntimeLease stepLease) {
        JSONObject resourceRef = new JSONObject();
        resourceRef.put("kind", output.resultIndex() == null ? "MEDIA_TASK" : "MEDIA_RESULT");
        resourceRef.put("mediaTaskId", String.valueOf(task.getId()));
        if (output.resultIndex() != null) resourceRef.put("resultIndex", output.resultIndex());

        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("receiptVersion", "aid-media-archive-receipt/v1");
        receipt.put("resourceRef", resourceRef);
        receipt.put("mediaType", logicalMediaType);
        receipt.put("storageMediaType", task.getMediaType());
        receipt.put("archiveState", "OSS_PERSISTED");
        receipt.put("mimeType", output.mimeType());
        receipt.put("fileSize", output.fileSize());
        receipt.put("durationSeconds", output.durationSeconds());
        receipt.put("mediaMetadataDigest", output.metadataDigest());
        String contentHash = SecureUtil.sha256(JSON.toJSONString(receipt));

        JSONObject metadata = new JSONObject();
        metadata.put("contentHashKind", "MEDIA_ARCHIVE_RECEIPT_SHA256");
        metadata.put("technicalEvidenceKind", "MEDIA_ARCHIVE");
        metadata.put("technicalStatus", "ARCHIVED");
        metadata.put("creativeQualityVerified", false);
        metadata.put("mediaTaskId", String.valueOf(task.getId()));
        metadata.put("resultIndex", output.resultIndex());
        metadata.put("mediaType", logicalMediaType);
        metadata.put("storageMediaType", task.getMediaType());
        metadata.put("mimeType", output.mimeType());
        metadata.put("fileSize", output.fileSize());
        metadata.put("durationSeconds", output.durationSeconds());
        metadata.put("mediaMetadataDigest", output.metadataDigest());
        if (output.mediaMetadata() != null) metadata.put("mediaMetadata", output.mediaMetadata());
        if (suppliedMetadata != null && !suppliedMetadata.isEmpty()) {
            metadata.put("requestMetadata", suppliedMetadata);
        }

        ArtifactWriteRequests.CreateCandidate create = new ArtifactWriteRequests.CreateCandidate();
        create.setRunId(run.getId());
        create.setStepId(step.getId());
        create.setLogicalArtifactId(logicalId(request.getLogicalArtifactId(), output.resultIndex()));
        create.setParentRevision(request.getParentRevision());
        create.setArtifactType(logicalMediaType);
        create.setSchemaVersion(SCHEMA_VERSION);
        create.setAgentVersionId(run.getVersionId());
        create.setPlanRevision(run.getPlanRevision());
        create.setResourceRefJson(resourceRef.toJSONString());
        create.setSourceContextDigest(run.getContextDigest());
        create.setSourceContextRefsJson(sourceRefsJson);
        create.setContentHash(contentHash);
        create.setExpectedChunkCount(0);
        create.setSummary(request.getSummary());
        create.setMetadataJson(metadata.toJSONString());
        ArtifactRef artifact = artifactWriter.createCandidate(create, runLease, stepLease);
        if ("FINALIZED".equals(artifact.getStatus())) return artifact;

        ArtifactWriteRequests.FinalizeMediaCandidate finalize =
                new ArtifactWriteRequests.FinalizeMediaCandidate();
        finalize.setRunId(run.getId());
        finalize.setStepId(step.getId());
        finalize.setArtifactId(artifact.getArtifactId());
        finalize.setContentHash(contentHash);
        return artifactWriter.finalizeMediaCandidate(finalize, runLease, stepLease);
    }

    private List<MediaOutput> outputs(AidMediaTask task, List<AidMediaResult> results, String resultType) {
        if (results == null || results.isEmpty()) {
            require(StrUtil.isNotBlank(task.getOssUrl()), "archived media task");
            return List.of(new MediaOutput(null, null, null, task.getOutputDurationSeconds(), null, null));
        }
        List<MediaOutput> outputs = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (AidMediaResult result : results) {
            require(result != null && Objects.equals(result.getTaskId(), task.getId())
                    && result.getResultIndex() != null && result.getResultIndex() >= 0
                    && seen.add(result.getResultIndex())
                    && Objects.equals(result.getMediaType(), resultType)
                    && StrUtil.isNotBlank(result.getOssUrl()), "archived media result");
            Object mediaMetadata = null;
            String metadataDigest = null;
            if (StrUtil.isNotBlank(result.getMetadataJson())) {
                require(result.getMetadataJson().length() <= 100000, "media result metadata");
                try {
                    mediaMetadata = JSON.parse(result.getMetadataJson());
                    metadataDigest = SecureUtil.sha256(result.getMetadataJson());
                } catch (RuntimeException invalid) {
                    invalid("media result metadata");
                }
            }
            outputs.add(new MediaOutput(result.getResultIndex(), trim(result.getMimeType()), result.getFileSize(),
                    result.getDurationSeconds() == null ? null : result.getDurationSeconds().longValue(),
                    mediaMetadata, metadataDigest));
        }
        return outputs;
    }

    private void requireTask(AidRuntimeRun run, AidRuntimeRunStep step, AidMediaTask task, Long mediaTaskId) {
        require(task != null && Objects.equals(task.getId(), mediaTaskId)
                && Objects.equals(task.getUserId(), run.getUserId())
                && Objects.equals(task.getBizTaskId(), step.getId())
                && "ASSISTANT_RUNTIME_STEP".equals(task.getBizTaskType())
                && Objects.equals(task.getIdempotencyKey(), step.getIdempotencyKey())
                && "SUCCEEDED".equals(task.getStatus()),
                "runtime task identity");
    }

    private String sourceRefs(Long userId, CreativeMaterializationRequests.MaterializeMedia request) {
        JSONArray refs = new JSONArray();
        Set<Long> seen = new HashSet<>();
        List<CreativeMaterializationRequests.SourceUse> sources = request.getSources() == null
                ? List.of() : request.getSources();
        require(sources.size() <= 1000, "sources");
        for (CreativeMaterializationRequests.SourceUse use : sources) {
            require(use != null && use.getSourceRevisionId() != null
                    && use.getSourceRevisionId() > 0 && seen.add(use.getSourceRevisionId()), "sourceRevisionId");
            require(StrUtil.isNotBlank(use.getPurpose()) && use.getPurpose().length() <= 40, "source purpose");
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

    private JSONObject optionalObject(String value, String field) {
        if (StrUtil.isBlank(value)) return null;
        require(value.length() <= 100000, field);
        try {
            JSONObject parsed = JSON.parseObject(value);
            require(parsed != null, field);
            return parsed;
        } catch (RuntimeException invalid) {
            invalid(field);
            return null;
        }
    }

    private String logicalId(String base, Integer resultIndex) {
        return resultIndex == null ? base : base + ".r" + resultIndex;
    }

    private String trim(String value) {
        return StrUtil.isBlank(value) ? null : value.trim();
    }

    private void positive(Long value, String field) {
        require(value != null && value > 0, field);
    }

    private void require(boolean condition, String field) {
        if (!condition) invalid(field);
    }

    private void invalid(String field) {
        throw new IllegalArgumentException("Invalid creative media materialization " + field);
    }

    private record MediaOutput(Integer resultIndex, String mimeType, Long fileSize, Long durationSeconds,
                               Object mediaMetadata, String metadataDigest) {
    }
}
