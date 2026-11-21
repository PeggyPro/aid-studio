package com.aid.assistant.creative.series;

import com.aid.aid.domain.AidComicEpisode;
import com.aid.aid.domain.AidComicProject;
import com.aid.aid.domain.AidComicScript;
import com.aid.aid.mapper.AidComicEpisodeMapper;
import com.aid.aid.mapper.AidComicScriptMapper;
import com.aid.assistant.artifact.domain.AidRuntimeArtifactAdoption;
import com.aid.assistant.artifact.mapper.AidRuntimeArtifactAdoptionMapper;
import com.aid.assistant.creative.series.SeriesArtifactSnapshotService.Snapshot;
import com.aid.assistant.creative.series.SeriesScriptSplitPolicy.Segment;
import com.aid.assistant.creative.series.SeriesScriptSplitPolicy.SplitPlan;
import com.aid.assistant.creative.series.SeriesSplitCapabilitySnapshotCodec.CapabilitySnapshot;
import com.aid.assistant.creative.series.dto.SeriesAdoptionRequests;
import com.aid.assistant.creative.series.mapper.AssistantSeriesAdoptionMapper;
import com.aid.assistant.creative.series.mapper.SeriesCurrentScriptProjection;
import com.aid.assistant.creative.series.vo.SeriesAdoptionViews;
import com.aid.assistant.domain.AidAssistantSession;
import com.aid.assistant.error.AssistantServiceException;
import com.aid.runtime.assistant.correction.AssistantDecisionCorrectionCommandNamespace;
import com.aid.enums.ScriptStatusEnum;
import com.aid.project.service.IProjectContentGuardService;
import com.aid.runtime.domain.AidRuntimeCommand;
import com.aid.runtime.mapper.AidRuntimeCommandMapper;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Dedicated project-scope atomic series adoption. It is isolated from the legacy single SCRIPT target. */
@Service
public class CreativeSeriesAdoptionService {
    public static final String MODE_UPDATE = "UPDATE_EXISTING";
    public static final String MODE_APPEND = "APPEND_NEW";
    private static final int MAX_UPDATE_ITEMS = 100;
    private static final String COMMAND_UPDATE = "ARTIFACT_SERIES_ADOPT_UPDATE";
    private static final String COMMAND_APPEND = "ARTIFACT_SERIES_ADOPT_APPEND";
    private static final String ADOPTION_UPDATE = "SCRIPT_SERIES_UPDATE_ITEM";
    private static final String ADOPTION_APPEND = "SCRIPT_SERIES_APPEND_ITEM";

    @Resource private AssistantSeriesAdoptionMapper series;
    @Resource private AidRuntimeCommandMapper commands;
    @Resource private AidRuntimeArtifactAdoptionMapper adoptions;
    @Resource private AidComicEpisodeMapper episodes;
    @Resource private AidComicScriptMapper scripts;
    @Resource private SeriesArtifactSnapshotService artifactSnapshots;
    @Resource private SeriesScriptSplitPolicy splitPolicy;
    @Resource private SeriesSplitCapabilitySnapshotCodec splitSnapshots;
    @Resource private IProjectContentGuardService projectGuard;
    @Resource private TransactionTemplate transactions;

    public SeriesAdoptionViews.Capabilities capabilities(Long userId,
                                                         SeriesAdoptionRequests.Capabilities request) {
        requireUser(userId);
        String mode = requireMode(request == null ? null : request.getMode());
        Long sessionId = id(request == null ? null : request.getSessionId(), "sessionId");
        AidAssistantSession session = series.selectSession(sessionId);
        CreativeSeriesCatalogService.requireProjectScope(session, userId);
        AidComicProject project = series.selectProject(session.getProjectId());
        CreativeSeriesCatalogService.requireProject(project, userId);
        requireEditable(project);
        SeriesProjectState.Snapshot state = SeriesProjectState.snapshot(project,
                series.selectEpisodes(project.getId()), series.selectCurrentScripts(project.getId()));

        CapabilityBuild build = MODE_UPDATE.equals(mode)
                ? updateCapabilities(userId, session, project, state, request)
                : appendCapabilities(userId, session, project, state, request);
        String digest = capabilityDigest(userId, session, project, state, mode, build.items(), build.split());
        List<SeriesAdoptionViews.CapabilityItem> views = build.items().stream().map(this::view).toList();
        return new SeriesAdoptionViews.Capabilities("assistant-series-adoption-capabilities/v1", mode,
                id(project.getId()), state.episodeSetDigest(), SeriesScriptSplitPolicy.CONTRACT_VERSION,
                digest, build.splitCapabilitySnapshot(), true, null, views);
    }

    public SeriesAdoptionViews.Receipt commit(Long userId, SeriesAdoptionRequests.Commit request) {
        requireUser(userId);
        String mode = requireMode(request == null ? null : request.getMode());
        Long sessionId = id(request == null ? null : request.getSessionId(), "sessionId");
        requireSha(request.getCapabilityDigest(), "capabilityDigest");
        requireSha(request.getExpectedProjectEpisodeSetDigest(), "expectedProjectEpisodeSetDigest");
        if (request.getClientRequestId() == null
                || !request.getClientRequestId().matches("[A-Za-z0-9._:-]{1,64}")) {
            fail(HttpStatus.BAD_REQUEST, "SERIES_CLIENT_REQUEST_INVALID", "Client request id is invalid", null);
        }
        String payloadDigest = SeriesAdoptionDigest.sha256Canonical(request);
        try {
            SeriesAdoptionViews.Receipt value = transactions.execute(status ->
                    commitInTransaction(userId, sessionId, mode, request, payloadDigest));
            if (value == null) fail(HttpStatus.INTERNAL_SERVER_ERROR, "SERIES_ADOPTION_EMPTY_RESULT",
                    "Series adoption returned no result", null);
            return value;
        } catch (DuplicateKeyException collision) {
            AidRuntimeCommand winner = findCommand(userId, sessionId, request.getClientRequestId(), false);
            if (winner == null) fail(HttpStatus.CONFLICT, "SERIES_COMMAND_COLLISION_UNKNOWN",
                    "Series adoption command ownership is not yet observable", null);
            return resolveAuthorizedCommand(winner, userId, sessionId, mode, request.getClientRequestId(), payloadDigest);
        }
    }

    private PreparedCommit prepareImmutableInputs(Long userId, Long sessionId, String mode,
                                                   SeriesAdoptionRequests.Commit request) {
        if (MODE_UPDATE.equals(mode)) {
            List<SeriesAdoptionRequests.CommitItem> requested = request.getItems();
            if (requested == null || requested.isEmpty() || requested.size() > MAX_UPDATE_ITEMS
                    || request.getArtifactId() != null || request.getArtifactContentHash() != null
                    || request.getSplitCapabilitySnapshot() != null) {
                fail(HttpStatus.BAD_REQUEST, "SERIES_UPDATE_ITEMS_INVALID",
                        "UPDATE_EXISTING requires 1 to 100 exact items", null);
            }
            Set<Long> episodeIds = new HashSet<>();
            Set<Long> artifactIds = new HashSet<>();
            List<PreparedItem> items = new ArrayList<>(requested.size());
            for (int index = 0; index < requested.size(); index++) {
                SeriesAdoptionRequests.CommitItem item = requested.get(index);
                Long artifactId = id(item == null ? null : item.getArtifactId(), "artifactId");
                Long episodeId = id(item == null ? null : item.getEpisodeId(), "episodeId");
                if (!artifactIds.add(artifactId)) fail(HttpStatus.BAD_REQUEST, "SERIES_UPDATE_ARTIFACT_DUPLICATE",
                        "UPDATE_EXISTING contains a duplicate artifact", id(artifactId));
                if (!episodeIds.add(episodeId)) fail(HttpStatus.BAD_REQUEST, "SERIES_UPDATE_EPISODE_DUPLICATE",
                        "UPDATE_EXISTING contains a duplicate episode", id(episodeId));
                requireSha(item.getArtifactContentHash(), "artifactContentHash");
                if (item.getExpectedTargetRevision() == null || item.getExpectedTargetRevision().isBlank()
                        || item.getExpectedTargetRevision().length() > 128) {
                    fail(HttpStatus.BAD_REQUEST, "SERIES_TARGET_REVISION_INVALID",
                            "Expected target revision is invalid", index);
                }
                Snapshot artifact = artifactSnapshots.load(userId, sessionId, artifactId,
                        item.getArtifactContentHash());
                int effective = splitPolicy.validateEpisodeContent(artifact.content());
                items.add(new PreparedItem(index, artifact, episodeId, item.getExpectedTargetRevision(),
                        new Segment(index + 1, null, null, artifact.content(), artifact.contentHash(),
                                0, artifact.content().length(), effective)));
            }
            return new PreparedCommit(List.copyOf(items), null, null);
        }
        if (request.getItems() != null && !request.getItems().isEmpty()) {
            fail(HttpStatus.BAD_REQUEST, "SERIES_APPEND_ITEMS_FORBIDDEN",
                    "APPEND_NEW accepts one full split artifact", null);
        }
        Long artifactId = id(request.getArtifactId(), "artifactId");
        requireSha(request.getArtifactContentHash(), "artifactContentHash");
        CapabilitySnapshot candidate = splitSnapshots.decode(request.getSplitCapabilitySnapshot());
        if (!Objects.equals(candidate.artifactId(), id(artifactId))
                || !Objects.equals(candidate.artifactContentHash(), request.getArtifactContentHash())) {
            fail(HttpStatus.CONFLICT, "SERIES_SPLIT_SNAPSHOT_ARTIFACT_CONFLICT",
                    "Series split capability snapshot does not match the requested artifact", null);
        }
        Snapshot artifact = artifactSnapshots.load(userId, sessionId, artifactId,
                request.getArtifactContentHash());
        SplitPlan split = splitPolicy.split(artifact.content(), candidate.episodeKeyword());
        splitSnapshots.assertRecomputed(candidate, artifact, split);
        List<PreparedItem> items = new ArrayList<>(split.segments().size());
        for (int index = 0; index < split.segments().size(); index++) {
            items.add(new PreparedItem(index, artifact, null, null, split.segments().get(index)));
        }
        return new PreparedCommit(List.copyOf(items), split, candidate);
    }

    private SeriesAdoptionViews.Receipt commitInTransaction(Long userId, Long sessionId, String mode,
                                                             SeriesAdoptionRequests.Commit request,
                                                             String payloadDigest) {
        AidAssistantSession session = series.lockSession(sessionId);
        CreativeSeriesCatalogService.requireProjectScope(session, userId);

        AidRuntimeCommand command = findCommand(userId, sessionId, request.getClientRequestId(), true);
        if (command != null) {
            return resolveAuthorizedCommand(command, userId, sessionId, mode,
                    request.getClientRequestId(), payloadDigest);
        }
        // Existing commands replay without re-reading an artifact that may later be archived. Only
        // a genuinely new command resolves and verifies its immutable artifact inputs.
        PreparedCommit prepared = prepareImmutableInputs(userId, sessionId, mode, request);
        command = new AidRuntimeCommand();
        command.setActorId(userId);
        command.setSessionId(sessionId);
        command.setClientRequestId(request.getClientRequestId());
        command.setPayloadDigest(payloadDigest);
        command.setCommandType(commandType(mode));
        command.setStatus("PROCESSING");
        command.setCreateBy(id(userId));
        command.setCreateTime(new Date());
        commands.insert(command);

        AidComicProject project = series.lockProject(session.getProjectId());
        CreativeSeriesCatalogService.requireProject(project, userId);
        requireEditable(project);
        List<AidComicEpisode> lockedEpisodes = series.lockEpisodes(project.getId());
        List<SeriesCurrentScriptProjection> lockedScripts = series.lockCurrentScripts(project.getId());
        SeriesProjectState.Snapshot state = SeriesProjectState.snapshot(project, lockedEpisodes, lockedScripts);
        if (!Objects.equals(state.episodeSetDigest(), request.getExpectedProjectEpisodeSetDigest())) {
            fail(HttpStatus.CONFLICT, "SERIES_EPISODE_SET_CONFLICT",
                    "Series episode set changed before commit", state.episodeSetDigest());
        }
        Set<Long> verifiedArtifactIds = new HashSet<>();
        for (PreparedItem item : prepared.items()) {
            Long artifactId = item.artifact().artifact().getId();
            if (verifiedArtifactIds.add(artifactId)) {
                artifactSnapshots.verifyIdentity(userId, sessionId, item.artifact());
            }
        }

        CapabilityBuild trusted = MODE_UPDATE.equals(mode)
                ? updateCommitCapabilities(state, prepared)
                : appendCommitCapabilities(state, prepared);
        String trustedDigest = capabilityDigest(userId, session, project, state, mode,
                trusted.items(), trusted.split());
        if (!Objects.equals(trustedDigest, request.getCapabilityDigest())) {
            fail(HttpStatus.CONFLICT, "SERIES_CAPABILITY_CONFLICT",
                    "Series adoption capability changed before commit", trustedDigest);
        }
        validateCommitBaselines(mode, request, trusted.items());

        Date now = new Date();
        List<SeriesAdoptionViews.ReceiptItem> receipts = MODE_UPDATE.equals(mode)
                ? writeUpdates(userId, sessionId, request, state, trusted.items(), now)
                : writeAppends(userId, sessionId, request, project, state, trusted.items(), now);
        SeriesProjectState.Snapshot after = SeriesProjectState.snapshot(project,
                series.lockEpisodes(project.getId()), series.lockCurrentScripts(project.getId()));
        List<String> adoptionIds = receipts.stream().map(SeriesAdoptionViews.ReceiptItem::adoptionId).toList();
        Map<String, Object> compact = new LinkedHashMap<>();
        compact.put("schemaVersion", "assistant-series-adoption-command/v1");
        compact.put("sessionId", id(sessionId));
        compact.put("mode", mode);
        compact.put("projectId", id(project.getId()));
        compact.put("splitterContractVersion", SeriesScriptSplitPolicy.CONTRACT_VERSION);
        compact.put("capabilityDigest", request.getCapabilityDigest());
        compact.put("beforeEpisodeSetDigest", state.episodeSetDigest());
        compact.put("afterEpisodeSetDigest", after.episodeSetDigest());
        compact.put("adoptionIds", adoptionIds);
        command.setStatus("COMPLETED");
        command.setResultJson(SeriesAdoptionDigest.canonicalJson(compact));
        command.setUpdateBy(id(userId));
        command.setUpdateTime(now);
        commands.updateById(command);
        return new SeriesAdoptionViews.Receipt("assistant-series-adoption-receipt/v1", id(command.getId()),
                id(sessionId), request.getClientRequestId(), mode, id(project.getId()), state.episodeSetDigest(),
                after.episodeSetDigest(), List.copyOf(receipts));
    }

    private CapabilityBuild updateCapabilities(Long userId, AidAssistantSession session, AidComicProject project,
                                                SeriesProjectState.Snapshot state,
                                                SeriesAdoptionRequests.Capabilities request) {
        if (request.getItems() == null || request.getItems().isEmpty()
                || request.getItems().size() > MAX_UPDATE_ITEMS || request.getArtifactId() != null
                || request.getEpisodeKeyword() != null) {
            fail(HttpStatus.BAD_REQUEST, "SERIES_UPDATE_ITEMS_INVALID",
                    "UPDATE_EXISTING requires 1 to 100 exact artifact and episode items", null);
        }
        Set<Long> targetEpisodes = new HashSet<>();
        Set<Long> sourceArtifacts = new HashSet<>();
        List<ModelItem> result = new ArrayList<>(request.getItems().size());
        for (int index = 0; index < request.getItems().size(); index++) {
            SeriesAdoptionRequests.CapabilityItem requested = request.getItems().get(index);
            Long artifactId = id(requested == null ? null : requested.getArtifactId(), "artifactId");
            Long episodeId = id(requested == null ? null : requested.getEpisodeId(), "episodeId");
            if (!sourceArtifacts.add(artifactId)) fail(HttpStatus.BAD_REQUEST,
                    "SERIES_UPDATE_ARTIFACT_DUPLICATE",
                    "UPDATE_EXISTING contains a duplicate artifact", id(artifactId));
            if (!targetEpisodes.add(episodeId)) fail(HttpStatus.BAD_REQUEST, "SERIES_UPDATE_EPISODE_DUPLICATE",
                    "UPDATE_EXISTING contains a duplicate episode", id(episodeId));
            AidComicEpisode episode = state.episode(episodeId);
            if (episode == null) fail(HttpStatus.NOT_FOUND, "SERIES_TARGET_EPISODE_NOT_FOUND",
                    "Series target episode is not available", id(episodeId));
            Snapshot artifact = artifactSnapshots.load(userId, session.getId(), artifactId, null);
            int effective = splitPolicy.validateEpisodeContent(artifact.content());
            Segment segment = new Segment(index + 1, episode.getComicTitle(), episode.getComicDesc(),
                    artifact.content(), artifact.contentHash(), 0, artifact.content().length(), effective);
            result.add(model(index, artifact, segment, episode, state.currentScripts().get(episodeId),
                    SeriesProjectState.revision(state.currentScripts().get(episodeId))));
        }
        return new CapabilityBuild(List.copyOf(result), null, null);
    }

    private CapabilityBuild appendCapabilities(Long userId, AidAssistantSession session, AidComicProject project,
                                                SeriesProjectState.Snapshot state,
                                                SeriesAdoptionRequests.Capabilities request) {
        if (request.getItems() != null && !request.getItems().isEmpty()) {
            fail(HttpStatus.BAD_REQUEST, "SERIES_APPEND_ITEMS_FORBIDDEN",
                    "APPEND_NEW accepts one full split artifact", null);
        }
        Long artifactId = id(request.getArtifactId(), "artifactId");
        Snapshot artifact = artifactSnapshots.load(userId, session.getId(), artifactId, null);
        SplitPlan split = splitPolicy.split(artifact.content(), request.getEpisodeKeyword());
        long firstEpisodeNo = Math.addExact(state.maxEpisodeNo(), 1L);
        List<ModelItem> result = new ArrayList<>(split.segments().size());
        for (int index = 0; index < split.segments().size(); index++) {
            long episodeNo = Math.addExact(firstEpisodeNo, index);
            Segment segment = split.segments().get(index);
            result.add(new ModelItem(index, artifact, segment, null, episodeNo,
                    "series-set:" + state.episodeSetDigest() + ":episode-no:" + episodeNo));
        }
        String snapshot = splitSnapshots.encode(artifact, split);
        return new CapabilityBuild(List.copyOf(result), split, snapshot);
    }

    private CapabilityBuild updateCommitCapabilities(SeriesProjectState.Snapshot state, PreparedCommit prepared) {
        List<ModelItem> values = new ArrayList<>(prepared.items().size());
        for (PreparedItem item : prepared.items()) {
            AidComicEpisode episode = state.episode(item.episodeId());
            if (episode == null) fail(HttpStatus.NOT_FOUND, "SERIES_TARGET_EPISODE_NOT_FOUND",
                    "Series target episode is not available", id(item.episodeId()));
            SeriesCurrentScriptProjection current = state.currentScripts().get(item.episodeId());
            values.add(model(item.index(), item.artifact(), item.segment(), episode, current,
                    SeriesProjectState.revision(current)));
        }
        return new CapabilityBuild(List.copyOf(values), null, null);
    }

    private CapabilityBuild appendCommitCapabilities(SeriesProjectState.Snapshot state, PreparedCommit prepared) {
        long firstEpisodeNo = Math.addExact(state.maxEpisodeNo(), 1L);
        List<ModelItem> values = new ArrayList<>(prepared.items().size());
        for (PreparedItem item : prepared.items()) {
            long episodeNo = Math.addExact(firstEpisodeNo, item.index());
            values.add(new ModelItem(item.index(), item.artifact(), item.segment(), null, episodeNo,
                    "series-set:" + state.episodeSetDigest() + ":episode-no:" + episodeNo));
        }
        return new CapabilityBuild(List.copyOf(values), prepared.split(), null);
    }

    private void validateCommitBaselines(String mode, SeriesAdoptionRequests.Commit request, List<ModelItem> models) {
        if (MODE_APPEND.equals(mode)) {
            if (models.size() > SeriesScriptSplitPolicy.MAX_EPISODES
                    || !Objects.equals(request.getArtifactId(), id(models.get(0).artifact().artifact().getId()))
                    || !Objects.equals(request.getArtifactContentHash(), models.get(0).artifact().contentHash())) {
                fail(HttpStatus.CONFLICT, "SERIES_APPEND_BASELINE_CONFLICT",
                        "APPEND_NEW artifact baseline does not match", null);
            }
            return;
        }
        if (request.getItems().size() != models.size()) fail(HttpStatus.CONFLICT,
                "SERIES_UPDATE_BASELINE_CONFLICT", "UPDATE_EXISTING item count changed", null);
        for (int index = 0; index < models.size(); index++) {
            SeriesAdoptionRequests.CommitItem supplied = request.getItems().get(index);
            ModelItem trusted = models.get(index);
            if (!Objects.equals(supplied.getArtifactId(), id(trusted.artifact().artifact().getId()))
                    || !Objects.equals(supplied.getArtifactContentHash(), trusted.artifact().contentHash())
                    || !Objects.equals(supplied.getEpisodeId(), id(trusted.episode().getId()))
                    || !Objects.equals(supplied.getExpectedTargetRevision(), trusted.currentTargetRevision())) {
                fail(HttpStatus.CONFLICT, "SERIES_UPDATE_BASELINE_CONFLICT",
                        "UPDATE_EXISTING item baseline changed", index);
            }
        }
    }

    private List<SeriesAdoptionViews.ReceiptItem> writeUpdates(Long userId, Long sessionId,
                                                               SeriesAdoptionRequests.Commit request,
                                                               SeriesProjectState.Snapshot state,
                                                               List<ModelItem> models, Date now) {
        List<SeriesAdoptionViews.ReceiptItem> result = new ArrayList<>(models.size());
        for (ModelItem item : models) {
            SeriesCurrentScriptProjection current = state.currentScripts().get(item.episode().getId());
            if (current != null) {
                int changed = scripts.update(null, Wrappers.<AidComicScript>lambdaUpdate()
                        .eq(AidComicScript::getId, current.getId())
                        .eq(AidComicScript::getStatus, ScriptStatusEnum.IN_USE.getValue())
                        .eq(AidComicScript::getDelFlag, "0")
                        .set(AidComicScript::getStatus, ScriptStatusEnum.HISTORY.getValue())
                        .set(AidComicScript::getUpdateBy, id(userId))
                        .set(AidComicScript::getUpdateTime, now));
                if (changed != 1) fail(HttpStatus.CONFLICT, "SERIES_SCRIPT_WRITE_CONFLICT",
                        "Series target script changed before write", id(item.episode().getId()));
            }
            AidComicScript created = newScript(userId, item.episode().getProjectId(), item.episode().getId(),
                    item.segment().content(), current == null ? 1 : current.getComicVersion() + 1,
                    current == null ? 0 : current.getIsExtracted(), now);
            scripts.insert(created);
            String revision = SeriesProjectState.revision(created);
            SeriesAdoptionViews.ReceiptItem receipt = receiptItem(item, null, created, revision);
            AidRuntimeArtifactAdoption adoption = insertAdoption(userId, sessionId, request.getClientRequestId(),
                    request.getCapabilityDigest(), ADOPTION_UPDATE, receipt.targetObjectId(), item,
                    revision, receipt, now);
            result.add(withAdoption(receipt, adoption.getId()));
        }
        return result;
    }

    private List<SeriesAdoptionViews.ReceiptItem> writeAppends(Long userId, Long sessionId,
                                                               SeriesAdoptionRequests.Commit request,
                                                               AidComicProject project,
                                                               SeriesProjectState.Snapshot state,
                                                               List<ModelItem> models, Date now) {
        List<SeriesAdoptionViews.ReceiptItem> result = new ArrayList<>(models.size());
        for (ModelItem item : models) {
            AidComicEpisode episode = new AidComicEpisode();
            episode.setProjectId(project.getId());
            episode.setUserId(userId);
            episode.setEpisodeNo(item.episodeNo());
            episode.setComicTitle(item.segment().title());
            episode.setComicDesc(item.segment().description());
            episode.setGenMode(project.getDefaultGenMode());
            episode.setCreationMode(project.getDefaultCreationMode());
            episode.setStatus(0);
            episode.setDelFlag("0");
            episode.setCreateBy(id(userId));
            episode.setCreateTime(now);
            episodes.insert(episode);
            AidComicScript script = newScript(userId, project.getId(), episode.getId(),
                    item.segment().content(), 1, 0, now);
            scripts.insert(script);
            String revision = SeriesProjectState.revision(script);
            ModelItem resolved = new ModelItem(item.index(), item.artifact(), item.segment(), episode,
                    item.episodeNo(), item.currentTargetRevision());
            SeriesAdoptionViews.ReceiptItem receipt = receiptItem(resolved, episode, script, revision);
            AidRuntimeArtifactAdoption adoption = insertAdoption(userId, sessionId, request.getClientRequestId(),
                    request.getCapabilityDigest(), ADOPTION_APPEND, receipt.targetObjectId(), resolved,
                    revision, receipt, now);
            result.add(withAdoption(receipt, adoption.getId()));
        }
        return result;
    }

    private AidRuntimeArtifactAdoption insertAdoption(Long userId, Long sessionId, String clientRequestId,
                                                       String batchCapabilityDigest,
                                                       String targetKind, String targetObjectId, ModelItem item,
                                                       String resultRevision,
                                                       SeriesAdoptionViews.ReceiptItem receipt, Date now) {
        AidRuntimeArtifactAdoption value = new AidRuntimeArtifactAdoption();
        value.setUserId(userId);
        value.setSessionId(sessionId);
        value.setArtifactId(item.artifact().artifact().getId());
        value.setTargetKind(targetKind);
        value.setTargetObjectId(targetObjectId);
        value.setExpectedTargetRevision(item.currentTargetRevision());
        value.setResultTargetRevision(resultRevision);
        value.setClientRequestId(clientRequestId);
        String projectId = id(item.episode().getProjectId());
        String itemDigest = adoptionRequestDigest(batchCapabilityDigest, projectId, targetKind, receipt);
        value.setRequestDigest(itemDigest);
        value.setStatus("SUCCEEDED");
        JSONObject durableReceipt = JSON.parseObject(JSON.toJSONString(receipt));
        durableReceipt.put("schemaVersion", "assistant-series-adoption-item/v1");
        durableReceipt.put("projectId", projectId);
        durableReceipt.put("splitterContractVersion", SeriesScriptSplitPolicy.CONTRACT_VERSION);
        durableReceipt.put("batchCapabilityDigest", batchCapabilityDigest);
        durableReceipt.put("itemRequestDigest", itemDigest);
        value.setReceiptJson(SeriesAdoptionDigest.canonicalJson(durableReceipt));
        value.setDelFlag("0");
        value.setCreateBy(id(userId));
        value.setCreateTime(now);
        adoptions.insert(value);
        return value;
    }

    private String adoptionRequestDigest(String batchCapabilityDigest, String projectId, String targetKind,
                                         SeriesAdoptionViews.ReceiptItem receipt) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("schemaVersion", "assistant-series-adoption-item-digest/v1");
        identity.put("splitterContractVersion", SeriesScriptSplitPolicy.CONTRACT_VERSION);
        identity.put("batchCapabilityDigest", batchCapabilityDigest);
        identity.put("projectId", projectId);
        identity.put("itemIndex", receipt.itemIndex());
        identity.put("artifactId", receipt.artifactId());
        identity.put("artifactRef", receipt.artifactRef());
        identity.put("artifactContentHash", receipt.artifactContentHash());
        identity.put("segmentContentHash", receipt.segmentContentHash());
        identity.put("targetKind", targetKind);
        identity.put("targetObjectId", receipt.targetObjectId());
        identity.put("episodeId", receipt.episodeId());
        identity.put("episodeNo", receipt.episodeNo());
        identity.put("previousTargetRevision", receipt.previousTargetRevision());
        identity.put("targetRevision", receipt.targetRevision());
        identity.put("createdEpisodeId", receipt.createdEpisodeId());
        identity.put("createdScriptId", receipt.createdScriptId());
        identity.put("sourceCharStart", receipt.sourceCharStart());
        identity.put("sourceCharEnd", receipt.sourceCharEnd());
        return SeriesAdoptionDigest.sha256Canonical(identity);
    }

    private AidComicScript newScript(Long userId, Long projectId, Long episodeId, String content,
                                      int version, int extracted, Date now) {
        AidComicScript value = new AidComicScript();
        value.setProjectId(projectId);
        value.setEpisodeId(episodeId);
        value.setUserId(userId);
        value.setOriginalText(content);
        value.setComicVersion(version);
        value.setStatus(ScriptStatusEnum.IN_USE.getValue());
        value.setIsExtracted(extracted);
        value.setDelFlag("0");
        value.setCreateBy(id(userId));
        value.setCreateTime(now);
        return value;
    }

    private String capabilityDigest(Long userId, AidAssistantSession session, AidComicProject project,
                                    SeriesProjectState.Snapshot state, String mode, List<ModelItem> items,
                                    SplitPlan split) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schemaVersion", "assistant-series-adoption-capability-digest/v1");
        envelope.put("action", mode);
        envelope.put("userId", id(userId));
        envelope.put("sessionId", id(session.getId()));
        envelope.put("projectId", id(project.getId()));
        envelope.put("projectType", project.getProjectType());
        envelope.put("projectEpisodeSetDigest", state.episodeSetDigest());
        envelope.put("splitterContractVersion", SeriesScriptSplitPolicy.CONTRACT_VERSION);
        envelope.put("episodeKeyword", split == null ? null : split.episodeKeyword());
        List<Map<String, Object>> values = new ArrayList<>(items.size());
        for (ModelItem item : items) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("itemIndex", item.index());
            value.put("artifactId", id(item.artifact().artifact().getId()));
            value.put("artifactRef", item.artifact().artifactRef());
            value.put("artifactContentHash", item.artifact().contentHash());
            value.put("segmentSequenceNo", item.segment().sequenceNo());
            value.put("segmentContentHash", item.segment().contentHash());
            value.put("sourceCharStart", item.segment().sourceCharStart());
            value.put("sourceCharEnd", item.segment().sourceCharEnd());
            value.put("effectiveCharCount", item.segment().effectiveCharCount());
            value.put("proposedTitle", item.segment().title());
            value.put("proposedDescription", item.segment().description());
            value.put("episodeId", item.episode() == null ? null : id(item.episode().getId()));
            value.put("episodeNo", item.episodeNo());
            value.put("currentTargetRevision", item.currentTargetRevision());
            values.add(value);
        }
        envelope.put("items", values);
        return SeriesAdoptionDigest.sha256Canonical(envelope);
    }

    private SeriesAdoptionViews.Receipt replay(AidRuntimeCommand command, Long userId, Long sessionId,
                                               String clientRequestId) {
        JSONObject compact = JSON.parseObject(command.getResultJson());
        String replayMode = modeFromCommandType(command.getCommandType());
        if (compact == null || !"assistant-series-adoption-command/v1".equals(compact.getString("schemaVersion"))
                || !Objects.equals(id(sessionId), compact.getString("sessionId"))
                || !Objects.equals(replayMode, compact.getString("mode"))
                || !isId(compact.getString("projectId"))
                || !SeriesScriptSplitPolicy.CONTRACT_VERSION.equals(
                compact.getString("splitterContractVersion"))
                || !isSha(compact.getString("capabilityDigest"))
                || !isSha(compact.getString("beforeEpisodeSetDigest"))
                || !isSha(compact.getString("afterEpisodeSetDigest"))) {
            fail(HttpStatus.INTERNAL_SERVER_ERROR, "SERIES_COMMAND_RECEIPT_INVALID",
                    "Series command receipt is invalid", id(command.getId()));
        }
        JSONArray ids = compact.getJSONArray("adoptionIds");
        if (ids == null || ids.isEmpty() || ids.size() > SeriesScriptSplitPolicy.MAX_EPISODES) {
            fail(HttpStatus.INTERNAL_SERVER_ERROR, "SERIES_COMMAND_RECEIPT_INVALID",
                    "Series command receipt has invalid adoption ids", id(command.getId()));
        }
        List<Long> orderedIds = ids.stream().map(value -> receiptId(String.valueOf(value), command.getId())).toList();
        if (new HashSet<>(orderedIds).size() != orderedIds.size()) {
            fail(HttpStatus.INTERNAL_SERVER_ERROR, "SERIES_COMMAND_RECEIPT_INVALID",
                    "Series command receipt contains duplicate adoption ids", id(command.getId()));
        }
        Map<Long, AidRuntimeArtifactAdoption> rows = new HashMap<>();
        for (AidRuntimeArtifactAdoption value : adoptions.selectBatchIds(orderedIds)) rows.put(value.getId(), value);
        List<SeriesAdoptionViews.ReceiptItem> items = new ArrayList<>(orderedIds.size());
        String expectedTargetKind = MODE_UPDATE.equals(replayMode) ? ADOPTION_UPDATE : ADOPTION_APPEND;
        for (int index = 0; index < orderedIds.size(); index++) {
            Long adoptionId = orderedIds.get(index);
            AidRuntimeArtifactAdoption value = rows.get(adoptionId);
            if (value == null || !Objects.equals(value.getUserId(), userId)
                    || !Objects.equals(value.getSessionId(), sessionId)
                    || !Objects.equals(value.getClientRequestId(), clientRequestId)
                    || !Objects.equals(expectedTargetKind, value.getTargetKind())
                    || !"SUCCEEDED".equals(value.getStatus()) || !"0".equals(value.getDelFlag())) {
                fail(HttpStatus.INTERNAL_SERVER_ERROR, "SERIES_ADOPTION_RECEIPT_INVALID",
                        "Series adoption receipt is invalid", id(adoptionId));
            }
            JSONObject item = JSON.parseObject(value.getReceiptJson());
            String episodeId = item == null ? null : item.getString("episodeId");
            String createdEpisodeId = item == null ? null : item.getString("createdEpisodeId");
            String createdScriptId = item == null ? null : item.getString("createdScriptId");
            if (item == null || item.getIntValue("itemIndex") != index
                    || !"assistant-series-adoption-item/v1".equals(item.getString("schemaVersion"))
                    || !Objects.equals(compact.getString("projectId"), item.getString("projectId"))
                    || !SeriesScriptSplitPolicy.CONTRACT_VERSION.equals(
                    item.getString("splitterContractVersion"))
                    || !Objects.equals(compact.getString("capabilityDigest"),
                    item.getString("batchCapabilityDigest"))
                    || !Objects.equals(id(value.getArtifactId()), item.getString("artifactId"))
                    || !Objects.equals(value.getTargetObjectId(), item.getString("targetObjectId"))
                    || !Objects.equals(value.getExpectedTargetRevision(), item.getString("previousTargetRevision"))
                    || !Objects.equals(value.getResultTargetRevision(), item.getString("targetRevision"))
                    || !isArtifactRef(item.getString("artifactRef"))
                    || !isId(item.getString("artifactId"))
                    || !isSha(item.getString("artifactContentHash"))
                    || !isSha(item.getString("segmentContentHash"))
                    || !isRevision(item.getString("previousTargetRevision"))
                    || !isRevision(item.getString("targetRevision"))
                    || !isId(episodeId) || !isId(createdScriptId)
                    || !Objects.equals("project:" + compact.getString("projectId") + ":episode:" + episodeId,
                    item.getString("targetObjectId"))
                    || item.getLongValue("episodeNo") < 1
                    || MODE_APPEND.equals(replayMode) && !Objects.equals(episodeId, createdEpisodeId)
                    || MODE_UPDATE.equals(replayMode) && createdEpisodeId != null
                    || item.getIntValue("sourceCharStart") < 0
                    || item.getIntValue("sourceCharEnd") <= item.getIntValue("sourceCharStart")) {
                fail(HttpStatus.INTERNAL_SERVER_ERROR, "SERIES_ADOPTION_RECEIPT_INVALID",
                        "Series adoption item receipt is invalid", id(adoptionId));
            }
            SeriesAdoptionViews.ReceiptItem receipt = new SeriesAdoptionViews.ReceiptItem(
                    item.getIntValue("itemIndex"), id(adoptionId),
                    item.getString("artifactId"), item.getString("artifactRef"),
                    item.getString("artifactContentHash"), item.getString("segmentContentHash"),
                    item.getString("targetObjectId"), item.getString("episodeId"),
                    item.getLong("episodeNo"), item.getString("previousTargetRevision"),
                    item.getString("targetRevision"), item.getString("createdEpisodeId"),
                    item.getString("createdScriptId"), item.getIntValue("sourceCharStart"),
                    item.getIntValue("sourceCharEnd"));
            String recomputed = adoptionRequestDigest(compact.getString("capabilityDigest"),
                    compact.getString("projectId"), expectedTargetKind, receipt);
            if (!Objects.equals(recomputed, value.getRequestDigest())
                    || !Objects.equals(recomputed, item.getString("itemRequestDigest"))) {
                fail(HttpStatus.INTERNAL_SERVER_ERROR, "SERIES_ADOPTION_RECEIPT_DIGEST_INVALID",
                        "Series adoption item receipt digest is invalid", id(adoptionId));
            }
            items.add(receipt);
        }
        return new SeriesAdoptionViews.Receipt("assistant-series-adoption-receipt/v1", id(command.getId()),
                id(sessionId), clientRequestId, compact.getString("mode"), compact.getString("projectId"),
                compact.getString("beforeEpisodeSetDigest"), compact.getString("afterEpisodeSetDigest"),
                List.copyOf(items));
    }

    private SeriesAdoptionViews.Receipt resolveAuthorizedCommand(AidRuntimeCommand command, Long userId,
                                                                 Long sessionId, String mode,
                                                                 String clientRequestId, String payloadDigest) {
        AidAssistantSession session = series.selectSession(sessionId);
        CreativeSeriesCatalogService.requireProjectScope(session, userId);
        CreativeSeriesCatalogService.requireProject(series.selectProject(session.getProjectId()), userId);
        SeriesAdoptionViews.Receipt receipt = resolveExistingCommand(command, userId, sessionId, mode,
                clientRequestId, payloadDigest);
        if (!Objects.equals(receipt.projectId(), id(session.getProjectId()))) {
            fail(HttpStatus.CONFLICT, "SERIES_COMMAND_SCOPE_CONFLICT", "采用范围已变化", null);
        }
        return receipt;
    }

    private SeriesAdoptionViews.Receipt resolveExistingCommand(AidRuntimeCommand command, Long userId,
                                                                Long sessionId, String mode,
                                                                String clientRequestId, String payloadDigest) {
        if (!Objects.equals(command.getActorId(), userId)
                || !Objects.equals(command.getSessionId(), sessionId)
                || !Objects.equals(command.getClientRequestId(), clientRequestId)) {
            fail(HttpStatus.FORBIDDEN, "SERIES_COMMAND_FORBIDDEN", "采用记录无权限", null);
        }
        if (!Objects.equals(payloadDigest, command.getPayloadDigest())) fail(HttpStatus.CONFLICT,
                "SERIES_COMMAND_REUSED", "Client request id was reused with another payload", null);
        if (!Objects.equals(commandType(mode), command.getCommandType())) fail(HttpStatus.CONFLICT,
                "SERIES_COMMAND_TYPE_CONFLICT", "Series command type does not match", null);
        if ("COMPLETED".equals(command.getStatus())) {
            return replay(command, userId, sessionId, clientRequestId);
        }
        if ("PROCESSING".equals(command.getStatus())) fail(HttpStatus.CONFLICT,
                "SERIES_COMMAND_IN_PROGRESS", "Series adoption command is still processing", null);
        if ("FAILED".equals(command.getStatus())) fail(HttpStatus.CONFLICT,
                "SERIES_COMMAND_FAILED", "Series adoption command has a terminal failure receipt",
                command.getErrorCode());
        fail(HttpStatus.INTERNAL_SERVER_ERROR, "SERIES_COMMAND_STATE_INVALID",
                "Series adoption command status is invalid", command.getStatus());
        return null;
    }

    private AidRuntimeCommand findCommand(Long userId, Long sessionId, String key, boolean lock) {
        AssistantDecisionCorrectionCommandNamespace.requireExternalClientRequestId(key);
        return commands.selectOne(Wrappers.<AidRuntimeCommand>query()
                .eq("actor_id", userId)
                .eq("session_id", sessionId)
                .eq("client_request_id", key)
                .eq("del_flag", "0")
                .last(lock ? "LIMIT 1 FOR UPDATE" : "LIMIT 1"));
    }

    private String modeFromCommandType(String commandType) {
        if (COMMAND_UPDATE.equals(commandType)) return MODE_UPDATE;
        if (COMMAND_APPEND.equals(commandType)) return MODE_APPEND;
        fail(HttpStatus.INTERNAL_SERVER_ERROR, "SERIES_COMMAND_RECEIPT_INVALID",
                "Series command type is invalid", commandType);
        return null;
    }

    private Long receiptId(String value, Long commandId) {
        if (!isId(value)) {
            fail(HttpStatus.INTERNAL_SERVER_ERROR, "SERIES_COMMAND_RECEIPT_INVALID",
                    "Series command receipt contains an invalid identifier", id(commandId));
        }
        return Long.parseLong(value);
    }

    private boolean isId(String value) {
        if (value == null || !value.matches("^[1-9][0-9]{0,18}$")) return false;
        try {
            return Long.parseLong(value) > 0;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    private boolean isSha(String value) {
        return value != null && value.matches("^[0-9a-f]{64}$");
    }

    private boolean isArtifactRef(String value) {
        return value != null && value.length() <= 300
                && value.matches("^artifact:[A-Za-z0-9._:-]{1,280}$");
    }

    private boolean isRevision(String value) {
        return value != null && !value.isBlank() && value.length() <= 128;
    }

    private ModelItem model(int index, Snapshot artifact, Segment segment, AidComicEpisode episode,
                            SeriesCurrentScriptProjection current, String revision) {
        Segment resolved = new Segment(segment.sequenceNo(), episode.getComicTitle(), episode.getComicDesc(),
                segment.content(), segment.contentHash(), segment.sourceCharStart(), segment.sourceCharEnd(),
                segment.effectiveCharCount());
        return new ModelItem(index, artifact, resolved, episode, episode.getEpisodeNo(), revision);
    }

    private SeriesAdoptionViews.CapabilityItem view(ModelItem item) {
        return new SeriesAdoptionViews.CapabilityItem(item.index(), id(item.artifact().artifact().getId()),
                item.artifact().artifactRef(), item.artifact().contentHash(), item.segment().contentHash(),
                item.episode() == null ? null : id(item.episode().getId()), item.episodeNo(),
                item.currentTargetRevision(), item.segment().title(), item.segment().description(),
                item.segment().sourceCharStart(), item.segment().sourceCharEnd(),
                item.segment().effectiveCharCount(), true, null);
    }

    private SeriesAdoptionViews.ReceiptItem receiptItem(ModelItem item, AidComicEpisode createdEpisode,
                                                        AidComicScript createdScript, String targetRevision) {
        AidComicEpisode target = createdEpisode == null ? item.episode() : createdEpisode;
        String object = "project:" + target.getProjectId() + ":episode:" + target.getId();
        return new SeriesAdoptionViews.ReceiptItem(item.index(), null,
                id(item.artifact().artifact().getId()), item.artifact().artifactRef(),
                item.artifact().contentHash(), item.segment().contentHash(), object, id(target.getId()),
                target.getEpisodeNo(), item.currentTargetRevision(), targetRevision,
                createdEpisode == null ? null : id(createdEpisode.getId()), id(createdScript.getId()),
                item.segment().sourceCharStart(), item.segment().sourceCharEnd());
    }

    private SeriesAdoptionViews.ReceiptItem withAdoption(SeriesAdoptionViews.ReceiptItem value, Long adoptionId) {
        return new SeriesAdoptionViews.ReceiptItem(value.itemIndex(), id(adoptionId), value.artifactId(),
                value.artifactRef(), value.artifactContentHash(), value.segmentContentHash(),
                value.targetObjectId(), value.episodeId(), value.episodeNo(), value.previousTargetRevision(),
                value.targetRevision(), value.createdEpisodeId(), value.createdScriptId(),
                value.sourceCharStart(), value.sourceCharEnd());
    }

    private void requireEditable(AidComicProject project) {
        if ("1".equals(project.getIsPublic())) fail(HttpStatus.CONFLICT, "SERIES_PROJECT_PUBLISHED",
                "Published project content cannot be changed", id(project.getId()));
        projectGuard.assertProjectEditable(project);
    }

    private String requireMode(String mode) {
        if (!MODE_UPDATE.equals(mode) && !MODE_APPEND.equals(mode)) {
            fail(HttpStatus.BAD_REQUEST, "SERIES_ADOPTION_MODE_INVALID", "Series adoption mode is invalid", mode);
        }
        return mode;
    }

    private void requireUser(Long userId) {
        if (userId == null || userId <= 0) fail(HttpStatus.FORBIDDEN, "SERIES_USER_REQUIRED",
                "Authenticated user is required", null);
    }

    private void requireSha(String value, String field) {
        if (value == null || !value.matches("^[0-9a-f]{64}$")) fail(HttpStatus.BAD_REQUEST,
                "SERIES_DIGEST_INVALID", "Series digest is invalid", field);
    }

    private Long id(String value, String field) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) throw new NumberFormatException();
            return parsed;
        } catch (RuntimeException error) {
            fail(HttpStatus.BAD_REQUEST, "SERIES_ID_INVALID", "Series identifier is invalid", field);
            return null;
        }
    }

    private String id(Long value) {
        return value == null ? null : String.valueOf(value);
    }

    private String commandType(String mode) {
        return MODE_UPDATE.equals(mode) ? COMMAND_UPDATE : COMMAND_APPEND;
    }

    private void fail(HttpStatus status, String code, String message, Object details) {
        throw new AssistantServiceException(status.value(), code, message, details);
    }

    private record CapabilityBuild(List<ModelItem> items, SplitPlan split, String splitCapabilitySnapshot) {
    }

    private record ModelItem(int index, Snapshot artifact, Segment segment, AidComicEpisode episode,
                             long episodeNo, String currentTargetRevision) {
    }

    private record PreparedCommit(List<PreparedItem> items, SplitPlan split,
                                  CapabilitySnapshot splitCapabilitySnapshot) {
    }

    private record PreparedItem(int index, Snapshot artifact, Long episodeId,
                                String expectedTargetRevision, Segment segment) {
    }
}
