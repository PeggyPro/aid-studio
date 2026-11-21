package com.aid.assistant.artifact.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.aid.aid.domain.media.AidMediaResult;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaResultMapper;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.assistant.artifact.domain.AidRuntimeArtifact;
import com.aid.assistant.artifact.domain.AidRuntimeArtifactAdoption;
import com.aid.assistant.artifact.domain.AidRuntimeArtifactChunk;
import com.aid.assistant.artifact.domain.AidRuntimeArtifactReview;
import com.aid.assistant.artifact.domain.AidRuntimeSourceCoverage;
import com.aid.assistant.artifact.domain.AidRuntimeSourceRevision;
import com.aid.assistant.artifact.dto.ArtifactRequests;
import com.aid.assistant.artifact.mapper.AidRuntimeArtifactAdoptionMapper;
import com.aid.assistant.artifact.mapper.AidRuntimeArtifactChunkMapper;
import com.aid.assistant.artifact.mapper.AidRuntimeArtifactMapper;
import com.aid.assistant.artifact.mapper.AidRuntimeArtifactReviewMapper;
import com.aid.assistant.artifact.mapper.AidRuntimeSourceCoverageMapper;
import com.aid.assistant.artifact.mapper.AidRuntimeSourceRevisionMapper;
import com.aid.assistant.artifact.service.ArtifactAdoptionTarget;
import com.aid.assistant.artifact.service.ArtifactAdoptionTargetRegistry;
import com.aid.assistant.artifact.service.AssistantArtifactService;
import com.aid.assistant.artifact.service.MediaArtifactTypeResolver;
import com.aid.assistant.artifact.vo.ArtifactViews;
import com.aid.assistant.creative.dto.CreativeRequests;
import com.aid.assistant.creative.service.CreativeContextAuthorizer;
import com.aid.assistant.creative.service.CreativeSourceService;
import com.aid.assistant.creative.vo.CreativeViews;
import com.aid.assistant.domain.AidAssistantSession;
import com.aid.assistant.error.AssistantServiceException;
import com.aid.assistant.effect.AssistantTargetEffectService;
import com.aid.assistant.mapper.AidAssistantSessionMapper;
import com.aid.assistant.vo.AssistantTargetViews;
import com.aid.common.aid.oss.util.MediaUrlResolver;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.mapper.AidRuntimeRunMapper;
import jakarta.annotation.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import java.time.Instant;
import java.net.URI;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Comparator;
import java.util.LinkedHashSet;

/** Authorized read, review and explicit adoption operations for immutable artifact revisions. */
@Service
public class AssistantArtifactServiceImpl implements AssistantArtifactService {
    private static final Set<String> MEDIA_ARTIFACT_TYPES = Set.of("IMAGE", "VIDEO", "AUDIO", "MUSIC");
    @Resource private AidRuntimeArtifactMapper artifactMapper;
    @Resource private AidRuntimeArtifactChunkMapper chunkMapper;
    @Resource private AidRuntimeArtifactReviewMapper reviewMapper;
    @Resource private AidRuntimeArtifactAdoptionMapper adoptionMapper;
    @Resource private AidRuntimeSourceCoverageMapper coverageMapper;
    @Resource private AidRuntimeSourceRevisionMapper sourceRevisionMapper;
    @Resource private ArtifactAdoptionTargetRegistry adoptionTargets;
    @Resource private AidAssistantSessionMapper sessionMapper;
    @Resource private AidMediaTaskMapper mediaTaskMapper;
    @Resource private AidMediaResultMapper mediaResultMapper;
    @Resource private MediaUrlResolver mediaUrlResolver;
    @Resource private MediaArtifactTypeResolver mediaArtifactTypes;
    @Resource private AidRuntimeRunMapper runMapper;
    @Resource private CreativeSourceService creativeSourceService;
    @Resource private CreativeContextAuthorizer contextAuthorizer;
    @Resource private AssistantTargetEffectService targetEffectService;

    @Override
    @Transactional(readOnly = true)
    public ArtifactViews.Page<ArtifactViews.Summary> page(Long userId, ArtifactRequests.Page request) {
        requireUser(userId);
        int limit = limit(request.getLimit());
        List<AidRuntimeArtifact> rows = artifactMapper.selectAuthorizedPage(userId, request.getRunId(),
                trim(request.getLogicalArtifactId()), trim(request.getArtifactType()),
                trim(request.getReviewStatus()), request.getCursor(), limit + 1);
        ArtifactViews.Page<ArtifactViews.Summary> result = new ArtifactViews.Page<>();
        boolean more = rows.size() > limit;
        if (more) rows = new ArrayList<>(rows.subList(0, limit));
        Map<Long, AssistantTargetViews.TargetEffect> targetEffects =
                targetEffectService.projectArtifacts(userId, rows);
        result.setItems(rows.stream().map(artifact -> summary(artifact, targetEffects.get(artifact.getId()))).toList());
        if (more && !rows.isEmpty()) result.setNextCursor(String.valueOf(rows.get(rows.size() - 1).getId()));
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public ArtifactViews.Detail detail(Long userId, ArtifactRequests.Detail request) {
        requireUser(userId);
        AidRuntimeArtifact artifact = requireArtifact(userId, request.getArtifactId());
        ArtifactViews.Detail result = new ArtifactViews.Detail();
        copySummary(artifact, result);
        result.setLatestTargetEffect(targetEffectService.projectArtifact(userId, artifact));
        result.setParentRevision(artifact.getParentRevision());
        result.setSourceContextDigest(artifact.getSourceContextDigest());
        result.setSourceContextRefs(json(artifact.getSourceContextRefsJson(), "sourceContextRefs"));
        result.setResourceId(string(artifact.getResourceId()));
        result.setFileId(string(artifact.getFileId()));
        result.setResourceRef(json(artifact.getResourceRefJson(), "resourceRef"));
        result.setAgentVersionId(string(artifact.getAgentVersionId()));
        result.setPlanRevision(artifact.getPlanRevision());
        int chunks = chunkMapper.countAuthorized(userId, artifact.getId());
        result.setChunkCount(chunks);
        Map<String, Object> metadata = map(artifact.getMetadataJson());
        Integer charCount = integer(metadata.get("charCount"));
        Integer wordCount = integer(metadata.get("wordCount"));
        result.setCharCount(charCount == null ? chunkMapper.sumAuthorizedCharCount(userId, artifact.getId()) : charCount);
        result.setWordCount(wordCount == null ? chunkMapper.sumAuthorizedWordCount(userId, artifact.getId()) : wordCount);
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public ArtifactViews.Media media(Long userId, ArtifactRequests.Media request) {
        requireUser(userId);
        AidRuntimeArtifact artifact = requireArtifact(userId, request.getArtifactId());
        return archivedMedia(userId, artifact).media();
    }

    @Override
    @Transactional(readOnly = true)
    public ArchivedMedia conversationMedia(Long userId, Long artifactId, Long sourceRunId) {
        requireUser(userId);
        AidRuntimeArtifact artifact = requireArtifact(userId, artifactId);
        if (sourceRunId == null || !sourceRunId.equals(artifact.getProducerRunId())) {
            fail(HttpStatus.NOT_FOUND, "ARTIFACT_NOT_FOUND", "Artifact was not found", artifactId);
        }
        return archivedMedia(userId, artifact);
    }

    private ArchivedMedia archivedMedia(Long userId, AidRuntimeArtifact artifact) {
        if (!"FINALIZED".equals(artifact.getStatus())
                || !MEDIA_ARTIFACT_TYPES.contains(artifact.getArtifactType())
                || !"aid-media/v1".equals(artifact.getSchemaVersion())) {
            fail(HttpStatus.UNPROCESSABLE_ENTITY, "ARTIFACT_NOT_ARCHIVED_MEDIA",
                    "Artifact is not a finalized archived media result", artifact.getArtifactType());
        }
        JSONObject metadata = object(artifact.getMetadataJson(), "metadata");
        JSONObject resourceRef = object(artifact.getResourceRefJson(), "resourceRef");
        if (!"MEDIA_ARCHIVE_RECEIPT_SHA256".equals(metadata.getString("contentHashKind"))
                || !"ARCHIVED".equals(metadata.getString("technicalStatus"))
                || !Boolean.FALSE.equals(metadata.getBoolean("creativeQualityVerified"))) {
            fail(HttpStatus.INTERNAL_SERVER_ERROR, "ARTIFACT_MEDIA_RECEIPT_CORRUPT",
                    "Media artifact receipt is invalid", artifact.getId());
        }
        MediaIdentity identity = mediaIdentity(resourceRef);
        AidMediaTask task = mediaTaskMapper.selectById(identity.mediaTaskId());
        if (task == null || !Objects.equals(task.getUserId(), userId)
                || !"SUCCEEDED".equals(task.getStatus())) {
            fail(HttpStatus.NOT_FOUND, "ARTIFACT_MEDIA_NOT_FOUND",
                    "Archived media result was not found", artifact.getId());
        }
        String logicalMediaType;
        try {
            logicalMediaType = mediaArtifactTypes.requireForRead(userId, artifact, task);
        } catch (MediaArtifactTypeResolver.InvalidMediaArtifactIdentityException invalid) {
            fail(HttpStatus.NOT_FOUND, "ARTIFACT_MEDIA_NOT_FOUND",
                    "Archived media result was not found", artifact.getId());
            return null;
        }
        if (!Objects.equals(logicalMediaType, metadata.getString("mediaType"))
                || !Objects.equals(task.getMediaType(), metadata.getString("storageMediaType"))) {
            corruptMedia(artifact.getId());
        }
        String resourceUrl;
        String mimeType = null;
        Long fileSize = null;
        Long durationSeconds = task.getOutputDurationSeconds();
        String mediaMetadataDigest = null;
        Long resultId = null;
        if (identity.resultIndex() == null) {
            long resultCount = mediaResultMapper.selectCount(new LambdaQueryWrapper<AidMediaResult>()
                    .eq(AidMediaResult::getTaskId, task.getId()));
            if (resultCount != 0) corruptMedia(artifact.getId());
            resourceUrl = task.getOssUrl();
        } else {
            AidMediaResult result = mediaResultMapper.selectOne(new LambdaQueryWrapper<AidMediaResult>()
                    .eq(AidMediaResult::getTaskId, task.getId())
                    .eq(AidMediaResult::getResultIndex, identity.resultIndex())
                    .last("LIMIT 1"));
            if (result == null || !Objects.equals(result.getMediaType(),
                    mediaArtifactTypes.resultMediaType(logicalMediaType))) {
                fail(HttpStatus.NOT_FOUND, "ARTIFACT_MEDIA_NOT_FOUND",
                        "Archived media result was not found", artifact.getId());
            }
            resourceUrl = result.getOssUrl();
            resultId = result.getId();
            mimeType = trim(result.getMimeType());
            fileSize = result.getFileSize();
            durationSeconds = result.getDurationSeconds() == null
                    ? null : result.getDurationSeconds().longValue();
            if (StrUtil.isNotBlank(result.getMetadataJson())) {
                try { JSON.parse(result.getMetadataJson()); }
                catch (RuntimeException invalid) { corruptMedia(artifact.getId()); }
                mediaMetadataDigest = SecureUtil.sha256(result.getMetadataJson());
            }
        }
        if (StrUtil.isBlank(resourceUrl)) corruptMedia(artifact.getId());
        String receiptHash = mediaReceiptHash(resourceRef, logicalMediaType, task.getMediaType(), mimeType,
                fileSize, durationSeconds, mediaMetadataDigest);
        if (!Objects.equals(receiptHash, artifact.getContentHash())) corruptMedia(artifact.getId());
        resourceUrl = authorizedAbsoluteMediaUrl(resourceUrl, artifact.getId());

        ArtifactViews.Media result = new ArtifactViews.Media();
        result.setArtifactId(String.valueOf(artifact.getId()));
        result.setArtifactType(artifact.getArtifactType());
        result.setMediaTaskId(String.valueOf(task.getId()));
        result.setResultIndex(identity.resultIndex());
        result.setResourceUrl(resourceUrl);
        result.setMimeType(mimeType);
        result.setFileSize(fileSize);
        result.setDurationSeconds(durationSeconds);
        result.setArchiveStatus("ARCHIVED");
        result.setContentHash(artifact.getContentHash());
        result.setContentHashKind("MEDIA_ARCHIVE_RECEIPT_SHA256");
        result.setCreativeQualityVerified(false);
        return new ArchivedMedia(result, resultId);
    }

    private String authorizedAbsoluteMediaUrl(String storedUrl, Long artifactId) {
        String resolved = mediaUrlResolver.toFullUrl(storedUrl);
        try {
            URI uri = URI.create(resolved);
            if (uri.getScheme() == null || uri.getHost() == null || uri.getUserInfo() != null
                    || !("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))) {
                throw new IllegalArgumentException("Media URL is not absolute HTTP(S)");
            }
            return resolved;
        } catch (RuntimeException unavailable) {
            fail(HttpStatus.SERVICE_UNAVAILABLE, "ARTIFACT_MEDIA_URL_UNAVAILABLE",
                    "Archived media URL cannot be projected with the current storage configuration", artifactId);
            return null;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public ArtifactViews.ContentPage content(Long userId, ArtifactRequests.Content request) {
        requireUser(userId);
        AidRuntimeArtifact artifact = requireArtifact(userId, request.getArtifactId());
        if (MEDIA_ARTIFACT_TYPES.contains(artifact.getArtifactType())) {
            fail(HttpStatus.UNPROCESSABLE_ENTITY, "ARTIFACT_NOT_TEXT",
                    "Media artifacts must be read through the media endpoint", artifact.getArtifactType());
        }
        int after = request.getAfterChunkIndex() == null ? -1 : request.getAfterChunkIndex();
        int limit = limit(request.getLimit());
        List<AidRuntimeArtifactChunk> rows = chunkMapper.selectAuthorizedPage(
                userId, artifact.getId(), after, limit + 1);
        boolean more = rows.size() > limit;
        if (more) rows = new ArrayList<>(rows.subList(0, limit));
        int expected = after + 1;
        List<ArtifactViews.Chunk> views = new ArrayList<>();
        for (AidRuntimeArtifactChunk row : rows) {
            validateChunk(row, expected++);
            views.add(chunk(row));
        }
        int count = chunkMapper.countAuthorized(userId, artifact.getId());
        Integer next = rows.isEmpty() ? after
                : rows.get(rows.size() - 1).getChunkIndex();
        boolean atEnd = !more && ((count == 0 && after < 0)
                || (next != null && next == count - 1)
                || (rows.isEmpty() && after >= count - 1));
        ArtifactViews.ContentPage result = new ArtifactViews.ContentPage();
        result.setArtifactId(String.valueOf(artifact.getId()));
        result.setContentHash(artifact.getContentHash());
        result.setChunks(views);
        result.setNextAfterChunkIndex(next);
        result.setComplete("FINALIZED".equals(artifact.getStatus()) && atEnd);
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public ArtifactViews.Page<ArtifactViews.Review> reviews(Long userId, ArtifactRequests.ReviewPage request) {
        requireUser(userId);
        requireArtifact(userId, request.getArtifactId());
        int limit = limit(request.getLimit());
        List<AidRuntimeArtifactReview> rows = reviewMapper.selectAuthorizedPage(
                userId, request.getArtifactId(), request.getCursor(), limit + 1);
        boolean more = rows.size() > limit;
        if (more) rows = new ArrayList<>(rows.subList(0, limit));
        ArtifactViews.Page<ArtifactViews.Review> result = new ArtifactViews.Page<>();
        result.setItems(rows.stream().map(this::review).toList());
        if (more && !rows.isEmpty()) result.setNextCursor(String.valueOf(rows.get(rows.size() - 1).getId()));
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public ArtifactViews.CoverageProgress coverage(Long userId, ArtifactRequests.CoveragePage request) {
        requireUser(userId);
        AidRuntimeSourceRevision source = sourceRevisionMapper.selectAuthorized(userId, request.getSourceRevisionId());
        if (source == null) fail(HttpStatus.NOT_FOUND, "SOURCE_REVISION_NOT_FOUND",
                "Source revision was not found", request.getSourceRevisionId());
        JSONObject manifest = requireRunManifest(userId, request.getRunId());
        contextAuthorizer.requireRead(manifest.toJSONString(), source.getSourceKind(),
                source.getSourceObjectId(), source.getContentHash());
        int limit = limit(request.getLimit());
        List<AidRuntimeSourceCoverage> rows = coverageMapper.selectAuthorizedPage(userId, request.getRunId(),
                request.getSourceRevisionId(), request.getCursor(), limit + 1);
        boolean more = rows.size() > limit;
        if (more) rows = new ArrayList<>(rows.subList(0, limit));
        ArtifactViews.CoverageProgress result = new ArtifactViews.CoverageProgress();
        result.setSourceRevisionId(String.valueOf(source.getId()));
        result.setTotalChunks(source.getChunkCount());
        result.setCoveredChunks(coverageMapper.countAuthorizedCovered(
                userId, request.getRunId(), request.getSourceRevisionId()));
        result.setItems(rows.stream().map(this::coverage).toList());
        if (more && !rows.isEmpty()) result.setNextCursor(String.valueOf(rows.get(rows.size() - 1).getId()));
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public ArtifactViews.Page<ArtifactViews.SourceRevision> sources(
            Long userId, ArtifactRequests.SourcePage request) {
        requireUser(userId);
        JSONObject manifest = requireRunManifest(userId, request.getRunId());
        JSONArray refs = manifest.getJSONArray("refs");
        int limit = limit(request.getLimit());
        Set<Long> seen = new LinkedHashSet<>();
        List<AidRuntimeSourceRevision> matched = new ArrayList<>();
        if (refs != null) {
            for (Object item : refs) {
                if (!(item instanceof JSONObject ref) || !"READ".equals(ref.getString("access"))) continue;
                JSONObject canonical = ref.getJSONObject("canonicalRef");
                String kind = trim(ref.getString("kind"));
                if (canonical == null || kind == null) continue;
                kind = kind.toUpperCase(Locale.ROOT);
                String objectId = "SCRIPT".equals(kind)
                        ? canonical.getString("scriptId") : "DOCUMENT".equals(kind)
                        ? canonical.getString("documentId") : canonical.getString("sourceObjectId");
                String version = canonical.getString("version");
                String contentHash = canonical.getString("contentHash");
                if (StrUtil.isBlank(objectId) || StrUtil.isBlank(version)
                        || contentHash == null || !contentHash.matches("[0-9a-f]{64}")) continue;
                AidRuntimeSourceRevision source = sourceRevisionMapper.selectImmutable(
                        userId, kind, objectId, storedSourceRevision(kind, objectId, version, contentHash), contentHash);
                if (source != null && "ACTIVE".equals(source.getStatus()) && seen.add(source.getId())
                        && (request.getCursor() == null || source.getId() < request.getCursor())) {
                    matched.add(source);
                }
            }
        }
        matched.sort(Comparator.comparing(AidRuntimeSourceRevision::getId).reversed());
        boolean more = matched.size() > limit;
        if (more) matched = new ArrayList<>(matched.subList(0, limit));
        ArtifactViews.Page<ArtifactViews.SourceRevision> result = new ArtifactViews.Page<>();
        result.setItems(matched.stream().map(this::sourceRevision).toList());
        if (more && !matched.isEmpty()) {
            result.setNextCursor(String.valueOf(matched.get(matched.size() - 1).getId()));
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public ArtifactViews.SourceManifest sourceManifest(
            Long userId, ArtifactRequests.SourceManifest request) {
        requireUser(userId);
        RunSourceContext context = requireRunSourceContext(userId, request.getRunId());
        ArtifactViews.SourceManifest result = new ArtifactViews.SourceManifest();
        result.setRunId(String.valueOf(context.run().getId()));
        result.setContextDigest(context.run().getContextDigest());
        result.setRunRevision(context.run().getStateRevision());
        result.setRunStatus(context.run().getStatus());
        if (Set.of("WAITING_APPROVAL", "WAITING_INPUT", "PAUSED").contains(context.run().getStatus())) {
            result.setAllowedActions(List.of("REFRESH_CONTEXT"));
        }

        JSONArray refs = context.manifest().getJSONArray("refs");
        List<ArtifactViews.SourceManifestItem> items = new ArrayList<>();
        if (refs != null) {
            for (Object raw : refs) {
                if (!(raw instanceof JSONObject ref)) continue;
                if ("DOCUMENT".equals(ref.getString("kind"))) {
                    JSONObject canonical = ref.getJSONObject("canonicalRef");
                    String documentId = canonical == null ? null : canonical.getString("documentId");
                    String version = canonical == null ? null : canonical.getString("version");
                    String hash = canonical == null ? null : canonical.getString("contentHash");
                    if (canonical == null || !"READ".equals(ref.getString("access")) || !positiveId(documentId)
                            || !positiveId(version) || hash == null || !hash.matches("[0-9a-f]{64}"))
                        fail(HttpStatus.CONFLICT, "ASSISTANT_CONTEXT_STALE", "文档上下文已变更", request.getRunId());
                    AidRuntimeSourceRevision source = sourceRevisionMapper.selectImmutable(userId, "DOCUMENT", documentId,
                            storedSourceRevision("DOCUMENT", documentId, version, hash), hash);
                    if (source == null || !"ACTIVE".equals(source.getStatus()))
                        fail(HttpStatus.CONFLICT, "ASSISTANT_CONTEXT_STALE", "文档版本不可用", request.getRunId());
                    ArtifactViews.SourceManifestItem item = new ArtifactViews.SourceManifestItem();
                    item.setKind("DOCUMENT"); item.setDocumentId(documentId);
                    item.setProjectId(canonical.getString("projectId")); item.setVersion(version); item.setContentHash(hash);
                    item.setAccess("READ"); item.setSourceRevisionId(String.valueOf(source.getId()));
                    item.setSnapshotStatus("ACTIVE"); item.setNextAction("READ_SOURCE"); items.add(item);
                    continue;
                }
                if (!"SCRIPT".equals(ref.getString("kind"))) continue;
                JSONObject canonical = ref.getJSONObject("canonicalRef");
                String access = ref.getString("access");
                String scriptId = canonical == null ? null : canonical.getString("scriptId");
                String projectId = canonical == null ? null : canonical.getString("projectId");
                String episodeId = canonical == null ? null : canonical.getString("episodeId");
                String version = canonical == null ? null : canonical.getString("version");
                String hash = canonical == null ? null : canonical.getString("contentHash");
                if (canonical == null || !"READ".equals(access) || !positiveId(scriptId)
                        || !positiveId(projectId) || !nonNegativeId(episodeId) || !positiveId(version)
                        || hash == null || !hash.matches("[0-9a-f]{64}")) {
                    fail(HttpStatus.CONFLICT, "ASSISTANT_CONTEXT_STALE",
                            "Assistant run has an invalid frozen script context", request.getRunId());
                }
                AidRuntimeSourceRevision source = sourceRevisionMapper.selectImmutable(
                        userId, "SCRIPT", scriptId,
                        storedSourceRevision("SCRIPT", scriptId, version, hash), hash);
                if (source != null && !"ACTIVE".equals(source.getStatus())) source = null;
                ArtifactViews.SourceManifestItem item = new ArtifactViews.SourceManifestItem();
                item.setKind("SCRIPT");
                item.setScriptId(scriptId);
                item.setProjectId(projectId);
                item.setEpisodeId(episodeId);
                item.setVersion(version);
                item.setContentHash(hash);
                item.setSelection(canonical.get("selection"));
                item.setAccess(access);
                item.setSourceRevisionId(source == null ? null : String.valueOf(source.getId()));
                item.setSnapshotStatus(source == null ? "NOT_INGESTED" : "ACTIVE");
                item.setNextAction(source == null ? "WAIT_FOR_AGENT_INGEST" : "READ_SOURCE");
                items.add(item);
            }
        }
        result.setItems(items);
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public CreativeViews.SourceChunkPage sourceContent(Long userId, ArtifactRequests.SourceContent request) {
        requireUser(userId);
        AidRuntimeSourceRevision source = sourceRevisionMapper.selectAuthorized(
                userId, request.getSourceRevisionId());
        if (source == null || !"ACTIVE".equals(source.getStatus())) {
            fail(HttpStatus.NOT_FOUND, "SOURCE_REVISION_NOT_FOUND",
                    "Source revision was not found", request.getSourceRevisionId());
        }
        JSONObject manifest = requireRunManifest(userId, request.getRunId());
        contextAuthorizer.requireRead(manifest.toJSONString(), source.getSourceKind(),
                source.getSourceObjectId(), source.getContentHash());
        CreativeRequests.SourceChunkRead read = new CreativeRequests.SourceChunkRead();
        read.setSourceRevisionId(source.getId());
        read.setAfterChunkIndex(request.getAfterChunkIndex());
        read.setLimit(request.getLimit());
        return creativeSourceService.read(userId, read, manifest.toJSONString());
    }

    @Override
    @Transactional(readOnly = true)
    public ArtifactViews.AdoptionCapabilities adoptionCapabilities(
            Long userId, ArtifactRequests.AdoptionCapabilities request) {
        requireUser(userId);
        AidRuntimeArtifact artifact = artifactMapper.selectAuthorizedForSession(
                userId, request.getSessionId(), request.getArtifactId());
        AidAssistantSession session = requireSession(userId, request.getSessionId());
        if (artifact == null || session == null) fail(HttpStatus.NOT_FOUND, "ARTIFACT_NOT_FOUND",
                "Artifact was not found", request.getArtifactId());
        ArtifactAdoptionTarget.CapabilityContext context =
                new ArtifactAdoptionTarget.CapabilityContext(userId, artifact, session, request);
        String requestedKind = trim(request.getTargetKind());
        if (requestedKind != null) requestedKind = requestedKind.toUpperCase(Locale.ROOT);
        if (requestedKind == null && (request.getWorkspaceId() != null
                || StrUtil.isNotBlank(request.getTargetObjectId()))) {
            fail(HttpStatus.UNPROCESSABLE_ENTITY, "ARTIFACT_ADOPTION_TARGET_KIND_REQUIRED",
                    "选择具体采纳目标时必须提供目标类型", null);
        }
        if (requestedKind != null && !List.of("SCRIPT", "FREE_CANVAS").contains(requestedKind)) {
            fail(HttpStatus.UNPROCESSABLE_ENTITY, "ARTIFACT_ADOPTION_TARGET_INVALID",
                    "采纳目标类型无效", requestedKind);
        }
        if ("FREE_CANVAS".equals(requestedKind) && (request.getWorkspaceId() == null
                || StrUtil.isBlank(request.getTargetObjectId()))) {
            fail(HttpStatus.UNPROCESSABLE_ENTITY, "FREE_CANVAS_TARGET_REQUIRED",
                    "画布采纳需要工作区和当前选中对象", null);
        }
        ArtifactViews.AdoptionCapabilities result = new ArtifactViews.AdoptionCapabilities();
        result.setArtifactId(String.valueOf(artifact.getId()));
        List<ArtifactAdoptionTarget.Capability> capabilities = requestedKind == null
                ? adoptionTargets.inspect(context)
                : List.of(adoptionTargets.require(requestedKind).inspect(context));
        result.setTargets(capabilities.stream().filter(Objects::nonNull).map(this::capability).toList());
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ArtifactViews.Adoption adopt(Long userId, ArtifactRequests.Adopt request) {
        requireUser(userId);
        AidRuntimeArtifact artifact = artifactMapper.selectAuthorizedForSession(
                userId, request.getSessionId(), request.getArtifactId());
        if (artifact == null) fail(HttpStatus.NOT_FOUND, "ARTIFACT_NOT_FOUND", "Artifact was not found", request.getArtifactId());
        AidAssistantSession session = requireSession(userId, request.getSessionId());
        if (!"FINALIZED".equals(artifact.getStatus())) {
            fail(HttpStatus.CONFLICT, "ARTIFACT_NOT_FINALIZED", "Artifact is not finalized", artifact.getStatus());
        }
        if (!Objects.equals(artifact.getContentHash(), request.getContentHash())) {
            fail(HttpStatus.CONFLICT, "ARTIFACT_HASH_CONFLICT", "Artifact content hash has changed", null);
        }
        String targetKind = request.getTargetKind().trim().toUpperCase(Locale.ROOT);
        requireCanvasFields(targetKind, request);
        String digest = adoptionDigest(userId, artifact, targetKind, request);
        AidRuntimeArtifactAdoption existing = adoptionMapper.selectIdempotencyForUpdate(userId, targetKind,
                request.getTargetObjectId(), request.getClientRequestId());
        if (existing != null) return replay(existing, digest);

        AidRuntimeArtifactAdoption adoption = new AidRuntimeArtifactAdoption();
        adoption.setUserId(userId);
        adoption.setSessionId(request.getSessionId());
        adoption.setArtifactId(artifact.getId());
        adoption.setTargetKind(targetKind);
        adoption.setTargetObjectId(request.getTargetObjectId());
        adoption.setExpectedTargetRevision(request.getExpectedTargetRevision());
        adoption.setClientRequestId(request.getClientRequestId());
        adoption.setRequestDigest(digest);
        adoption.setStatus("PENDING");
        adoption.setDelFlag("0");
        adoption.setCreateBy(String.valueOf(userId));
        adoption.setCreateTime(new Date());
        adoption.setUpdateBy(String.valueOf(userId));
        adoption.setUpdateTime(new Date());
        try {
            adoptionMapper.insert(adoption);
        } catch (DuplicateKeyException duplicate) {
            AidRuntimeArtifactAdoption raced = adoptionMapper.selectIdempotencyForUpdate(userId, targetKind,
                    request.getTargetObjectId(), request.getClientRequestId());
            if (raced != null) return replay(raced, digest);
            throw duplicate;
        }

        ArtifactAdoptionTarget.Result adopted = adoptionTargets.require(targetKind)
                .adopt(new ArtifactAdoptionTarget.Context(userId, artifact, session, request));
        if (adopted == null || StrUtil.isBlank(adopted.getTargetRevision())) {
            fail(HttpStatus.INTERNAL_SERVER_ERROR, "ARTIFACT_ADOPTION_INVALID_RECEIPT",
                    "Adoption target returned no revision", targetKind);
        }
        adoption.setResultTargetRevision(adopted.getTargetRevision());
        adoption.setReceiptJson(adopted.getReceipt() == null ? null : JSON.toJSONString(adopted.getReceipt()));
        adoption.setStatus("SUCCEEDED");
        adoption.setUpdateTime(new Date());
        adoptionMapper.updateById(adoption);
        return adoption(adoption);
    }

    private AidRuntimeArtifact requireArtifact(Long userId, Long artifactId) {
        AidRuntimeArtifact artifact = artifactMapper.selectAuthorized(userId, artifactId);
        if (artifact == null) fail(HttpStatus.NOT_FOUND, "ARTIFACT_NOT_FOUND", "Artifact was not found", artifactId);
        return artifact;
    }

    private JSONObject requireRunManifest(Long userId, Long runId) {
        return requireRunSourceContext(userId, runId).manifest();
    }

    private RunSourceContext requireRunSourceContext(Long userId, Long runId) {
        AidRuntimeRun run = runMapper.selectById(runId);
        if (run == null || !Objects.equals(run.getUserId(), userId) || !"0".equals(run.getDelFlag())
                || run.getSessionId() == null) {
            fail(HttpStatus.NOT_FOUND, "ASSISTANT_RUN_NOT_FOUND", "Assistant run was not found", runId);
        }
        AidAssistantSession session = sessionMapper.selectOne(new LambdaQueryWrapper<AidAssistantSession>()
                .eq(AidAssistantSession::getId, run.getSessionId())
                .eq(AidAssistantSession::getUserId, userId)
                .eq(AidAssistantSession::getDelFlag, "0")
                .last("LIMIT 1"));
        if (session == null) {
            fail(HttpStatus.NOT_FOUND, "ASSISTANT_RUN_NOT_FOUND", "Assistant run was not found", runId);
        }
        JSONObject request = object(run.getRequestJson(), "runRequest");
        JSONObject manifest = request.getJSONObject("_contextManifest");
        if (manifest == null || !"assistant-context-manifest/v1".equals(manifest.getString("schemaVersion"))
                || StrUtil.isBlank(run.getContextDigest())
                || !Objects.equals(run.getContextDigest(), SecureUtil.sha256(manifest.toJSONString()))) {
            fail(HttpStatus.CONFLICT, "ASSISTANT_CONTEXT_STALE",
                    "Assistant run has no valid frozen source context", runId);
        }
        return new RunSourceContext(run, manifest);
    }

    private String storedSourceRevision(String kind, String objectId, String version, String contentHash) {
        if ("SCRIPT".equals(kind)) return "script:" + objectId + ":v" + version + ":" + contentHash;
        if ("DOCUMENT".equals(kind)) return "document:" + objectId + ":v" + version + ":" + contentHash;
        return version;
    }

    private boolean positiveId(String value) {
        return value != null && value.matches("[1-9][0-9]{0,18}");
    }

    private boolean nonNegativeId(String value) {
        return value != null && value.matches("(?:0|[1-9][0-9]{0,18})");
    }

    private record RunSourceContext(AidRuntimeRun run, JSONObject manifest) {
    }

    private ArtifactViews.SourceRevision sourceRevision(AidRuntimeSourceRevision row) {
        ArtifactViews.SourceRevision result = new ArtifactViews.SourceRevision();
        result.setSourceRevisionId(String.valueOf(row.getId()));
        result.setSourceKind(row.getSourceKind());
        result.setSourceObjectId(row.getSourceObjectId());
        result.setSourceRevision(row.getSourceRevision());
        result.setContentHash(row.getContentHash());
        result.setCharCount(row.getCharCount());
        result.setWordCount(row.getWordCount());
        result.setChunkCount(row.getChunkCount());
        result.setStatus(row.getStatus());
        result.setCreatedAt(instant(row.getCreateTime()));
        return result;
    }

    private AidAssistantSession requireSession(Long userId, Long sessionId) {
        AidAssistantSession session = sessionMapper.selectOne(new LambdaQueryWrapper<AidAssistantSession>()
                .eq(AidAssistantSession::getId, sessionId)
                .eq(AidAssistantSession::getUserId, userId)
                .eq(AidAssistantSession::getDelFlag, "0")
                .last("LIMIT 1"));
        if (session == null) fail(HttpStatus.NOT_FOUND, "ASSISTANT_SESSION_NOT_FOUND",
                "Assistant session was not found", sessionId);
        return session;
    }

    private ArtifactViews.Summary summary(AidRuntimeArtifact artifact,
                                          AssistantTargetViews.TargetEffect targetEffect) {
        ArtifactViews.Summary result = new ArtifactViews.Summary();
        copySummary(artifact, result);
        result.setLatestTargetEffect(targetEffect);
        return result;
    }

    private void copySummary(AidRuntimeArtifact artifact, ArtifactViews.Summary result) {
        result.setArtifactId(string(artifact.getId()));
        result.setPresentationRole(com.aid.assistant.artifact.AssistantArtifactPresentation.role(artifact));
        result.setLogicalArtifactId(artifact.getLogicalArtifactId());
        result.setRevision(artifact.getRevision());
        result.setArtifactType(artifact.getArtifactType());
        result.setSchemaVersion(artifact.getSchemaVersion());
        result.setContentHash(artifact.getContentHash());
        result.setReviewStatus(artifact.getReviewStatus());
        result.setSummary(artifact.getSummary());
        result.setStatus(artifact.getStatus());
        result.setProducerRunId(string(artifact.getProducerRunId()));
        result.setProducerStepId(string(artifact.getProducerStepId()));
        result.setMetadata(json(artifact.getMetadataJson(), "metadata"));
        result.setCreatedAt(instant(artifact.getCreateTime()));
    }

    private ArtifactViews.Chunk chunk(AidRuntimeArtifactChunk row) {
        ArtifactViews.Chunk result = new ArtifactViews.Chunk();
        result.setChunkIndex(row.getChunkIndex());
        result.setCharStart(row.getCharStart());
        result.setCharEnd(row.getCharEnd());
        result.setCharCount(row.getCharCount());
        result.setWordCount(row.getWordCount());
        result.setContentHash(row.getContentHash());
        result.setContent(row.getContentText());
        return result;
    }

    private ArtifactViews.Review review(AidRuntimeArtifactReview row) {
        ArtifactViews.Review result = new ArtifactViews.Review();
        result.setReviewId(string(row.getId()));
        result.setReviewArtifactId(string(row.getReviewArtifactId()));
        result.setVerdict(row.getVerdict());
        result.setSeverityCounts(json(row.getSeverityCountsJson(), "severityCounts"));
        result.setContentHash(row.getContentHash());
        result.setCreatedAt(instant(row.getCreateTime()));
        return result;
    }

    private ArtifactViews.Coverage coverage(AidRuntimeSourceCoverage row) {
        ArtifactViews.Coverage result = new ArtifactViews.Coverage();
        result.setCoverageId(string(row.getId()));
        result.setSourceRevisionId(string(row.getSourceRevisionId()));
        result.setChunkIndex(row.getChunkIndex());
        result.setPurpose(row.getPurpose());
        result.setCoverageStatus(row.getCoverageStatus());
        result.setEvidenceArtifactId(string(row.getEvidenceArtifactId()));
        result.setRevision(row.getRevision());
        result.setNotes(row.getNotes());
        result.setUpdatedAt(instant(row.getUpdateTime()));
        return result;
    }

    private ArtifactViews.Adoption replay(AidRuntimeArtifactAdoption existing, String digest) {
        if (!Objects.equals(existing.getRequestDigest(), digest)) {
            fail(HttpStatus.CONFLICT, "ARTIFACT_ADOPTION_IDEMPOTENCY_CONFLICT",
                    "Client request id was already used with different input", existing.getId());
        }
        if ("SUCCEEDED".equals(existing.getStatus())) return adoption(existing);
        if ("FAILED".equals(existing.getStatus())) {
            fail(HttpStatus.CONFLICT, existing.getErrorCode() == null ? "ARTIFACT_ADOPTION_FAILED" : existing.getErrorCode(),
                    "The previous adoption attempt failed", existing.getId());
        }
        fail(HttpStatus.CONFLICT, "ARTIFACT_ADOPTION_IN_PROGRESS", "Adoption is already in progress", existing.getId());
        return null;
    }

    private ArtifactViews.Adoption adoption(AidRuntimeArtifactAdoption row) {
        ArtifactViews.Adoption result = new ArtifactViews.Adoption();
        result.setAdoptionId(string(row.getId()));
        result.setArtifactId(string(row.getArtifactId()));
        result.setTargetKind(row.getTargetKind());
        result.setTargetObjectId(row.getTargetObjectId());
        result.setTargetRevision(row.getResultTargetRevision());
        result.setStatus(row.getStatus());
        result.setReceipt(json(row.getReceiptJson(), "receipt"));
        return result;
    }

    private ArtifactViews.AdoptionCapability capability(ArtifactAdoptionTarget.Capability source) {
        ArtifactViews.AdoptionCapability result = new ArtifactViews.AdoptionCapability();
        result.setTargetKind(source.getTargetKind());
        result.setTargetObjectId(source.getTargetObjectId());
        result.setCurrentTargetRevision(source.getCurrentTargetRevision());
        result.setAllowed(source.isAllowed());
        result.setReasonCode(source.getReasonCode());
        result.setDisplayName(source.getDisplayName());
        result.setRequiredContext(source.getRequiredContext());
        return result;
    }

    private void validateChunk(AidRuntimeArtifactChunk row, int expectedIndex) {
        if (row.getChunkIndex() == null || row.getChunkIndex() != expectedIndex
                || row.getCharStart() == null || row.getCharEnd() == null || row.getCharCount() == null
                || row.getCharEnd() < row.getCharStart()
                || row.getCharEnd() - row.getCharStart() != row.getCharCount()
                || row.getContentText() == null || row.getContentText().length() != row.getCharCount()
                || !SecureUtil.sha256(row.getContentText()).equals(row.getContentHash())) {
            fail(HttpStatus.INTERNAL_SERVER_ERROR, "ARTIFACT_CHUNK_CORRUPT",
                    "Artifact chunk integrity check failed", row.getChunkIndex());
        }
    }

    private String adoptionDigest(Long userId, AidRuntimeArtifact artifact, String targetKind,
                                  ArtifactRequests.Adopt request) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("userId", userId);
        canonical.put("sessionId", request.getSessionId());
        canonical.put("artifactId", artifact.getId());
        canonical.put("contentHash", artifact.getContentHash());
        canonical.put("targetKind", targetKind);
        canonical.put("targetObjectId", request.getTargetObjectId());
        canonical.put("expectedTargetRevision", request.getExpectedTargetRevision());
        canonical.put("workspaceId", request.getWorkspaceId());
        canonical.put("editSessionId", request.getEditSessionId());
        canonical.put("runtimeLeaseId", request.getRuntimeLeaseId());
        canonical.put("clientRequestId", request.getClientRequestId());
        return SecureUtil.sha256(JSON.toJSONString(canonical));
    }

    private void requireCanvasFields(String targetKind, ArtifactRequests.Adopt request) {
        if ("FREE_CANVAS".equals(targetKind) && (request.getWorkspaceId() == null
                || StrUtil.isBlank(request.getEditSessionId()) || StrUtil.isBlank(request.getRuntimeLeaseId()))) {
            fail(HttpStatus.UNPROCESSABLE_ENTITY, "FREE_CANVAS_CONTEXT_REQUIRED",
                    "Free canvas adoption requires workspace, edit session and runtime lease", null);
        }
    }

    private Object json(String value, String field) {
        if (StrUtil.isBlank(value)) return null;
        try {
            return JSON.parse(value);
        } catch (RuntimeException invalid) {
            fail(HttpStatus.INTERNAL_SERVER_ERROR, "ARTIFACT_JSON_CORRUPT",
                    "Artifact JSON field is invalid", field);
            return null;
        }
    }

    private JSONObject object(String value, String field) {
        try {
            JSONObject parsed = JSON.parseObject(value);
            if (parsed == null) throw new IllegalArgumentException();
            return parsed;
        } catch (RuntimeException invalid) {
            fail(HttpStatus.INTERNAL_SERVER_ERROR, "ARTIFACT_JSON_CORRUPT",
                    "Artifact JSON field is invalid", field);
            return null;
        }
    }

    private MediaIdentity mediaIdentity(JSONObject ref) {
        String kind = ref.getString("kind");
        Long taskId;
        try { taskId = Long.valueOf(ref.getString("mediaTaskId")); }
        catch (RuntimeException invalid) { taskId = null; }
        if (taskId == null || taskId < 1) {
            fail(HttpStatus.INTERNAL_SERVER_ERROR, "ARTIFACT_MEDIA_RECEIPT_CORRUPT",
                    "Media artifact reference is invalid", null);
        }
        if ("MEDIA_TASK".equals(kind) && ref.size() == 2) return new MediaIdentity(taskId, null);
        Integer resultIndex = ref.getInteger("resultIndex");
        if ("MEDIA_RESULT".equals(kind) && ref.size() == 3
                && resultIndex != null && resultIndex >= 0) {
            return new MediaIdentity(taskId, resultIndex);
        }
        fail(HttpStatus.INTERNAL_SERVER_ERROR, "ARTIFACT_MEDIA_RECEIPT_CORRUPT",
                "Media artifact reference is invalid", null);
        return null;
    }

    private String mediaReceiptHash(JSONObject resourceRef, String mediaType, String storageMediaType,
                                    String mimeType,
                                    Long fileSize, Long durationSeconds, String mediaMetadataDigest) {
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("receiptVersion", "aid-media-archive-receipt/v1");
        receipt.put("resourceRef", resourceRef);
        receipt.put("mediaType", mediaType);
        receipt.put("storageMediaType", storageMediaType);
        receipt.put("archiveState", "OSS_PERSISTED");
        receipt.put("mimeType", mimeType);
        receipt.put("fileSize", fileSize);
        receipt.put("durationSeconds", durationSeconds);
        receipt.put("mediaMetadataDigest", mediaMetadataDigest);
        return SecureUtil.sha256(JSON.toJSONString(receipt));
    }

    private void corruptMedia(Long artifactId) {
        fail(HttpStatus.INTERNAL_SERVER_ERROR, "ARTIFACT_MEDIA_RECEIPT_CORRUPT",
                "Media artifact receipt is invalid", artifactId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(String value) {
        Object parsed = json(value, "metadata");
        return parsed instanceof Map<?, ?> ? (Map<String, Object>) parsed : Map.of();
    }

    private Integer integer(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value instanceof String text) {
            try { return Integer.valueOf(text); } catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    private int limit(Integer value) { return value == null ? 20 : Math.max(1, Math.min(value, 100)); }
    private String trim(String value) { return StrUtil.isBlank(value) ? null : value.trim(); }
    private String string(Object value) { return value == null ? null : String.valueOf(value); }
    private String instant(Date value) { return value == null ? null : Instant.ofEpochMilli(value.getTime()).toString(); }

    private void requireUser(Long userId) {
        if (userId == null || userId < 1) fail(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication is required", null);
    }

    private void fail(HttpStatus status, String code, String message, Object details) {
        throw new AssistantServiceException(status.value(), code, message, details);
    }

    private record MediaIdentity(Long mediaTaskId, Integer resultIndex) {
    }
}
