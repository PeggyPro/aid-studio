package com.aid.director;

import com.aid.director.domain.DirectorEntities.Scene;
import com.aid.director.domain.DirectorEntities.Camera;
import com.aid.director.domain.DirectorEntities.Take;
import com.aid.director.domain.DirectorEntities.Chunk;
import com.aid.director.dto.DirectorRequests;
import com.aid.director.mapper.DirectorSceneMapper;
import com.aid.director.mapper.DirectorCameraMapper;
import com.aid.director.mapper.DirectorTakeMapper;
import com.aid.director.mapper.DirectorChunkMapper;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONArray;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Semaphore;

/** 实际相机轨迹的分块确认、恢复和唯一应用。 */
@Service
@RequiredArgsConstructor
public class DirectorRecordingService {
    private final DirectorTakeMapper takes;
    private final DirectorChunkMapper chunks;
    private final DirectorSceneMapper sceneMapper;
    private final DirectorCameraMapper cameraMapper;
    private final DirectorSceneService scenes;
    private final DirectorPermissionGuard permissions;
    private final DirectorSessionService sessions;
    private final DirectorRoomStore rooms;
    private final DirectorObjectStore objects;
    private final DirectorProperties properties;
    private final TransactionTemplate transactions;
    private final Semaphore uploads = new Semaphore(64);
    private static final Set<String> ACTIVE = Set.of("PREPARING", "RECORDING", "STOPPING");

    public JSONObject control(DirectorRequests.RecordingControl request, Long user) {
        DirectorIds.key(request.getCommandId());
        return rooms.mutate(request.getSessionId(), room -> {
            DirectorSessionService.assertHost(room, user); DirectorSessionService.live(room); sessions.writable(room);
            if (room.getPeerEpoch() != request.getPeerEpoch() || room.getBindingEpoch() != request.getBindingEpoch())
                throw DirectorException.conflict("STALE_EPOCH");
            if ("STOP".equals(request.getAction())) {
                Take known = owned(DirectorIds.id(request.getTakeId()), user, true);
                return transactions.execute(status -> {
                    Take take = lockForWrite(known, user);
                    if (!take.getRoomId().equals(room.getId())) throw DirectorException.denied();
                    if ("STOPPING".equals(take.getState()) && request.getCommandId().equals(take.getStopRequestId())) return view(take);
                    if (!Set.of("PREPARING", "RECORDING").contains(take.getState())) throw DirectorException.conflict("RECORDING_STATE_CONFLICT");
                    take.setState("STOPPING"); take.setStopRequestId(request.getCommandId()); touch(take, user); takes.updateById(take);
                    return view(take);
                });
            }
            if (!"START".equals(request.getAction())) throw DirectorException.invalid();
            String requestId = DirectorIds.hash(room.getId() + ":" + request.getCommandId());
            String hash = DirectorIds.requestHash(request);
            if (!"READY".equals(room.getState()) || !room.isControlAuthorized() || room.getLeaseUntil() <= System.currentTimeMillis())
                throw DirectorException.conflict("HOST_NOT_READY");
            return transactions.execute(status -> {
                permissions.require(room.getProjectId(), room.getWorkspaceId(), user, true);
                Scene scene = sceneMapper.lock(room.getSceneRowId());
                Camera camera = cameraMapper.lock(room.getCameraRowId());
                scenes.assertFence(camera, room.getFence(), room.getId(), room.getEpoch());
                Take previous = takes.selectOne(Wrappers.<Take>lambdaQuery().eq(Take::getHostUserId, user).eq(Take::getStartRequestId, requestId));
                if (previous != null) {
                    if (!hash.equals(previous.getStartRequestHash())) throw DirectorException.conflict("IDEMPOTENCY_CONFLICT");
                    return view(previous);
                }
                if (scene == null || scene.getDeleted() != 0) throw DirectorException.denied();
                if (takes.selectCount(Wrappers.<Take>lambdaQuery().eq(Take::getHostUserId, user)
                        .ne(Take::getState, "APPLIED")) >= properties.getMaxDraftsPerUser())
                    throw new DirectorException("CAPACITY_REACHED", 429, "录制草稿数量已满");
                if (takes.selectCount(Wrappers.<Take>lambdaQuery().eq(Take::getCameraRowId, camera.getId()).in(Take::getState, ACTIVE)) > 0)
                    throw DirectorException.conflict("RECORDING_BUSY");
                Take take = new Take(); DirectorSceneService.init(take, user);
                take.setProjectId(room.getProjectId()); take.setWorkspaceId(room.getWorkspaceId()); take.setSceneId(scene.getId());
                take.setCameraRowId(camera.getId()); take.setHostUserId(user); take.setOriginRoomId(room.getId());
                take.setRoomId(room.getId()); take.setRoomEpoch(room.getEpoch()); take.setControlFence(room.getFence());
                take.setContextVersion(1L); take.setStartRequestId(requestId); take.setStartRequestHash(hash);
                take.setState("PREPARING"); take.setDurableFrameSeq(-1L); take.setChunkCount(0); take.setFrameCount(0);
                take.setDurationMs(0L); take.setSceneRevisionAtStart(scene.getRevision()); take.setRecovered(false);
                take.setHostStopped(false); take.setExpireAt(new Date(System.currentTimeMillis() + properties.getDraftDays() * 86400000L));
                touch(take, user); takes.insert(take); return view(take);
            });
        });
    }

    public JSONObject confirm(DirectorRequests.Confirm request, Long user) {
        Take known = owned(DirectorIds.id(request.getTakeId()), user, true);
        if ("STARTED".equals(request.getResult())) {
            DirectorRoom room = sessions.writableHost(known.getRoomId(), user);
            if (!"READY".equals(room.getState()) || !room.isControlAuthorized() || room.getLeaseUntil() <= System.currentTimeMillis())
                throw DirectorException.conflict("HOST_NOT_READY");
        }
        return transactions.execute(status -> {
            Take take = lockForWrite(known, user); context(take, request.getContextVersion());
            switch (request.getResult()) {
                case "STARTED" -> {
                    String command = DirectorIds.hash(take.getOriginRoomId() + ":" + DirectorIds.key(request.getCommandId()));
                    if (!command.equals(take.getStartRequestId())) throw DirectorException.denied();
                    if ("RECORDING".equals(take.getState())) return view(take);
                    if (!"PREPARING".equals(take.getState()) || System.currentTimeMillis() - take.getCreateTime().getTime() > 15000)
                        throw DirectorException.conflict("RECORDING_STATE_CONFLICT");
                    take.setState("RECORDING");
                }
                case "STOPPED" -> {
                    if (!request.getCommandId().equals(take.getStopRequestId()) || !"STOPPING".equals(take.getState()))
                        throw DirectorException.conflict("RECORDING_STATE_CONFLICT");
                    take.setHostStopped(true);
                }
                case "REJECTED" -> {
                    if (!ACTIVE.contains(take.getState())) throw DirectorException.conflict("RECORDING_STATE_CONFLICT");
                    take.setState("INTERRUPTED"); take.setHostStopped(true);
                }
                default -> throw DirectorException.invalid();
            }
            touch(take, user); takes.updateById(take); return view(take);
        });
    }

    public JSONObject append(DirectorRequests.Chunk request, Long user) {
        Take known = owned(DirectorIds.id(request.getTakeId()), user, true);
        if (request.getSamples().size() != request.getLastFrameSeq() - request.getFirstFrameSeq() + 1) throw DirectorException.invalid();
        long last = -1;
        for (DirectorRequests.Sample sample : request.getSamples()) {
            if (sample.getTMs() <= last || sample.getTMs() > 600000) throw DirectorException.invalid();
            last = sample.getTMs();
            validateSample(sample);
        }
        byte[] bytes = JSON.toJSONBytes(request.getSamples());
        if (bytes.length > 128 * 1024) throw new DirectorException("PAYLOAD_TOO_LARGE", 413, "录制分块过大");
        if (!uploads.tryAcquire()) throw new DirectorException("CAPACITY_REACHED", 429, "保存繁忙，请稍后");
        try {
            // 文件写入在事务外，只有元数据提交后才返回DURABLE。
            String objectKey = objects.put(bytes);
            return transactions.execute(status -> {
                Take take = lockForWrite(known, user); context(take, request.getContextVersion());
                Chunk previous = chunks.selectOne(Wrappers.<Chunk>lambdaQuery().eq(Chunk::getTakeId, take.getId())
                        .eq(Chunk::getChunkNo, request.getChunkNo()));
                if (previous != null) {
                    if (!previous.getContentHash().equals(objectKey) || previous.getFirstFrameSeq() != request.getFirstFrameSeq().longValue()
                            || previous.getLastFrameSeq() != request.getLastFrameSeq().longValue()) throw DirectorException.conflict("CHUNK_CONFLICT");
                    return receipt(take);
                }
                if (!Set.of("RECORDING", "STOPPING").contains(take.getState())) throw DirectorException.conflict("RECORDING_STATE_CONFLICT");
                if (request.getChunkNo() != take.getChunkCount().intValue() || request.getFirstFrameSeq() != take.getDurableFrameSeq() + 1)
                    throw DirectorException.conflict("CHUNK_OUT_OF_ORDER");
                long firstTime = request.getSamples().get(0).getTMs();
                if (take.getChunkCount() == 0 ? firstTime != 0 : firstTime <= take.getDurationMs()) throw DirectorException.invalid();
                if (take.getFrameCount() + request.getSamples().size() > 18000 || take.getChunkCount() >= 200) throw DirectorException.invalid();
                Chunk chunk = new Chunk(); DirectorSceneService.init(chunk, user); chunk.setTakeId(take.getId());
                chunk.setChunkNo(request.getChunkNo()); chunk.setFirstFrameSeq(request.getFirstFrameSeq()); chunk.setLastFrameSeq(request.getLastFrameSeq());
                chunk.setStartMs(firstTime); chunk.setEndMs(request.getSamples().get(request.getSamples().size() - 1).getTMs());
                chunk.setFrameCount(request.getSamples().size()); chunk.setByteSize(bytes.length); chunk.setObjectKey(objectKey); chunk.setContentHash(objectKey);
                chunks.insert(chunk); take.setChunkCount(take.getChunkCount() + 1); take.setFrameCount(take.getFrameCount() + chunk.getFrameCount());
                take.setDurableFrameSeq(chunk.getLastFrameSeq()); take.setDurationMs(chunk.getEndMs()); touch(take, user); takes.updateById(take);
                return receipt(take);
            });
        } finally { uploads.release(); }
    }

    public JSONObject state(String id, Long user) { return view(owned(DirectorIds.id(id), user, false)); }

    public JSONObject finalizeTake(DirectorRequests.Finalize request, Long user) {
        Take known = owned(DirectorIds.id(request.getTakeId()), user, true);
        context(known, request.getContextVersion());
        if (!Set.of("NORMAL", "RECOVER_PREFIX").contains(request.getMode())) throw DirectorException.invalid();
        List<Chunk> rows = chunkList(known.getId(), 0, 201);
        if (rows.size() > 200 || known.getChunkCount() != rows.size() || request.getExpectedLastChunkNo() != rows.size() - 1
                || request.getExpectedFrameCount() != known.getFrameCount().intValue() || known.getFrameCount() < 2)
            throw DirectorException.conflict("INCOMPLETE_RECORDING");
        JSONArray items = new JSONArray();
        for (Chunk chunk : rows) {
            objects.read(chunk.getObjectKey(), 128 * 1024);
            items.add(JSONObject.of("chunkNo", chunk.getChunkNo(), "hash", chunk.getContentHash(), "firstFrameSeq", chunk.getFirstFrameSeq(),
                    "lastFrameSeq", chunk.getLastFrameSeq(), "frameCount", chunk.getFrameCount(), "endMs", chunk.getEndMs()));
        }
        JSONObject manifest = JSONObject.of("takeId", String.valueOf(known.getId()), "frameCount", known.getFrameCount(),
                "durationMs", known.getDurationMs(), "chunks", items);
        String key = objects.put(JSON.toJSONBytes(manifest));
        if (Set.of("READY", "APPLIED").contains(known.getState())) {
            if (!key.equals(known.getManifestKey())) throw DirectorException.conflict("FINALIZE_CONFLICT");
            return view(known);
        }
        return transactions.execute(status -> {
            Take take = lockForWrite(known, user); context(take, request.getContextVersion());
            if (Set.of("READY", "APPLIED").contains(take.getState())) {
                if (!key.equals(take.getManifestKey())) throw DirectorException.conflict("FINALIZE_CONFLICT");
                return view(take);
            }
            if (!"STOPPING".equals(take.getState()) || !Boolean.TRUE.equals(take.getHostStopped())
                    || take.getChunkCount() != rows.size() || take.getFrameCount() != request.getExpectedFrameCount().intValue())
                throw DirectorException.conflict("INCOMPLETE_RECORDING");
            if ("RECOVER_PREFIX".equals(request.getMode()) != Boolean.TRUE.equals(take.getRecovered())) throw DirectorException.invalid();
            take.setManifestKey(key); take.setContentHash(key); take.setState("READY"); touch(take, user); takes.updateById(take);
            return view(take);
        });
    }

    public JSONObject apply(DirectorRequests.Apply request, Long user) {
        Take known = owned(DirectorIds.id(request.getTakeId()), user, true); context(known, request.getContextVersion());
        String applyHash = DirectorIds.requestHash(request);
        if ("APPLIED".equals(known.getState())) {
            if (!applyHash.equals(known.getApplyRequestHash())) throw DirectorException.conflict("IDEMPOTENCY_CONFLICT");
            return view(known);
        }
        if (!"READY".equals(known.getState())) throw DirectorException.conflict("RECORDING_STATE_CONFLICT");
        Scene scene = sceneMapper.selectById(known.getSceneId());
        if (scene == null || scene.getDeleted() != 0) throw DirectorException.denied();
        if (!scene.getRevision().equals(request.getExpectedSceneRevision())) throw DirectorException.conflict("REVISION_CONFLICT");
        Camera camera = cameraMapper.selectById(known.getCameraRowId());
        JSONObject content = scenes.content(scene);
        JSONObject timeline = content.getJSONObject("timeline");
        if (timeline == null) { timeline = new JSONObject(); content.put("timeline", timeline); }
        JSONArray clips = timeline.getJSONArray("pathClips");
        if (clips == null) { clips = new JSONArray(); timeline.put("pathClips", clips); }
        double start = request.getTimelineStartMs() / 1000.0, end = start + known.getDurationMs() / 1000.0;
        if (end <= start || clips.size() >= 500) throw DirectorException.invalid();
        for (Object raw : clips) {
            JSONObject clip = (JSONObject) raw;
            if (camera.getCameraKey().equals(clip.getString("targetId")) && "camera".equals(clip.getString("targetKind"))
                    && clip.getDoubleValue("start") < end && clip.getDoubleValue("end") > start)
                throw DirectorException.conflict("TRACK_CONFLICT");
        }
        String clipId = "take_" + known.getId();
        clips.add(JSONObject.of("id", clipId, "targetKind", "camera", "targetId", camera.getCameraKey(), "source", "virtual-camera",
                "takeId", String.valueOf(known.getId()), "sessionId", known.getOriginRoomId(), "start", start, "end", end));
        timeline.put("duration", Math.max(timeline.getDoubleValue("duration"), end));
        byte[] bytes = JSON.toJSONBytes(DirectorSceneService.canonical(content, 0));
        String key = objects.put(bytes);
        return transactions.execute(status -> {
            permissions.require(known.getProjectId(), known.getWorkspaceId(), user, true);
            Scene lockedScene = sceneMapper.lock(known.getSceneId());
            Camera lockedCamera = cameraMapper.lock(known.getCameraRowId());
            Take take = takes.lock(known.getId()); context(take, request.getContextVersion());
            if ("APPLIED".equals(take.getState())) {
                if (!applyHash.equals(take.getApplyRequestHash())) throw DirectorException.conflict("IDEMPOTENCY_CONFLICT");
                return view(take);
            }
            scenes.assertFence(lockedCamera, take.getControlFence(), take.getRoomId(), take.getRoomEpoch());
            if (lockedScene == null || lockedScene.getDeleted() != 0) throw DirectorException.denied();
            if (!"READY".equals(take.getState()) || !lockedScene.getRevision().equals(request.getExpectedSceneRevision()))
                throw DirectorException.conflict("REVISION_CONFLICT");
            lockedScene.setRevision(lockedScene.getRevision() + 1); lockedScene.setObjectKey(key); lockedScene.setContentHash(key);
            lockedScene.setByteSize(bytes.length); DirectorSceneService.touch(lockedScene, user); sceneMapper.updateById(lockedScene);
            take.setState("APPLIED"); take.setAppliedRevision(lockedScene.getRevision()); take.setAppliedClipId(clipId);
            take.setApplyRequestHash(applyHash);
            touch(take, user); takes.updateById(take); return view(take);
        });
    }

    public JSONObject recover(DirectorRequests.Recover request, Long user) {
        Take known = owned(DirectorIds.id(request.getTakeId()), user, true);
        return rooms.mutate(request.getSessionId(), room -> {
            DirectorSessionService.assertHost(room, user); DirectorSessionService.live(room); sessions.writable(room);
            if (!known.getCameraRowId().equals(room.getCameraRowId()) || !known.getSceneId().equals(room.getSceneRowId())) throw DirectorException.denied();
            return transactions.execute(status -> {
                permissions.require(known.getProjectId(), known.getWorkspaceId(), user, true);
                Camera camera = cameraMapper.lock(known.getCameraRowId());
                scenes.assertFence(camera, room.getFence(), room.getId(), room.getEpoch());
                Take take = takes.lock(known.getId());
                if (room.getId().equals(take.getRoomId()) && room.getFence().equals(take.getControlFence())
                        && take.getContextVersion() == request.getExpectedContextVersion() + 1) return view(take);
                context(take, request.getExpectedContextVersion());
                if (ACTIVE.contains(take.getState()) && System.currentTimeMillis() - take.getLastActivityAt().getTime() < 45000)
                    throw DirectorException.conflict("RECORDING_BUSY");
                if (!Set.of("INTERRUPTED", "READY", "PREPARING", "RECORDING", "STOPPING").contains(take.getState()))
                    throw DirectorException.conflict("RECORDING_STATE_CONFLICT");
                take.setRoomId(room.getId()); take.setRoomEpoch(room.getEpoch()); take.setControlFence(room.getFence());
                take.setContextVersion(take.getContextVersion() + 1); take.setRecovered(true); take.setHostStopped(true);
                if (!"READY".equals(take.getState())) take.setState("STOPPING");
                touch(take, user); takes.updateById(take); return view(take);
            });
        });
    }

    public JSONObject discard(DirectorRequests.TakeKey request, Long user) {
        Take known = owned(DirectorIds.id(request.getTakeId()), user, true);
        return transactions.execute(status -> {
            permissions.require(known.getProjectId(), known.getWorkspaceId(), user, true);
            Take take = takes.lock(known.getId());
            if ("APPLIED".equals(take.getState())) throw DirectorException.conflict("TAKE_ALREADY_APPLIED");
            take.setState("DISCARDED"); take.setExpireAt(new Date(System.currentTimeMillis() + 86400000));
            touch(take, user); takes.updateById(take); return view(take);
        });
    }

    public JSONObject list(DirectorRequests.TakeList request, Long user) {
        Scene scene = scenes.require(request, user, false);
        var query = Wrappers.<Take>lambdaQuery().select(Take::getId, Take::getState, Take::getFrameCount,
                        Take::getDurationMs, Take::getChunkCount, Take::getContextVersion, Take::getDurableFrameSeq,
                        Take::getAppliedClipId, Take::getAppliedRevision, Take::getRecovered)
                .eq(Take::getSceneId, scene.getId()).eq(Take::getHostUserId, user).orderByAsc(Take::getId)
                .last("LIMIT " + (request.getPageSize() + 1));
        if (request.getCursor() != null) query.gt(Take::getId, DirectorIds.id(request.getCursor()));
        List<Take> rows = takes.selectList(query); boolean more = rows.size() > request.getPageSize();
        if (more) rows = rows.subList(0, request.getPageSize());
        return JSONObject.of("items", rows.stream().map(this::view).toList(),
                "nextCursor", more ? String.valueOf(rows.get(rows.size() - 1).getId()) : null);
    }

    public JSONObject read(DirectorRequests.Read request, Long user) {
        Take take = owned(DirectorIds.id(request.getTakeId()), user, false);
        List<Chunk> rows = chunkList(take.getId(), request.getChunkCursor(), 2);
        JSONArray result = new JSONArray();
        if (!rows.isEmpty()) {
            Chunk chunk = rows.get(0);
            result.add(JSONObject.of("chunkNo", chunk.getChunkNo(), "samples", JSON.parseArray(objects.text(chunk.getObjectKey(), 128 * 1024))));
        }
        return JSONObject.of("recording", view(take), "chunks", result,
                "nextChunkCursor", rows.size() > 1 ? rows.get(1).getChunkNo() : null);
    }

    private Take owned(Long id, Long user, boolean write) {
        properties.requireEnabled(); Take take = takes.selectById(id);
        if (take == null || !user.equals(take.getHostUserId()) && (write || !"APPLIED".equals(take.getState()))) throw DirectorException.denied();
        permissions.require(take.getProjectId(), take.getWorkspaceId(), user, write);
        Scene scene = sceneMapper.selectOne(Wrappers.<Scene>lambdaQuery().select(Scene::getId, Scene::getDeleted)
                .eq(Scene::getId, take.getSceneId()));
        if (scene == null || scene.getDeleted() != 0) throw new DirectorException("RESOURCE_UNAVAILABLE", 404, "场景不可用");
        if (!"APPLIED".equals(take.getState()) && take.getExpireAt().getTime() <= System.currentTimeMillis())
            throw new DirectorException("RECORDING_EXPIRED", 410, "录制草稿已过期");
        return take;
    }

    private Take lockForWrite(Take known, Long user) {
        permissions.require(known.getProjectId(), known.getWorkspaceId(), user, true);
        Camera camera = cameraMapper.lock(known.getCameraRowId());
        Take take = takes.lock(known.getId());
        scenes.assertFence(camera, take.getControlFence(), take.getRoomId(), take.getRoomEpoch());
        if (!user.equals(take.getHostUserId())) throw DirectorException.denied();
        return take;
    }

    private List<Chunk> chunkList(Long takeId, int cursor, int limit) {
        return chunks.selectList(Wrappers.<Chunk>lambdaQuery().eq(Chunk::getTakeId, takeId).ge(Chunk::getChunkNo, cursor)
                .orderByAsc(Chunk::getChunkNo).last("LIMIT " + limit));
    }

    private void context(Take take, Long version) {
        if (!Objects.equals(take.getContextVersion(), version)) throw DirectorException.conflict("STALE_CONTEXT");
    }

    private void validateSample(DirectorRequests.Sample sample) {
        for (List<Double> vector : List.of(sample.getPosition(), sample.getTarget()))
            for (Double value : vector) DirectorSceneService.number(value, -1_000_000, 1_000_000);
        DirectorSceneService.number(sample.getFov(), 1, 179); DirectorSceneService.number(sample.getRoll(), -360, 360);
    }

    private void touch(Take take, Long user) { DirectorSceneService.touch(take, user); take.setLastActivityAt(new Date()); }
    private JSONObject receipt(Take take) { JSONObject view = view(take); view.put("durability", "DURABLE"); return view; }

    public JSONObject view(Take take) {
        return JSONObject.of("takeId", String.valueOf(take.getId()), "state", take.getState(), "contextVersion", take.getContextVersion(),
                "durableFrameSeq", take.getDurableFrameSeq(), "expectedChunkNo", take.getChunkCount(), "frameCount", take.getFrameCount(),
                "durationMs", take.getDurationMs(), "recovered", take.getRecovered(), "contentHash", take.getContentHash(),
                "sceneRevision", take.getAppliedRevision(), "clipId", take.getAppliedClipId());
    }
}
