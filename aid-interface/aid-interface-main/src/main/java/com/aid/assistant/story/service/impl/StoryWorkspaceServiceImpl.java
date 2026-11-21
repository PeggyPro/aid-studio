package com.aid.assistant.story.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.aid.aid.domain.AidComicProject;
import com.aid.aid.mapper.AidComicProjectMapper;
import com.aid.canvas.runtime.CanvasStoryScopeAccess;
import com.aid.assistant.artifact.domain.AidRuntimeArtifact;
import com.aid.assistant.artifact.domain.AidRuntimeArtifactChunk;
import com.aid.assistant.artifact.domain.AidRuntimeArtifactReview;
import com.aid.assistant.artifact.domain.AidRuntimeSourceRevision;
import com.aid.assistant.artifact.domain.AidRuntimeSourceChunk;
import com.aid.assistant.artifact.dto.ArtifactWriteRequests;
import com.aid.assistant.artifact.mapper.AidRuntimeArtifactChunkMapper;
import com.aid.assistant.artifact.mapper.AidRuntimeArtifactMapper;
import com.aid.assistant.artifact.mapper.AidRuntimeArtifactReviewMapper;
import com.aid.assistant.artifact.mapper.AidRuntimeSourceRevisionMapper;
import com.aid.assistant.artifact.mapper.AidRuntimeSourceChunkMapper;
import com.aid.assistant.artifact.service.AssistantArtifactWriter;
import com.aid.assistant.artifact.vo.ArtifactRef;
import com.aid.assistant.creative.dto.CreativeAuditRequests;
import com.aid.assistant.creative.dto.CreativeRequests;
import com.aid.assistant.creative.service.CreativeAuditService;
import com.aid.assistant.creative.service.CreativeContextAuthorizer;
import com.aid.assistant.creative.service.CreativeSourceService;
import com.aid.assistant.creative.service.CreativeTextChunker;
import com.aid.assistant.creative.service.CreativeTextSegmenter;
import com.aid.assistant.creative.vo.CreativeViews;
import com.aid.assistant.domain.AidAssistantSession;
import com.aid.assistant.mapper.AidAssistantSessionMapper;
import com.aid.assistant.story.dto.StoryWorkspaceRequests;
import com.aid.assistant.story.service.StoryWorkspaceService;
import com.aid.assistant.story.service.StoryReviewCorrectionPolicy;
import com.aid.assistant.story.vo.StoryWorkspaceViews;
import com.aid.runtime.assistant.admission.RuntimeOperationSubmissionClaim;
import com.aid.runtime.assistant.admission.RuntimeOperationSubmissionDecision;
import com.aid.runtime.assistant.admission.RuntimeOperationSubmissionGuard;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.mapper.AidRuntimeRunMapper;
import com.aid.runtime.tool.RuntimeToolInvocation;
import com.aid.runtime.tool.RuntimeToolPreparedInvocation;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Project-scoped screenplay files backed by immutable Runtime artifact revisions. */
@Service
public class StoryWorkspaceServiceImpl implements StoryWorkspaceService {
    private static final String WORKSPACE_SCHEMA = "story-workspace/v2";
    private static final String FILE_SCHEMA = "story-file/v1";
    private static final String REVIEW_SCHEMA = "story-review/v1";
    private static final String FRAGMENT_SCHEMA = "story-fragment/v1";
    private static final String CHECKPOINT_SCHEMA = "story-checkpoint/v1";
    private static final int MAX_CONTENT_CHARS = 80_000;
    private static final int MAX_LONG_CONTENT_CHARS = 500_000;
    private static final int MAX_LINE_UTF8_BYTES = 32_768;
    private static final int MAX_SOURCE_CHARS = 5_000_000;
    private static final int MAX_FACTS = 2_000;
    private static final int MAX_UPLOADS = 20;
    private static final int MAX_DRAFTS = 200;
    private static final int MAX_CHECKPOINTS = 200;
    private static final int MAX_FILES = 2_500;
    private static final int ARTIFACT_AUTHORIZATION_BATCH_SIZE = 200;
    private static final Set<String> MODES = Set.of("ORIGINAL", "ADAPTATION");
    private static final Set<String> ROOT_FILES = Set.of(
            "skeleton.md", "characters.md", "relations.md", "locations.md", "props.md", "story_events.md");
    private static final Pattern ARC = Pattern.compile("arcs/ARC(?!000)[0-9]{3}/arc\\.md");
    private static final Pattern EPISODE_SKELETON = Pattern.compile(
            "episodes_skeleton/EP(?!000)[0-9]{3}_skeleton\\.md");
    private static final Pattern EPISODE_SCRIPT = Pattern.compile("scripts/EP(?!000)[0-9]{3}\\.md");
    private static final Pattern UPLOAD_KEY = Pattern.compile("[A-Za-z0-9._:-]{1,64}");
    private static final Pattern STRONG_CHAPTER = Pattern.compile(
            "^(?:#{1,6}\\s*)?(?:第[零〇一二三四五六七八九十百千万两0-9]{1,12}[章节回集卷篇幕](?:\\s+.*)?|(?i:chapter)\\s+(?:[0-9]{1,6}|[ivxlcdm]{1,16})(?:[\\s:：.\\-]+.*)?)$");

    @Resource private RuntimeOperationSubmissionGuard submissionGuard;
    @Resource private AidRuntimeRunMapper runMapper;
    @Resource private AidAssistantSessionMapper sessionMapper;
    @Resource private AidComicProjectMapper projectMapper;
    @Resource private CanvasStoryScopeAccess canvasStoryScopeAccess;
    @Resource private AidRuntimeArtifactMapper artifactMapper;
    @Resource private AidRuntimeArtifactChunkMapper chunkMapper;
    @Resource private AidRuntimeArtifactReviewMapper reviewMapper;
    @Resource private AidRuntimeSourceRevisionMapper sourceRevisionMapper;
    @Resource private AidRuntimeSourceChunkMapper sourceChunkMapper;
    @Resource private AssistantArtifactWriter artifactWriter;
    @Resource private CreativeContextAuthorizer contextAuthorizer;
    @Resource private CreativeSourceService sourceService;
    @Resource private CreativeAuditService auditService;

    @Override
    public StoryWorkspaceRequests.PreparedInitialize prepareInitialize(
            RuntimeToolInvocation invocation, StoryWorkspaceRequests.Initialize request) {
        require(request != null, "request");
        String mode = canonicalMode(request.getMode());
        Scope scope = requireScope(invocation);
        AidRuntimeArtifact workspace = latest(scope.userId(), workspaceLogicalId(scope.projectId()));
        if (workspace != null) requireWorkspace(workspace, scope, mode);
        StoryWorkspaceRequests.Initialize canonical = new StoryWorkspaceRequests.Initialize();
        canonical.setMode(mode);
        StoryWorkspaceRequests.PreparedInitialize prepared = new StoryWorkspaceRequests.PreparedInitialize();
        prepared.setRequest(canonical);
        prepared.setScope(freeze(scope, workspace, mode));
        return prepared;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public StoryWorkspaceViews.Workspace initialize(RuntimeToolPreparedInvocation invocation,
                                                     StoryWorkspaceRequests.PreparedInitialize prepared,
                                                     RuntimeOperationSubmissionClaim claim) {
        RuntimeOperationSubmissionDecision decision = submissionGuard.claim(claim);
        requirePrepared(invocation, prepared == null ? null : prepared.getScope());
        Scope scope = requireScope(invocation);
        requireFrozen(scope, prepared.getScope());
        require(prepared.getRequest() != null
                && Objects.equals(canonicalMode(prepared.getRequest().getMode()), prepared.getScope().getMode()),
                "prepared mode");
        AidRuntimeArtifact existing = latest(scope.userId(), workspaceLogicalId(scope.projectId()));
        if (existing != null) {
            requireWorkspace(existing, scope, prepared.getRequest().getMode());
            return workspaceView(scope, existing);
        }
        if (decision == RuntimeOperationSubmissionDecision.RECONCILE_ONLY) receiptMissing();
        JSONObject content = emptyWorkspace(scope.projectId(), prepared.getRequest().getMode());
        ArtifactRef workspace = writeArtifact(invocation, workspaceLogicalId(scope.projectId()), null,
                "CREATIVE_TEXT", WORKSPACE_SCHEMA, workspaceJson(content), "Screenplay workspace", new JSONArray(),
                metadata("STORY_WORKSPACE", scope, null, prepared.getRequest().getMode()));
        return workspaceView(scope, requireArtifact(scope.userId(), workspace.getArtifactId()));
    }

    @Override
    public StoryWorkspaceViews.Workspace inspect(RuntimeToolInvocation invocation) {
        Scope scope = requireScope(invocation);
        return workspaceView(scope, requireWorkspace(scope));
    }

    @Override
    public StoryWorkspaceViews.ArtifactContent read(RuntimeToolInvocation invocation,
                                                    StoryWorkspaceRequests.ArtifactRead request) {
        Scope scope = requireScope(invocation);
        AidRuntimeArtifact workspace = requireWorkspace(scope);
        JSONObject manifest = workspaceContent(workspace, scope);
        String path = canonicalPath(request == null ? null : request.getPath(), manifest.getString("mode"));
        JSONObject entry = requireFileEntry(manifest, path);
        AidRuntimeArtifact artifact = requireEntryArtifact(scope, path, entry);
        String content = readContent(artifact);
        require(content.length() <= MAX_CONTENT_CHARS, "artifact requires lines read");
        StoryWorkspaceViews.ArtifactContent view = new StoryWorkspaceViews.ArtifactContent();
        copyReceipt(view, scope, path, artifact, workspace);
        view.setArtifactType(artifact.getArtifactType());
        view.setContent(content);
        return view;
    }

    @Override
    public StoryWorkspaceRequests.PreparedWrite prepareWrite(
            RuntimeToolInvocation invocation, StoryWorkspaceRequests.ArtifactWrite request) {
        Scope scope = requireScope(invocation);
        AidRuntimeArtifact workspace = requireWorkspace(scope);
        JSONObject manifest = workspaceContent(workspace, scope);
        StoryWorkspaceRequests.ArtifactWrite canonical = canonicalWrite(request, manifest.getString("mode"));
        JSONObject entry = fileEntry(manifest, canonical.getPath());
        AidRuntimeArtifact current = entry == null ? null : requireEntryArtifact(scope, canonical.getPath(), entry);
        if ("FINAL".equals(canonical.getDisposition())) {
            require(current != null || manifest.getJSONObject("files").size() < MAX_FILES, "file capacity");
        }
        requireExpected(current, canonical.getExpectedRevision(), canonical.getExpectedContentHash());
        JSONArray sourceRefs = mergeSourceRefs(current == null ? null : current.getSourceContextRefsJson(),
                sourceRefs(scope, invocation.getContextManifestJson(), canonical.getSources()));
        if ("DRAFT".equals(canonical.getDisposition())) {
            require(manifest.getJSONObject("drafts").containsKey(canonical.getPath())
                    || manifest.getJSONObject("drafts").size() < MAX_DRAFTS, "draft capacity");
        }
        StoryWorkspaceRequests.PreparedWrite prepared = new StoryWorkspaceRequests.PreparedWrite();
        prepared.setScope(freeze(scope, workspace, manifest.getString("mode")));
        prepared.setRequest(canonical);
        prepared.setLogicalArtifactId("DRAFT".equals(canonical.getDisposition())
                ? draftLogicalId(scope.projectId(), canonical.getPath())
                : fileLogicalId(scope.projectId(), canonical.getPath()));
        prepared.setArtifactType(artifactType(canonical.getPath()));
        prepared.setSourceRefsJson(sourceRefs.toJSONString());
        if (current != null) {
            prepared.setCurrentArtifactId(current.getId());
            prepared.setCurrentArtifactRef(ref(current));
        }
        return prepared;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public StoryWorkspaceViews.ArtifactReceipt write(RuntimeToolPreparedInvocation invocation,
                                                      StoryWorkspaceRequests.PreparedWrite prepared,
                                                      RuntimeOperationSubmissionClaim claim) {
        RuntimeOperationSubmissionDecision decision = submissionGuard.claim(claim);
        requirePrepared(invocation, prepared == null ? null : prepared.getScope());
        Scope scope = requireScope(invocation);
        requireFrozen(scope, prepared.getScope());
        if (decision == RuntimeOperationSubmissionDecision.RECONCILE_ONLY) {
            StoryWorkspaceViews.ArtifactReceipt replay = reconcileWrite(invocation, prepared);
            if (replay == null) receiptMissing();
            return replay;
        }
        AidRuntimeArtifact workspace = requireFrozenWorkspace(scope, prepared.getScope());
        JSONObject manifest = workspaceContent(workspace, scope);
        StoryWorkspaceRequests.ArtifactWrite request = canonicalWrite(prepared.getRequest(), manifest.getString("mode"));
        String expectedLogicalId = "DRAFT".equals(request.getDisposition())
                ? draftLogicalId(scope.projectId(), request.getPath())
                : fileLogicalId(scope.projectId(), request.getPath());
        require(Objects.equals(prepared.getLogicalArtifactId(), expectedLogicalId)
                && Objects.equals(prepared.getArtifactType(), artifactType(request.getPath())), "prepared artifact");
        JSONObject currentEntry = fileEntry(manifest, request.getPath());
        AidRuntimeArtifact current = currentEntry == null ? null : requireEntryArtifact(scope, request.getPath(), currentEntry);
        if ("FINAL".equals(request.getDisposition())) {
            require(current != null || manifest.getJSONObject("files").size() < MAX_FILES, "file capacity");
        }
        JSONArray sourceRefs = mergeSourceRefs(current == null ? null : current.getSourceContextRefsJson(),
                sourceRefs(scope, invocation.getContextManifestJson(), request.getSources()));
        require(Objects.equals(sourceRefs.toJSONString(), prepared.getSourceRefsJson()), "prepared sourceRefs");
        requireExpected(current, request.getExpectedRevision(), request.getExpectedContentHash());
        require(current == null
                        ? prepared.getCurrentArtifactId() == null && prepared.getCurrentArtifactRef() == null
                        : Objects.equals(current.getId(), prepared.getCurrentArtifactId())
                        && Objects.equals(ref(current), prepared.getCurrentArtifactRef()),
                "prepared current artifact");
        AidRuntimeArtifact previousLogical = latest(scope.userId(), prepared.getLogicalArtifactId());
        ArtifactRef written = writeArtifact(invocation, prepared.getLogicalArtifactId(),
                "DRAFT".equals(request.getDisposition())
                        ? previousLogical == null ? null : previousLogical.getRevision()
                        : current == null ? null : current.getRevision(),
                prepared.getArtifactType(), FILE_SCHEMA,
                request.getContent(), request.getSummary(), sourceRefs,
                fileMetadata(scope, request, manifest.getString("mode")));
        AidRuntimeArtifact artifact = requireArtifact(scope.userId(), written.getArtifactId());
        JSONObject nextManifest = JSON.parseObject(workspaceJson(manifest));
        if ("DRAFT".equals(request.getDisposition())) {
            JSONObject draft = fileEntry(artifact, null);
            draft.put("baseArtifactRef", current == null ? null : ref(current));
            draft.put("baseRevision", current == null ? 0L : current.getRevision());
            draft.put("baseContentHash", current == null ? null : current.getContentHash());
            nextManifest.getJSONObject("drafts").put(request.getPath(), draft);
        } else {
            nextManifest.getJSONObject("files").put(request.getPath(),
                    followupFileEntry(artifact, currentEntry));
            applyPlan(nextManifest, request);
        }
        ArtifactRef nextWorkspace = writeArtifact(invocation, workspaceLogicalId(scope.projectId()),
                workspace.getRevision(), "CREATIVE_TEXT", WORKSPACE_SCHEMA, workspaceJson(nextManifest),
                "Screenplay workspace revision", new JSONArray(),
                metadata("STORY_WORKSPACE", scope, null, manifest.getString("mode")));
        StoryWorkspaceViews.ArtifactReceipt result = new StoryWorkspaceViews.ArtifactReceipt();
        copyReceipt(result, scope, request.getPath(), artifact,
                requireArtifact(scope.userId(), nextWorkspace.getArtifactId()));
        result.setDisposition(request.getDisposition());
        return result;
    }

    @Override
    public CreativeViews.SourceChunkPage readSource(RuntimeToolInvocation invocation,
                                                    StoryWorkspaceRequests.SourceRead request) {
        requireScope(invocation);
        require(request != null, "source request");
        CreativeRequests.SourceChunkRead read = new CreativeRequests.SourceChunkRead();
        read.setSourceRevisionId(request.getSourceRevisionId());
        read.setAfterChunkIndex(request.getAfterChunkIndex());
        read.setLimit(request.getLimit());
        return sourceService.read(invocation.getUserId(), read, invocation.getContextManifestJson());
    }

    @Override
    public StoryWorkspaceRequests.PreparedReview prepareReview(
            RuntimeToolInvocation invocation, StoryWorkspaceRequests.ReviewRecord request) {
        return prepareReview(invocation, request, null);
    }

    @Override
    public StoryWorkspaceRequests.PreparedReview prepareReviewCorrection(
            RuntimeToolInvocation invocation, StoryWorkspaceRequests.ReviewCorrection request) {
        require(request != null, "review correction request");
        StoryWorkspaceRequests.ReviewCorrectionLink link = new StoryWorkspaceRequests.ReviewCorrectionLink();
        link.setExpectedReviewArtifactRef(request.getExpectedReviewArtifactRef());
        link.setExpectedReviewContentHash(request.getExpectedReviewContentHash());
        link.setCorrectionReason(request.getCorrectionReason());
        require(wellFormedUtf16(Objects.toString(link.getCorrectionReason(), "")), "correctionReason");
        return prepareReview(invocation, request, link);
    }

    private StoryWorkspaceRequests.PreparedReview prepareReview(RuntimeToolInvocation invocation,
            StoryWorkspaceRequests.ReviewRecord request, StoryWorkspaceRequests.ReviewCorrectionLink correction) {
        Scope scope = requireScope(invocation);
        AidRuntimeArtifact workspace = requireWorkspace(scope);
        JSONObject manifest = workspaceContent(workspace, scope);
        StoryWorkspaceRequests.ReviewRecord canonical = canonicalReview(request, manifest.getString("mode"));
        JSONObject targetEntry = requireFileEntry(manifest, canonical.getPath());
        AidRuntimeArtifact target = requireEntryArtifact(scope, canonical.getPath(), targetEntry);
        if (correction == null) require(targetEntry.getJSONObject("reviewWorkflow") == null, "episode review already recorded");
        else requireReviewCorrection(scope, canonical.getPath(), targetEntry, target, correction);
        requireExpected(target, canonical.getExpectedRevision(), canonical.getExpectedContentHash());
        StoryWorkspaceRequests.PreparedReview prepared = new StoryWorkspaceRequests.PreparedReview();
        prepared.setScope(freeze(scope, workspace, manifest.getString("mode")));
        prepared.setRequest(canonical);
        prepared.setTargetArtifactId(target.getId());
        prepared.setTargetArtifactRef(ref(target));
        prepared.setReviewLogicalArtifactId(reviewLogicalId(scope.projectId(), canonical.getPath()));
        prepared.setCorrection(correction);
        return prepared;
    }

    private AidRuntimeArtifact requireReviewCorrection(Scope scope, String path, JSONObject entry,
            AidRuntimeArtifact target, StoryWorkspaceRequests.ReviewCorrectionLink correction) {
        JSONObject workflow = entry.getJSONObject("reviewWorkflow");
        JSONObject last = workflow == null ? null : workflow.getJSONObject("lastReview");
        require(last != null, "review correction requires an existing review");
        require(Objects.equals(last.getString("reviewedArtifactId"), String.valueOf(target.getId())), "review correction body changed");
        AidRuntimeArtifact previous = requireArtifact(scope.userId(), positiveLong(last.getString("reviewArtifactId"), "reviewArtifactId"));
        requireReviewWorkflow(scope, path, entry, target, Map.of(target.getId(), target, previous.getId(), previous));
        try {
            StoryReviewCorrectionPolicy.validate(ref(target), last.getString("reviewedArtifactRef"),
                    workflow.getLong("followupRevision"), ref(previous), previous.getContentHash(),
                    correction.getExpectedReviewArtifactRef(), correction.getExpectedReviewContentHash(), correction.getCorrectionReason());
        } catch (StoryReviewCorrectionPolicy.InvalidCorrectionException invalid) {
            require(false, "review correction identity");
        }
        return previous;
    }

    @Override
    public StoryWorkspaceViews.ReviewContent readReview(RuntimeToolInvocation invocation,
            StoryWorkspaceRequests.ArtifactRead request) {
        require(request != null, "review request");
        Scope scope = requireScope(invocation);
        AidRuntimeArtifact workspace = requireWorkspace(scope);
        JSONObject manifest = workspaceContent(workspace, scope);
        String path = canonicalPath(request.getPath(), manifest.getString("mode"));
        require(EPISODE_SCRIPT.matcher(path).matches(), "review path");
        JSONObject entry = requireFileEntry(manifest, path);
        JSONObject workflow = entry.getJSONObject("reviewWorkflow");
        JSONObject last = workflow == null ? null : workflow.getJSONObject("lastReview");
        require(last != null, "episode has no review");
        Set<Long> ids = new LinkedHashSet<>();
        collectEntryArtifactIds(entry, true, ids);
        Map<Long, AidRuntimeArtifact> loaded = authorizedArtifacts(scope.userId(), ids);
        AidRuntimeArtifact current = requireEntryArtifact(scope, path, entry, loaded);
        Long reviewedId = positiveLong(last.getString("reviewedArtifactId"), "reviewedArtifactId");
        AidRuntimeArtifact reviewed = requireArtifact(reviewedId, loaded);
        AidRuntimeArtifact report = requireArtifact(positiveLong(last.getString("reviewArtifactId"), "reviewArtifactId"), loaded);
        StoryWorkspaceViews.ReviewContent result = new StoryWorkspaceViews.ReviewContent();
        org.springframework.beans.BeanUtils.copyProperties(reviewReceipt(scope, path, reviewed, report,
                positiveLong(last.getString("reviewId"), "reviewId"), last.getString("verdict"), workspace), result);
        String content = readContent(report);
        require(content.length() <= 20_000, "report");
        result.setReport(content);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public StoryWorkspaceViews.ReviewReceipt recordReview(RuntimeToolPreparedInvocation invocation,
                                                          StoryWorkspaceRequests.PreparedReview prepared,
                                                          RuntimeOperationSubmissionClaim claim) {
        RuntimeOperationSubmissionDecision decision = submissionGuard.claim(claim);
        requirePrepared(invocation, prepared == null ? null : prepared.getScope());
        require((prepared.getCorrection() != null) == "canvas.story.review.correct".equals(invocation.getToolCode()), "review correction tool identity");
        Scope scope = requireScope(invocation);
        requireFrozen(scope, prepared.getScope());
        if (decision == RuntimeOperationSubmissionDecision.RECONCILE_ONLY) {
            StoryWorkspaceViews.ReviewReceipt replay = reconcileReview(invocation, prepared);
            if (replay == null) receiptMissing();
            return replay;
        }
        AidRuntimeArtifact workspace;
        JSONObject manifest;
        StoryWorkspaceRequests.ReviewRecord request;
        AidRuntimeArtifact target;
        AidRuntimeArtifact correctionSource = null;
        try {
            if (prepared.getCorrection() != null) lockReviewCorrection(scope, prepared);
            workspace = requireFrozenWorkspace(scope, prepared.getScope());
            manifest = workspaceContent(workspace, scope);
            request = canonicalReview(prepared.getRequest(), manifest.getString("mode"));
            JSONObject targetEntry = requireFileEntry(manifest, request.getPath());
            target = requireEntryArtifact(scope, request.getPath(), targetEntry);
            if (prepared.getCorrection() == null) require(targetEntry.getJSONObject("reviewWorkflow") == null, "episode review already recorded");
            else correctionSource = requireReviewCorrection(scope, request.getPath(), targetEntry, target, prepared.getCorrection());
            requireExpected(target, request.getExpectedRevision(), request.getExpectedContentHash());
            require(Objects.equals(target.getId(), prepared.getTargetArtifactId())
                    && Objects.equals(ref(target), prepared.getTargetArtifactRef()), "review target");
            require(Objects.equals(prepared.getReviewLogicalArtifactId(),
                    reviewLogicalId(scope.projectId(), request.getPath())), "prepared review artifact");
        } catch (WorkspaceValidationException rejected) {
            // No artifact/audit write has begun. The transactional service rolls back the claim.
            if (prepared.getCorrection() != null) throw new ReviewCorrectionPreconditionRejectedException();
            throw rejected;
        }
        JSONObject reviewMetadata = metadata("STORY_REVIEW", scope, request.getPath(), manifest.getString("mode"));
        reviewMetadata.put("reviewSubjectRef", ref(target));
        reviewMetadata.put("reviewSubjectContentHash", target.getContentHash());
        reviewMetadata.put("reviewSubmissionPromptDigest", invocation.getArgumentsDigest());
        reviewMetadata.put("reviewSubmissionDigestKind", "TOOL_ARGUMENTS_SHA256");
        if (prepared.getCorrection() != null) {
            reviewMetadata.put("supersedesReviewArtifactRef", ref(correctionSource));
            reviewMetadata.put("supersedesReviewContentHash", correctionSource.getContentHash());
            reviewMetadata.put("correctionReason", prepared.getCorrection().getCorrectionReason());
        }
        AidRuntimeArtifact previous = correctionSource == null ? latest(scope.userId(), prepared.getReviewLogicalArtifactId()) : correctionSource;
        ArtifactRef reviewRef = writeArtifact(invocation, prepared.getReviewLogicalArtifactId(),
                previous == null ? null : previous.getRevision(), "REVIEW_REPORT", REVIEW_SCHEMA,
                request.getReport(), "Screenplay review", new JSONArray(), reviewMetadata);
        CreativeAuditRequests.ReviewRecord audit = new CreativeAuditRequests.ReviewRecord();
        audit.setRunId(invocation.getRunId());
        audit.setStepId(invocation.getRuntimeStepId());
        audit.setArtifactId(target.getId());
        audit.setReviewArtifactId(reviewRef.getArtifactId());
        audit.setVerdict(request.getVerdict());
        audit.setSeverityCountsJson(JSON.toJSONString(request.getSeverityCounts()));
        Long reviewId = auditService.recordReview(audit, invocation.getRunLease(), invocation.getStepLease());
        require(reviewId != null && reviewId > 0, "review receipt");

        AidRuntimeArtifact reviewArtifact = requireArtifact(scope.userId(), reviewRef.getArtifactId());
        JSONObject nextManifest = JSON.parseObject(workspaceJson(manifest));
        JSONObject file = requireFileEntry(nextManifest, request.getPath());
        file.put("reviewStatus", request.getVerdict());
        file.put("reviewWorkflow", reviewWorkflow(invocation, target, reviewArtifact, reviewId, request));
        ArtifactRef nextWorkspace = writeArtifact(invocation, workspaceLogicalId(scope.projectId()),
                workspace.getRevision(), "CREATIVE_TEXT", WORKSPACE_SCHEMA, workspaceJson(nextManifest),
                "Screenplay workspace review revision", new JSONArray(),
                metadata("STORY_WORKSPACE", scope, null, manifest.getString("mode")));
        return reviewReceipt(scope, request.getPath(), target, reviewArtifact, reviewId,
                request.getVerdict(), requireArtifact(scope.userId(), nextWorkspace.getArtifactId()));
    }

    private void lockReviewCorrection(Scope scope, StoryWorkspaceRequests.PreparedReview prepared) {
        // Match writers' body/report -> workspace lock order. Locking reads see the current
        // committed revisions even when this transaction already has a repeatable-read snapshot.
        String path = canonicalPath(prepared.getRequest().getPath(), prepared.getScope().getMode());
        AidRuntimeArtifact body = artifactMapper.selectLatestForUpdate(fileLogicalId(scope.projectId(), path));
        AidRuntimeArtifact report = artifactMapper.selectLatestForUpdate(reviewLogicalId(scope.projectId(), path));
        AidRuntimeArtifact workspace = artifactMapper.selectLatestForUpdate(workspaceLogicalId(scope.projectId()));
        require(body != null && Objects.equals(body.getId(), prepared.getTargetArtifactId())
                && report != null && Objects.equals(ref(report), prepared.getCorrection().getExpectedReviewArtifactRef())
                && Objects.equals(report.getContentHash(), prepared.getCorrection().getExpectedReviewContentHash())
                && workspace != null && Objects.equals(workspace.getId(), prepared.getScope().getWorkspaceArtifactId())
                && Objects.equals(workspace.getRevision(), prepared.getScope().getWorkspaceRevision())
                && Objects.equals(workspace.getContentHash(), prepared.getScope().getWorkspaceContentHash()),
                "review correction concurrent change");
    }

    @Override
    public StoryWorkspaceViews.Workspace reconcileInitialize(RuntimeToolPreparedInvocation invocation,
                                                             StoryWorkspaceRequests.PreparedInitialize prepared) {
        Scope scope = requireScope(invocation);
        requireFrozen(scope, prepared == null ? null : prepared.getScope());
        require(prepared.getRequest() != null
                && Objects.equals(canonicalMode(prepared.getRequest().getMode()), prepared.getScope().getMode()),
                "prepared mode");
        AidRuntimeArtifact workspace = latest(scope.userId(), workspaceLogicalId(scope.projectId()));
        if (workspace == null) return null;
        requireWorkspace(workspace, scope, prepared.getRequest().getMode());
        return workspaceView(scope, workspace);
    }

    @Override
    public StoryWorkspaceViews.ArtifactReceipt reconcileWrite(RuntimeToolPreparedInvocation invocation,
                                                               StoryWorkspaceRequests.PreparedWrite prepared) {
        Scope scope = requireScope(invocation);
        requireFrozen(scope, prepared == null ? null : prepared.getScope());
        require(prepared.getRequest() != null, "prepared artifact");
        String path = canonicalPath(prepared.getRequest().getPath(), prepared.getScope().getMode());
        String disposition = canonicalDisposition(prepared.getRequest().getDisposition());
        require(Objects.equals(prepared.getLogicalArtifactId(),
                "DRAFT".equals(disposition) ? draftLogicalId(scope.projectId(), path)
                        : fileLogicalId(scope.projectId(), path)), "prepared artifact");
        AidRuntimeArtifact artifact = produced(scope.userId(), invocation.getRunId(), invocation.getRuntimeStepId(),
                prepared.getLogicalArtifactId());
        AidRuntimeArtifact workspace = produced(scope.userId(), invocation.getRunId(), invocation.getRuntimeStepId(),
                workspaceLogicalId(scope.projectId()));
        String expectedHash = SecureUtil.sha256(prepared.getRequest().getContent());
        if (artifact == null || workspace == null || !Objects.equals(artifact.getContentHash(), expectedHash)) return null;
        StoryWorkspaceViews.ArtifactReceipt result = new StoryWorkspaceViews.ArtifactReceipt();
        copyReceipt(result, scope, prepared.getRequest().getPath(), artifact, workspace);
        result.setDisposition(disposition);
        return result;
    }

    @Override
    public StoryWorkspaceViews.ReviewReceipt reconcileReview(RuntimeToolPreparedInvocation invocation,
                                                              StoryWorkspaceRequests.PreparedReview prepared) {
        Scope scope = requireScope(invocation);
        requireFrozen(scope, prepared == null ? null : prepared.getScope());
        require((prepared.getCorrection() != null) == "canvas.story.review.correct".equals(invocation.getToolCode()), "review correction tool identity");
        require(prepared.getRequest() != null && Objects.equals(prepared.getReviewLogicalArtifactId(),
                reviewLogicalId(scope.projectId(), canonicalPath(prepared.getRequest().getPath(),
                        prepared.getScope().getMode()))), "prepared review artifact");
        AidRuntimeArtifact reviewArtifact = produced(scope.userId(), invocation.getRunId(),
                invocation.getRuntimeStepId(), prepared.getReviewLogicalArtifactId());
        AidRuntimeArtifact workspace = produced(scope.userId(), invocation.getRunId(), invocation.getRuntimeStepId(),
                workspaceLogicalId(scope.projectId()));
        if (reviewArtifact == null || workspace == null) return null;
        if (prepared.getCorrection() != null) {
            JSONObject correctionMetadata = JSON.parseObject(reviewArtifact.getMetadataJson());
            require(correctionMetadata != null
                    && Objects.equals(correctionMetadata.getString("supersedesReviewArtifactRef"), prepared.getCorrection().getExpectedReviewArtifactRef())
                    && Objects.equals(correctionMetadata.getString("supersedesReviewContentHash"), prepared.getCorrection().getExpectedReviewContentHash())
                    && Objects.equals(correctionMetadata.getString("correctionReason"), prepared.getCorrection().getCorrectionReason()), "review correction receipt");
        }
        AidRuntimeArtifactReview review = reviewMapper.selectReceipt(prepared.getTargetArtifactId(),
                reviewArtifact.getId(), invocation.getRunId(), invocation.getRuntimeStepId());
        AidRuntimeArtifact target = artifactMapper.selectAuthorized(scope.userId(), prepared.getTargetArtifactId());
        if (review == null || target == null || !Objects.equals(review.getVerdict(), prepared.getRequest().getVerdict())) {
            return null;
        }
        JSONObject manifest = workspaceContent(workspace, scope);
        JSONObject entry = requireFileEntry(manifest, prepared.getRequest().getPath());
        AidRuntimeArtifact current = requireEntryArtifact(scope, prepared.getRequest().getPath(), entry);
        JSONObject lastReview = requireReviewWorkflow(scope, prepared.getRequest().getPath(), entry, current)
                .getJSONObject("lastReview");
        String expectedRequestHash = SecureUtil.sha256(JSON.toJSONString(
                canonicalReview(prepared.getRequest(), prepared.getScope().getMode()),
                JSONWriter.Feature.WriteNulls));
        require(Objects.equals(lastReview.getString("reviewId"), String.valueOf(review.getId()))
                && Objects.equals(lastReview.getString("reviewArtifactRef"), ref(reviewArtifact))
                && Objects.equals(lastReview.getString("verdict"), review.getVerdict())
                && Objects.equals(lastReview.getString("reviewerRunId"), String.valueOf(invocation.getRunId()))
                && Objects.equals(lastReview.getString("reviewerStepId"),
                String.valueOf(invocation.getRuntimeStepId()))
                && Objects.equals(lastReview.getString("reviewRequestHash"), expectedRequestHash),
                "review workflow receipt");
        return reviewReceipt(scope, prepared.getRequest().getPath(), target, reviewArtifact,
                review.getId(), review.getVerdict(), workspace);
    }

    @Override
    public StoryWorkspaceRequests.PreparedOperation prepareOperation(
            RuntimeToolInvocation invocation, String operation, String argumentsJson) {
        Scope scope = requireScope(invocation);
        AidRuntimeArtifact workspace = requireWorkspace(scope);
        JSONObject manifest = workspaceContent(workspace, scope);
        Object request = parseOperation(operation, argumentsJson);
        validateOperationRequest(scope, workspace, manifest, operation, request, invocation.getContextManifestJson());
        StoryWorkspaceRequests.PreparedOperation prepared = new StoryWorkspaceRequests.PreparedOperation();
        prepared.setScope(freeze(scope, workspace, manifest.getString("mode")));
        prepared.setOperation(operation);
        prepared.setCanonicalRequestJson(JSON.toJSONString(request, JSONWriter.Feature.WriteNulls));
        if (request instanceof StoryWorkspaceRequests.ArtifactPatch patch) {
            AidRuntimeArtifact source = "DRAFT".equals(patch.getSource())
                    ? requireDraftEntryArtifact(scope, patch.getPath(),
                    manifest.getJSONObject("drafts").getJSONObject(patch.getPath()))
                    : requireEntryArtifact(scope, patch.getPath(), requireFileEntry(manifest, patch.getPath()));
            prepared.setSourceRefsJson(mergeSourceRefs(source.getSourceContextRefsJson(),
                    sourceRefs(scope, invocation.getContextManifestJson(), patch.getSources())).toJSONString());
        } else if (request instanceof StoryWorkspaceRequests.UploadBegin begin) {
            AidRuntimeArtifact current = currentArtifact(scope, manifest, begin.getPath());
            prepared.setSourceRefsJson(mergeSourceRefs(current == null ? null : current.getSourceContextRefsJson(),
                    sourceRefs(scope, invocation.getContextManifestJson(), begin.getSources())).toJSONString());
        } else if (request instanceof StoryWorkspaceRequests.PipelineInitialize initialize) {
            SourceSnapshot source = requireSource(scope, invocation.getContextManifestJson(),
                    initialize.getSourceRevisionId());
            prepared.setFrozenSourceJson(sourceIdentity(source.source()).toJSONString());
        }
        return prepared;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Object executeOperation(RuntimeToolPreparedInvocation invocation,
                                   StoryWorkspaceRequests.PreparedOperation prepared,
                                   RuntimeOperationSubmissionClaim claim) {
        RuntimeOperationSubmissionDecision decision = submissionGuard.claim(claim);
        requirePrepared(invocation, prepared == null ? null : prepared.getScope());
        Scope scope = requireScope(invocation);
        requireFrozen(scope, prepared.getScope());
        if (decision == RuntimeOperationSubmissionDecision.RECONCILE_ONLY) {
            Object replay = reconcileOperation(invocation, prepared);
            if (replay == null) receiptMissing();
            return replay;
        }
        AidRuntimeArtifact workspace = requireFrozenWorkspace(scope, prepared.getScope());
        JSONObject manifest = workspaceContent(workspace, scope);
        Object request = parseOperation(prepared.getOperation(), prepared.getCanonicalRequestJson());
        validateOperationRequest(scope, workspace, manifest, prepared.getOperation(), request,
                invocation.getContextManifestJson());
        if (request instanceof StoryWorkspaceRequests.ArtifactPatch patch) {
            AidRuntimeArtifact source = "DRAFT".equals(patch.getSource())
                    ? requireDraftEntryArtifact(scope, patch.getPath(),
                    manifest.getJSONObject("drafts").getJSONObject(patch.getPath()))
                    : requireEntryArtifact(scope, patch.getPath(), requireFileEntry(manifest, patch.getPath()));
            require(Objects.equals(prepared.getSourceRefsJson(), mergeSourceRefs(source.getSourceContextRefsJson(),
                    sourceRefs(scope, invocation.getContextManifestJson(), patch.getSources())).toJSONString()),
                    "prepared sourceRefs");
        } else if (request instanceof StoryWorkspaceRequests.UploadBegin begin) {
            AidRuntimeArtifact current = currentArtifact(scope, manifest, begin.getPath());
            require(Objects.equals(prepared.getSourceRefsJson(), mergeSourceRefs(
                    current == null ? null : current.getSourceContextRefsJson(),
                    sourceRefs(scope, invocation.getContextManifestJson(), begin.getSources())).toJSONString()),
                    "prepared sourceRefs");
        } else if (request instanceof StoryWorkspaceRequests.PipelineInitialize initialize) {
            SourceSnapshot source = requireSource(scope, invocation.getContextManifestJson(),
                    initialize.getSourceRevisionId());
            require(Objects.equals(prepared.getFrozenSourceJson(), sourceIdentity(source.source()).toJSONString()),
                    "prepared source");
        }
        return switch (prepared.getOperation()) {
            case "canvas.story.source.pipeline.initialize" -> initializePipeline(invocation, scope, workspace,
                    manifest, (StoryWorkspaceRequests.PipelineInitialize) request);
            case "canvas.story.source.pipeline.advance" -> advancePipeline(invocation, scope, workspace,
                    manifest, (StoryWorkspaceRequests.PipelineAdvance) request);
            case "canvas.story.source.boundaries.write" -> writeBoundaries(invocation, scope, workspace,
                    manifest, (StoryWorkspaceRequests.BoundariesWrite) request);
            case "canvas.story.artifact.patch" -> patchArtifact(invocation, scope, workspace, manifest,
                    (StoryWorkspaceRequests.ArtifactPatch) request, prepared.getSourceRefsJson());
            case "canvas.story.artifact.upload.begin" -> beginUpload(invocation, scope, workspace, manifest,
                    (StoryWorkspaceRequests.UploadBegin) request, prepared.getSourceRefsJson());
            case "canvas.story.artifact.upload.append" -> appendUpload(invocation, scope, workspace, manifest,
                    (StoryWorkspaceRequests.UploadAppend) request);
            case "canvas.story.artifact.upload.finalize" -> finalizeUpload(invocation, scope, workspace, manifest,
                    (StoryWorkspaceRequests.UploadFinalize) request);
            case "canvas.story.draft.resolve" -> resolveDraft(invocation, scope, workspace, manifest,
                    (StoryWorkspaceRequests.DraftResolve) request);
            case "canvas.story.checkpoint.record" -> recordCheckpoint(invocation, scope, workspace, manifest,
                    (StoryWorkspaceRequests.CheckpointRecord) request);
            default -> throw new IllegalArgumentException("Unsupported screenplay workspace operation");
        };
    }

    @Override
    public Object reconcileOperation(RuntimeToolPreparedInvocation invocation,
                                     StoryWorkspaceRequests.PreparedOperation prepared) {
        Scope scope = requireScope(invocation);
        requireFrozen(scope, prepared == null ? null : prepared.getScope());
        AidRuntimeArtifact workspace = produced(scope.userId(), invocation.getRunId(), invocation.getRuntimeStepId(),
                workspaceLogicalId(scope.projectId()));
        if (workspace == null) {
            // Absence alone is not failure: only the exact frozen, unchanged state can prove
            // that the original source input was deterministically rejected before writes.
            if ("canvas.story.source.pipeline.advance".equals(prepared.getOperation())) {
                AidRuntimeArtifact unchanged = requireFrozenWorkspace(scope, prepared.getScope());
                JSONObject original = workspaceContent(unchanged, scope);
                Object originalRequest = parseOperation(prepared.getOperation(), prepared.getCanonicalRequestJson());
                validateOperationRequest(scope, unchanged, original, prepared.getOperation(), originalRequest,
                        invocation.getContextManifestJson());
            }
            return null;
        }
        JSONObject manifest = workspaceContent(workspace, scope);
        Object request = parseOperation(prepared.getOperation(), prepared.getCanonicalRequestJson());
        return switch (prepared.getOperation()) {
            case "canvas.story.source.pipeline.initialize", "canvas.story.source.pipeline.advance" ->
                    pipelineReceipt(scope, workspace, manifest);
            case "canvas.story.source.boundaries.write" -> boundariesReceipt(scope, workspace, manifest);
            case "canvas.story.artifact.patch" -> reconcileArtifactMutation(scope, workspace, manifest,
                    ((StoryWorkspaceRequests.ArtifactPatch) request).getPath(), "DRAFT");
            case "canvas.story.artifact.upload.begin", "canvas.story.artifact.upload.append" ->
                    uploadReceipt(scope, workspace, manifest, operationUploadKey(request), null);
            case "canvas.story.artifact.upload.finalize" -> reconcileFinalizedUpload(scope, workspace, manifest,
                    (StoryWorkspaceRequests.UploadFinalize) request);
            case "canvas.story.draft.resolve" -> reconcileDraftResolution(scope, workspace, manifest,
                    (StoryWorkspaceRequests.DraftResolve) request, invocation.getRunId(), invocation.getRuntimeStepId());
            case "canvas.story.checkpoint.record" -> reconcileCheckpoint(scope, workspace, manifest,
                    (StoryWorkspaceRequests.CheckpointRecord) request, invocation.getRunId(),
                    invocation.getRuntimeStepId());
            default -> null;
        };
    }

    @Override
    public StoryWorkspaceViews.SourceRangePage readChapter(
            RuntimeToolInvocation invocation, StoryWorkspaceRequests.SourcePage request) {
        Scope scope = requireScope(invocation);
        JSONObject manifest = workspaceContent(requireWorkspace(scope), scope);
        JSONObject pipeline = requirePipeline(manifest);
        int number = positiveNumber(request == null ? null : request.getNumber(), "chapter");
        JSONArray chapters = pipeline.getJSONArray("chapters");
        require(number <= chapters.size(), "chapter");
        JSONObject chapter = chapters.getJSONObject(number - 1);
        return sourceRangePage(scope, invocation.getContextManifestJson(), pipeline, "CHAPTER", number,
                chapter.getString("title"), chapter.getIntValue("charStart"), chapter.getIntValue("charEnd"),
                null, null, request);
    }

    @Override
    public StoryWorkspaceViews.PipelineInspection inspectPipeline(
            RuntimeToolInvocation invocation, StoryWorkspaceRequests.PipelineInspect request) {
        Scope scope = requireScope(invocation);
        AidRuntimeArtifact workspace = requireWorkspace(scope);
        JSONObject manifest = workspaceContent(workspace, scope);
        JSONObject pipeline = requirePipeline(manifest);
        int after = request == null || request.getAfterItemIndex() == null
                ? -1 : request.getAfterItemIndex();
        int limit = request == null || request.getLimit() == null ? 50 : request.getLimit();
        require(after >= -1 && limit >= 1 && limit <= 100, "pipeline page");

        JSONObject registry = pipeline.getJSONObject("registry");
        List<StoryWorkspaceViews.PipelineItem> items = new ArrayList<>();
        int itemIndex = 0;
        for (String category : List.of("events", "primaryCharacters", "supportingCharacters",
                "minorCharacters", "locations", "props", "redlines")) {
            JSONArray facts = registry.getJSONArray(category);
            require(facts != null, "pipeline registry " + category);
            for (Object factValue : facts) {
                require(factValue instanceof JSONObject, "pipeline fact");
                JSONObject fact = (JSONObject) factValue;
                String key = fact.getString("key");
                String value = fact.getString("value");
                JSONArray evidence = fact.getJSONArray("evidence");
                require(StrUtil.isNotBlank(key) && key.length() <= 120 && StrUtil.isNotBlank(value)
                        && value.length() <= 2000 && evidence != null, "pipeline fact");
                for (Object evidenceValue : evidence) {
                    require(evidenceValue instanceof JSONObject, "pipeline evidence");
                    JSONObject stored = (JSONObject) evidenceValue;
                    validateStoredEvidence(stored, pipeline.getIntValue("chaptersTotal"));
                    if (itemIndex > after && items.size() < limit) {
                        items.add(pipelineFactItem(category, key, value, stored));
                    }
                    itemIndex++;
                }
            }
        }
        JSONArray drafts = registry.getJSONArray("chapterDrafts");
        require(drafts != null, "pipeline chapter drafts");
        for (Object draftValue : drafts) {
            require(draftValue instanceof JSONObject, "pipeline chapter draft");
            JSONObject stored = (JSONObject) draftValue;
            validateStoredDraft(stored, pipeline.getIntValue("chaptersTotal"));
            if (itemIndex > after && items.size() < limit) items.add(pipelineDraftItem(stored));
            itemIndex++;
        }
        require(after == -1 || after < itemIndex, "pipeline page cursor");
        boolean complete = after + 1 + items.size() >= itemIndex;

        StoryWorkspaceViews.PipelineInspection result = new StoryWorkspaceViews.PipelineInspection();
        result.setProjectId(String.valueOf(scope.projectId()));
        result.setSourceRevisionId(pipeline.getString("sourceRevisionId"));
        result.setSourceContentHash(pipeline.getString("sourceContentHash"));
        result.setStatus(pipeline.getString("status"));
        result.setDone(pipeline.getBooleanValue("done"));
        result.setProcessedSegments(pipeline.getInteger("processedSegments"));
        result.setTotalItems(itemIndex);
        result.setAfterItemIndex(after);
        result.setNextAfterItemIndex(complete ? null : after + items.size());
        result.setComplete(complete);
        Map<Long, AidRuntimeArtifact> episodeArtifacts = authorizedEpisodeArtifacts(scope, manifest);
        int completedReviewWorkflows = reviewWorkflowCompletedScriptCount(scope, manifest, episodeArtifacts);
        result.setReviewWorkflowCompletedScriptCount(completedReviewWorkflows);
        Integer totalEpisodes = manifest.getInteger("totalEpisodes");
        result.setAllEpisodeReviewWorkflowsComplete(totalEpisodes != null && totalEpisodes > 0
                && completedReviewWorkflows == totalEpisodes && scriptCount(manifest) == totalEpisodes);
        result.setItems(items);
        result.setWorkspaceArtifactRef(ref(workspace));
        result.setWorkspaceRevision(workspace.getRevision());
        result.setWorkspaceContentHash(workspace.getContentHash());
        return result;
    }

    @Override
    public StoryWorkspaceViews.SourceRangePage readEpisode(
            RuntimeToolInvocation invocation, StoryWorkspaceRequests.SourcePage request) {
        Scope scope = requireScope(invocation);
        JSONObject manifest = workspaceContent(requireWorkspace(scope), scope);
        JSONObject pipeline = requirePipeline(manifest);
        int number = positiveNumber(request == null ? null : request.getNumber(), "episode");
        JSONArray episodes = pipeline.getJSONArray("episodes");
        require(episodes != null && number <= episodes.size(), "episode");
        JSONObject episode = episodes.getJSONObject(number - 1);
        return sourceRangePage(scope, invocation.getContextManifestJson(), pipeline, "EPISODE", number,
                episode.getString("title"), episode.getIntValue("charStart"), episode.getIntValue("charEnd"),
                episode.getString("mustKeep"), episode.getString("ending"), request);
    }

    @Override
    public StoryWorkspaceViews.LinePage readLines(
            RuntimeToolInvocation invocation, StoryWorkspaceRequests.ArtifactLinesRead request) {
        Scope scope = requireScope(invocation);
        AidRuntimeArtifact workspace = requireWorkspace(scope);
        JSONObject manifest = workspaceContent(workspace, scope);
        require(request != null, "line request");
        String path = canonicalPath(request.getPath(), manifest.getString("mode"));
        String source = canonicalArtifactSource(request.getSource());
        AidRuntimeArtifact artifact = "DRAFT".equals(source)
                ? requireDraftEntryArtifact(scope, path, manifest.getJSONObject("drafts").getJSONObject(path))
                : requireEntryArtifact(scope, path, requireFileEntry(manifest, path));
        String content = readContent(artifact);
        int startLine = request.getStartLine() == null ? 1 : request.getStartLine();
        int maxLines = request.getMaxLines() == null ? 200 : request.getMaxLines();
        int maxBytes = request.getMaxUtf8Bytes() == null ? 8192 : request.getMaxUtf8Bytes();
        require(startLine >= 1 && maxLines >= 1 && maxLines <= 500
                && maxBytes >= 256 && maxBytes <= MAX_LINE_UTF8_BYTES, "line page");
        return linePage(scope, path, source, artifact, content, startLine, maxLines, maxBytes);
    }

    private Object parseOperation(String operation, String json) {
        require(StrUtil.isNotBlank(operation) && json != null, "operation");
        return switch (operation) {
            case "canvas.story.source.pipeline.initialize" -> JSON.parseObject(json,
                    StoryWorkspaceRequests.PipelineInitialize.class);
            case "canvas.story.source.pipeline.advance" -> JSON.parseObject(json,
                    StoryWorkspaceRequests.PipelineAdvance.class);
            case "canvas.story.source.boundaries.write" -> JSON.parseObject(json,
                    StoryWorkspaceRequests.BoundariesWrite.class);
            case "canvas.story.artifact.patch" -> JSON.parseObject(json,
                    StoryWorkspaceRequests.ArtifactPatch.class);
            case "canvas.story.artifact.upload.begin" -> JSON.parseObject(json,
                    StoryWorkspaceRequests.UploadBegin.class);
            case "canvas.story.artifact.upload.append" -> JSON.parseObject(json,
                    StoryWorkspaceRequests.UploadAppend.class);
            case "canvas.story.artifact.upload.finalize" -> JSON.parseObject(json,
                    StoryWorkspaceRequests.UploadFinalize.class);
            case "canvas.story.draft.resolve" -> JSON.parseObject(json,
                    StoryWorkspaceRequests.DraftResolve.class);
            case "canvas.story.checkpoint.record" -> JSON.parseObject(json,
                    StoryWorkspaceRequests.CheckpointRecord.class);
            default -> throw new IllegalArgumentException("Unsupported screenplay workspace operation");
        };
    }

    private void validateOperationRequest(Scope scope, AidRuntimeArtifact workspace, JSONObject manifest,
                                          String operation, Object request, String contextManifestJson) {
        require(request instanceof StoryWorkspaceRequests.WorkspaceCas, "operation request");
        requireWorkspaceCas(workspace, (StoryWorkspaceRequests.WorkspaceCas) request);
        switch (operation) {
            case "canvas.story.source.pipeline.initialize" -> {
                StoryWorkspaceRequests.PipelineInitialize value =
                        (StoryWorkspaceRequests.PipelineInitialize) request;
                require("ADAPTATION".equals(manifest.getString("mode"))
                        && manifest.getJSONObject("sourcePipeline") == null
                        && value.getSourceRevisionId() != null && value.getSourceRevisionId() > 0,
                        "source pipeline initialize");
                SourceSnapshot source = requireSource(scope, contextManifestJson, value.getSourceRevisionId());
                require(!source.content().isEmpty(), "source content");
            }
            case "canvas.story.source.pipeline.advance" -> {
                StoryWorkspaceRequests.PipelineAdvance value = (StoryWorkspaceRequests.PipelineAdvance) request;
                JSONObject pipeline = requirePipeline(manifest);
                require(!pipeline.getBooleanValue("done") && pipeline.getJSONObject("pending") != null
                        && value.getCharStart() != null && value.getCharEnd() != null
                        && value.getCharStart() >= 0 && value.getCharEnd() > value.getCharStart()
                        && isDigest(value.getContentHash()) && value.getFacts() != null,
                        "source pipeline advance");
                canonicalFacts(value.getFacts());
                validatePendingSourceFacts(scope, pipeline, value, contextManifestJson);
            }
            case "canvas.story.source.boundaries.write" -> {
                StoryWorkspaceRequests.BoundariesWrite value = (StoryWorkspaceRequests.BoundariesWrite) request;
                JSONObject pipeline = requirePipeline(manifest);
                require(pipeline.getBooleanValue("done") && value.getTargetEpisodes() != null
                        && value.getTargetEpisodes() >= 1 && value.getTargetEpisodes() <= 999
                        && value.getEpisodes() != null && !value.getEpisodes().isEmpty()
                        && value.getEpisodes().size() <= 999, "source boundaries");
            }
            case "canvas.story.artifact.patch" -> {
                StoryWorkspaceRequests.ArtifactPatch value = (StoryWorkspaceRequests.ArtifactPatch) request;
                value.setPath(canonicalPath(value.getPath(), manifest.getString("mode")));
                value.setSource(canonicalArtifactSource(value.getSource()));
                require(value.getExpectedRevision() != null && value.getExpectedRevision() > 0
                        && isDigest(value.getExpectedContentHash()) && value.getStartLine() != null
                        && value.getEndLine() != null && value.getStartLine() >= 1
                        && value.getEndLine() >= value.getStartLine() && isDigest(value.getExpectedBlockHash())
                        && value.getReplacement() != null && value.getReplacement().length() <= MAX_CONTENT_CHARS
                        && wellFormedUtf16(value.getReplacement()), "artifact patch");
                value.setSummary(trim(value.getSummary()));
                require(value.getSummary() == null || value.getSummary().length() <= 500, "patch summary");
                value.setSources(value.getSources() == null ? new ArrayList<>() : value.getSources());
                require(value.getSources().size() <= 100, "patch sources");
                require(manifest.getJSONObject("drafts").containsKey(value.getPath())
                        || manifest.getJSONObject("drafts").size() < MAX_DRAFTS, "draft capacity");
                sourceRefs(scope, contextManifestJson, value.getSources());
            }
            case "canvas.story.artifact.upload.begin" -> {
                StoryWorkspaceRequests.UploadBegin value = (StoryWorkspaceRequests.UploadBegin) request;
                value.setUploadKey(canonicalUploadKey(value.getUploadKey()));
                value.setPath(canonicalPath(value.getPath(), manifest.getString("mode")));
                value.setDisposition(canonicalDisposition(value.getDisposition()));
                require(value.getExpectedRevision() != null && value.getExpectedRevision() >= 0
                        && (value.getExpectedRevision() == 0 ? StrUtil.isBlank(value.getExpectedContentHash())
                        : isDigest(value.getExpectedContentHash()))
                        && value.getExpectedFragmentCount() != null && value.getExpectedFragmentCount() >= 2
                        && value.getExpectedFragmentCount() <= 128 && value.getExpectedCharCount() != null
                        && value.getExpectedCharCount() > MAX_CONTENT_CHARS
                        && value.getExpectedCharCount() <= MAX_LONG_CONTENT_CHARS
                        && isDigest(value.getFinalContentHash()), "upload begin");
                value.setSummary(trim(value.getSummary()));
                require(value.getSummary() == null || value.getSummary().length() <= 500, "upload summary");
                value.setSources(value.getSources() == null ? new ArrayList<>() : value.getSources());
                require(value.getSources().size() <= 100, "upload sources");
                require(manifest.getJSONObject("uploads").size() < MAX_UPLOADS, "upload capacity");
                if ("DRAFT".equals(value.getDisposition())) {
                    require(manifest.getJSONObject("drafts").containsKey(value.getPath())
                            || manifest.getJSONObject("drafts").size() < MAX_DRAFTS, "draft capacity");
                } else {
                    require(fileEntry(manifest, value.getPath()) != null
                            || manifest.getJSONObject("files").size() < MAX_FILES, "file capacity");
                }
                sourceRefs(scope, contextManifestJson, value.getSources());
                if ("skeleton.md".equals(value.getPath())) validatePlan(value.getSkeletonPlan(),
                        manifest.getString("mode"));
                else require(value.getSkeletonPlan() == null, "upload skeletonPlan");
            }
            case "canvas.story.artifact.upload.append" -> {
                StoryWorkspaceRequests.UploadAppend value = (StoryWorkspaceRequests.UploadAppend) request;
                value.setUploadKey(canonicalUploadKey(value.getUploadKey()));
                require(value.getFragmentIndex() != null && value.getFragmentIndex() >= 0
                        && value.getFragmentIndex() < 128 && value.getCharStart() != null
                        && value.getCharStart() >= 0 && value.getContent() != null
                        && !value.getContent().isEmpty() && value.getContent().length() <= MAX_CONTENT_CHARS
                        && wellFormedUtf16(value.getContent()) && isDigest(value.getContentHash())
                        && Objects.equals(value.getContentHash(), SecureUtil.sha256(value.getContent())),
                        "upload fragment");
            }
            case "canvas.story.artifact.upload.finalize" -> {
                StoryWorkspaceRequests.UploadFinalize value = (StoryWorkspaceRequests.UploadFinalize) request;
                value.setUploadKey(canonicalUploadKey(value.getUploadKey()));
            }
            case "canvas.story.draft.resolve" -> {
                StoryWorkspaceRequests.DraftResolve value = (StoryWorkspaceRequests.DraftResolve) request;
                value.setPath(canonicalPath(value.getPath(), manifest.getString("mode")));
                value.setAction(value.getAction() == null ? null : value.getAction().trim().toUpperCase(Locale.ROOT));
                require(Set.of("ADOPT", "IGNORE").contains(value.getAction())
                        && isArtifactRef(value.getDraftArtifactRef()) && isDigest(value.getDraftContentHash()),
                        "draft resolve");
            }
            case "canvas.story.checkpoint.record" -> {
                StoryWorkspaceRequests.CheckpointRecord value = (StoryWorkspaceRequests.CheckpointRecord) request;
                value.setCheckpoint(value.getCheckpoint() == null ? null
                        : value.getCheckpoint().trim().toUpperCase(Locale.ROOT));
                require(Set.of("C1", "C2").contains(value.getCheckpoint()) && StrUtil.isNotBlank(value.getSummary())
                        && value.getSummary().length() <= 2000, "checkpoint");
                value.setSummary(value.getSummary().trim());
                value.setPaths(canonicalPaths(value.getPaths(), manifest.getString("mode")));
                value.setDraftPaths(canonicalPaths(value.getDraftPaths(), manifest.getString("mode")));
                require(!value.getPaths().isEmpty() || !value.getDraftPaths().isEmpty(), "checkpoint artifacts");
                require(manifest.getJSONArray("checkpoints").size() < MAX_CHECKPOINTS, "checkpoint capacity");
            }
            default -> throw new IllegalArgumentException("Unsupported screenplay workspace operation");
        }
    }

    private void requireWorkspaceCas(AidRuntimeArtifact workspace, StoryWorkspaceRequests.WorkspaceCas request) {
        require(request != null && Objects.equals(request.getExpectedWorkspaceRevision(), workspace.getRevision())
                && Objects.equals(request.getExpectedWorkspaceContentHash(), workspace.getContentHash()),
                "workspace request CAS");
    }

    private StoryWorkspaceViews.PipelineReceipt initializePipeline(
            RuntimeToolPreparedInvocation invocation, Scope scope, AidRuntimeArtifact workspace,
            JSONObject manifest, StoryWorkspaceRequests.PipelineInitialize request) {
        SourceSnapshot source = requireSource(scope, invocation.getContextManifestJson(), request.getSourceRevisionId());
        require(!source.content().isEmpty(), "source content");
        List<ChapterRange> chapters = detectChapters(source.content());
        JSONObject pipeline = new JSONObject(new LinkedHashMap<>());
        pipeline.put("schemaVersion", "story-source-pipeline/v1");
        pipeline.putAll(sourceIdentity(source.source()));
        pipeline.put("status", "INGESTING");
        pipeline.put("warning", chapters.size() == 1 && "原文".equals(chapters.get(0).title())
                ? "NO_STRONG_CHAPTER_HEADING" : null);
        pipeline.put("chaptersTotal", chapters.size());
        JSONArray chapterJson = new JSONArray();
        for (int index = 0; index < chapters.size(); index++) {
            ChapterRange chapter = chapters.get(index);
            JSONObject value = new JSONObject(new LinkedHashMap<>());
            value.put("chapter", index + 1);
            value.put("title", chapter.title());
            value.put("charStart", chapter.start());
            value.put("charEnd", chapter.end());
            value.put("contentHash", SecureUtil.sha256(source.content().substring(chapter.start(), chapter.end())));
            chapterJson.add(value);
        }
        pipeline.put("chapters", chapterJson);
        pipeline.put("cursorCharOffset", 0);
        pipeline.put("processedSegments", 0);
        pipeline.put("done", source.content().isEmpty());
        pipeline.put("registry", emptyRegistry());
        pipeline.put("episodes", new JSONArray());
        pipeline.put("pending", source.content().isEmpty() ? null
                : segmentJson(source.content(), 0, chapterEnd(chapterJson, 0), 8192));
        if (source.content().isEmpty()) pipeline.put("status", "INGESTED");
        JSONObject next = JSON.parseObject(workspaceJson(manifest));
        next.put("sourcePipeline", pipeline);
        AidRuntimeArtifact nextWorkspace = writeWorkspace(invocation, scope, workspace, next,
                "Screenplay source pipeline initialized");
        return pipelineReceipt(scope, nextWorkspace, next);
    }

    /** Validate against the exact pending slice before any submission or artifact write. */
    private void validatePendingSourceFacts(Scope scope, JSONObject pipeline,
                                           StoryWorkspaceRequests.PipelineAdvance request,
                                           String contextManifestJson) {
        requirePendingSourceSegment(pipeline.getJSONObject("pending"), request);
        SourceSnapshot source = requireSource(scope, contextManifestJson, pipeline.getLong("sourceRevisionId"));
        requireSourceIdentity(source.source(), pipeline);
        String segment = source.content().substring(request.getCharStart(), request.getCharEnd());
        require(Objects.equals(SecureUtil.sha256(segment), request.getContentHash()), "source segment hash");
        JSONObject projected = JSON.parseObject(pipeline.getJSONObject("registry").toJSONString());
        try {
            mergeFacts(projected, request.getFacts(), segment, request.getCharStart(), request.getCharEnd(),
                    request.getContentHash(), chapterNumber(pipeline.getJSONArray("chapters"), request.getCharStart()));
        } catch (WorkspaceValidationException rejected) {
            if (Set.of("fact quote", "fact key conflict", "chapter draft segment conflict",
                    "pipeline registry size").contains(rejected.validationField()))
                throw new SourceInputRejectedException("STORY_SOURCE_FACTS_INVALID", rejected);
            throw rejected;
        }
    }

    static void requirePendingSourceSegment(JSONObject pending, StoryWorkspaceRequests.PipelineAdvance request) {
        if (pending == null || request == null
                || !Objects.equals(pending.getInteger("charStart"), request.getCharStart())
                || !Objects.equals(pending.getInteger("charEnd"), request.getCharEnd())
                || !Objects.equals(pending.getString("contentHash"), request.getContentHash()))
            throw new SourceInputRejectedException("STORY_SOURCE_SEGMENT_MISMATCH", null);
    }

    /** Raised only by source-input validation before artifact writes; transaction rolls back the claim. */
    public static final class SourceInputRejectedException extends IllegalArgumentException {
        private final String errorCode;
        SourceInputRejectedException(String errorCode, Throwable cause) {
            super(errorCode, cause);
            this.errorCode = errorCode;
        }
        public String errorCode() { return errorCode; }
    }

    private StoryWorkspaceViews.PipelineReceipt advancePipeline(
            RuntimeToolPreparedInvocation invocation, Scope scope, AidRuntimeArtifact workspace,
            JSONObject manifest, StoryWorkspaceRequests.PipelineAdvance request) {
        JSONObject pipeline = requirePipeline(manifest);
        SourceSnapshot source = requireSource(scope, invocation.getContextManifestJson(),
                pipeline.getLong("sourceRevisionId"));
        requireSourceIdentity(source.source(), pipeline);
        JSONObject pending = pipeline.getJSONObject("pending");
        require(pending != null && Objects.equals(pending.getInteger("charStart"), request.getCharStart())
                && Objects.equals(pending.getInteger("charEnd"), request.getCharEnd())
                && Objects.equals(pending.getString("contentHash"), request.getContentHash()),
                "pending source segment");
        String content = source.content().substring(request.getCharStart(), request.getCharEnd());
        require(Objects.equals(SecureUtil.sha256(content), request.getContentHash()), "source segment hash");
        JSONObject next = JSON.parseObject(workspaceJson(manifest));
        JSONObject nextPipeline = next.getJSONObject("sourcePipeline");
        mergeFacts(nextPipeline.getJSONObject("registry"), request.getFacts(), content,
                request.getCharStart(), request.getCharEnd(), request.getContentHash(),
                chapterNumber(nextPipeline.getJSONArray("chapters"), request.getCharStart()));
        int cursor = request.getCharEnd();
        nextPipeline.put("cursorCharOffset", cursor);
        nextPipeline.put("processedSegments", nextPipeline.getIntValue("processedSegments") + 1);
        if (cursor >= source.content().length()) {
            nextPipeline.put("pending", null);
            nextPipeline.put("done", true);
            nextPipeline.put("status", "INGESTED");
        } else {
            nextPipeline.put("pending", segmentJson(source.content(), cursor,
                    chapterEnd(nextPipeline.getJSONArray("chapters"), cursor), 8192));
        }
        AidRuntimeArtifact nextWorkspace = writeWorkspace(invocation, scope, workspace, next,
                "Screenplay source pipeline advanced");
        return pipelineReceipt(scope, nextWorkspace, next);
    }

    private StoryWorkspaceViews.BoundariesReceipt writeBoundaries(
            RuntimeToolPreparedInvocation invocation, Scope scope, AidRuntimeArtifact workspace,
            JSONObject manifest, StoryWorkspaceRequests.BoundariesWrite request) {
        JSONObject pipeline = requirePipeline(manifest);
        SourceSnapshot source = requireSource(scope, invocation.getContextManifestJson(),
                pipeline.getLong("sourceRevisionId"));
        requireSourceIdentity(source.source(), pipeline);
        List<StoryWorkspaceRequests.EpisodeBoundary> episodes = request.getEpisodes();
        int expectedStart = 0;
        JSONArray stored = new JSONArray();
        int residualMinimum = Math.max(1, source.content().length()
                / Math.max(1, request.getTargetEpisodes()) / 4);
        for (int index = 0; index < episodes.size(); index++) {
            StoryWorkspaceRequests.EpisodeBoundary episode = episodes.get(index);
            require(episode != null && Objects.equals(episode.getEpisode(), index + 1)
                    && StrUtil.isNotBlank(episode.getTitle()) && episode.getTitle().length() <= 200
                    && episode.getCharStart() != null && episode.getCharEnd() != null
                    && episode.getCharStart() == expectedStart && episode.getCharEnd() > episode.getCharStart()
                    && episode.getCharEnd() <= source.content().length()
                    && episode.getCharEnd() - episode.getCharStart() >= residualMinimum,
                    "episode boundary");
            requireUtf16Boundary(source.content(), episode.getCharStart());
            requireUtf16Boundary(source.content(), episode.getCharEnd());
            if (index > 0) validateInternalCut(source.content(), pipeline.getJSONArray("chapters"),
                    episode.getCharStart(), episodes.get(index - 1).getEndAnchor(),
                    episodes.get(index - 1).getEndAnchorSide(), episode.getStartAnchor(),
                    episode.getStartAnchorSide());
            require(episode.getMustKeep() == null || episode.getMustKeep().length() <= 2000,
                    "episode mustKeep");
            require(episode.getEnding() == null || episode.getEnding().length() <= 2000,
                    "episode ending");
            JSONObject value = new JSONObject(new LinkedHashMap<>());
            value.put("episode", index + 1);
            value.put("title", episode.getTitle().trim());
            value.put("charStart", episode.getCharStart());
            value.put("charEnd", episode.getCharEnd());
            value.put("contentHash", SecureUtil.sha256(source.content().substring(
                    episode.getCharStart(), episode.getCharEnd())));
            value.put("mustKeep", trim(episode.getMustKeep()));
            value.put("ending", trim(episode.getEnding()));
            stored.add(value);
            expectedStart = episode.getCharEnd();
        }
        require(expectedStart == source.content().length(), "episode coverage");
        JSONObject next = JSON.parseObject(workspaceJson(manifest));
        JSONObject nextPipeline = next.getJSONObject("sourcePipeline");
        nextPipeline.put("episodes", stored);
        nextPipeline.put("targetEpisodes", request.getTargetEpisodes());
        nextPipeline.put("boundariesDigest", SecureUtil.sha256(stored.toJSONString()));
        nextPipeline.put("status", "BOUNDED");
        AidRuntimeArtifact nextWorkspace = writeWorkspace(invocation, scope, workspace, next,
                "Screenplay source boundaries persisted");
        return boundariesReceipt(scope, nextWorkspace, next);
    }

    private StoryWorkspaceViews.SourceRangePage sourceRangePage(
            Scope scope, String contextManifestJson, JSONObject pipeline, String kind, int number,
            String title, int start, int end, String mustKeep, String ending,
            StoryWorkspaceRequests.SourcePage request) {
        require(request != null, "source page");
        int offset = request.getCharOffset() == null ? 0 : request.getCharOffset();
        int maxBytes = request.getMaxUtf8Bytes() == null ? 8192 : request.getMaxUtf8Bytes();
        require(offset >= 0 && offset < end - start && maxBytes >= 256 && maxBytes <= 8192,
                "source page cursor");
        SourceSnapshot source = requireSource(scope, contextManifestJson, pipeline.getLong("sourceRevisionId"));
        requireSourceIdentity(source.source(), pipeline);
        int absoluteStart = Math.addExact(start, offset);
        StoryWorkspaceViews.SourceRangePage page = new StoryWorkspaceViews.SourceRangePage();
        page.setProjectId(String.valueOf(scope.projectId()));
        page.setSourceRevisionId(String.valueOf(source.source().getId()));
        page.setSourceContentHash(source.source().getContentHash());
        page.setRangeKind(kind);
        page.setNumber(number);
        page.setTitle(title);
        page.setRangeCharStart(start);
        page.setRangeCharEnd(end);
        page.setMustKeep(mustKeep);
        page.setEnding(ending);
        page.setSegment(segmentView(source.content(), absoluteStart, end, maxBytes, start));
        return page;
    }

    private SourceSnapshot requireSource(Scope scope, String contextManifestJson, Long sourceRevisionId) {
        require(sourceRevisionId != null && sourceRevisionId > 0, "sourceRevisionId");
        AidRuntimeSourceRevision source = sourceRevisionMapper.selectAuthorized(scope.userId(), sourceRevisionId);
        require(source != null && "ACTIVE".equals(source.getStatus()) && "0".equals(source.getDelFlag())
                && source.getCharCount() != null && source.getCharCount() >= 0
                && source.getCharCount() <= MAX_SOURCE_CHARS && source.getChunkCount() != null
                && source.getChunkCount() >= 0 && isDigest(source.getContentHash()), "sourceRevisionId");
        contextAuthorizer.requireRead(contextManifestJson, source.getSourceKind(),
                source.getSourceObjectId(), source.getContentHash());
        StringBuilder content = new StringBuilder(source.getCharCount());
        MessageDigest digest = sha256Digest();
        int expectedStart = 0;
        for (int index = 0; index < source.getChunkCount(); index++) {
            AidRuntimeSourceChunk chunk = sourceChunkMapper.selectAuthorizedChunk(scope.userId(), source.getId(), index);
            String text = chunk == null ? null : chunk.getContentText();
            require(chunk != null && Objects.equals(chunk.getSourceRevisionId(), source.getId())
                    && Objects.equals(chunk.getChunkIndex(), index) && chunk.getCharStart() != null
                    && chunk.getCharStart() == expectedStart && text != null && !text.isEmpty()
                    && wellFormedUtf16(text) && Objects.equals(chunk.getCharEnd(), expectedStart + text.length())
                    && Objects.equals(chunk.getCharCount(), text.length())
                    && Objects.equals(chunk.getContentHash(), SecureUtil.sha256(text)), "source chunk integrity");
            content.append(text);
            digest.update(text.getBytes(StandardCharsets.UTF_8));
            expectedStart = chunk.getCharEnd();
        }
        require(expectedStart == source.getCharCount() && Objects.equals(source.getContentHash(),
                HexFormat.of().formatHex(digest.digest())), "source integrity");
        return new SourceSnapshot(source, content.toString());
    }

    private JSONObject sourceIdentity(AidRuntimeSourceRevision source) {
        JSONObject value = new JSONObject(new LinkedHashMap<>());
        value.put("sourceRevisionId", String.valueOf(source.getId()));
        value.put("sourceKind", source.getSourceKind());
        value.put("sourceObjectId", source.getSourceObjectId());
        value.put("sourceRevision", source.getSourceRevision());
        value.put("sourceContentHash", source.getContentHash());
        value.put("sourceCharCount", source.getCharCount());
        value.put("sourceChunkCount", source.getChunkCount());
        return value;
    }

    private void requireSourceIdentity(AidRuntimeSourceRevision source, JSONObject pipeline) {
        require(Objects.equals(String.valueOf(source.getId()), pipeline.getString("sourceRevisionId"))
                && Objects.equals(source.getSourceKind(), pipeline.getString("sourceKind"))
                && Objects.equals(source.getSourceObjectId(), pipeline.getString("sourceObjectId"))
                && Objects.equals(source.getSourceRevision(), pipeline.getString("sourceRevision"))
                && Objects.equals(source.getContentHash(), pipeline.getString("sourceContentHash"))
                && Objects.equals(source.getCharCount(), pipeline.getInteger("sourceCharCount"))
                && Objects.equals(source.getChunkCount(), pipeline.getInteger("sourceChunkCount")),
                "source pipeline identity");
    }

    private List<ChapterRange> detectChapters(String content) {
        if (content.isEmpty()) return List.of(new ChapterRange("原文", 0, 0));
        List<Heading> headings = new ArrayList<>();
        int start = 0;
        while (start < content.length()) {
            int newline = content.indexOf('\n', start);
            int end = newline < 0 ? content.length() : newline;
            int textEnd = end > start && content.charAt(end - 1) == '\r' ? end - 1 : end;
            String line = content.substring(start, textEnd).trim();
            if (!line.isEmpty() && line.length() <= 200 && STRONG_CHAPTER.matcher(line).matches()) {
                headings.add(new Heading(line.replaceFirst("^#{1,6}\\s*", ""), start));
            }
            start = newline < 0 ? content.length() : newline + 1;
        }
        if (headings.isEmpty()) return List.of(new ChapterRange("原文", 0, content.length()));
        List<ChapterRange> chapters = new ArrayList<>();
        if (headings.get(0).start() > 0) {
            if (content.substring(0, headings.get(0).start()).isBlank()) {
                headings.set(0, new Heading(headings.get(0).title(), 0));
            } else {
                chapters.add(new ChapterRange("章前内容", 0, headings.get(0).start()));
            }
        }
        for (int index = 0; index < headings.size(); index++) {
            Heading heading = headings.get(index);
            int end = index + 1 < headings.size() ? headings.get(index + 1).start() : content.length();
            if (end > heading.start()) chapters.add(new ChapterRange(heading.title(), heading.start(), end));
        }
        return chapters.isEmpty() ? List.of(new ChapterRange("原文", 0, content.length())) : List.copyOf(chapters);
    }

    private JSONObject emptyRegistry() {
        JSONObject value = new JSONObject(new LinkedHashMap<>());
        value.put("events", new JSONArray());
        value.put("primaryCharacters", new JSONArray());
        value.put("supportingCharacters", new JSONArray());
        value.put("minorCharacters", new JSONArray());
        value.put("locations", new JSONArray());
        value.put("props", new JSONArray());
        value.put("redlines", new JSONArray());
        value.put("chapterDrafts", new JSONArray());
        return value;
    }

    private void canonicalFacts(StoryWorkspaceRequests.PipelineFacts facts) {
        require(facts != null, "pipeline facts");
        facts.setEvents(canonicalFacts(facts.getEvents()));
        facts.setPrimaryCharacters(canonicalFacts(facts.getPrimaryCharacters()));
        facts.setSupportingCharacters(canonicalFacts(facts.getSupportingCharacters()));
        facts.setMinorCharacters(canonicalFacts(facts.getMinorCharacters()));
        facts.setLocations(canonicalFacts(facts.getLocations()));
        facts.setProps(canonicalFacts(facts.getProps()));
        facts.setRedlines(canonicalFacts(facts.getRedlines()));
        facts.setChapterDraft(trim(facts.getChapterDraft()));
        require(facts.getChapterDraft() == null || facts.getChapterDraft().length() <= 4000,
                "chapter draft");
        int count = facts.getEvents().size() + facts.getPrimaryCharacters().size()
                + facts.getSupportingCharacters().size() + facts.getMinorCharacters().size()
                + facts.getLocations().size() + facts.getProps().size() + facts.getRedlines().size();
        require(count <= 100, "pipeline facts count");
    }

    private List<StoryWorkspaceRequests.EvidenceFact> canonicalFacts(
            List<StoryWorkspaceRequests.EvidenceFact> values) {
        List<StoryWorkspaceRequests.EvidenceFact> result = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (StoryWorkspaceRequests.EvidenceFact raw : values == null ? List.<StoryWorkspaceRequests.EvidenceFact>of() : values) {
            require(raw != null && StrUtil.isNotBlank(raw.getKey()) && raw.getKey().length() <= 120
                    && StrUtil.isNotBlank(raw.getValue()) && raw.getValue().length() <= 2000
                    && StrUtil.isNotBlank(raw.getQuote()) && raw.getQuote().length() <= 500,
                    "evidence fact");
            StoryWorkspaceRequests.EvidenceFact fact = new StoryWorkspaceRequests.EvidenceFact();
            fact.setKey(raw.getKey().trim());
            fact.setValue(raw.getValue().trim());
            fact.setQuote(raw.getQuote());
            require(keys.add(fact.getKey()), "duplicate evidence fact");
            result.add(fact);
        }
        return result;
    }

    private void mergeFacts(JSONObject registry, StoryWorkspaceRequests.PipelineFacts facts, String segment,
                            int start, int end, String segmentHash, int chapter) {
        mergeFacts(registry.getJSONArray("events"), facts.getEvents(), segment, start, end, segmentHash, chapter);
        mergeFacts(registry.getJSONArray("primaryCharacters"), facts.getPrimaryCharacters(), segment,
                start, end, segmentHash, chapter);
        mergeFacts(registry.getJSONArray("supportingCharacters"), facts.getSupportingCharacters(), segment,
                start, end, segmentHash, chapter);
        mergeFacts(registry.getJSONArray("minorCharacters"), facts.getMinorCharacters(), segment,
                start, end, segmentHash, chapter);
        mergeFacts(registry.getJSONArray("locations"), facts.getLocations(), segment, start, end, segmentHash, chapter);
        mergeFacts(registry.getJSONArray("props"), facts.getProps(), segment, start, end, segmentHash, chapter);
        mergeFacts(registry.getJSONArray("redlines"), facts.getRedlines(), segment, start, end, segmentHash, chapter);
        if (facts.getChapterDraft() != null) {
            mergeChapterDraft(registry.getJSONArray("chapterDrafts"), facts.getChapterDraft(),
                    start, end, segmentHash, chapter);
        }
        require(factCount(registry) <= MAX_FACTS, "pipeline registry size");
    }

    private void mergeFacts(JSONArray stored, List<StoryWorkspaceRequests.EvidenceFact> facts, String segment,
                            int start, int end, String segmentHash, int chapter) {
        for (StoryWorkspaceRequests.EvidenceFact fact : facts) {
            int quote = segment.indexOf(fact.getQuote());
            require(quote >= 0, "fact quote");
            JSONObject existing = null;
            for (Object item : stored) {
                JSONObject value = (JSONObject) item;
                if (Objects.equals(value.getString("key"), fact.getKey())) {
                    if (Objects.equals(value.getString("value"), fact.getValue())) {
                        existing = value;
                    } else {
                        // A later source segment may add an observation about the same subject.
                        // Preserve earlier values and their evidence; never rewrite one segment's fact.
                        JSONArray previousEvidence = value.getJSONArray("evidence");
                        require(previousEvidence != null, "pipeline evidence");
                        for (Object previous : previousEvidence) {
                            require(previous instanceof JSONObject, "pipeline evidence");
                            JSONObject source = (JSONObject) previous;
                            require(!Objects.equals(source.getInteger("segmentCharStart"), start)
                                    || !Objects.equals(source.getInteger("segmentCharEnd"), end),
                                    "fact key conflict");
                        }
                    }
                }
            }
            if (existing == null) {
                existing = new JSONObject(new LinkedHashMap<>());
                existing.put("key", fact.getKey());
                existing.put("value", fact.getValue());
                existing.put("evidence", new JSONArray());
                stored.add(existing);
            }
            JSONObject evidence = new JSONObject(new LinkedHashMap<>());
            evidence.put("chapter", chapter);
            evidence.put("segmentCharStart", start);
            evidence.put("segmentCharEnd", end);
            evidence.put("segmentContentHash", segmentHash);
            evidence.put("quoteCharStart", start + quote);
            evidence.put("quoteCharEnd", start + quote + fact.getQuote().length());
            evidence.put("quote", fact.getQuote());
            JSONArray list = existing.getJSONArray("evidence");
            boolean duplicate = false;
            for (Object evidenceValue : list) {
                require(evidenceValue instanceof JSONObject, "pipeline evidence");
                JSONObject value = (JSONObject) evidenceValue;
                if (Objects.equals(value.getInteger("quoteCharStart"), evidence.getInteger("quoteCharStart"))
                        && Objects.equals(value.getInteger("quoteCharEnd"), evidence.getInteger("quoteCharEnd"))) {
                    require(Objects.equals(value, evidence), "fact evidence conflict");
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) list.add(evidence);
        }
    }

    private void mergeChapterDraft(JSONArray stored, String draft, int start, int end,
                                   String segmentHash, int chapter) {
        require(stored != null, "pipeline chapter drafts");
        JSONObject candidate = new JSONObject(new LinkedHashMap<>());
        candidate.put("chapter", chapter);
        candidate.put("segmentCharStart", start);
        candidate.put("segmentCharEnd", end);
        candidate.put("segmentContentHash", segmentHash);
        candidate.put("draft", draft);
        for (Object draftValue : stored) {
            require(draftValue instanceof JSONObject, "pipeline chapter draft");
            JSONObject existing = (JSONObject) draftValue;
            if (Objects.equals(existing.getInteger("segmentCharStart"), start)
                    && Objects.equals(existing.getInteger("segmentCharEnd"), end)) {
                require(Objects.equals(existing, candidate), "chapter draft segment conflict");
                return;
            }
        }
        stored.add(candidate);
    }

    private void validateStoredEvidence(JSONObject value, int chaptersTotal) {
        Integer chapter = value.getInteger("chapter");
        Integer segmentStart = value.getInteger("segmentCharStart");
        Integer segmentEnd = value.getInteger("segmentCharEnd");
        Integer quoteStart = value.getInteger("quoteCharStart");
        Integer quoteEnd = value.getInteger("quoteCharEnd");
        String quote = value.getString("quote");
        require(chapter != null && chapter >= 1 && chapter <= chaptersTotal
                && segmentStart != null && segmentStart >= 0 && segmentEnd != null && segmentEnd > segmentStart
                && isDigest(value.getString("segmentContentHash")) && quoteStart != null
                && quoteEnd != null && quoteStart >= segmentStart && quoteEnd > quoteStart
                && quoteEnd <= segmentEnd && quote != null && quote.length() <= 500
                && quoteEnd - quoteStart == quote.length(), "pipeline evidence");
    }

    private void validateStoredDraft(JSONObject value, int chaptersTotal) {
        Integer chapter = value.getInteger("chapter");
        Integer start = value.getInteger("segmentCharStart");
        Integer end = value.getInteger("segmentCharEnd");
        String draft = value.getString("draft");
        require(chapter != null && chapter >= 1 && chapter <= chaptersTotal && start != null && start >= 0
                && end != null && end > start && isDigest(value.getString("segmentContentHash"))
                && StrUtil.isNotBlank(draft) && draft.length() <= 4000, "pipeline chapter draft");
    }

    private StoryWorkspaceViews.PipelineItem pipelineFactItem(
            String category, String key, String value, JSONObject evidence) {
        StoryWorkspaceViews.PipelineItem item = new StoryWorkspaceViews.PipelineItem();
        item.setKind("FACT_EVIDENCE");
        item.setCategory(category);
        item.setKey(key);
        item.setValue(value);
        item.setChapter(evidence.getInteger("chapter"));
        item.setSegmentCharStart(evidence.getInteger("segmentCharStart"));
        item.setSegmentCharEnd(evidence.getInteger("segmentCharEnd"));
        item.setSegmentContentHash(evidence.getString("segmentContentHash"));
        item.setQuoteCharStart(evidence.getInteger("quoteCharStart"));
        item.setQuoteCharEnd(evidence.getInteger("quoteCharEnd"));
        item.setQuote(evidence.getString("quote"));
        return item;
    }

    private StoryWorkspaceViews.PipelineItem pipelineDraftItem(JSONObject draft) {
        StoryWorkspaceViews.PipelineItem item = new StoryWorkspaceViews.PipelineItem();
        item.setKind("CHAPTER_DRAFT");
        item.setChapter(draft.getInteger("chapter"));
        item.setSegmentCharStart(draft.getInteger("segmentCharStart"));
        item.setSegmentCharEnd(draft.getInteger("segmentCharEnd"));
        item.setSegmentContentHash(draft.getString("segmentContentHash"));
        item.setDraft(draft.getString("draft"));
        return item;
    }

    private int factCount(JSONObject registry) {
        return List.of("events", "primaryCharacters", "supportingCharacters", "minorCharacters",
                        "locations", "props", "redlines").stream()
                .map(registry::getJSONArray).filter(Objects::nonNull).mapToInt(JSONArray::size).sum();
    }

    private JSONObject segmentJson(String content, int start, int rangeEnd, int maxBytes) {
        StoryWorkspaceViews.SourceSegment view = segmentView(content, start, rangeEnd, maxBytes, 0);
        return (JSONObject) JSON.toJSON(view);
    }

    private StoryWorkspaceViews.SourceSegment segmentView(String content, int start, int rangeEnd,
                                                          int maxBytes, int relativeBase) {
        require(start >= 0 && start < rangeEnd && rangeEnd <= content.length(), "source segment range");
        CreativeTextSegmenter.Slice slice = CreativeTextSegmenter.slice(
                content.substring(0, rangeEnd), start, maxBytes);
        StoryWorkspaceViews.SourceSegment result = new StoryWorkspaceViews.SourceSegment();
        result.setCharStart(start);
        result.setCharEnd(slice.charEnd());
        result.setCharCount(slice.content().length());
        result.setUtf8ByteCount(slice.utf8ByteCount());
        result.setContentHash(slice.contentHash());
        result.setContent(slice.content());
        result.setFirstLine(lineNumber(content, start));
        result.setLastLine(lineNumber(content, Math.max(start, slice.charEnd() - 1)));
        result.setNextCharOffset(slice.charEnd() >= rangeEnd ? null : slice.charEnd() - relativeBase);
        result.setComplete(slice.charEnd() >= rangeEnd);
        return result;
    }

    private int lineNumber(String content, int offset) {
        int result = 1;
        for (int index = 0; index < offset; index++) if (content.charAt(index) == '\n') result++;
        return result;
    }

    private int chapterEnd(JSONArray chapters, int cursor) {
        for (Object item : chapters) {
            JSONObject chapter = (JSONObject) item;
            if (cursor >= chapter.getIntValue("charStart") && cursor < chapter.getIntValue("charEnd")) {
                return chapter.getIntValue("charEnd");
            }
        }
        throw new IllegalStateException("Stored screenplay chapter boundaries are invalid");
    }

    private int chapterNumber(JSONArray chapters, int cursor) {
        for (int index = 0; index < chapters.size(); index++) {
            JSONObject chapter = chapters.getJSONObject(index);
            if (cursor >= chapter.getIntValue("charStart") && cursor < chapter.getIntValue("charEnd")) return index + 1;
        }
        throw new IllegalStateException("Stored screenplay chapter boundaries are invalid");
    }

    private void validateInternalCut(String content, JSONArray chapters, int cut, String leftAnchor,
                                     String leftSide, String rightAnchor, String rightSide) {
        for (Object item : chapters) {
            JSONObject chapter = (JSONObject) item;
            if (cut == chapter.getIntValue("charStart") || cut == chapter.getIntValue("charEnd")) return;
        }
        Integer left = resolveAnchor(content, chapters, cut, leftAnchor, leftSide);
        Integer right = resolveAnchor(content, chapters, cut, rightAnchor, rightSide);
        require(left != null || right != null, "internal boundary anchor");
        require((left == null || left == cut) && (right == null || right == cut), "internal boundary anchor");
    }

    private Integer resolveAnchor(String content, JSONArray chapters, int cut, String anchor, String side) {
        if (anchor == null && side == null) return null;
        require(StrUtil.isNotBlank(anchor) && anchor.length() <= 500 && Set.of("BEFORE", "AFTER").contains(side),
                "boundary anchor");
        JSONObject chapter = null;
        for (Object item : chapters) {
            JSONObject candidate = (JSONObject) item;
            if (cut > candidate.getIntValue("charStart") && cut < candidate.getIntValue("charEnd")) {
                chapter = candidate;
                break;
            }
        }
        require(chapter != null, "boundary chapter");
        String value = content.substring(chapter.getIntValue("charStart"), chapter.getIntValue("charEnd"));
        int first = value.indexOf(anchor);
        require(first >= 0 && value.indexOf(anchor, first + 1) < 0, "boundary anchor uniqueness");
        int absolute = chapter.getIntValue("charStart") + first;
        return "BEFORE".equals(side) ? absolute : absolute + anchor.length();
    }

    private void requireUtf16Boundary(String content, int boundary) {
        require(boundary >= 0 && boundary <= content.length()
                && (boundary == 0 || boundary == content.length()
                || !Character.isLowSurrogate(content.charAt(boundary))), "UTF-16 boundary");
    }

    private StoryWorkspaceViews.PipelineReceipt pipelineReceipt(
            Scope scope, AidRuntimeArtifact workspace, JSONObject manifest) {
        JSONObject pipeline = requirePipeline(manifest);
        StoryWorkspaceViews.PipelineReceipt result = new StoryWorkspaceViews.PipelineReceipt();
        result.setProjectId(String.valueOf(scope.projectId()));
        result.setSourceRevisionId(pipeline.getString("sourceRevisionId"));
        result.setSourceContentHash(pipeline.getString("sourceContentHash"));
        result.setStatus(pipeline.getString("status"));
        result.setWarning(pipeline.getString("warning"));
        result.setChaptersTotal(pipeline.getInteger("chaptersTotal"));
        result.setDone(pipeline.getBooleanValue("done"));
        result.setProcessedSegments(pipeline.getInteger("processedSegments"));
        result.setFactCount(factCount(pipeline.getJSONObject("registry")));
        List<StoryWorkspaceViews.Chapter> chapters = new ArrayList<>();
        for (Object item : pipeline.getJSONArray("chapters")) {
            JSONObject value = (JSONObject) item;
            StoryWorkspaceViews.Chapter chapter = new StoryWorkspaceViews.Chapter();
            chapter.setChapter(value.getInteger("chapter"));
            chapter.setTitle(value.getString("title"));
            chapter.setCharStart(value.getInteger("charStart"));
            chapter.setCharEnd(value.getInteger("charEnd"));
            chapter.setContentHash(value.getString("contentHash"));
            chapters.add(chapter);
        }
        result.setChapters(chapters);
        JSONObject pending = pipeline.getJSONObject("pending");
        result.setNextSegment(pending == null ? null : pending.toJavaObject(StoryWorkspaceViews.SourceSegment.class));
        result.setWorkspaceArtifactRef(ref(workspace));
        result.setWorkspaceRevision(workspace.getRevision());
        return result;
    }

    private StoryWorkspaceViews.BoundariesReceipt boundariesReceipt(
            Scope scope, AidRuntimeArtifact workspace, JSONObject manifest) {
        JSONObject pipeline = requirePipeline(manifest);
        JSONArray episodes = pipeline.getJSONArray("episodes");
        require(episodes != null && !episodes.isEmpty() && isDigest(pipeline.getString("boundariesDigest")),
                "stored source boundaries");
        StoryWorkspaceViews.BoundariesReceipt result = new StoryWorkspaceViews.BoundariesReceipt();
        result.setProjectId(String.valueOf(scope.projectId()));
        result.setSourceRevisionId(pipeline.getString("sourceRevisionId"));
        result.setTargetEpisodes(pipeline.getInteger("targetEpisodes"));
        result.setActualEpisodes(episodes.size());
        result.setCompleteCoverage(true);
        result.setBoundariesDigest(pipeline.getString("boundariesDigest"));
        result.setWorkspaceArtifactRef(ref(workspace));
        result.setWorkspaceRevision(workspace.getRevision());
        return result;
    }

    private JSONObject requirePipeline(JSONObject manifest) {
        JSONObject pipeline = manifest == null ? null : manifest.getJSONObject("sourcePipeline");
        JSONObject registry = pipeline == null ? null : pipeline.getJSONObject("registry");
        require(pipeline != null && "story-source-pipeline/v1".equals(pipeline.getString("schemaVersion"))
                && isDigest(pipeline.getString("sourceContentHash")) && pipeline.getJSONArray("chapters") != null
                && registry != null && registry.getJSONArray("events") != null
                && registry.getJSONArray("primaryCharacters") != null
                && registry.getJSONArray("supportingCharacters") != null
                && registry.getJSONArray("minorCharacters") != null
                && registry.getJSONArray("locations") != null && registry.getJSONArray("props") != null
                && registry.getJSONArray("redlines") != null && registry.getJSONArray("chapterDrafts") != null
                && pipeline.getJSONArray("episodes") != null,
                "source pipeline");
        return pipeline;
    }

    private int positiveNumber(Integer value, String field) {
        require(value != null && value >= 1 && value <= 999, field);
        return value;
    }

    private StoryWorkspaceViews.LinePage linePage(Scope scope, String path, String source,
                                                   AidRuntimeArtifact artifact, String content,
                                                   int startLine, int maxLines, int maxBytes) {
        List<LineRange> ranges = lineRanges(content);
        require(startLine <= ranges.size(), "startLine");
        List<StoryWorkspaceViews.LineItem> items = new ArrayList<>();
        int bytes = 0;
        int cursor = startLine - 1;
        int blockStart = ranges.get(cursor).start();
        int blockEnd = blockStart;
        while (cursor < ranges.size() && items.size() < maxLines) {
            LineRange range = ranges.get(cursor);
            String text = content.substring(range.start(), range.contentEnd());
            int lineBytes = content.substring(range.start(), range.end()).getBytes(StandardCharsets.UTF_8).length;
            if (!items.isEmpty() && bytes + lineBytes > maxBytes) break;
            require(lineBytes <= maxBytes, "line byte budget");
            StoryWorkspaceViews.LineItem item = new StoryWorkspaceViews.LineItem();
            item.setLine(cursor + 1);
            item.setContent(text);
            item.setContentHash(SecureUtil.sha256(text));
            items.add(item);
            bytes += lineBytes;
            blockEnd = range.end();
            cursor++;
            if (bytes >= maxBytes) break;
        }
        require(!items.isEmpty(), "line byte budget");
        StoryWorkspaceViews.LinePage result = new StoryWorkspaceViews.LinePage();
        result.setProjectId(String.valueOf(scope.projectId()));
        result.setPath(path);
        result.setSource(source);
        result.setArtifactRef(ref(artifact));
        result.setRevision(artifact.getRevision());
        result.setContentHash(artifact.getContentHash());
        result.setStartLine(startLine);
        result.setEndLine(cursor);
        result.setTotalLines(ranges.size());
        result.setNextLine(cursor >= ranges.size() ? null : cursor + 1);
        result.setComplete(cursor >= ranges.size());
        result.setBlockHash(SecureUtil.sha256(content.substring(blockStart, blockEnd)));
        result.setLines(items);
        return result;
    }

    private StoryWorkspaceViews.ArtifactReceipt patchArtifact(
            RuntimeToolPreparedInvocation invocation, Scope scope, AidRuntimeArtifact workspace,
            JSONObject manifest, StoryWorkspaceRequests.ArtifactPatch request, String sourceRefsJson) {
        String path = request.getPath();
        AidRuntimeArtifact source = "DRAFT".equals(request.getSource())
                ? requireDraftEntryArtifact(scope, path, manifest.getJSONObject("drafts").getJSONObject(path))
                : requireEntryArtifact(scope, path, requireFileEntry(manifest, path));
        requireExpected(source, request.getExpectedRevision(), request.getExpectedContentHash());
        String content = readContent(source);
        List<LineRange> lines = lineRanges(content);
        require(request.getEndLine() <= lines.size(), "patch lines");
        int start = lines.get(request.getStartLine() - 1).start();
        int end = lines.get(request.getEndLine() - 1).end();
        require(Objects.equals(request.getExpectedBlockHash(), SecureUtil.sha256(content.substring(start, end))),
                "patch block CAS");
        String nextContent = content.substring(0, start) + request.getReplacement() + content.substring(end);
        require(!nextContent.isEmpty() && nextContent.length() <= MAX_CONTENT_CHARS && wellFormedUtf16(nextContent),
                "patched content");
        JSONObject draftEntry = manifest.getJSONObject("drafts").getJSONObject(path);
        AidRuntimeArtifact base = "DRAFT".equals(request.getSource())
                ? baseArtifactForDraft(scope, path, manifest, draftEntry)
                : source;
        AidRuntimeArtifact previousDraft = latest(scope.userId(), draftLogicalId(scope.projectId(), path));
        require(manifest.getJSONObject("drafts").containsKey(path)
                || manifest.getJSONObject("drafts").size() < MAX_DRAFTS, "draft capacity");
        StoryWorkspaceRequests.ArtifactWrite metadataRequest = new StoryWorkspaceRequests.ArtifactWrite();
        metadataRequest.setPath(path);
        metadataRequest.setDisposition("DRAFT");
        metadataRequest.setSummary(request.getSummary());
        ArtifactRef written = writeArtifact(invocation, draftLogicalId(scope.projectId(), path),
                previousDraft == null ? null : previousDraft.getRevision(), artifactType(path), FILE_SCHEMA,
                nextContent, request.getSummary(), JSON.parseArray(sourceRefsJson),
                fileMetadata(scope, metadataRequest, manifest.getString("mode")));
        AidRuntimeArtifact draft = requireArtifact(scope.userId(), written.getArtifactId());
        JSONObject next = JSON.parseObject(workspaceJson(manifest));
        JSONObject entry = fileEntry(draft, null);
        entry.put("baseArtifactRef", base == null ? null : ref(base));
        entry.put("baseRevision", base == null ? 0L : base.getRevision());
        entry.put("baseContentHash", base == null ? null : base.getContentHash());
        next.getJSONObject("drafts").put(path, entry);
        AidRuntimeArtifact nextWorkspace = writeWorkspace(invocation, scope, workspace, next,
                "Screenplay draft patched");
        StoryWorkspaceViews.ArtifactReceipt result = new StoryWorkspaceViews.ArtifactReceipt();
        copyReceipt(result, scope, path, draft, nextWorkspace);
        result.setDisposition("DRAFT");
        return result;
    }

    private StoryWorkspaceViews.UploadReceipt beginUpload(
            RuntimeToolPreparedInvocation invocation, Scope scope, AidRuntimeArtifact workspace,
            JSONObject manifest, StoryWorkspaceRequests.UploadBegin request, String sourceRefsJson) {
        require(!manifest.getJSONObject("uploads").containsKey(request.getUploadKey())
                && manifest.getJSONObject("uploads").size() < MAX_UPLOADS, "upload key");
        JSONObject currentEntry = fileEntry(manifest, request.getPath());
        AidRuntimeArtifact current = currentEntry == null ? null
                : requireEntryArtifact(scope, request.getPath(), currentEntry);
        requireExpected(current, request.getExpectedRevision(), request.getExpectedContentHash());
        JSONObject upload = new JSONObject(new LinkedHashMap<>());
        upload.put("schemaVersion", "story-upload/v1");
        upload.put("uploadKey", request.getUploadKey());
        upload.put("path", request.getPath());
        upload.put("disposition", request.getDisposition());
        upload.put("baseArtifactRef", current == null ? null : ref(current));
        upload.put("baseRevision", current == null ? 0L : current.getRevision());
        upload.put("baseContentHash", current == null ? null : current.getContentHash());
        upload.put("expectedFragmentCount", request.getExpectedFragmentCount());
        upload.put("expectedCharCount", request.getExpectedCharCount());
        upload.put("finalContentHash", request.getFinalContentHash());
        upload.put("summary", request.getSummary());
        upload.put("sourceRefs", mergeSourceRefs(current == null ? null : current.getSourceContextRefsJson(),
                JSON.parseArray(sourceRefsJson)));
        upload.put("sourceUses", JSON.toJSON(request.getSources()));
        upload.put("skeletonPlan", JSON.toJSON(request.getSkeletonPlan()));
        upload.put("fragments", new JSONArray());
        JSONObject next = JSON.parseObject(workspaceJson(manifest));
        next.getJSONObject("uploads").put(request.getUploadKey(), upload);
        AidRuntimeArtifact nextWorkspace = writeWorkspace(invocation, scope, workspace, next,
                "Screenplay staged upload started");
        return uploadReceipt(scope, nextWorkspace, next, request.getUploadKey(), null);
    }

    private StoryWorkspaceViews.UploadReceipt appendUpload(
            RuntimeToolPreparedInvocation invocation, Scope scope, AidRuntimeArtifact workspace,
            JSONObject manifest, StoryWorkspaceRequests.UploadAppend request) {
        JSONObject upload = requireUpload(manifest, request.getUploadKey());
        JSONArray fragments = upload.getJSONArray("fragments");
        require(request.getFragmentIndex() == fragments.size()
                && request.getFragmentIndex() < upload.getIntValue("expectedFragmentCount"),
                "upload fragment index");
        int received = fragments.stream().map(value -> (JSONObject) value)
                .mapToInt(value -> value.getIntValue("charCount")).sum();
        require(request.getCharStart() == received
                && received + request.getContent().length() <= upload.getIntValue("expectedCharCount"),
                "upload fragment range");
        String logicalId = fragmentLogicalId(scope.projectId(), request.getUploadKey(), request.getFragmentIndex());
        JSONObject metadata = metadata("STORY_FRAGMENT", scope, upload.getString("path"),
                manifest.getString("mode"));
        metadata.put("uploadKey", request.getUploadKey());
        metadata.put("fragmentIndex", request.getFragmentIndex());
        ArtifactRef written = writeArtifact(invocation, logicalId, null, "CREATIVE_TEXT", FRAGMENT_SCHEMA,
                request.getContent(), "Screenplay staged fragment", new JSONArray(), metadata);
        AidRuntimeArtifact fragment = requireArtifact(scope.userId(), written.getArtifactId());
        JSONObject next = JSON.parseObject(workspaceJson(manifest));
        JSONObject nextUpload = requireUpload(next, request.getUploadKey());
        JSONObject entry = new JSONObject(new LinkedHashMap<>());
        entry.put("fragmentIndex", request.getFragmentIndex());
        entry.put("charStart", request.getCharStart());
        entry.put("charCount", request.getContent().length());
        entry.put("contentHash", request.getContentHash());
        entry.put("artifactId", String.valueOf(fragment.getId()));
        entry.put("artifactRef", ref(fragment));
        nextUpload.getJSONArray("fragments").add(entry);
        AidRuntimeArtifact nextWorkspace = writeWorkspace(invocation, scope, workspace, next,
                "Screenplay staged fragment persisted");
        return uploadReceipt(scope, nextWorkspace, next, request.getUploadKey(), ref(fragment));
    }

    private StoryWorkspaceViews.UploadReceipt finalizeUpload(
            RuntimeToolPreparedInvocation invocation, Scope scope, AidRuntimeArtifact workspace,
            JSONObject manifest, StoryWorkspaceRequests.UploadFinalize request) {
        JSONObject upload = requireUpload(manifest, request.getUploadKey());
        JSONArray fragments = upload.getJSONArray("fragments");
        require(fragments.size() == upload.getIntValue("expectedFragmentCount"), "upload completeness");
        StringBuilder content = new StringBuilder(upload.getIntValue("expectedCharCount"));
        for (int index = 0; index < fragments.size(); index++) {
            JSONObject entry = fragments.getJSONObject(index);
            require(entry.getIntValue("fragmentIndex") == index && entry.getIntValue("charStart") == content.length(),
                    "upload fragment order");
            AidRuntimeArtifact artifact = requireArtifact(scope.userId(), entry.getLong("artifactId"));
            require(Objects.equals(artifact.getLogicalArtifactId(), fragmentLogicalId(scope.projectId(),
                            request.getUploadKey(), index)) && Objects.equals(ref(artifact), entry.getString("artifactRef"))
                    && Objects.equals(artifact.getContentHash(), entry.getString("contentHash")),
                    "upload fragment receipt");
            String text = readContent(artifact);
            require(text.length() == entry.getIntValue("charCount"), "upload fragment content");
            content.append(text);
        }
        require(content.length() == upload.getIntValue("expectedCharCount")
                && Objects.equals(SecureUtil.sha256(content.toString()), upload.getString("finalContentHash")),
                "upload final hash");
        require(lineRanges(content.toString()).stream().allMatch(line -> content.substring(line.start(), line.end())
                .getBytes(StandardCharsets.UTF_8).length <= MAX_LINE_UTF8_BYTES), "upload line byte budget");
        String path = upload.getString("path");
        String disposition = upload.getString("disposition");
        reauthorizeStoredSourceRefs(scope, invocation.getContextManifestJson(), upload.getJSONArray("sourceRefs"));
        JSONObject currentEntry = fileEntry(manifest, path);
        AidRuntimeArtifact current = currentEntry == null ? null : requireEntryArtifact(scope, path, currentEntry);
        if ("FINAL".equals(disposition)) {
            require(current != null || manifest.getJSONObject("files").size() < MAX_FILES, "file capacity");
        }
        requireBase(current, upload);
        String logicalId = "DRAFT".equals(disposition) ? draftLogicalId(scope.projectId(), path)
                : fileLogicalId(scope.projectId(), path);
        AidRuntimeArtifact previousLogical = latest(scope.userId(), logicalId);
        JSONObject fileMetadata = metadata("STORY_FILE", scope, path, manifest.getString("mode"));
        fileMetadata.put("disposition", disposition);
        fileMetadata.put("uploadKey", request.getUploadKey());
        if (upload.get("skeletonPlan") != null) fileMetadata.put("skeletonPlan", upload.get("skeletonPlan"));
        ArtifactRef written = writeArtifact(invocation, logicalId,
                previousLogical == null ? null : previousLogical.getRevision(), artifactType(path), FILE_SCHEMA,
                content.toString(), upload.getString("summary"), upload.getJSONArray("sourceRefs"), fileMetadata);
        AidRuntimeArtifact artifact = requireArtifact(scope.userId(), written.getArtifactId());
        JSONObject next = JSON.parseObject(workspaceJson(manifest));
        if ("DRAFT".equals(disposition)) {
            require(next.getJSONObject("drafts").containsKey(path)
                    || next.getJSONObject("drafts").size() < MAX_DRAFTS, "draft capacity");
            JSONObject entry = fileEntry(artifact, null);
            entry.put("baseArtifactRef", upload.getString("baseArtifactRef"));
            entry.put("baseRevision", upload.getLong("baseRevision"));
            entry.put("baseContentHash", upload.getString("baseContentHash"));
            next.getJSONObject("drafts").put(path, entry);
        } else {
            next.getJSONObject("files").put(path, followupFileEntry(artifact, currentEntry));
            applyUploadPlan(next, upload);
        }
        next.getJSONObject("uploads").remove(request.getUploadKey());
        AidRuntimeArtifact nextWorkspace = writeWorkspace(invocation, scope, workspace, next,
                "Screenplay staged upload finalized");
        return uploadReceipt(scope, nextWorkspace, next, request.getUploadKey(), null,
                artifactReceipt(scope, path, artifact, nextWorkspace, disposition));
    }

    private StoryWorkspaceViews.DraftResolutionReceipt resolveDraft(
            RuntimeToolPreparedInvocation invocation, Scope scope, AidRuntimeArtifact workspace,
            JSONObject manifest, StoryWorkspaceRequests.DraftResolve request) {
        JSONObject draftEntry = manifest.getJSONObject("drafts").getJSONObject(request.getPath());
        AidRuntimeArtifact draft = requireDraftEntryArtifact(scope, request.getPath(), draftEntry);
        require(Objects.equals(ref(draft), request.getDraftArtifactRef())
                && Objects.equals(draft.getContentHash(), request.getDraftContentHash()), "draft receipt");
        AidRuntimeArtifact base = null;
        if ("ADOPT".equals(request.getAction())) {
            base = baseArtifactForDraft(scope, request.getPath(), manifest, draftEntry);
            require(base != null || manifest.getJSONObject("files").size() < MAX_FILES, "file capacity");
            reauthorizeStoredSourceRefs(scope, invocation.getContextManifestJson(),
                    JSON.parseArray(draft.getSourceContextRefsJson()));
        }
        JSONObject next = JSON.parseObject(workspaceJson(manifest));
        AidRuntimeArtifact adopted = null;
        if ("ADOPT".equals(request.getAction())) {
            String content = readContent(draft);
            JSONObject draftMetadata = JSON.parseObject(draft.getMetadataJson());
            ArtifactRef written = writeArtifact(invocation, fileLogicalId(scope.projectId(), request.getPath()),
                    base == null ? null : base.getRevision(), artifactType(request.getPath()), FILE_SCHEMA,
                    content, draft.getSummary(), JSON.parseArray(draft.getSourceContextRefsJson()),
                    draftMetadata == null ? metadata("STORY_FILE", scope, request.getPath(),
                            manifest.getString("mode")) : draftMetadata);
            adopted = requireArtifact(scope.userId(), written.getArtifactId());
            next.getJSONObject("files").put(request.getPath(), followupFileEntry(adopted,
                    fileEntry(manifest, request.getPath())));
            applyMetadataPlan(next, draftMetadata, request.getPath());
        }
        next.getJSONObject("drafts").remove(request.getPath());
        AidRuntimeArtifact nextWorkspace = writeWorkspace(invocation, scope, workspace, next,
                "Screenplay draft " + request.getAction().toLowerCase(Locale.ROOT));
        return draftResolutionReceipt(scope, request, draft, adopted, nextWorkspace);
    }

    private StoryWorkspaceViews.CheckpointReceipt recordCheckpoint(
            RuntimeToolPreparedInvocation invocation, Scope scope, AidRuntimeArtifact workspace,
            JSONObject manifest, StoryWorkspaceRequests.CheckpointRecord request) {
        JSONArray refs = new JSONArray();
        for (String path : request.getPaths()) {
            refs.add(ref(requireEntryArtifact(scope, path, requireFileEntry(manifest, path))));
        }
        JSONArray draftRefs = new JSONArray();
        for (String path : request.getDraftPaths()) {
            draftRefs.add(ref(requireDraftEntryArtifact(scope, path,
                    manifest.getJSONObject("drafts").getJSONObject(path))));
        }
        JSONObject content = new JSONObject(new LinkedHashMap<>());
        content.put("schemaVersion", CHECKPOINT_SCHEMA);
        content.put("projectId", String.valueOf(scope.projectId()));
        content.put("checkpoint", request.getCheckpoint());
        content.put("summary", request.getSummary());
        content.put("artifactRefs", refs);
        content.put("draftArtifactRefs", draftRefs);
        String logicalId = checkpointLogicalId(scope.projectId(), request.getCheckpoint());
        AidRuntimeArtifact previous = latest(scope.userId(), logicalId);
        ArtifactRef written = writeArtifact(invocation, logicalId,
                previous == null ? null : previous.getRevision(), "CREATIVE_TEXT", CHECKPOINT_SCHEMA,
                workspaceJson(content), "Screenplay checkpoint result card", new JSONArray(),
                metadata("STORY_CHECKPOINT", scope, null, manifest.getString("mode")));
        AidRuntimeArtifact checkpoint = requireArtifact(scope.userId(), written.getArtifactId());
        JSONObject next = JSON.parseObject(workspaceJson(manifest));
        JSONObject entry = new JSONObject(new LinkedHashMap<>());
        entry.put("checkpoint", request.getCheckpoint());
        entry.put("artifactId", String.valueOf(checkpoint.getId()));
        entry.put("artifactRef", ref(checkpoint));
        entry.put("revision", checkpoint.getRevision());
        entry.put("contentHash", checkpoint.getContentHash());
        next.getJSONArray("checkpoints").add(entry);
        AidRuntimeArtifact nextWorkspace = writeWorkspace(invocation, scope, workspace, next,
                "Screenplay checkpoint result card persisted");
        return checkpointReceipt(scope, checkpoint, content, nextWorkspace);
    }

    private AidRuntimeArtifact writeWorkspace(RuntimeToolPreparedInvocation invocation, Scope scope,
                                              AidRuntimeArtifact workspace, JSONObject content, String summary) {
        ArtifactRef written = writeArtifact(invocation, workspaceLogicalId(scope.projectId()),
                workspace.getRevision(), "CREATIVE_TEXT", WORKSPACE_SCHEMA, workspaceJson(content), summary,
                new JSONArray(), metadata("STORY_WORKSPACE", scope, null, content.getString("mode")));
        return requireArtifact(scope.userId(), written.getArtifactId());
    }

    private StoryWorkspaceViews.ArtifactReceipt reconcileArtifactMutation(
            Scope scope, AidRuntimeArtifact workspace, JSONObject manifest, String path, String disposition) {
        JSONObject entry = "DRAFT".equals(disposition) ? manifest.getJSONObject("drafts").getJSONObject(path)
                : fileEntry(manifest, path);
        AidRuntimeArtifact artifact = "DRAFT".equals(disposition)
                ? requireDraftEntryArtifact(scope, path, entry) : requireEntryArtifact(scope, path, entry);
        return artifactReceipt(scope, path, artifact, workspace, disposition);
    }

    private StoryWorkspaceViews.UploadReceipt reconcileFinalizedUpload(
            Scope scope, AidRuntimeArtifact workspace, JSONObject manifest,
            StoryWorkspaceRequests.UploadFinalize request) {
        require(!manifest.getJSONObject("uploads").containsKey(request.getUploadKey()), "finalized upload");
        AidRuntimeArtifact artifact = producedByStep(scope.userId(), workspace.getProducerRunId(),
                workspace.getProducerStepId()).stream().filter(value -> {
            JSONObject metadata = parseObject(value.getMetadataJson());
            return metadata != null && Objects.equals(request.getUploadKey(), metadata.getString("uploadKey"));
        }).findFirst().orElse(null);
        if (artifact == null) return null;
        JSONObject metadata = parseObject(artifact.getMetadataJson());
        String path = metadata.getString("path");
        String disposition = metadata.getString("disposition");
        return uploadReceipt(scope, workspace, manifest, request.getUploadKey(), null,
                artifactReceipt(scope, path, artifact, workspace, disposition));
    }

    private StoryWorkspaceViews.DraftResolutionReceipt reconcileDraftResolution(
            Scope scope, AidRuntimeArtifact workspace, JSONObject manifest,
            StoryWorkspaceRequests.DraftResolve request, Long runId, Long stepId) {
        require(!manifest.getJSONObject("drafts").containsKey(request.getPath()), "resolved draft");
        AidRuntimeArtifact adopted = "ADOPT".equals(request.getAction())
                ? produced(scope.userId(), runId, stepId, fileLogicalId(scope.projectId(), request.getPath())) : null;
        StoryWorkspaceViews.DraftResolutionReceipt result = new StoryWorkspaceViews.DraftResolutionReceipt();
        result.setProjectId(String.valueOf(scope.projectId()));
        result.setPath(request.getPath());
        result.setAction(request.getAction());
        result.setDraftArtifactRef(request.getDraftArtifactRef());
        result.setDraftContentHash(request.getDraftContentHash());
        result.setStatus("ADOPT".equals(request.getAction()) ? "ADOPTED" : "IGNORED");
        result.setAdoptedArtifact(adopted == null ? null
                : artifactReceipt(scope, request.getPath(), adopted, workspace, "FINAL"));
        result.setWorkspaceArtifactRef(ref(workspace));
        result.setWorkspaceRevision(workspace.getRevision());
        return result;
    }

    private StoryWorkspaceViews.CheckpointReceipt reconcileCheckpoint(
            Scope scope, AidRuntimeArtifact workspace, JSONObject manifest,
            StoryWorkspaceRequests.CheckpointRecord request, Long runId, Long stepId) {
        AidRuntimeArtifact checkpoint = produced(scope.userId(), runId, stepId,
                checkpointLogicalId(scope.projectId(), request.getCheckpoint()));
        if (checkpoint == null) return null;
        return checkpointReceipt(scope, checkpoint, JSON.parseObject(readContent(checkpoint)), workspace);
    }

    private StoryWorkspaceViews.DraftResolutionReceipt draftResolutionReceipt(
            Scope scope, StoryWorkspaceRequests.DraftResolve request, AidRuntimeArtifact draft,
            AidRuntimeArtifact adopted, AidRuntimeArtifact workspace) {
        StoryWorkspaceViews.DraftResolutionReceipt result = new StoryWorkspaceViews.DraftResolutionReceipt();
        result.setProjectId(String.valueOf(scope.projectId()));
        result.setPath(request.getPath());
        result.setAction(request.getAction());
        result.setDraftArtifactRef(ref(draft));
        result.setDraftContentHash(draft.getContentHash());
        result.setStatus("ADOPT".equals(request.getAction()) ? "ADOPTED" : "IGNORED");
        result.setAdoptedArtifact(adopted == null ? null
                : artifactReceipt(scope, request.getPath(), adopted, workspace, "FINAL"));
        result.setWorkspaceArtifactRef(ref(workspace));
        result.setWorkspaceRevision(workspace.getRevision());
        return result;
    }

    private StoryWorkspaceViews.CheckpointReceipt checkpointReceipt(
            Scope scope, AidRuntimeArtifact checkpoint, JSONObject content, AidRuntimeArtifact workspace) {
        StoryWorkspaceViews.CheckpointReceipt result = new StoryWorkspaceViews.CheckpointReceipt();
        result.setProjectId(String.valueOf(scope.projectId()));
        result.setCheckpoint(content.getString("checkpoint"));
        result.setCheckpointArtifactId(String.valueOf(checkpoint.getId()));
        result.setCheckpointArtifactRef(ref(checkpoint));
        result.setCheckpointRevision(checkpoint.getRevision());
        result.setCheckpointContentHash(checkpoint.getContentHash());
        result.setArtifactRefs(content.getJSONArray("artifactRefs").toJavaList(String.class));
        result.setDraftArtifactRefs(content.getJSONArray("draftArtifactRefs").toJavaList(String.class));
        result.setWorkspaceArtifactRef(ref(workspace));
        result.setWorkspaceRevision(workspace.getRevision());
        return result;
    }

    private StoryWorkspaceViews.UploadReceipt uploadReceipt(
            Scope scope, AidRuntimeArtifact workspace, JSONObject manifest, String uploadKey,
            String fragmentArtifactRef) {
        return uploadReceipt(scope, workspace, manifest, uploadKey, fragmentArtifactRef, null);
    }

    private StoryWorkspaceViews.UploadReceipt uploadReceipt(
            Scope scope, AidRuntimeArtifact workspace, JSONObject manifest, String uploadKey,
            String fragmentArtifactRef, StoryWorkspaceViews.ArtifactReceipt artifact) {
        JSONObject upload = manifest.getJSONObject("uploads").getJSONObject(uploadKey);
        StoryWorkspaceViews.UploadReceipt result = new StoryWorkspaceViews.UploadReceipt();
        result.setProjectId(String.valueOf(scope.projectId()));
        result.setUploadKey(uploadKey);
        result.setPath(upload == null ? artifact == null ? null : artifact.getPath() : upload.getString("path"));
        result.setStatus(upload == null ? "FINALIZED" : upload.getJSONArray("fragments").isEmpty()
                ? "STARTED" : "UPLOADING");
        if (upload != null) {
            JSONArray fragments = upload.getJSONArray("fragments");
            result.setExpectedFragmentCount(upload.getInteger("expectedFragmentCount"));
            result.setReceivedFragmentCount(fragments.size());
            result.setNextFragmentIndex(fragments.size());
            result.setExpectedCharCount(upload.getInteger("expectedCharCount"));
            int receivedCharCount = fragments.stream().map(value -> (JSONObject) value)
                    .mapToInt(value -> value.getIntValue("charCount")).sum();
            result.setReceivedCharCount(receivedCharCount);
            result.setNextCharStart(receivedCharCount);
            result.setFinalContentHash(upload.getString("finalContentHash"));
        } else if (artifact != null) {
            result.setFinalContentHash(artifact.getContentHash());
        }
        result.setFragmentArtifactRef(fragmentArtifactRef);
        result.setWorkspaceArtifactRef(ref(workspace));
        result.setWorkspaceRevision(workspace.getRevision());
        result.setArtifact(artifact);
        return result;
    }

    private StoryWorkspaceViews.ArtifactReceipt artifactReceipt(
            Scope scope, String path, AidRuntimeArtifact artifact, AidRuntimeArtifact workspace,
            String disposition) {
        StoryWorkspaceViews.ArtifactReceipt result = new StoryWorkspaceViews.ArtifactReceipt();
        copyReceipt(result, scope, path, artifact, workspace);
        result.setDisposition(disposition);
        return result;
    }

    private JSONObject requireUpload(JSONObject manifest, String uploadKey) {
        JSONObject value = manifest.getJSONObject("uploads").getJSONObject(uploadKey);
        require(value != null && "story-upload/v1".equals(value.getString("schemaVersion"))
                && Objects.equals(uploadKey, value.getString("uploadKey"))
                && value.getJSONArray("fragments") != null, "staged upload");
        return value;
    }

    private AidRuntimeArtifact currentArtifact(Scope scope, JSONObject manifest, String path) {
        JSONObject entry = fileEntry(manifest, path);
        return entry == null ? null : requireEntryArtifact(scope, path, entry);
    }

    private void requireBase(AidRuntimeArtifact current, JSONObject stored) {
        if (stored.getLongValue("baseRevision") == 0) {
            require(current == null && stored.getString("baseArtifactRef") == null
                    && stored.getString("baseContentHash") == null, "upload base CAS");
            return;
        }
        require(current != null && Objects.equals(ref(current), stored.getString("baseArtifactRef"))
                && Objects.equals(current.getRevision(), stored.getLong("baseRevision"))
                && Objects.equals(current.getContentHash(), stored.getString("baseContentHash")),
                "upload base CAS");
    }

    private AidRuntimeArtifact baseArtifactForDraft(Scope scope, String path, JSONObject manifest,
                                                    JSONObject draftEntry) {
        require(draftEntry != null, "draft entry");
        AidRuntimeArtifact current = currentArtifact(scope, manifest, path);
        if (draftEntry.getLongValue("baseRevision") == 0) {
            require(current == null && draftEntry.getString("baseArtifactRef") == null
                    && draftEntry.getString("baseContentHash") == null, "draft base CAS");
            return null;
        }
        require(current != null && Objects.equals(ref(current), draftEntry.getString("baseArtifactRef"))
                && Objects.equals(current.getRevision(), draftEntry.getLong("baseRevision"))
                && Objects.equals(current.getContentHash(), draftEntry.getString("baseContentHash")),
                "draft base CAS");
        return current;
    }

    private AidRuntimeArtifact requireDraftEntryArtifact(Scope scope, String path, JSONObject entry) {
        return requireDraftEntryArtifact(scope, path, entry, null);
    }

    private AidRuntimeArtifact requireDraftEntryArtifact(
            Scope scope, String path, JSONObject entry, Map<Long, AidRuntimeArtifact> authorizedArtifacts) {
        require(entry != null && entry.keySet().containsAll(Set.of("artifactId", "artifactRef", "artifactType",
                "revision", "contentHash", "reviewStatus", "baseArtifactRef", "baseRevision",
                "baseContentHash")), "draft entry");
        Long artifactId;
        try { artifactId = Long.valueOf(entry.getString("artifactId")); }
        catch (RuntimeException invalid) { throw new IllegalStateException("Stored screenplay draft is invalid", invalid); }
        AidRuntimeArtifact artifact = authorizedArtifacts == null
                ? requireArtifact(scope.userId(), artifactId) : requireArtifact(artifactId, authorizedArtifacts);
        require(Objects.equals(entry.getString("artifactRef"), ref(artifact))
                && Objects.equals(entry.getString("artifactType"), artifact.getArtifactType())
                && Objects.equals(entry.getLong("revision"), artifact.getRevision())
                && Objects.equals(entry.getString("contentHash"), artifact.getContentHash())
                && Objects.equals(draftLogicalId(scope.projectId(), path), artifact.getLogicalArtifactId()),
                "draft entry");
        return artifact;
    }

    private void applyUploadPlan(JSONObject manifest, JSONObject upload) {
        if (!"skeleton.md".equals(upload.getString("path"))) return;
        Object raw = upload.get("skeletonPlan");
        require(raw != null, "upload skeletonPlan");
        StoryWorkspaceRequests.SkeletonPlan plan = JSON.to(StoryWorkspaceRequests.SkeletonPlan.class, raw);
        manifest.put("title", plan.getTitle().trim());
        manifest.put("totalEpisodes", plan.getTotalEpisodes());
        manifest.put("episodeDurationSeconds", plan.getEpisodeDurationSeconds());
    }

    private void applyMetadataPlan(JSONObject manifest, JSONObject metadata, String path) {
        if (!"skeleton.md".equals(path) || metadata == null || metadata.get("skeletonPlan") == null) return;
        StoryWorkspaceRequests.SkeletonPlan plan = JSON.to(StoryWorkspaceRequests.SkeletonPlan.class,
                metadata.get("skeletonPlan"));
        validatePlan(plan, manifest.getString("mode"));
        manifest.put("title", plan.getTitle().trim());
        manifest.put("totalEpisodes", plan.getTotalEpisodes());
        manifest.put("episodeDurationSeconds", plan.getEpisodeDurationSeconds());
    }

    private List<LineRange> lineRanges(String content) {
        List<LineRange> result = new ArrayList<>();
        int start = 0;
        while (start < content.length()) {
            int newline = content.indexOf('\n', start);
            int end = newline < 0 ? content.length() : newline + 1;
            int contentEnd = newline < 0 ? end : newline > start && content.charAt(newline - 1) == '\r'
                    ? newline - 1 : newline;
            result.add(new LineRange(start, contentEnd, end));
            start = end;
        }
        if (content.isEmpty() || content.endsWith("\n")) result.add(new LineRange(content.length(),
                content.length(), content.length()));
        return result;
    }

    private List<String> canonicalPaths(List<String> values, String mode) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String value : values == null ? List.<String>of() : values) {
            require(unique.add(canonicalPath(value, mode)), "duplicate checkpoint path");
        }
        require(unique.size() <= 100, "checkpoint paths");
        return new ArrayList<>(unique);
    }

    private String canonicalArtifactSource(String value) {
        String source = value == null ? "CURRENT" : value.trim().toUpperCase(Locale.ROOT);
        require(Set.of("CURRENT", "DRAFT").contains(source), "artifact source");
        return source;
    }

    private String canonicalUploadKey(String value) {
        require(value != null && value.equals(value.trim()) && UPLOAD_KEY.matcher(value).matches(), "uploadKey");
        return value;
    }

    private String operationUploadKey(Object request) {
        if (request instanceof StoryWorkspaceRequests.UploadBegin value) return value.getUploadKey();
        if (request instanceof StoryWorkspaceRequests.UploadAppend value) return value.getUploadKey();
        if (request instanceof StoryWorkspaceRequests.UploadFinalize value) return value.getUploadKey();
        throw new IllegalArgumentException("Invalid screenplay upload request");
    }

    private String fragmentLogicalId(Long projectId, String uploadKey, int index) {
        return "story:" + projectId + ":g:" + SecureUtil.sha256(uploadKey + ":" + index).substring(0, 32);
    }

    private String checkpointLogicalId(Long projectId, String checkpoint) {
        return "story:" + projectId + ":c:" + checkpoint.toLowerCase(Locale.ROOT);
    }

    private boolean isDigest(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private boolean isArtifactRef(String value) {
        return value != null && value.length() <= 300 && value.matches("artifact:[A-Za-z0-9._:-]{1,280}");
    }

    private MessageDigest sha256Digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private JSONObject parseObject(String json) {
        try { return StrUtil.isBlank(json) ? null : JSON.parseObject(json); }
        catch (RuntimeException invalid) { return null; }
    }

    private void reauthorizeStoredSourceRefs(Scope scope, String contextManifestJson, JSONArray refs) {
        for (Object item : refs == null ? new JSONArray() : refs) {
            require(item instanceof JSONObject, "stored source ref");
            JSONObject ref = (JSONObject) item;
            Long id;
            try { id = Long.valueOf(ref.getString("sourceRevisionId")); }
            catch (RuntimeException invalid) { throw new IllegalStateException("Stored source ref is invalid", invalid); }
            AidRuntimeSourceRevision source = sourceRevisionMapper.selectAuthorized(scope.userId(), id);
            require(source != null && "ACTIVE".equals(source.getStatus())
                    && Objects.equals(source.getSourceKind(), ref.getString("sourceKind"))
                    && Objects.equals(source.getSourceObjectId(), ref.getString("sourceObjectId"))
                    && Objects.equals(source.getSourceRevision(), ref.getString("sourceRevision"))
                    && Objects.equals(source.getContentHash(), ref.getString("contentHash")), "stored source ref");
            contextAuthorizer.requireRead(contextManifestJson, source.getSourceKind(),
                    source.getSourceObjectId(), source.getContentHash());
        }
    }

    private JSONArray mergeSourceRefs(String existingJson, JSONArray added) {
        JSONArray existing;
        try {
            existing = StrUtil.isBlank(existingJson) ? new JSONArray() : JSON.parseArray(existingJson);
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("Stored source refs are invalid", invalid);
        }
        java.util.TreeMap<Long, JSONObject> merged = new java.util.TreeMap<>();
        for (Object item : existing) mergeSourceRef(merged, item);
        for (Object item : added == null ? new JSONArray() : added) mergeSourceRef(merged, item);
        JSONArray result = new JSONArray();
        merged.values().forEach(result::add);
        return result;
    }

    private void mergeSourceRef(java.util.Map<Long, JSONObject> merged, Object item) {
        require(item instanceof JSONObject, "source ref");
        JSONObject sourceRef = (JSONObject) item;
        Long sourceRevisionId;
        try {
            sourceRevisionId = Long.valueOf(sourceRef.getString("sourceRevisionId"));
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("Stored source ref is invalid", invalid);
        }
        require(sourceRevisionId > 0, "source ref");
        JSONObject copy = JSON.parseObject(sourceRef.toJSONString());
        JSONObject previous = merged.putIfAbsent(sourceRevisionId, copy);
        require(previous == null || Objects.equals(previous, copy), "source ref conflict");
    }

    private List<AidRuntimeArtifact> producedByStep(Long userId, Long runId, Long stepId) {
        List<AidRuntimeArtifact> values = artifactMapper.selectAuthorizedPage(userId, runId, null,
                null, null, null, 100);
        return values == null ? List.of() : values.stream()
                .filter(value -> Objects.equals(value.getProducerStepId(), stepId)).toList();
    }

    private record SourceSnapshot(AidRuntimeSourceRevision source, String content) { }
    private record Heading(String title, int start) { }
    private record ChapterRange(String title, int start, int end) { }
    private record LineRange(int start, int contentEnd, int end) { }

    private Scope requireScope(RuntimeToolInvocation invocation) {
        require(invocation != null && invocation.getRunId() != null && invocation.getUserId() != null,
                "runtime identity");
        AidRuntimeRun run = runMapper.selectById(invocation.getRunId());
        AidAssistantSession session = run == null ? null : sessionMapper.selectById(run.getSessionId());
        require(run != null && session != null && "0".equals(run.getDelFlag())
                && "0".equals(session.getDelFlag())
                && Objects.equals(run.getUserId(), invocation.getUserId())
                && Objects.equals(session.getId(), run.getSessionId())
                && Objects.equals(session.getUserId(), run.getUserId())
                && sameProjectReference(session.getProjectId(), run.getProjectId(), run.getWorkspaceId())
                && Objects.equals(session.getWorkspaceId(), run.getWorkspaceId())
                && Objects.equals(run.getPlanRevision(), invocation.getPlanRevision())
                && Objects.equals(run.getContextDigest(), SecureUtil.sha256(invocation.getContextManifestJson())),
                "runtime scope");
        Long projectId = run.getProjectId();
        boolean canvasScope = run.getWorkspaceId() != null && run.getWorkspaceId() > 0;
        if (canvasScope) {
            projectId = canvasStoryScopeAccess.authorizeProject(run.getUserId(), projectId,
                    run.getWorkspaceId(), invocation.getContextManifestJson());
        }
        AidComicProject project = projectId == null ? null : projectMapper.selectById(projectId);
        require(project != null && "0".equals(project.getDelFlag())
                && (canvasScope || Objects.equals(project.getUserId(), run.getUserId())), "runtime project scope");
        return new Scope(run.getId(), run.getUserId(), invocation.getRuntimeStepId(), run.getSessionId(),
                projectId, run.getVersionId(),
                run.getPlanRevision(), run.getContextDigest());
    }

    private Scope requireScope(RuntimeToolPreparedInvocation invocation) {
        RuntimeToolInvocation value = new RuntimeToolInvocation();
        value.setRunId(invocation.getRunId());
        value.setUserId(invocation.getUserId());
        value.setRuntimeStepId(invocation.getRuntimeStepId());
        value.setPlanRevision(invocation.getPlanRevision());
        value.setContextManifestJson(invocation.getContextManifestJson());
        return requireScope(value);
    }

    private static boolean sameProjectReference(Long sessionProject, Long runProject, Long workspaceId) {
        return Objects.equals(sessionProject, runProject)
                || workspaceId != null && workspaceId > 0
                && (sessionProject == null || sessionProject == 0L)
                && (runProject == null || runProject == 0L);
    }

    private void requirePrepared(RuntimeToolPreparedInvocation invocation, StoryWorkspaceRequests.FrozenScope frozen) {
        require(invocation != null && frozen != null
                && Objects.equals(invocation.getRunId(), frozen.getRunId())
                && Objects.equals(invocation.getUserId(), frozen.getUserId())
                && Objects.equals(invocation.getRuntimeStepId(), frozen.getRuntimeStepId())
                && Objects.equals(invocation.getPlanRevision(), frozen.getPlanRevision()), "prepared identity");
    }

    private void requireFrozen(Scope scope, StoryWorkspaceRequests.FrozenScope frozen) {
        require(frozen != null && Objects.equals(scope.runId(), frozen.getRunId())
                && Objects.equals(scope.userId(), frozen.getUserId())
                && Objects.equals(scope.runtimeStepId(), frozen.getRuntimeStepId())
                && Objects.equals(scope.sessionId(), frozen.getSessionId())
                && Objects.equals(scope.projectId(), frozen.getProjectId())
                && Objects.equals(scope.versionId(), frozen.getAgentVersionId())
                && Objects.equals(scope.planRevision(), frozen.getPlanRevision())
                && Objects.equals(scope.contextDigest(), frozen.getContextDigest()), "frozen scope");
    }

    private AidRuntimeArtifact requireFrozenWorkspace(Scope scope, StoryWorkspaceRequests.FrozenScope frozen) {
        AidRuntimeArtifact workspace = requireWorkspace(scope);
        require(Objects.equals(workspace.getId(), frozen.getWorkspaceArtifactId())
                && Objects.equals(workspace.getRevision(), frozen.getWorkspaceRevision())
                && Objects.equals(workspace.getContentHash(), frozen.getWorkspaceContentHash())
                && Objects.equals(workspaceContent(workspace, scope).getString("mode"), frozen.getMode()),
                "workspace CAS");
        return workspace;
    }

    private StoryWorkspaceRequests.FrozenScope freeze(Scope scope, AidRuntimeArtifact workspace, String mode) {
        StoryWorkspaceRequests.FrozenScope frozen = new StoryWorkspaceRequests.FrozenScope();
        frozen.setRunId(scope.runId());
        frozen.setUserId(scope.userId());
        frozen.setRuntimeStepId(scope.runtimeStepId());
        frozen.setSessionId(scope.sessionId());
        frozen.setProjectId(scope.projectId());
        frozen.setAgentVersionId(scope.versionId());
        frozen.setPlanRevision(scope.planRevision());
        frozen.setContextDigest(scope.contextDigest());
        frozen.setMode(mode);
        if (workspace != null) {
            frozen.setWorkspaceArtifactId(workspace.getId());
            frozen.setWorkspaceRevision(workspace.getRevision());
            frozen.setWorkspaceContentHash(workspace.getContentHash());
        }
        return frozen;
    }

    private AidRuntimeArtifact requireWorkspace(Scope scope) {
        AidRuntimeArtifact workspace = latest(scope.userId(), workspaceLogicalId(scope.projectId()));
        if (workspace == null) throw new WorkspaceNotInitializedException();
        requireWorkspace(workspace, scope, null);
        return workspace;
    }

    private void requireWorkspace(AidRuntimeArtifact workspace, Scope scope, String expectedMode) {
        JSONObject content = workspaceContent(workspace, scope);
        if (expectedMode != null) require(Objects.equals(content.getString("mode"), expectedMode), "workspace mode");
    }

    private JSONObject workspaceContent(AidRuntimeArtifact workspace, Scope scope) {
        require(workspace != null && "FINALIZED".equals(workspace.getStatus())
                && "CREATIVE_TEXT".equals(workspace.getArtifactType())
                && WORKSPACE_SCHEMA.equals(workspace.getSchemaVersion()), "workspace artifact");
        JSONObject content;
        try { content = JSON.parseObject(readContent(workspace)); }
        catch (RuntimeException invalid) { throw new IllegalStateException("Stored screenplay workspace is invalid", invalid); }
        require(content != null && Set.of("schemaVersion", "projectId", "mode", "title", "totalEpisodes",
                        "episodeDurationSeconds", "files", "drafts", "uploads", "checkpoints",
                        "sourcePipeline").equals(content.keySet())
                && WORKSPACE_SCHEMA.equals(content.getString("schemaVersion"))
                && Objects.equals(String.valueOf(scope.projectId()), content.getString("projectId"))
                && MODES.contains(content.getString("mode")) && content.getJSONObject("files") != null
                && content.getJSONObject("drafts") != null && content.getJSONObject("uploads") != null
                && content.getJSONArray("checkpoints") != null
                && content.getJSONObject("files").size() <= MAX_FILES
                && content.getJSONObject("drafts").size() <= MAX_DRAFTS
                && content.getJSONObject("uploads").size() <= MAX_UPLOADS
                && content.getJSONArray("checkpoints").size() <= MAX_CHECKPOINTS, "workspace content");
        return content;
    }

    private JSONObject emptyWorkspace(Long projectId, String mode) {
        JSONObject value = new JSONObject(new LinkedHashMap<>());
        value.put("schemaVersion", WORKSPACE_SCHEMA);
        value.put("projectId", String.valueOf(projectId));
        value.put("mode", mode);
        value.put("title", null);
        value.put("totalEpisodes", null);
        value.put("episodeDurationSeconds", null);
        value.put("files", new JSONObject(new LinkedHashMap<>()));
        value.put("drafts", new JSONObject(new LinkedHashMap<>()));
        value.put("uploads", new JSONObject(new LinkedHashMap<>()));
        value.put("checkpoints", new JSONArray());
        value.put("sourcePipeline", null);
        return value;
    }

    private StoryWorkspaceViews.Workspace workspaceView(Scope scope, AidRuntimeArtifact workspace) {
        JSONObject manifest = workspaceContent(workspace, scope);
        StoryWorkspaceViews.Workspace view = new StoryWorkspaceViews.Workspace();
        view.setProjectId(String.valueOf(scope.projectId()));
        view.setMode(manifest.getString("mode"));
        view.setWorkspaceArtifactId(String.valueOf(workspace.getId()));
        view.setWorkspaceArtifactRef(ref(workspace));
        view.setWorkspaceRevision(workspace.getRevision());
        view.setWorkspaceContentHash(workspace.getContentHash());
        view.setTitle(manifest.getString("title"));
        view.setTotalEpisodes(manifest.getInteger("totalEpisodes"));
        view.setEpisodeDurationSeconds(manifest.getInteger("episodeDurationSeconds"));
        JSONObject files = manifest.getJSONObject("files");
        JSONObject draftEntries = manifest.getJSONObject("drafts");
        Map<Long, AidRuntimeArtifact> entryArtifacts = authorizedWorkspaceArtifacts(scope, files, draftEntries);
        List<StoryWorkspaceViews.FileEntry> entries = new ArrayList<>();
        for (Map.Entry<String, Object> item : files.entrySet()) {
            require(item.getValue() instanceof JSONObject, "workspace file");
            entries.add(fileView(scope, item.getKey(), (JSONObject) item.getValue(), false, entryArtifacts));
        }
        entries.sort(java.util.Comparator.comparing(StoryWorkspaceViews.FileEntry::getPath));
        view.setFiles(entries);
        view.setSkeletonReady(files.containsKey("skeleton.md"));
        Integer total = view.getTotalEpisodes();
        int next = 1;
        if (total != null) while (next <= total && files.containsKey(String.format("scripts/EP%03d.md", next))) next++;
        view.setNextEpisode(total == null ? null : next);
        int scripts = 0;
        int reviewed = 0;
        int approved = 0;
        int completedReviewWorkflows = 0;
        for (Map.Entry<String, Object> item : files.entrySet()) {
            if (!EPISODE_SCRIPT.matcher(item.getKey()).matches() || !(item.getValue() instanceof JSONObject file)) {
                continue;
            }
            scripts++;
            String reviewStatus = file.getString("reviewStatus");
            String workflowStatus = reviewWorkflowStatus(file);
            if (!"UNREVIEWED".equals(workflowStatus)) reviewed++;
            if ("PASS".equals(reviewStatus)) approved++;
            if (Set.of("PASS", "REVISION_PERSISTED_AFTER_REVIEW").contains(workflowStatus)) {
                completedReviewWorkflows++;
            }
        }
        view.setScriptCount(scripts);
        view.setReviewedScriptCount(reviewed);
        view.setApprovedScriptCount(approved);
        view.setReviewWorkflowCompletedScriptCount(completedReviewWorkflows);
        view.setAllEpisodeScriptsPresent(total != null && scripts == total && next > total);
        view.setAllEpisodeScriptsApproved(total != null && approved == total && scripts == total);
        view.setAllEpisodeReviewWorkflowsComplete(total != null && completedReviewWorkflows == total
                && scripts == total);
        JSONObject pipeline = manifest.getJSONObject("sourcePipeline");
        view.setPipelineStatus(pipeline == null ? null : pipeline.getString("status"));
        view.setChaptersTotal(pipeline == null ? null : pipeline.getInteger("chaptersTotal"));
        view.setIngestDone(pipeline != null && pipeline.getBooleanValue("done"));
        JSONArray boundaries = pipeline == null ? null : pipeline.getJSONArray("episodes");
        view.setBoundaryEpisodeCount(boundaries == null ? null : boundaries.size());
        view.setDraftCount(manifest.getJSONObject("drafts").size());
        view.setStagedUploadCount(manifest.getJSONObject("uploads").size());
        List<StoryWorkspaceViews.FileEntry> drafts = new ArrayList<>();
        for (Map.Entry<String, Object> item : draftEntries.entrySet()) {
            require(item.getValue() instanceof JSONObject, "workspace draft");
            drafts.add(fileView(scope, item.getKey(), (JSONObject) item.getValue(), true, entryArtifacts));
        }
        drafts.sort(Comparator.comparing(StoryWorkspaceViews.FileEntry::getPath));
        view.setDrafts(drafts);
        List<StoryWorkspaceViews.UploadState> uploads = new ArrayList<>();
        for (Map.Entry<String, Object> item : manifest.getJSONObject("uploads").entrySet()) {
            require(item.getValue() instanceof JSONObject, "workspace upload");
            JSONObject upload = (JSONObject) item.getValue();
            JSONArray fragments = upload.getJSONArray("fragments");
            StoryWorkspaceViews.UploadState state = new StoryWorkspaceViews.UploadState();
            state.setUploadKey(item.getKey());
            state.setPath(upload.getString("path"));
            state.setDisposition(upload.getString("disposition"));
            state.setExpectedFragmentCount(upload.getInteger("expectedFragmentCount"));
            state.setReceivedFragmentCount(fragments.size());
            state.setNextFragmentIndex(fragments.size());
            state.setExpectedCharCount(upload.getInteger("expectedCharCount"));
            int receivedCharCount = fragments.stream().map(value -> (JSONObject) value)
                    .mapToInt(value -> value.getIntValue("charCount")).sum();
            state.setReceivedCharCount(receivedCharCount);
            state.setNextCharStart(receivedCharCount);
            state.setFinalContentHash(upload.getString("finalContentHash"));
            uploads.add(state);
        }
        uploads.sort(Comparator.comparing(StoryWorkspaceViews.UploadState::getUploadKey));
        view.setUploads(uploads);
        view.setSourcePipeline(pipeline == null ? null : pipelineReceipt(scope, workspace, manifest));
        return view;
    }

    private StoryWorkspaceViews.FileEntry fileView(
            Scope scope, String path, JSONObject entry, boolean draft,
            Map<Long, AidRuntimeArtifact> entryArtifacts) {
        AidRuntimeArtifact artifact = draft ? requireDraftEntryArtifact(scope, path, entry, entryArtifacts)
                : requireEntryArtifact(scope, path, entry, entryArtifacts);
        StoryWorkspaceViews.FileEntry view = new StoryWorkspaceViews.FileEntry();
        view.setPath(path);
        view.setArtifactId(entry.getString("artifactId"));
        view.setArtifactRef(entry.getString("artifactRef"));
        view.setArtifactType(entry.getString("artifactType"));
        view.setRevision(entry.getLong("revision"));
        view.setContentHash(entry.getString("contentHash"));
        view.setReviewStatus(entry.getString("reviewStatus"));
        JSONObject workflow = entry.getJSONObject("reviewWorkflow");
        view.setReviewWorkflowStatus(reviewWorkflowStatus(entry));
        if (workflow != null) {
            JSONObject lastReview = workflow.getJSONObject("lastReview");
            view.setLastReview(lastReviewView(lastReview));
            view.setFollowupArtifactRef(workflow.getString("followupArtifactRef"));
            view.setFollowupRevision(workflow.getLong("followupRevision"));
            view.setFollowupContentHash(workflow.getString("followupContentHash"));
        }
        require(Objects.equals(artifact.getId(), Long.valueOf(view.getArtifactId())), "file view artifact");
        return view;
    }

    private StoryWorkspaceRequests.ArtifactWrite canonicalWrite(StoryWorkspaceRequests.ArtifactWrite request,
                                                                 String mode) {
        require(request != null, "write request");
        StoryWorkspaceRequests.ArtifactWrite value = new StoryWorkspaceRequests.ArtifactWrite();
        value.setPath(canonicalPath(request.getPath(), mode));
        require(request.getContent() != null && !request.getContent().isEmpty()
                && request.getContent().length() <= MAX_CONTENT_CHARS
                && wellFormedUtf16(request.getContent()), "content");
        value.setContent(request.getContent());
        value.setExpectedRevision(request.getExpectedRevision());
        value.setExpectedContentHash(trim(request.getExpectedContentHash()));
        value.setDisposition(canonicalDisposition(request.getDisposition()));
        value.setSummary(trim(request.getSummary()));
        require(value.getSummary() == null || value.getSummary().length() <= 500, "summary");
        List<StoryWorkspaceRequests.SourceUse> sources = request.getSources() == null ? List.of() : request.getSources();
        require(sources.size() <= 100, "sources");
        value.setSources(sources);
        value.setSkeletonPlan(request.getSkeletonPlan());
        if ("skeleton.md".equals(value.getPath())) validatePlan(value.getSkeletonPlan(), mode);
        else require(value.getSkeletonPlan() == null, "skeletonPlan");
        return value;
    }

    private StoryWorkspaceRequests.ReviewRecord canonicalReview(StoryWorkspaceRequests.ReviewRecord request,
                                                                 String mode) {
        require(request != null, "review request");
        StoryWorkspaceRequests.ReviewRecord value = new StoryWorkspaceRequests.ReviewRecord();
        value.setPath(canonicalPath(request.getPath(), mode));
        require(EPISODE_SCRIPT.matcher(value.getPath()).matches(), "review path");
        value.setExpectedRevision(request.getExpectedRevision());
        value.setExpectedContentHash(trim(request.getExpectedContentHash()));
        require(Set.of("PASS", "CHANGES_REQUESTED", "REJECT").contains(request.getVerdict()), "verdict");
        value.setVerdict(request.getVerdict());
        require(request.getSeverityCounts() != null && request.getSeverityCounts().size() <= 20, "severityCounts");
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        request.getSeverityCounts().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(item -> {
            require(StrUtil.isNotBlank(item.getKey()) && item.getKey().length() <= 40
                    && item.getValue() != null && item.getValue() >= 0, "severityCounts");
            counts.put(item.getKey(), item.getValue());
        });
        value.setSeverityCounts(counts);
        require(StrUtil.isNotBlank(request.getReport()) && request.getReport().length() <= 20_000
                && wellFormedUtf16(request.getReport()), "report");
        value.setReport(request.getReport());
        return value;
    }

    private void validatePlan(StoryWorkspaceRequests.SkeletonPlan plan, String mode) {
        require(plan != null && StrUtil.isNotBlank(plan.getTitle()) && plan.getTitle().length() <= 200
                && plan.getTotalEpisodes() != null && plan.getTotalEpisodes() >= 1 && plan.getTotalEpisodes() <= 999
                && plan.getEpisodeDurationSeconds() != null && plan.getEpisodeDurationSeconds() >= 15
                && plan.getEpisodeDurationSeconds() <= 7200 && plan.getEpisodes() != null
                && plan.getEpisodes().size() == plan.getTotalEpisodes(), "skeletonPlan");
        for (int index = 0; index < plan.getEpisodes().size(); index++) {
            StoryWorkspaceRequests.EpisodePlan episode = plan.getEpisodes().get(index);
            require(episode != null && Objects.equals(episode.getEpisode(), index + 1)
                    && StrUtil.isNotBlank(episode.getTitle()) && episode.getTitle().length() <= 200, "episodes");
            if ("ORIGINAL".equals(mode)) {
                require(StrUtil.isBlank(episode.getMustKeep()) && StrUtil.isBlank(episode.getEnding()), "original plan");
            } else {
                require(StrUtil.isNotBlank(episode.getMustKeep()) && episode.getMustKeep().length() <= 2000
                        && (episode.getEnding() == null || episode.getEnding().length() <= 2000), "adaptation plan");
            }
        }
    }

    private void applyPlan(JSONObject manifest, StoryWorkspaceRequests.ArtifactWrite request) {
        if (!"skeleton.md".equals(request.getPath())) return;
        StoryWorkspaceRequests.SkeletonPlan plan = request.getSkeletonPlan();
        manifest.put("title", plan.getTitle().trim());
        manifest.put("totalEpisodes", plan.getTotalEpisodes());
        manifest.put("episodeDurationSeconds", plan.getEpisodeDurationSeconds());
    }

    private JSONArray sourceRefs(Scope scope, String contextManifestJson,
                                 List<StoryWorkspaceRequests.SourceUse> uses) {
        JSONArray refs = new JSONArray();
        Set<Long> seen = new java.util.HashSet<>();
        for (StoryWorkspaceRequests.SourceUse use : uses) {
            require(use != null && use.getSourceRevisionId() != null && use.getSourceRevisionId() > 0
                    && seen.add(use.getSourceRevisionId()) && StrUtil.isNotBlank(use.getPurpose())
                    && use.getPurpose().length() <= 40, "source use");
            AidRuntimeSourceRevision source = sourceRevisionMapper.selectAuthorized(scope.userId(), use.getSourceRevisionId());
            require(source != null && "ACTIVE".equals(source.getStatus()), "sourceRevisionId");
            contextAuthorizer.requireRead(contextManifestJson, source.getSourceKind(),
                    source.getSourceObjectId(), source.getContentHash());
            JSONObject ref = new JSONObject(new LinkedHashMap<>());
            ref.put("ref", "source:" + source.getId());
            ref.put("sourceRevisionId", String.valueOf(source.getId()));
            ref.put("sourceKind", source.getSourceKind());
            ref.put("sourceObjectId", source.getSourceObjectId());
            ref.put("sourceRevision", source.getSourceRevision());
            ref.put("contentHash", source.getContentHash());
            ref.put("purpose", use.getPurpose().trim());
            refs.add(ref);
        }
        return refs;
    }

    private ArtifactRef writeArtifact(RuntimeToolPreparedInvocation invocation, String logicalId, Long parentRevision,
                                      String artifactType, String schemaVersion, String content, String summary,
                                      JSONArray sourceRefs, JSONObject metadata) {
        return writeArtifact(invocation.getRunId(), invocation.getRuntimeStepId(), invocation.getDescriptor() == null
                        ? null : invocation.getDescriptor().code(), invocation.getRunLease(), invocation.getStepLease(),
                invocation.getUserId(), invocation.getPlanRevision(), logicalId, parentRevision, artifactType,
                schemaVersion, content, summary, sourceRefs, metadata);
    }

    private ArtifactRef writeArtifact(Long runId, Long stepId, String ignoredToolCode,
                                      com.aid.runtime.assistant.RuntimeLease runLease,
                                      com.aid.runtime.assistant.RuntimeLease stepLease, Long userId, Long planRevision,
                                      String logicalId, Long parentRevision, String artifactType, String schemaVersion,
                                      String content, String summary, JSONArray sourceRefs, JSONObject metadata) {
        AidRuntimeRun run = runMapper.selectById(runId);
        require(run != null && Objects.equals(run.getUserId(), userId)
                && Objects.equals(run.getPlanRevision(), planRevision), "artifact run");
        List<CreativeTextChunker.Chunk> chunks = new ArrayList<>();
        CreativeTextChunker.Stats stats;
        try (StringReader reader = new StringReader(content)) {
            stats = CreativeTextChunker.scan(reader, 12_000, chunks::add);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        ArtifactWriteRequests.CreateCandidate create = new ArtifactWriteRequests.CreateCandidate();
        create.setRunId(runId);
        create.setStepId(stepId);
        create.setLogicalArtifactId(logicalId);
        create.setParentRevision(parentRevision);
        create.setArtifactType(artifactType);
        create.setSchemaVersion(schemaVersion);
        create.setAgentVersionId(run.getVersionId());
        create.setPlanRevision(run.getPlanRevision());
        create.setSourceContextDigest(run.getContextDigest());
        create.setSourceContextRefsJson(sourceRefs.toJSONString());
        create.setContentHash(stats.contentHash());
        create.setExpectedChunkCount(stats.chunkCount());
        create.setSummary(summary);
        create.setMetadataJson(metadata.toJSONString());
        ArtifactRef artifact = artifactWriter.createCandidate(create, runLease, stepLease);
        if ("FINALIZED".equals(artifact.getStatus())) return artifact;
        for (CreativeTextChunker.Chunk chunk : chunks) {
            ArtifactWriteRequests.AppendChunk append = new ArtifactWriteRequests.AppendChunk();
            append.setRunId(runId);
            append.setStepId(stepId);
            append.setArtifactId(artifact.getArtifactId());
            append.setChunkIndex(chunk.index());
            append.setCharStart(chunk.charStart());
            append.setContent(chunk.content());
            append.setContentHash(chunk.contentHash());
            artifactWriter.appendChunk(append, runLease, stepLease);
        }
        ArtifactWriteRequests.FinalizeCandidate finish = new ArtifactWriteRequests.FinalizeCandidate();
        finish.setRunId(runId);
        finish.setStepId(stepId);
        finish.setArtifactId(artifact.getArtifactId());
        finish.setExpectedChunkCount(stats.chunkCount());
        finish.setContentHash(stats.contentHash());
        return artifactWriter.finalizeCandidate(finish, runLease, stepLease);
    }

    private JSONObject metadata(String kind, Scope scope, String path, String mode) {
        JSONObject metadata = new JSONObject(new LinkedHashMap<>());
        metadata.put("kind", kind);
        metadata.put("presentationRole", "STORY_WORKSPACE".equals(kind) ? "INTERNAL_STATE" : "DELIVERABLE");
        metadata.put("projectId", String.valueOf(scope.projectId()));
        metadata.put("mode", mode);
        if (path != null) metadata.put("path", path);
        return metadata;
    }

    private JSONObject fileMetadata(Scope scope, StoryWorkspaceRequests.ArtifactWrite request, String mode) {
        JSONObject metadata = metadata("STORY_FILE", scope, request.getPath(), mode);
        metadata.put("disposition", request.getDisposition());
        if (request.getSkeletonPlan() != null) metadata.put("skeletonPlan", request.getSkeletonPlan());
        return metadata;
    }

    private AidRuntimeArtifact latest(Long userId, String logicalId) {
        List<AidRuntimeArtifact> values = artifactMapper.selectAuthorizedPage(userId, null, logicalId,
                null, null, null, 2);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private AidRuntimeArtifact produced(Long userId, Long runId, Long stepId, String logicalId) {
        List<AidRuntimeArtifact> values = artifactMapper.selectAuthorizedPage(userId, runId, logicalId,
                null, null, null, 100);
        if (values == null) return null;
        return values.stream().filter(value -> Objects.equals(value.getProducerStepId(), stepId)).findFirst().orElse(null);
    }

    private AidRuntimeArtifact requireArtifact(Long userId, Long artifactId) {
        AidRuntimeArtifact value = artifactMapper.selectAuthorized(userId, artifactId);
        require(value != null && "FINALIZED".equals(value.getStatus()), "artifact receipt");
        return value;
    }

    private AidRuntimeArtifact requireArtifact(
            Long artifactId, Map<Long, AidRuntimeArtifact> authorizedArtifacts) {
        AidRuntimeArtifact value = authorizedArtifacts.get(artifactId);
        require(value != null && "FINALIZED".equals(value.getStatus()), "artifact receipt");
        return value;
    }

    private Map<Long, AidRuntimeArtifact> authorizedWorkspaceArtifacts(
            Scope scope, JSONObject files, JSONObject drafts) {
        require(files != null && drafts != null, "workspace entries");
        Set<Long> artifactIds = new LinkedHashSet<>();
        for (Map.Entry<String, Object> item : files.entrySet()) {
            require(item.getValue() instanceof JSONObject, "workspace file");
            collectEntryArtifactIds((JSONObject) item.getValue(), true, artifactIds);
        }
        for (Map.Entry<String, Object> item : drafts.entrySet()) {
            require(item.getValue() instanceof JSONObject, "workspace draft");
            collectEntryArtifactIds((JSONObject) item.getValue(), false, artifactIds);
        }
        return authorizedArtifacts(scope.userId(), artifactIds);
    }

    private Map<Long, AidRuntimeArtifact> authorizedEpisodeArtifacts(Scope scope, JSONObject manifest) {
        Set<Long> artifactIds = new LinkedHashSet<>();
        for (Map.Entry<String, Object> item : manifest.getJSONObject("files").entrySet()) {
            if (!EPISODE_SCRIPT.matcher(item.getKey()).matches()) continue;
            require(item.getValue() instanceof JSONObject, "workspace file");
            collectEntryArtifactIds((JSONObject) item.getValue(), true, artifactIds);
        }
        return authorizedArtifacts(scope.userId(), artifactIds);
    }

    private void collectEntryArtifactIds(JSONObject entry, boolean includeReviewArtifacts, Set<Long> artifactIds) {
        require(entry != null, "file entry");
        artifactIds.add(positiveLong(entry.getString("artifactId"), "artifactId"));
        if (!includeReviewArtifacts) return;
        JSONObject workflow = entry.getJSONObject("reviewWorkflow");
        if (workflow == null) return;
        JSONObject lastReview = workflow.getJSONObject("lastReview");
        require(lastReview != null, "last review");
        artifactIds.add(positiveLong(lastReview.getString("reviewedArtifactId"), "reviewedArtifactId"));
        artifactIds.add(positiveLong(lastReview.getString("reviewArtifactId"), "reviewArtifactId"));
    }

    private Map<Long, AidRuntimeArtifact> authorizedArtifacts(Long userId, Set<Long> artifactIds) {
        if (artifactIds.isEmpty()) return Map.of();
        List<Long> orderedIds = new ArrayList<>(artifactIds);
        Map<Long, AidRuntimeArtifact> result = new LinkedHashMap<>();
        for (int start = 0; start < orderedIds.size(); start += ARTIFACT_AUTHORIZATION_BATCH_SIZE) {
            List<Long> batch = new ArrayList<>(orderedIds.subList(start,
                    Math.min(start + ARTIFACT_AUTHORIZATION_BATCH_SIZE, orderedIds.size())));
            List<AidRuntimeArtifact> values = artifactMapper.selectAuthorizedByIds(userId, batch);
            require(values != null, "artifact receipts");
            Set<Long> expected = new HashSet<>(batch);
            for (AidRuntimeArtifact value : values) {
                require(value != null && value.getId() != null && expected.contains(value.getId())
                        && result.putIfAbsent(value.getId(), value) == null, "artifact receipts");
            }
        }
        require(result.size() == artifactIds.size() && result.keySet().containsAll(artifactIds),
                "artifact receipts");
        return result;
    }

    private AidRuntimeArtifact requireEntryArtifact(Scope scope, String path, JSONObject entry) {
        return requireEntryArtifact(scope, path, entry, null);
    }

    private AidRuntimeArtifact requireEntryArtifact(
            Scope scope, String path, JSONObject entry, Map<Long, AidRuntimeArtifact> authorizedArtifacts) {
        require(entry != null && entry.keySet().containsAll(Set.of("artifactId", "artifactRef", "artifactType",
                "revision", "contentHash", "reviewStatus")), "file entry");
        Long artifactId;
        try { artifactId = Long.valueOf(entry.getString("artifactId")); }
        catch (RuntimeException invalid) { throw new IllegalStateException("Stored screenplay file is invalid", invalid); }
        AidRuntimeArtifact artifact = authorizedArtifacts == null
                ? requireArtifact(scope.userId(), artifactId) : requireArtifact(artifactId, authorizedArtifacts);
        require(Objects.equals(entry.getString("artifactRef"), ref(artifact))
                && Objects.equals(entry.getString("artifactType"), artifact.getArtifactType())
                && Objects.equals(entry.getLong("revision"), artifact.getRevision())
                && Objects.equals(entry.getString("contentHash"), artifact.getContentHash())
                && Objects.equals(fileLogicalId(scope.projectId(), path), artifact.getLogicalArtifactId()), "file entry");
        if (entry.getJSONObject("reviewWorkflow") == null) {
            require(entry.getString("reviewStatus") == null, "file review status");
        } else {
            requireReviewWorkflow(scope, path, entry, artifact, authorizedArtifacts);
        }
        return artifact;
    }

    private JSONObject fileEntry(JSONObject manifest, String path) {
        Object value = manifest.getJSONObject("files").get(path);
        require(value == null || value instanceof JSONObject, "file entry");
        return (JSONObject) value;
    }

    private JSONObject requireFileEntry(JSONObject manifest, String path) {
        JSONObject entry = fileEntry(manifest, path);
        require(entry != null, "file missing");
        return entry;
    }

    private JSONObject fileEntry(AidRuntimeArtifact artifact, String reviewStatus) {
        JSONObject value = new JSONObject(new LinkedHashMap<>());
        value.put("artifactId", String.valueOf(artifact.getId()));
        value.put("artifactRef", ref(artifact));
        value.put("artifactType", artifact.getArtifactType());
        value.put("revision", artifact.getRevision());
        value.put("contentHash", artifact.getContentHash());
        value.put("reviewStatus", reviewStatus);
        value.put("reviewWorkflow", null);
        return value;
    }

    private JSONObject followupFileEntry(AidRuntimeArtifact artifact, JSONObject previousEntry) {
        JSONObject value = fileEntry(artifact, null);
        JSONObject previousWorkflow = previousEntry == null ? null : previousEntry.getJSONObject("reviewWorkflow");
        if (previousWorkflow == null) return value;
        JSONObject workflow = JSON.parseObject(previousWorkflow.toJSONString());
        workflow.put("followupArtifactId", String.valueOf(artifact.getId()));
        workflow.put("followupArtifactRef", ref(artifact));
        workflow.put("followupRevision", artifact.getRevision());
        workflow.put("followupContentHash", artifact.getContentHash());
        value.put("reviewWorkflow", workflow);
        return value;
    }

    private JSONObject reviewWorkflow(RuntimeToolPreparedInvocation invocation, AidRuntimeArtifact target,
                                      AidRuntimeArtifact reviewArtifact, Long reviewId,
                                      StoryWorkspaceRequests.ReviewRecord request) {
        JSONObject lastReview = new JSONObject(new LinkedHashMap<>());
        lastReview.put("reviewedArtifactId", String.valueOf(target.getId()));
        lastReview.put("reviewedArtifactRef", ref(target));
        lastReview.put("reviewedRevision", target.getRevision());
        lastReview.put("reviewedContentHash", target.getContentHash());
        lastReview.put("reviewId", String.valueOf(reviewId));
        lastReview.put("reviewerRunId", String.valueOf(invocation.getRunId()));
        lastReview.put("reviewerStepId", String.valueOf(invocation.getRuntimeStepId()));
        lastReview.put("reviewRequestHash", SecureUtil.sha256(
                JSON.toJSONString(request, JSONWriter.Feature.WriteNulls)));
        lastReview.put("verdict", request.getVerdict());
        lastReview.put("severityCounts", JSON.toJSON(request.getSeverityCounts()));
        lastReview.put("reviewArtifactId", String.valueOf(reviewArtifact.getId()));
        lastReview.put("reviewArtifactRef", ref(reviewArtifact));
        lastReview.put("reviewArtifactRevision", reviewArtifact.getRevision());
        lastReview.put("reviewArtifactContentHash", reviewArtifact.getContentHash());
        JSONObject workflow = new JSONObject(new LinkedHashMap<>());
        workflow.put("lastReview", lastReview);
        workflow.put("followupArtifactId", null);
        workflow.put("followupArtifactRef", null);
        workflow.put("followupRevision", null);
        workflow.put("followupContentHash", null);
        return workflow;
    }

    private JSONObject requireReviewWorkflow(
            Scope scope, String path, JSONObject entry, AidRuntimeArtifact current) {
        return requireReviewWorkflow(scope, path, entry, current, null);
    }

    private JSONObject requireReviewWorkflow(
            Scope scope, String path, JSONObject entry, AidRuntimeArtifact current,
            Map<Long, AidRuntimeArtifact> authorizedArtifacts) {
        JSONObject workflow = entry.getJSONObject("reviewWorkflow");
        require(workflow != null && workflow.keySet().equals(Set.of("lastReview", "followupArtifactId",
                "followupArtifactRef", "followupRevision", "followupContentHash")), "review workflow");
        JSONObject last = workflow.getJSONObject("lastReview");
        require(last != null && last.keySet().equals(Set.of("reviewedArtifactId", "reviewedArtifactRef",
                        "reviewedRevision", "reviewedContentHash", "reviewId", "reviewerRunId",
                        "reviewerStepId", "reviewRequestHash", "verdict", "severityCounts",
                        "reviewArtifactId", "reviewArtifactRef", "reviewArtifactRevision",
                        "reviewArtifactContentHash")), "last review");
        Long reviewedArtifactId = positiveLong(last.getString("reviewedArtifactId"), "reviewedArtifactId");
        Long reviewArtifactId = positiveLong(last.getString("reviewArtifactId"), "reviewArtifactId");
        positiveLong(last.getString("reviewId"), "reviewId");
        positiveLong(last.getString("reviewerRunId"), "reviewerRunId");
        positiveLong(last.getString("reviewerStepId"), "reviewerStepId");
        AidRuntimeArtifact reviewed = authorizedArtifacts == null
                ? requireArtifact(scope.userId(), reviewedArtifactId)
                : requireArtifact(reviewedArtifactId, authorizedArtifacts);
        AidRuntimeArtifact reviewArtifact = authorizedArtifacts == null
                ? requireArtifact(scope.userId(), reviewArtifactId)
                : requireArtifact(reviewArtifactId, authorizedArtifacts);
        require(Objects.equals(last.getString("reviewedArtifactRef"), ref(reviewed))
                && Objects.equals(last.getLong("reviewedRevision"), reviewed.getRevision())
                && Objects.equals(last.getString("reviewedContentHash"), reviewed.getContentHash())
                && Objects.equals(last.getString("reviewArtifactRef"), ref(reviewArtifact))
                && Objects.equals(last.getLong("reviewArtifactRevision"), reviewArtifact.getRevision())
                && Objects.equals(last.getString("reviewArtifactContentHash"), reviewArtifact.getContentHash())
                && Objects.equals(fileLogicalId(scope.projectId(), path), reviewed.getLogicalArtifactId())
                && Objects.equals(reviewLogicalId(scope.projectId(), path), reviewArtifact.getLogicalArtifactId())
                && "REVIEW_REPORT".equals(reviewArtifact.getArtifactType())
                && REVIEW_SCHEMA.equals(reviewArtifact.getSchemaVersion())
                && isDigest(last.getString("reviewRequestHash"))
                && Set.of("PASS", "CHANGES_REQUESTED", "REJECT").contains(last.getString("verdict"))
                && last.getJSONObject("severityCounts") != null, "last review receipt");
        JSONObject severity = last.getJSONObject("severityCounts");
        require(severity.size() <= 20, "review severity counts");
        for (Map.Entry<String, Object> item : severity.entrySet()) {
            require(StrUtil.isNotBlank(item.getKey()) && item.getKey().length() <= 40
                    && item.getValue() instanceof Number number && number.intValue() >= 0,
                    "review severity count");
        }
        String currentRef = ref(current);
        if (Objects.equals(currentRef, last.getString("reviewedArtifactRef"))) {
            require(workflow.get("followupArtifactId") == null && workflow.get("followupArtifactRef") == null
                    && workflow.get("followupRevision") == null && workflow.get("followupContentHash") == null
                    && Objects.equals(entry.getString("reviewStatus"), last.getString("verdict")),
                    "review current revision");
        } else {
            require(Objects.equals(workflow.getString("followupArtifactId"), String.valueOf(current.getId()))
                    && Objects.equals(workflow.getString("followupArtifactRef"), currentRef)
                    && Objects.equals(workflow.getLong("followupRevision"), current.getRevision())
                    && Objects.equals(workflow.getString("followupContentHash"), current.getContentHash())
                    && entry.getString("reviewStatus") == null, "review followup revision");
        }
        return workflow;
    }

    private String reviewWorkflowStatus(JSONObject entry) {
        JSONObject workflow = entry.getJSONObject("reviewWorkflow");
        if (workflow == null) return "UNREVIEWED";
        JSONObject last = workflow.getJSONObject("lastReview");
        require(last != null, "last review");
        if (workflow.get("followupRevision") != null) return "REVISION_PERSISTED_AFTER_REVIEW";
        return "PASS".equals(last.getString("verdict")) ? "PASS" : "REVISION_REQUIRED";
    }

    private StoryWorkspaceViews.LastReview lastReviewView(JSONObject value) {
        StoryWorkspaceViews.LastReview result = new StoryWorkspaceViews.LastReview();
        result.setReviewedArtifactId(value.getString("reviewedArtifactId"));
        result.setReviewedArtifactRef(value.getString("reviewedArtifactRef"));
        result.setReviewedRevision(value.getLong("reviewedRevision"));
        result.setReviewedContentHash(value.getString("reviewedContentHash"));
        result.setReviewId(value.getString("reviewId"));
        result.setReviewerRunId(value.getString("reviewerRunId"));
        result.setReviewerStepId(value.getString("reviewerStepId"));
        result.setReviewRequestHash(value.getString("reviewRequestHash"));
        result.setVerdict(value.getString("verdict"));
        Map<String, Integer> severityCounts = new LinkedHashMap<>();
        value.getJSONObject("severityCounts").forEach((key, count) ->
                severityCounts.put(key, ((Number) count).intValue()));
        result.setSeverityCounts(severityCounts);
        result.setReviewArtifactId(value.getString("reviewArtifactId"));
        result.setReviewArtifactRef(value.getString("reviewArtifactRef"));
        result.setReviewArtifactRevision(value.getLong("reviewArtifactRevision"));
        result.setReviewArtifactContentHash(value.getString("reviewArtifactContentHash"));
        return result;
    }

    private int reviewWorkflowCompletedScriptCount(
            Scope scope, JSONObject manifest, Map<Long, AidRuntimeArtifact> authorizedArtifacts) {
        int count = 0;
        for (Map.Entry<String, Object> item : manifest.getJSONObject("files").entrySet()) {
            if (!EPISODE_SCRIPT.matcher(item.getKey()).matches() || !(item.getValue() instanceof JSONObject file)) {
                continue;
            }
            requireEntryArtifact(scope, item.getKey(), file, authorizedArtifacts);
            if (Set.of("PASS", "REVISION_PERSISTED_AFTER_REVIEW").contains(reviewWorkflowStatus(file))) count++;
        }
        return count;
    }

    private int scriptCount(JSONObject manifest) {
        return (int) manifest.getJSONObject("files").keySet().stream()
                .filter(path -> EPISODE_SCRIPT.matcher(path).matches()).count();
    }

    private Long positiveLong(String value, String field) {
        try {
            Long parsed = Long.valueOf(value);
            require(parsed > 0, field);
            return parsed;
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("Stored screenplay " + field + " is invalid", invalid);
        }
    }

    private String readContent(AidRuntimeArtifact artifact) {
        List<AidRuntimeArtifactChunk> chunks = chunkMapper.selectAllActive(artifact.getId());
        require(chunks != null && !chunks.isEmpty(), "artifact chunks");
        StringBuilder result = new StringBuilder();
        int start = 0;
        for (int index = 0; index < chunks.size(); index++) {
            AidRuntimeArtifactChunk chunk = chunks.get(index);
            String content = chunk.getContentText();
            require(chunk.getChunkIndex() != null && chunk.getChunkIndex() == index
                    && chunk.getCharStart() != null && chunk.getCharStart() == start && content != null
                    && chunk.getCharEnd() != null && chunk.getCharEnd() == start + content.length()
                    && Objects.equals(chunk.getCharCount(), content.length())
                    && Objects.equals(chunk.getContentHash(), SecureUtil.sha256(content)), "artifact chunk integrity");
            result.append(content);
            start = chunk.getCharEnd();
        }
        require(Objects.equals(artifact.getContentHash(), SecureUtil.sha256(result.toString())),
                "artifact contentHash");
        return result.toString();
    }

    private void requireExpected(AidRuntimeArtifact current, Long expectedRevision, String expectedHash) {
        require(expectedRevision != null && expectedRevision >= 0, "expectedRevision");
        if (current == null) {
            require(expectedRevision == 0 && StrUtil.isBlank(expectedHash), "new file CAS");
            return;
        }
        require(expectedRevision > 0 && Objects.equals(current.getRevision(), expectedRevision)
                && Objects.equals(current.getContentHash(), expectedHash), "file CAS");
    }

    private String canonicalPath(String value, String mode) {
        require(value != null && value.equals(value.trim()) && !value.contains("\\") && !value.contains("//")
                && !value.startsWith("/") && !value.endsWith("/") && !value.contains(".."), "path");
        boolean root = ROOT_FILES.contains(value);
        boolean original = root || ARC.matcher(value).matches()
                || EPISODE_SKELETON.matcher(value).matches() || EPISODE_SCRIPT.matcher(value).matches();
        boolean adaptation = Set.of("skeleton.md", "characters.md", "locations.md", "props.md", "story_events.md")
                .contains(value) || EPISODE_SCRIPT.matcher(value).matches();
        require(("ORIGINAL".equals(mode) && original && !"story_events.md".equals(value))
                || ("ADAPTATION".equals(mode) && adaptation), "path for mode");
        return value;
    }

    private String artifactType(String path) {
        if ("skeleton.md".equals(path) || ARC.matcher(path).matches()
                || EPISODE_SKELETON.matcher(path).matches()) return "OUTLINE";
        if (EPISODE_SCRIPT.matcher(path).matches()) return "SCREENPLAY";
        return "CREATIVE_TEXT";
    }

    private String workspaceLogicalId(Long projectId) { return "story:" + projectId + ":workspace"; }
    private String fileLogicalId(Long projectId, String path) {
        return "story:" + projectId + ":f:" + SecureUtil.sha256(path).substring(0, 32);
    }
    private String draftLogicalId(Long projectId, String path) {
        return "story:" + projectId + ":d:" + SecureUtil.sha256(path).substring(0, 32);
    }
    private String reviewLogicalId(Long projectId, String path) {
        return "story:" + projectId + ":r:" + SecureUtil.sha256(path).substring(0, 32);
    }

    private String ref(AidRuntimeArtifact artifact) {
        return "artifact:" + artifact.getLogicalArtifactId() + ":" + artifact.getRevision();
    }

    private StoryWorkspaceViews.ReviewReceipt reviewReceipt(Scope scope, String path, AidRuntimeArtifact target,
                                                             AidRuntimeArtifact review, Long reviewId, String verdict,
                                                             AidRuntimeArtifact workspace) {
        StoryWorkspaceViews.ReviewReceipt result = new StoryWorkspaceViews.ReviewReceipt();
        result.setProjectId(String.valueOf(scope.projectId()));
        result.setPath(path);
        result.setTargetArtifactRef(ref(target));
        result.setTargetContentHash(target.getContentHash());
        result.setReviewId(String.valueOf(reviewId));
        result.setReviewArtifactId(String.valueOf(review.getId()));
        result.setReviewArtifactRef(ref(review));
        result.setReviewRevision(review.getRevision());
        result.setReviewContentHash(review.getContentHash());
        result.setVerdict(verdict);
        result.setWorkspaceArtifactRef(ref(workspace));
        result.setWorkspaceRevision(workspace.getRevision());
        return result;
    }

    private void copyReceipt(StoryWorkspaceViews.ArtifactReceipt result, Scope scope, String path,
                             AidRuntimeArtifact artifact, AidRuntimeArtifact workspace) {
        result.setProjectId(String.valueOf(scope.projectId()));
        result.setPath(path);
        result.setArtifactId(String.valueOf(artifact.getId()));
        result.setArtifactRef(ref(artifact));
        result.setRevision(artifact.getRevision());
        result.setContentHash(artifact.getContentHash());
        result.setStatus(artifact.getStatus());
        result.setDisposition("FINAL");
        result.setWorkspaceArtifactRef(ref(workspace));
        result.setWorkspaceRevision(workspace.getRevision());
    }

    private String canonicalMode(String value) {
        String mode = value == null ? null : value.trim().toUpperCase(java.util.Locale.ROOT);
        require(MODES.contains(mode), "mode");
        return mode;
    }

    private String canonicalDisposition(String value) {
        String disposition = value == null ? "FINAL" : value.trim().toUpperCase(Locale.ROOT);
        require(Set.of("FINAL", "DRAFT").contains(disposition), "disposition");
        return disposition;
    }

    private String workspaceJson(JSONObject value) {
        return value.toJSONString(JSONWriter.Feature.WriteMapNullValue);
    }

    private boolean wellFormedUtf16(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isLowSurrogate(current)) return false;
            if (!Character.isHighSurrogate(current)) continue;
            if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) return false;
        }
        return true;
    }

    private String trim(String value) { return StrUtil.isBlank(value) ? null : value.trim(); }
    private void receiptMissing() { throw new ReceiptMissingException(); }
    private void require(boolean condition, String field) {
        if (!condition) throw new WorkspaceValidationException(field);
    }

    private record Scope(Long runId, Long userId, Long runtimeStepId, Long sessionId, Long projectId, Long versionId,
                         Long planRevision, String contextDigest) { }

    /** Explicit domain validation only; infrastructure exceptions keep their original type. */
    public static final class WorkspaceValidationException extends IllegalArgumentException {
        private final String field;
        public WorkspaceValidationException(String field) {
            super("Invalid screenplay workspace " + field);
            this.field = field;
        }
        public String validationField() { return field; }
    }

    public static final class ReviewCorrectionPreconditionRejectedException extends RuntimeException {
        public ReviewCorrectionPreconditionRejectedException() { super("Review correction precondition changed before write"); }
    }

    public static final class ReceiptMissingException extends RuntimeException {
        public ReceiptMissingException() { super("Screenplay workspace receipt is unavailable"); }
    }

    public static final class WorkspaceNotInitializedException extends RuntimeException {
        public WorkspaceNotInitializedException() { super("Screenplay workspace is not initialized"); }
    }
}
