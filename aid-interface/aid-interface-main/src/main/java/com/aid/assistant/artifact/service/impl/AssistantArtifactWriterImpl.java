package com.aid.assistant.artifact.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.aid.assistant.artifact.domain.AidRuntimeArtifact;
import com.aid.assistant.artifact.domain.AidRuntimeArtifactChunk;
import com.aid.assistant.artifact.domain.AidRuntimeSourceRevision;
import com.aid.assistant.artifact.dto.ArtifactWriteRequests;
import com.aid.assistant.artifact.mapper.AidRuntimeArtifactChunkMapper;
import com.aid.assistant.artifact.mapper.AidRuntimeArtifactMapper;
import com.aid.assistant.artifact.mapper.AidRuntimeSourceRevisionMapper;
import com.aid.assistant.artifact.service.AssistantArtifactWriter;
import com.aid.assistant.artifact.vo.ArtifactRef;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Lease-fenced, append-only text artifact writer. Finalized revisions cannot be changed. */
@Service
public class AssistantArtifactWriterImpl implements AssistantArtifactWriter {
    private static final Set<String> WRITABLE_STEP_STATES = Set.of(
            "SUBMITTING", "INFLIGHT", "RECONCILING", "SUCCEEDED");
    private static final Set<String> SOURCE_REF_FIELDS = Set.of(
            "ref", "sourceRevisionId", "sourceKind", "sourceObjectId",
            "sourceRevision", "contentHash", "purpose");
    private static final Set<String> MEDIA_ARTIFACT_TYPES = Set.of("IMAGE", "VIDEO", "AUDIO", "MUSIC");

    @Resource private AidRuntimeArtifactMapper artifactMapper;
    @Resource private AidRuntimeArtifactChunkMapper chunkMapper;
    @Resource private AidRuntimeRunMapper runMapper;
    @Resource private AidRuntimeRunStepMapper stepMapper;
    @Resource private AssistantRuntimeLeaseService leaseService;
    @Resource private AidRuntimeSourceRevisionMapper sourceRevisionMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ArtifactRef createCandidate(ArtifactWriteRequests.CreateCandidate request,
                                       RuntimeLease runLease, RuntimeLease stepLease) {
        require(request != null, "request");
        AidRuntimeRun run = requireFence(request.getRunId(), request.getStepId(), runLease, stepLease);
        requirePositive(request.getAgentVersionId(), "agentVersionId");
        requireNonNegative(request.getPlanRevision(), "planRevision");
        require(Objects.equals(run.getVersionId(), request.getAgentVersionId()), "agentVersionId");
        require(Objects.equals(run.getPlanRevision(), request.getPlanRevision()), "planRevision");
        requireText(request.getArtifactType(), 40, "artifactType");
        requireText(request.getSchemaVersion(), 20, "schemaVersion");
        requireDigest(request.getSourceContextDigest(), "sourceContextDigest");
        requireDigest(run.getContextDigest(), "run contextDigest");
        if (!Objects.equals(run.getContextDigest(), request.getSourceContextDigest())) {
            invalid("sourceContextDigest");
        }
        requireSourceRefs(run.getUserId(), request.getSourceContextRefsJson());
        String sourceRefsDigest = SecureUtil.sha256(request.getSourceContextRefsJson());
        requireOptionalJson(request.getResourceRefJson(), "resourceRefJson");
        requireOptionalJson(request.getMetadataJson(), "metadataJson");
        requireDigest(request.getContentHash(), "contentHash");
        requireRange(request.getExpectedChunkCount(), 0, 1000000, "expectedChunkCount");
        if (request.getSummary() != null && request.getSummary().length() > 1000) invalid("summary");

        String logicalId = StrUtil.isBlank(request.getLogicalArtifactId())
                ? "art_" + UUID.randomUUID().toString().replace("-", "")
                : request.getLogicalArtifactId().trim();
        requireText(logicalId, 64, "logicalArtifactId");
        AidRuntimeArtifact produced = artifactMapper.selectProducedForUpdate(
                request.getRunId(), request.getStepId(), logicalId);
        if (produced != null) {
            requireReplay(produced, request, logicalId, sourceRefsDigest);
            return ref(produced);
        }
        AidRuntimeArtifact latest = artifactMapper.selectLatestForUpdate(logicalId);
        Long revision = request.getRevision();
        Long parentRevision = request.getParentRevision();
        if (latest == null) {
            revision = revision == null ? 1L : revision;
            if (revision != 1L || parentRevision != null) invalid("revision");
        } else {
            AidRuntimeRun parentRun = runMapper.selectById(latest.getProducerRunId());
            if (parentRun == null || !Objects.equals(parentRun.getUserId(), run.getUserId())) invalid("logicalArtifactId");
            long next = latest.getRevision() + 1L;
            revision = revision == null ? next : revision;
            parentRevision = parentRevision == null ? latest.getRevision() : parentRevision;
            if (revision != next || !Objects.equals(parentRevision, latest.getRevision())) invalid("revision");
        }

        JSONObject metadata = StrUtil.isBlank(request.getMetadataJson())
                ? new JSONObject() : JSON.parseObject(request.getMetadataJson());
        if (metadata.containsKey("sourceRefsDigest")) invalid("metadataJson.sourceRefsDigest");
        metadata.put("chunkCount", request.getExpectedChunkCount());
        metadata.put("sourceRefsDigest", sourceRefsDigest);
        AidRuntimeArtifact artifact = new AidRuntimeArtifact();
        artifact.setLogicalArtifactId(logicalId);
        artifact.setRevision(revision);
        artifact.setParentRevision(parentRevision);
        artifact.setArtifactType(request.getArtifactType().trim());
        artifact.setSchemaVersion(request.getSchemaVersion().trim());
        artifact.setAgentVersionId(request.getAgentVersionId());
        artifact.setPlanRevision(request.getPlanRevision());
        artifact.setResourceId(request.getResourceId());
        artifact.setFileId(request.getFileId());
        artifact.setResourceRefJson(trim(request.getResourceRefJson()));
        artifact.setSourceContextDigest(request.getSourceContextDigest());
        artifact.setSourceContextRefsJson(request.getSourceContextRefsJson());
        artifact.setProducerRunId(request.getRunId());
        artifact.setProducerStepId(request.getStepId());
        artifact.setContentHash(request.getContentHash());
        artifact.setReviewStatus("UNREVIEWED");
        artifact.setSummary(trim(request.getSummary()));
        artifact.setMetadataJson(metadata.toJSONString());
        artifact.setStatus("DRAFT");
        artifact.setDelFlag("0");
        artifact.setCreateBy(runLease.getOwner());
        artifact.setCreateTime(new Date());
        artifact.setUpdateBy(runLease.getOwner());
        artifact.setUpdateTime(new Date());
        artifactMapper.insert(artifact);
        return ref(artifact);
    }

    private void requireReplay(AidRuntimeArtifact artifact,
                               ArtifactWriteRequests.CreateCandidate request,
                               String logicalId, String sourceRefsDigest) {
        JSONObject metadata;
        try {
            metadata = JSON.parseObject(artifact.getMetadataJson());
        } catch (RuntimeException invalid) {
            invalid("existing artifact metadata");
            return;
        }
        Integer chunkCount = metadata == null ? null : metadata.getInteger("chunkCount");
        String persistedRefsDigest = metadata == null ? null : metadata.getString("sourceRefsDigest");
        if (!("DRAFT".equals(artifact.getStatus()) || "FINALIZED".equals(artifact.getStatus()))
                || !"0".equals(artifact.getDelFlag())
                || !Objects.equals(artifact.getProducerRunId(), request.getRunId())
                || !Objects.equals(artifact.getProducerStepId(), request.getStepId())
                || !Objects.equals(artifact.getLogicalArtifactId(), logicalId)
                || !Objects.equals(artifact.getArtifactType(), request.getArtifactType().trim())
                || !Objects.equals(artifact.getSchemaVersion(), request.getSchemaVersion().trim())
                || !Objects.equals(artifact.getAgentVersionId(), request.getAgentVersionId())
                || !Objects.equals(artifact.getPlanRevision(), request.getPlanRevision())
                || !Objects.equals(artifact.getResourceId(), request.getResourceId())
                || !Objects.equals(artifact.getFileId(), request.getFileId())
                || !Objects.equals(artifact.getResourceRefJson(), trim(request.getResourceRefJson()))
                || !Objects.equals(artifact.getSourceContextDigest(), request.getSourceContextDigest())
                || !Objects.equals(artifact.getSourceContextRefsJson(), request.getSourceContextRefsJson())
                || !Objects.equals(artifact.getContentHash(), request.getContentHash())
                || !Objects.equals(chunkCount, request.getExpectedChunkCount())
                || !Objects.equals(persistedRefsDigest, sourceRefsDigest)
                || request.getRevision() != null && !Objects.equals(artifact.getRevision(), request.getRevision())
                || request.getParentRevision() != null
                    && !Objects.equals(artifact.getParentRevision(), request.getParentRevision())) {
            invalid("existing artifact");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ArtifactRef appendChunk(ArtifactWriteRequests.AppendChunk request,
                                   RuntimeLease runLease, RuntimeLease stepLease) {
        require(request != null, "request");
        requireFence(request.getRunId(), request.getStepId(), runLease, stepLease);
        requirePositive(request.getArtifactId(), "artifactId");
        requireRange(request.getChunkIndex(), 0, 1000000, "chunkIndex");
        requireRange(request.getCharStart(), 0, Integer.MAX_VALUE, "charStart");
        if (request.getContent() == null) invalid("content");
        rejectBrokenSurrogateBoundary(request.getContent());
        String hash = SecureUtil.sha256(request.getContent());
        if (StrUtil.isNotBlank(request.getContentHash()) && !hash.equals(request.getContentHash())) {
            invalid("contentHash");
        }
        AidRuntimeArtifact artifact = requireDraftForStep(
                request.getArtifactId(), request.getRunId(), request.getStepId());
        AidRuntimeArtifactChunk existing = chunkMapper.selectActive(artifact.getId(), request.getChunkIndex());
        int end = Math.addExact(request.getCharStart(), request.getContent().length());
        if (existing != null) {
            if (!Objects.equals(existing.getCharStart(), request.getCharStart())
                    || !Objects.equals(existing.getCharEnd(), end)
                    || !Objects.equals(existing.getContentHash(), hash)
                    || !Objects.equals(existing.getContentText(), request.getContent())) invalid("chunkIndex");
            return ref(artifact);
        }
        AidRuntimeArtifactChunk last = chunkMapper.selectLastActive(artifact.getId());
        int expectedIndex = last == null ? 0 : last.getChunkIndex() + 1;
        int expectedStart = last == null ? 0 : last.getCharEnd();
        if (request.getChunkIndex() != expectedIndex || request.getCharStart() != expectedStart) invalid("chunk sequence");

        AidRuntimeArtifactChunk chunk = new AidRuntimeArtifactChunk();
        chunk.setArtifactId(artifact.getId());
        chunk.setChunkIndex(request.getChunkIndex());
        chunk.setCharStart(request.getCharStart());
        chunk.setCharEnd(end);
        chunk.setCharCount(request.getContent().length());
        chunk.setWordCount(wordCount(request.getContent()));
        chunk.setContentText(request.getContent());
        chunk.setContentHash(hash);
        chunk.setStatus("ACTIVE");
        chunk.setCreateBy(stepLease.getOwner());
        chunk.setCreateTime(new Date());
        chunkMapper.insert(chunk);
        return ref(artifact);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ArtifactRef finalizeCandidate(ArtifactWriteRequests.FinalizeCandidate request,
                                         RuntimeLease runLease, RuntimeLease stepLease) {
        require(request != null, "request");
        requireFence(request.getRunId(), request.getStepId(), runLease, stepLease);
        requirePositive(request.getArtifactId(), "artifactId");
        requireRange(request.getExpectedChunkCount(), 0, 1000000, "expectedChunkCount");
        requireDigest(request.getContentHash(), "contentHash");
        AidRuntimeArtifact artifact = artifactMapper.selectByIdForUpdate(request.getArtifactId());
        if (artifact == null || !Objects.equals(artifact.getProducerRunId(), request.getRunId())
                || !Objects.equals(artifact.getProducerStepId(), request.getStepId())) invalid("artifactId");
        if ("FINALIZED".equals(artifact.getStatus())) {
            if (!Objects.equals(artifact.getContentHash(), request.getContentHash())) invalid("contentHash");
            return ref(artifact);
        }
        if (!"DRAFT".equals(artifact.getStatus())) invalid("artifact status");
        if (!Objects.equals(artifact.getContentHash(), request.getContentHash())) invalid("contentHash");

        List<AidRuntimeArtifactChunk> chunks = chunkMapper.selectAllActive(artifact.getId());
        if (chunks.size() != request.getExpectedChunkCount()) invalid("expectedChunkCount");
        MessageDigest digest = sha256();
        int charStart = 0;
        int words = 0;
        for (int index = 0; index < chunks.size(); index++) {
            AidRuntimeArtifactChunk chunk = chunks.get(index);
            if (chunk.getChunkIndex() == null || chunk.getChunkIndex() != index
                    || chunk.getCharStart() == null || chunk.getCharStart() != charStart
                    || chunk.getCharEnd() == null || chunk.getCharCount() == null
                    || chunk.getCharEnd() - chunk.getCharStart() != chunk.getCharCount()
                    || chunk.getContentText() == null || chunk.getContentText().length() != chunk.getCharCount()
                    || !SecureUtil.sha256(chunk.getContentText()).equals(chunk.getContentHash())) {
                invalid("chunk integrity");
            }
            charStart = chunk.getCharEnd();
            words += chunk.getWordCount() == null ? wordCount(chunk.getContentText()) : chunk.getWordCount();
            digest.update(chunk.getContentText().getBytes(StandardCharsets.UTF_8));
        }
        String actualHash = HexFormat.of().formatHex(digest.digest());
        if (!actualHash.equals(request.getContentHash())) invalid("contentHash");
        JSONObject metadata = StrUtil.isBlank(artifact.getMetadataJson())
                ? new JSONObject() : JSON.parseObject(artifact.getMetadataJson());
        metadata.put("chunkCount", chunks.size());
        metadata.put("charCount", charStart);
        metadata.put("wordCount", words);
        Date now = new Date();
        int updated = artifactMapper.update(null, new LambdaUpdateWrapper<AidRuntimeArtifact>()
                .set(AidRuntimeArtifact::getStatus, "FINALIZED")
                .set(AidRuntimeArtifact::getMetadataJson, metadata.toJSONString())
                .set(AidRuntimeArtifact::getUpdateBy, stepLease.getOwner())
                .set(AidRuntimeArtifact::getUpdateTime, now)
                .eq(AidRuntimeArtifact::getId, artifact.getId())
                .eq(AidRuntimeArtifact::getStatus, "DRAFT")
                .eq(AidRuntimeArtifact::getContentHash, request.getContentHash())
                .eq(AidRuntimeArtifact::getDelFlag, "0"));
        if (updated != 1) invalid("artifact status");
        artifact.setStatus("FINALIZED");
        artifact.setMetadataJson(metadata.toJSONString());
        return ref(artifact);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ArtifactRef finalizeMediaCandidate(ArtifactWriteRequests.FinalizeMediaCandidate request,
                                              RuntimeLease runLease, RuntimeLease stepLease) {
        require(request != null, "request");
        requireFence(request.getRunId(), request.getStepId(), runLease, stepLease);
        requirePositive(request.getArtifactId(), "artifactId");
        requireDigest(request.getContentHash(), "contentHash");
        AidRuntimeArtifact artifact = artifactMapper.selectByIdForUpdate(request.getArtifactId());
        if (artifact == null || !Objects.equals(artifact.getProducerRunId(), request.getRunId())
                || !Objects.equals(artifact.getProducerStepId(), request.getStepId())
                || !MEDIA_ARTIFACT_TYPES.contains(artifact.getArtifactType())
                || !Objects.equals(artifact.getContentHash(), request.getContentHash())) {
            invalid("media artifact");
        }
        if (chunkMapper.countActive(artifact.getId()) != 0) invalid("media chunks");
        JSONObject metadata;
        JSONObject resourceRef;
        try {
            metadata = JSON.parseObject(artifact.getMetadataJson());
            resourceRef = JSON.parseObject(artifact.getResourceRefJson());
        } catch (RuntimeException error) {
            invalid("media metadata");
            return null;
        }
        if (metadata == null
                || !"MEDIA_ARCHIVE_RECEIPT_SHA256".equals(metadata.getString("contentHashKind"))
                || !"ARCHIVED".equals(metadata.getString("technicalStatus"))
                || !Boolean.FALSE.equals(metadata.getBoolean("creativeQualityVerified"))
                || resourceRef == null || !validMediaRef(resourceRef)) {
            invalid("media metadata");
        }
        if ("FINALIZED".equals(artifact.getStatus())) return ref(artifact);
        if (!"DRAFT".equals(artifact.getStatus())) invalid("artifact status");
        Date now = new Date();
        int updated = artifactMapper.update(null, new LambdaUpdateWrapper<AidRuntimeArtifact>()
                .set(AidRuntimeArtifact::getStatus, "FINALIZED")
                .set(AidRuntimeArtifact::getUpdateBy, stepLease.getOwner())
                .set(AidRuntimeArtifact::getUpdateTime, now)
                .eq(AidRuntimeArtifact::getId, artifact.getId())
                .eq(AidRuntimeArtifact::getStatus, "DRAFT")
                .eq(AidRuntimeArtifact::getContentHash, request.getContentHash())
                .eq(AidRuntimeArtifact::getDelFlag, "0"));
        if (updated != 1) invalid("artifact status");
        artifact.setStatus("FINALIZED");
        return ref(artifact);
    }

    private boolean validMediaRef(JSONObject ref) {
        String kind = ref.getString("kind");
        if ("MEDIA_TASK".equals(kind)) {
            return ref.size() == 2 && positiveDecimal(ref.getString("mediaTaskId"));
        }
        if ("MEDIA_RESULT".equals(kind)) {
            Integer index = ref.getInteger("resultIndex");
            return ref.size() == 3 && positiveDecimal(ref.getString("mediaTaskId"))
                    && index != null && index >= 0;
        }
        return false;
    }

    private boolean positiveDecimal(String value) {
        return value != null && value.matches("[1-9][0-9]{0,18}");
    }

    private AidRuntimeRun requireFence(Long runId, Long stepId, RuntimeLease runLease, RuntimeLease stepLease) {
        requirePositive(runId, "runId");
        requirePositive(stepId, "stepId");
        Date now = new Date();
        if (!leaseService.isRunLeaseCurrent(runId, runLease, now)) invalid("run lease");
        AidRuntimeRun run = runMapper.selectById(runId);
        AidRuntimeRunStep step = stepMapper.selectById(stepId);
        if (run == null || step == null || !"0".equals(run.getDelFlag()) || !"0".equals(step.getDelFlag())
                || !Objects.equals(step.getRunId(), runId) || !WRITABLE_STEP_STATES.contains(step.getStatus())
                || stepLease == null || StrUtil.isBlank(stepLease.getOwner())
                || !Objects.equals(step.getLeaseOwner(), stepLease.getOwner())
                || !Objects.equals(step.getFencingToken(), stepLease.getFencingToken())
                || step.getLeaseUntil() == null || !step.getLeaseUntil().after(now)) invalid("step lease");
        return run;
    }

    private AidRuntimeArtifact requireDraftForStep(Long artifactId, Long runId, Long stepId) {
        AidRuntimeArtifact artifact = artifactMapper.selectByIdForUpdate(artifactId);
        if (artifact == null || !"DRAFT".equals(artifact.getStatus()) || !"0".equals(artifact.getDelFlag())
                || !Objects.equals(artifact.getProducerRunId(), runId)
                || !Objects.equals(artifact.getProducerStepId(), stepId)) invalid("artifactId");
        return artifact;
    }

    private ArtifactRef ref(AidRuntimeArtifact artifact) {
        return new ArtifactRef(artifact.getId(), artifact.getLogicalArtifactId(), artifact.getRevision(),
                "artifact:" + artifact.getLogicalArtifactId() + ":" + artifact.getRevision(),
                artifact.getContentHash(), artifact.getStatus());
    }

    private int wordCount(String content) {
        int count = 0;
        boolean inWord = false;
        for (int offset = 0; offset < content.length();) {
            int codePoint = content.codePointAt(offset);
            boolean word = Character.isLetterOrDigit(codePoint);
            if (word && !inWord) count++;
            inWord = word;
            offset += Character.charCount(codePoint);
        }
        return count;
    }

    private void rejectBrokenSurrogateBoundary(String content) {
        if (!content.isEmpty() && (Character.isLowSurrogate(content.charAt(0))
                || Character.isHighSurrogate(content.charAt(content.length() - 1)))) invalid("content surrogate boundary");
    }

    private MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private void requireJson(String value, String field) {
        requireText(value, Integer.MAX_VALUE, field);
        try { JSON.parse(value); } catch (RuntimeException invalid) { invalid(field); }
    }

    private void requireSourceRefs(Long userId, String value) {
        requireText(value, Integer.MAX_VALUE, "sourceContextRefsJson");
        JSONArray refs;
        try { refs = JSON.parseArray(value); }
        catch (RuntimeException error) { invalid("sourceContextRefsJson"); return; }
        if (refs == null || refs.size() > 1000) invalid("sourceContextRefsJson");
        for (Object item : refs) {
            if (!(item instanceof JSONObject)) {
                invalid("sourceContextRefsJson");
            }
            JSONObject ref = (JSONObject) item;
            if (!SOURCE_REF_FIELDS.equals(ref.keySet())) invalid("sourceContextRefsJson");
            Long sourceRevisionId;
            try { sourceRevisionId = Long.valueOf(ref.getString("sourceRevisionId")); }
            catch (RuntimeException error) { invalid("sourceRevisionId"); return; }
            AidRuntimeSourceRevision source = sourceRevisionMapper.selectAuthorized(userId, sourceRevisionId);
            if (source == null || !Objects.equals("source:" + sourceRevisionId, ref.getString("ref"))
                    || !Objects.equals(source.getSourceKind(), ref.getString("sourceKind"))
                    || !Objects.equals(source.getSourceObjectId(), ref.getString("sourceObjectId"))
                    || !Objects.equals(source.getSourceRevision(), ref.getString("sourceRevision"))
                    || !Objects.equals(source.getContentHash(), ref.getString("contentHash"))
                    || StrUtil.isBlank(ref.getString("purpose")) || ref.getString("purpose").length() > 40) {
                invalid("sourceContextRefsJson");
            }
        }
    }

    private void requireOptionalJson(String value, String field) {
        if (StrUtil.isBlank(value)) return;
        try { JSON.parse(value); } catch (RuntimeException invalid) { invalid(field); }
    }

    private void requireDigest(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) invalid(field);
    }

    private void requireText(String value, int max, String field) {
        if (StrUtil.isBlank(value) || value.length() > max) invalid(field);
    }

    private void requirePositive(Long value, String field) {
        if (value == null || value < 1) invalid(field);
    }

    private void requireNonNegative(Long value, String field) {
        if (value == null || value < 0) invalid(field);
    }

    private void requireRange(Integer value, int min, int max, String field) {
        if (value == null || value < min || value > max) invalid(field);
    }

    private void require(boolean condition, String field) { if (!condition) invalid(field); }
    private String trim(String value) { return StrUtil.isBlank(value) ? null : value.trim(); }
    private void invalid(String field) { throw new IllegalArgumentException("Invalid artifact " + field); }
}
