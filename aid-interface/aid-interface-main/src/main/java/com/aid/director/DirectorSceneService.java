package com.aid.director;

import com.aid.director.domain.DirectorEntities.Audit;
import com.aid.director.domain.DirectorEntities.Scene;
import com.aid.director.domain.DirectorEntities.Camera;
import com.aid.director.domain.DirectorEntities.Take;
import com.aid.director.dto.DirectorRequests;
import com.aid.director.mapper.DirectorSceneMapper;
import com.aid.director.mapper.DirectorCameraMapper;
import com.aid.director.mapper.DirectorTakeMapper;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONArray;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.ArrayList;
import java.util.Objects;

/** 场景版本及机位控制权持久化。 */
@Service
@RequiredArgsConstructor
public class DirectorSceneService {
    private final DirectorSceneMapper scenes;
    private final DirectorCameraMapper cameras;
    private final DirectorTakeMapper takes;
    private final DirectorPermissionGuard permissions;
    private final DirectorScopeAccess scopes;
    private final DirectorObjectStore objects;
    private final DirectorProperties properties;
    private final DirectorRoomStore rooms;
    private final TransactionTemplate transactions;
    private static final Set<String> SCENE_FIELDS = Set.of("id", "name", "objects", "modelAssets", "lights", "camera",
            "cameraBookmarks", "mainCameraLens", "mainCameraLocked", "activeCameraId", "environmentColor",
            "environmentPreset", "gridVisible", "groundVisible", "aspectRatio", "timeline", "updatedAt");

    public Scene require(DirectorRequests.SceneKey request, Long userId, boolean write) {
        return require(DirectorIds.id(request.getProjectId()), optionalId(request.getWorkspaceId()),
                request.getSceneId(), userId, write);
    }

    public Scene require(Long projectId, Long workspaceId, String sceneKey, Long userId, boolean write) {
        properties.requireEnabled();
        permissions.require(projectId, workspaceId, userId, write);
        Scene scene = find(projectId, workspaceId, sceneKey);
        if (scene == null || scene.getDeleted() != 0) throw new DirectorException("RESOURCE_UNAVAILABLE", 404, "场景不可用");
        return scene;
    }

    public Scene find(Long projectId, Long workspaceId, String sceneKey) {
        return scenes.selectOne(Wrappers.<Scene>lambdaQuery().eq(Scene::getProjectId, projectId)
                .eq(Scene::getScopeKey, scopeKey(workspaceId)).eq(Scene::getSceneKey, DirectorIds.key(sceneKey)));
    }

    public JSONObject save(DirectorRequests.SceneSave request, Long userId) {
        properties.requireEnabled();
        rooms.rate("scene-save:" + userId, 30, 60);
        Long projectId = DirectorIds.id(request.getProjectId()), workspaceId = optionalId(request.getWorkspaceId());
        DirectorIds.key(request.getRequestId());
        permissions.require(projectId, workspaceId, userId, true);
        // Jackson为动态Map创建LinkedHashMap；统一为Fastjson容器后再做嵌套白名单校验。
        request.setScene(JSON.parseObject(request.getScene().toJSONString()));
        validateScene(request.getScene());
        List<Long> resources = new ArrayList<>();
        JSONArray models = request.getScene().getJSONArray("modelAssets");
        if (models != null) for (Object value : models) {
            if (!(value instanceof JSONObject model)) throw DirectorException.invalid();
            resources.add(DirectorIds.id(model.getString("resourceId")));
        }
        scopes.requireResources(projectId, workspaceId, userId, resources);
        Scene before = find(projectId, workspaceId, request.getSceneId());
        JSONObject previous = before == null ? null : content(before);
        byte[] bytes = JSON.toJSONBytes(canonical(request.getScene(), 0));
        String objectKey = objects.put(bytes), requestHash = DirectorIds.requestHash(request);
        return transactions.execute(status -> {
            permissions.require(projectId, workspaceId, userId, true);
            Scene scene = find(projectId, workspaceId, request.getSceneId());
            if (scene != null) scene = scenes.lock(scene.getId());
            if (scene != null && scene.getDeleted() != 0) throw DirectorException.conflict("SCENE_DELETED");
            if (scene != null && request.getRequestId().equals(scene.getLastRequestId())) {
                if (!requestHash.equals(scene.getLastRequestHash())) throw DirectorException.conflict("IDEMPOTENCY_CONFLICT");
                return view(scene, false);
            }
            if ((scene == null ? 0L : scene.getRevision()) != request.getExpectedRevision().longValue())
                throw DirectorException.conflict("REVISION_CONFLICT");
            List<Camera> existing = scene == null ? List.of() : cameras.selectList(Wrappers.<Camera>lambdaQuery()
                    .eq(Camera::getSceneId, scene.getId()).orderByAsc(Camera::getId).last("FOR UPDATE"));
            Map<String, JSONObject> nextCameras = cameraDefinitions(request.getScene());
            Set<String> allKeys = new TreeSet<>(nextCameras.keySet());
            for (Camera current : existing) {
                allKeys.add(current.getCameraKey());
                if (active(current) && !Objects.equals(cameraDefinition(previous, current.getCameraKey()),
                        nextCameras.get(current.getCameraKey()))) throw DirectorException.conflict("CAMERA_BUSY");
            }
            if (allKeys.size() > 32) throw DirectorException.conflict("CAMERA_LIMIT_REACHED");
            if (scene == null) {
                if (scenes.selectCount(Wrappers.<Scene>lambdaQuery().eq(Scene::getProjectId, projectId)
                        .eq(Scene::getScopeKey, scopeKey(workspaceId)).eq(Scene::getDeleted, 0)) >= properties.getMaxScenesPerScope())
                    throw new DirectorException("CAPACITY_REACHED", 429, "场景数量已达上限");
                scene = new Scene(); init(scene, userId);
                scene.setProjectId(projectId); scene.setWorkspaceId(workspaceId); scene.setScopeKey(scopeKey(workspaceId));
                scene.setSceneKey(DirectorIds.key(request.getSceneId())); scene.setRevision(0L); scene.setDeleted(0);
            }
            validateTakeReferences(request.getScene(), scene.getId());
            scene.setName(request.getName()); scene.setRevision(scene.getRevision() + 1);
            scene.setObjectKey(objectKey); scene.setContentHash(objectKey); scene.setByteSize(bytes.length);
            scene.setLastRequestId(request.getRequestId()); scene.setLastRequestHash(requestHash); touch(scene, userId);
            if (scene.getRevision() == 1) scenes.insert(scene); else scenes.updateById(scene);
            for (Camera item : existing) {
                boolean deleted = !nextCameras.containsKey(item.getCameraKey());
                item.setDeleted(deleted ? 1 : 0);
                if (!deleted) item.setLocked(nextCameras.get(item.getCameraKey()).getBooleanValue("locked"));
                touch(item, userId); cameras.updateById(item);
                nextCameras.remove(item.getCameraKey());
            }
            for (String cameraKey : nextCameras.keySet()) {
                Camera camera = new Camera(); init(camera, userId); camera.setSceneId(scene.getId());
                camera.setCameraKey(cameraKey); camera.setControlFence(0L); camera.setDeleted(0);
                camera.setLocked(nextCameras.get(cameraKey).getBooleanValue("locked")); cameras.insert(camera);
            }
            return view(scene, false);
        });
    }

    public JSONObject detail(DirectorRequests.SceneKey request, Long userId) { return view(require(request, userId, false), true); }

    public JSONObject list(DirectorRequests.SceneList request, Long userId) {
        properties.requireEnabled();
        Long projectId = DirectorIds.id(request.getProjectId()), workspaceId = optionalId(request.getWorkspaceId());
        permissions.require(projectId, workspaceId, userId, false);
        var query = Wrappers.<Scene>lambdaQuery().select(Scene::getId, Scene::getSceneKey, Scene::getName, Scene::getRevision)
                .eq(Scene::getProjectId, projectId).eq(Scene::getScopeKey, scopeKey(workspaceId)).eq(Scene::getDeleted, 0)
                .orderByAsc(Scene::getId).last("LIMIT " + (request.getPageSize() + 1));
        if (request.getCursor() != null) query.gt(Scene::getId, DirectorIds.id(request.getCursor()));
        List<Scene> rows = scenes.selectList(query);
        boolean more = rows.size() > request.getPageSize();
        if (more) rows = rows.subList(0, request.getPageSize());
        JSONArray items = new JSONArray();
        for (Scene scene : rows) items.add(JSONObject.of("sceneId", scene.getSceneKey(), "name", scene.getName(),
                "revision", scene.getRevision()));
        return JSONObject.of("items", items, "nextCursor", more ? String.valueOf(rows.get(rows.size() - 1).getId()) : null);
    }

    public JSONObject delete(DirectorRequests.SceneDelete request, Long userId) {
        properties.requireEnabled();
        Long projectId = DirectorIds.id(request.getProjectId()), workspaceId = optionalId(request.getWorkspaceId());
        permissions.require(projectId, workspaceId, userId, true);
        Scene found = find(projectId, workspaceId, request.getSceneId());
        if (found == null) throw new DirectorException("RESOURCE_UNAVAILABLE", 404, "场景不可用");
        DirectorIds.key(request.getRequestId());
        String hash = DirectorIds.requestHash(request);
        return transactions.execute(status -> {
            permissions.require(found.getProjectId(), found.getWorkspaceId(), userId, true);
            Scene scene = scenes.lock(found.getId());
            if (scene.getDeleted() != 0) {
                if (request.getRequestId().equals(scene.getLastRequestId()) && hash.equals(scene.getLastRequestHash()))
                    return JSONObject.of("deleted", true, "revision", scene.getRevision());
                throw DirectorException.conflict("SCENE_DELETED");
            }
            if (!scene.getRevision().equals(request.getExpectedRevision())) throw DirectorException.conflict("REVISION_CONFLICT");
            for (Camera camera : cameraList(scene.getId())) {
                camera.setDeleted(1); camera.setControlFence(camera.getControlFence() + 1); touch(camera, userId);
                cameras.updateById(camera);
            }
            scene.setDeleted(1); scene.setRevision(scene.getRevision() + 1);
            scene.setLastRequestId(request.getRequestId()); scene.setLastRequestHash(hash);
            touch(scene, userId); scenes.updateById(scene);
            return JSONObject.of("deleted", true, "revision", scene.getRevision());
        });
    }

    public Camera claim(Scene scene, String cameraKey, String roomId, String epoch, long expiresAt, Long userId) {
        return transactions.execute(status -> {
            permissions.require(scene.getProjectId(), scene.getWorkspaceId(), userId, true);
            Scene currentScene = scenes.lock(scene.getId());
            if (currentScene == null || currentScene.getDeleted() != 0) throw DirectorException.denied();
            Camera camera = camera(scene.getId(), cameraKey);
            camera = cameras.lock(camera.getId());
            if (Boolean.TRUE.equals(camera.getLocked())) throw DirectorException.conflict("CAMERA_LOCKED");
            requireIdle(camera.getId());
            if (active(camera) && !roomId.equals(camera.getOwnerRoomId())) throw DirectorException.conflict("CAMERA_BUSY");
            camera.setControlFence(camera.getControlFence() + 1); camera.setOwnerRoomId(roomId);
            camera.setOwnerRoomEpoch(epoch); camera.setOwnerExpiresAt(new Date(expiresAt)); touch(camera, userId);
            cameras.updateById(camera); return camera;
        });
    }

    public void release(Long cameraId, String roomId, String epoch, Long userId) {
        transactions.executeWithoutResult(status -> {
            Camera camera = cameras.lock(cameraId);
            if (camera == null || !Objects.equals(camera.getOwnerRoomId(), roomId)
                    || !Objects.equals(camera.getOwnerRoomEpoch(), epoch)) return;
            camera.setOwnerRoomId(null); camera.setOwnerRoomEpoch(null); camera.setOwnerExpiresAt(null);
            takes.update(null, Wrappers.<Take>lambdaUpdate().eq(Take::getCameraRowId, cameraId)
                    .eq(Take::getRoomId, roomId).in(Take::getState, List.of("PREPARING", "RECORDING", "STOPPING"))
                    .set(Take::getState, "INTERRUPTED").set(Take::getHostStopped, true).set(Take::getUpdateTime, new Date()));
            camera.setControlFence(camera.getControlFence() + 1); touch(camera, userId);
            cameras.update(null, Wrappers.<Camera>lambdaUpdate().eq(Camera::getId, cameraId)
                    .set(Camera::getOwnerRoomId, null).set(Camera::getOwnerRoomEpoch, null)
                    .set(Camera::getOwnerExpiresAt, null).set(Camera::getControlFence, camera.getControlFence())
                    .set(Camera::getUpdateTime, camera.getUpdateTime()).set(Camera::getUpdateBy, String.valueOf(userId)));
        });
    }

    public Camera camera(Long sceneId, String cameraKey) {
        Camera camera = cameras.selectOne(Wrappers.<Camera>lambdaQuery().eq(Camera::getSceneId, sceneId)
                .eq(Camera::getCameraKey, DirectorIds.key(cameraKey)).eq(Camera::getDeleted, 0));
        if (camera == null) throw new DirectorException("CAMERA_UNAVAILABLE", 404, "机位不可用");
        return camera;
    }

    public void assertFence(Camera camera, Long fence, String room, String epoch) {
        if (!active(camera) || Boolean.TRUE.equals(camera.getLocked()) || !Objects.equals(camera.getControlFence(), fence)
                || !Objects.equals(camera.getOwnerRoomId(), room) || !Objects.equals(camera.getOwnerRoomEpoch(), epoch))
            throw DirectorException.conflict("FENCE_EXPIRED");
    }

    public List<Camera> cameraList(Long sceneId) {
        return cameras.selectList(Wrappers.<Camera>lambdaQuery().eq(Camera::getSceneId, sceneId).orderByAsc(Camera::getId));
    }

    public void requireIdle(Long cameraId) {
        takes.update(null, Wrappers.<Take>lambdaUpdate().eq(Take::getCameraRowId, cameraId)
                .in(Take::getState, List.of("PREPARING", "RECORDING", "STOPPING"))
                .lt(Take::getLastActivityAt, new Date(System.currentTimeMillis() - 45000))
                .set(Take::getState, "INTERRUPTED").set(Take::getHostStopped, true).set(Take::getUpdateTime, new Date()));
        if (takes.selectCount(Wrappers.<Take>lambdaQuery().eq(Take::getCameraRowId, cameraId)
                .in(Take::getState, List.of("PREPARING", "RECORDING", "STOPPING"))) > 0)
            throw DirectorException.conflict("RECORDING_BUSY");
    }

    public JSONObject content(Scene scene) { return JSON.parseObject(objects.text(scene.getObjectKey(), 2 * 1024 * 1024)); }

    public JSONObject view(Scene scene, boolean includeContent) {
        JSONObject result = JSONObject.of("sceneId", scene.getSceneKey(), "revision", scene.getRevision(), "name", scene.getName(),
                "contentHash", scene.getContentHash());
        JSONArray keys = new JSONArray();
        for (Camera camera : cameraList(scene.getId())) if (camera.getDeleted() == 0) keys.add(camera.getCameraKey());
        result.put("cameraIds", keys);
        if (includeContent) result.put("scene", content(scene));
        return result;
    }

    public void validateScene(JSONObject scene) {
        if (!SCENE_FIELDS.containsAll(scene.keySet())) throw DirectorException.invalid();
        canonical(scene, 0);
        cameraDefinitions(scene);
        for (String field : List.of("objects", "lights", "modelAssets")) {
            JSONArray rows = scene.getJSONArray(field);
            if (rows != null && rows.size() > (field.equals("objects") ? 1000 : 100)) throw DirectorException.invalid();
        }
    }

    private Map<String, JSONObject> cameraDefinitions(JSONObject scene) {
        Map<String, JSONObject> result = new TreeMap<>();
        JSONObject main = scene.getJSONObject("camera"); validateCamera(main);
        result.put("main-camera", JSONObject.of("camera", main, "lens", scene.get("mainCameraLens"), "locked", scene.getBooleanValue("mainCameraLocked")));
        JSONArray bookmarks = scene.getJSONArray("cameraBookmarks");
        if (bookmarks != null) for (Object raw : bookmarks) {
            if (!(raw instanceof JSONObject item)) throw DirectorException.invalid();
            String key = DirectorIds.key(item.getString("id"));
            JSONObject camera = item.getJSONObject("camera"); validateCamera(camera);
            if (result.putIfAbsent(key, JSONObject.of("camera", camera, "lens", item.get("lens"), "locked", item.getBooleanValue("locked"))) != null)
                throw DirectorException.invalid();
        }
        if (result.size() > 32) throw DirectorException.invalid();
        return result;
    }

    private JSONObject cameraDefinition(JSONObject scene, String key) { return scene == null ? null : cameraDefinitions(scene).get(key); }

    private void validateTakeReferences(JSONObject value, Long sceneId) {
        JSONObject timeline = value.getJSONObject("timeline");
        if (timeline == null) return;
        JSONArray clips = timeline.getJSONArray("pathClips");
        if (clips == null) return;
        if (clips.size() > 500) throw DirectorException.invalid();
        Set<String> ids = new TreeSet<>();
        Set<Long> takeIds = new TreeSet<>();
        for (Object raw : clips) {
            if (!(raw instanceof JSONObject clip)) throw DirectorException.invalid();
            if (!ids.add(DirectorIds.key(clip.getString("id")))) throw DirectorException.invalid();
            number(clip.get("start"), 0, 86400); number(clip.get("end"), 0, 87000);
            if (clip.getDoubleValue("start") >= clip.getDoubleValue("end")) throw DirectorException.invalid();
            if (clip.containsKey("takeId")) {
                Long takeId = DirectorIds.id(clip.getString("takeId"));
                if (!takeIds.add(takeId) || !("take_" + takeId).equals(clip.getString("id"))) throw DirectorException.invalid();
            }
        }
        if (!takeIds.isEmpty() && takes.selectCount(Wrappers.<Take>lambdaQuery().in(Take::getId, takeIds)
                .eq(Take::getSceneId, sceneId).eq(Take::getState, "APPLIED")) != takeIds.size()) throw DirectorException.denied();
    }

    public static void validateCamera(JSONObject camera) {
        if (camera == null || !Set.of("position", "target", "fov", "roll").containsAll(camera.keySet())) throw DirectorException.invalid();
        for (String key : List.of("position", "target")) {
            JSONArray vector = camera.getJSONArray(key);
            if (vector == null || vector.size() != 3) throw DirectorException.invalid();
            for (Object value : vector) number(value, -1_000_000, 1_000_000);
        }
        number(camera.get("fov"), 1, 179);
        if (camera.containsKey("roll")) number(camera.get("roll"), -360, 360);
    }

    public static Object canonical(Object value, int depth) {
        if (depth > 20) throw DirectorException.invalid();
        if (value instanceof Map<?, ?> map) {
            if (map.size() > 2000) throw DirectorException.invalid();
            Map<String, Object> result = new TreeMap<>();
            for (var entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (key.length() > 100 || Set.of("__proto__", "prototype", "constructor").contains(key)
                        || key.toLowerCase(java.util.Locale.ROOT).matches(".*(token|password|secret|prompt|base64|url).*"))
                    throw DirectorException.invalid();
                result.put(key, canonical(entry.getValue(), depth + 1));
            }
            return result;
        }
        if (value instanceof List<?> list) {
            if (list.size() > 18000) throw DirectorException.invalid();
            return list.stream().map(item -> canonical(item, depth + 1)).toList();
        }
        if (value instanceof String text) {
            if (text.length() > 2048 || text.matches("(?is).*(https?://|data:|blob:|file:).*")) throw DirectorException.invalid();
            return text;
        }
        if (value instanceof Number number) { if (!Double.isFinite(number.doubleValue())) throw DirectorException.invalid(); return value; }
        if (value == null || value instanceof Boolean) return value;
        throw DirectorException.invalid();
    }

    public static void number(Object value, double min, double max) {
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() < min || n.doubleValue() > max)
            throw DirectorException.invalid();
    }

    public static boolean active(Camera camera) {
        return camera != null && camera.getDeleted() == 0 && camera.getOwnerRoomId() != null
                && camera.getOwnerExpiresAt() != null && camera.getOwnerExpiresAt().getTime() > System.currentTimeMillis();
    }

    public static String scopeKey(Long workspaceId) { return workspaceId == null ? "project" : "workspace:" + workspaceId; }
    public static Long optionalId(String id) { return id == null || id.isBlank() ? null : DirectorIds.id(id); }
    public static void init(Audit row, Long user) { row.setId(IdWorker.getId()); row.setCreateTime(new Date()); row.setCreateBy(String.valueOf(user)); touch(row, user); }
    public static void touch(Audit row, Long user) { row.setUpdateTime(new Date()); row.setUpdateBy(String.valueOf(user)); }
}
