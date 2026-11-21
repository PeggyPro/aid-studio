package com.aid.director;

import com.aid.director.domain.DirectorEntities.Camera;
import com.aid.director.domain.DirectorEntities.Scene;
import com.aid.director.dto.DirectorRequests;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 云端配对、角色绑定与局域网协商。 */
@Service
@RequiredArgsConstructor
public class DirectorSessionService {
    private final DirectorRoomStore store;
    private final DirectorSceneService scenes;
    private final DirectorPermissionGuard permissions;
    private final DirectorProperties properties;
    private final DirectorSignalBus bus;
    private static final Set<String> TERMINAL = Set.of("CLOSED", "EXPIRED", "REVOKED");

    public JSONObject create(DirectorRequests.Create request, Long user) {
        properties.requireEnabled(); store.rate("create:" + user, 10, 60);
        Scene scene = scenes.require(request, user, true);
        String hash = DirectorIds.requestHash(request), roomId = "vc_" + DirectorIds.token();
        String reservation = roomId + ":" + hash;
        String existing = store.reserveRequest(user, request.getRequestId(), reservation, properties.getSessionSeconds());
        if (!reservation.equals(existing)) {
            if (existing == null || !existing.endsWith(":" + hash)) throw DirectorException.conflict("IDEMPOTENCY_CONFLICT");
            JSONObject replay = view(host(existing.substring(0, existing.indexOf(':')), user), true);
            replay.put("pairingRequired", true); return replay;
        }
        DirectorRoom room = new DirectorRoom();
        room.setId(roomId); room.setEpoch(DirectorIds.token()); room.setHostUserId(user);
        room.setProjectId(scene.getProjectId()); room.setWorkspaceId(scene.getWorkspaceId()); room.setSceneRowId(scene.getId());
        room.setSceneKey(scene.getSceneKey()); room.setCameraKey(DirectorIds.key(request.getCameraId()));
        room.setCreatedAt(System.currentTimeMillis()); room.setExpiresAt(room.getCreatedAt() + properties.getSessionSeconds() * 1000L);
        room.setCreateHash(hash);
        String pairing = DirectorIds.token(); room.setPairingHash(DirectorIds.hash(pairing));
        room.setPairingUntil(System.currentTimeMillis() + 120000);
        Camera claimed = null;
        try {
            store.reserveCapacity(room, Math.min(20, Math.max(0, properties.getMaxRoomsPerUser())),
                    Math.min(2000, Math.max(0, properties.getMaxRooms())));
            claimed = scenes.claim(scene, room.getCameraKey(), room.getId(), room.getEpoch(), room.getExpiresAt(), user);
            room.setCameraRowId(claimed.getId()); room.setFence(claimed.getControlFence()); store.create(room);
            JSONObject result = view(room, true); pairing(result, room, pairing); return result;
        } catch (RuntimeException e) {
            if (claimed != null) scenes.release(claimed.getId(), roomId, room.getEpoch(), user);
            store.releaseCapacity(room); store.releaseRequest(user, request.getRequestId(), reservation); throw e;
        }
    }

    public JSONObject join(DirectorRequests.Join request, String clientIp) {
        properties.requireEnabled(); store.rate("join-ip:" + clientIp, 30, 60);
        store.rate("join-room:" + request.getSessionId(), 20, 60);
        return store.mutate(request.getSessionId(), room -> {
            live(room); writable(room);
            long now = System.currentTimeMillis();
            if (room.getGuestId() != null && (room.getGuestConnection() != null || room.getGuestReservedUntil() > now))
                throw new DirectorException("GUEST_FULL", 409, "已有手机连接");
            if (room.getPairingHash() == null || now > room.getPairingUntil()
                    || !room.getPairingHash().equals(DirectorIds.hash(request.getPairingCode())))
                throw new DirectorException("INVALID_PAIRING", 401, "配对码已失效");
            String grant = DirectorIds.token();
            room.setGuestId("gd_" + DirectorIds.token()); room.setGrantHash(DirectorIds.hash(grant));
            room.setGuestReservedUntil(now + 15000); room.setPairingHash(null); room.setVersion(room.getVersion() + 1);
            resetPeer(room);
            JSONObject result = view(room, false); result.put("guestGrant", grant);
            result.put("guestDeviceId", room.getGuestId()); result.put("wsTicket", issueTicket(room, false));
            return result;
        });
    }

    public JSONObject state(String id, Long user, String grant) {
        DirectorRoom room = access(id, user, grant);
        if (user != null && !TERMINAL.contains(room.getState())) { live(room); writable(room); }
        return view(room, user != null && room.getHostUserId().equals(user));
    }

    public JSONObject ticket(String id, Long user, String grant) {
        DirectorRoom room = access(id, user, grant); live(room);
        store.rate("ticket:" + id + ":" + (user == null ? "guest" : "host"), 12, 60);
        if (user != null) writable(room);
        else if (room.getGuestConnection() == null && room.getGuestReservedUntil() < System.currentTimeMillis())
            throw new DirectorException("INVALID_GRANT", 401, "配对凭证已失效");
        return JSONObject.of("wsTicket", issueTicket(room, user != null), "expiresInSeconds", 60,
                "signalingPath", properties.getSignalingPath());
    }

    public JSONObject action(DirectorRequests.Action request, Long user) {
        DirectorIds.key(request.getRequestId());
        return store.mutate(request.getSessionId(), room -> {
            assertHost(room, user); live(room); writable(room);
            String actionHash = DirectorIds.requestHash(request);
            String previous = room.getActions().get(request.getRequestId());
            if (previous != null) {
                if (!actionHash.equals(previous)) throw DirectorException.conflict("IDEMPOTENCY_CONFLICT");
                JSONObject replay = view(room, true); replay.put("pairingRequired", "PAIRING_REFRESH".equals(request.getAction())); return replay;
            }
            if (!Objects.equals(room.getEpoch(), request.getExpectedRoomEpoch()) || room.getVersion() != request.getExpectedStateVersion())
                throw DirectorException.conflict("STALE_STATE");
            scenes.requireIdle(room.getCameraRowId());
            JSONObject result;
            switch (request.getAction()) {
                case "PAIRING_REFRESH" -> {
                    if (room.getGuestConnection() != null) throw DirectorException.conflict("GUEST_FULL");
                    String pairing = DirectorIds.token(); room.setPairingHash(DirectorIds.hash(pairing));
                    room.setPairingUntil(System.currentTimeMillis() + 120000);
                    room.setGuestId(null); room.setGrantHash(null); resetPeer(room);
                    result = view(room, true); pairing(result, room, pairing);
                }
                case "DISCONNECT_GUEST" -> {
                    bus.send(room.getGuestInstance(), room.getGuestConnection(), JSONObject.of("type", "SESSION_CLOSED", "reason", "DISCONNECTED"));
                    room.setGuestConnection(null); room.setGuestInstance(null); room.setGuestId(null); room.setGrantHash(null);
                    resetPeer(room); result = view(room, true);
                }
                case "RESET_CONTROL" -> {
                    Scene scene = scenes.require(room.getProjectId(), room.getWorkspaceId(), room.getSceneKey(), user, true);
                    Camera current = scenes.claim(scene, room.getCameraKey(), room.getId(), room.getEpoch(), room.getExpiresAt(), user);
                    room.setFence(current.getControlFence());
                    room.setBindingEpoch(room.getBindingEpoch() + 1); resetPeer(room); result = view(room, true);
                }
                case "SWITCH_CAMERA" -> {
                    Scene scene = scenes.require(room.getProjectId(), room.getWorkspaceId(), room.getSceneKey(), user, true);
                    Camera next = scenes.claim(scene, DirectorIds.key(request.getCameraId()), room.getId(), room.getEpoch(), room.getExpiresAt(), user);
                    if (!next.getId().equals(room.getCameraRowId())) scenes.release(room.getCameraRowId(), room.getId(), room.getEpoch(), user);
                    room.setCameraRowId(next.getId()); room.setCameraKey(next.getCameraKey()); room.setFence(next.getControlFence());
                    room.setBindingEpoch(room.getBindingEpoch() + 1); resetPeer(room); result = view(room, true);
                }
                default -> throw DirectorException.invalid();
            }
            room.setVersion(room.getVersion() + 1); result.put("stateVersion", room.getVersion());
            room.getActions().put(request.getRequestId(), actionHash);
            if (room.getActions().size() > 256) room.getActions().remove(room.getActions().keySet().iterator().next());
            return result;
        });
    }

    public JSONObject negotiate(DirectorRequests.Negotiate request, Long user) {
        store.rate("negotiate:" + request.getSessionId(), 3, 60);
        DirectorIds.key(request.getRequestId());
        return store.mutate(request.getSessionId(), room -> {
            assertHost(room, user); live(room); writable(room);
            if (request.getRequestId().equals(room.getNegotiationRequestId())) return negotiationView(room);
            scenes.requireIdle(room.getCameraRowId());
            if (room.getHostConnection() == null || room.getGuestConnection() == null) throw DirectorException.conflict("PEER_OFFLINE");
            if (room.getPeerEpoch() != request.getExpectedPeerEpoch()) throw DirectorException.conflict("STALE_EPOCH");
            resetPeer(room); room.setState("NEGOTIATING");
            room.setNegotiationId("ng_" + DirectorIds.token()); room.setPeerNonce(DirectorIds.token());
            room.setNegotiationUntil(System.currentTimeMillis() + 15000); room.setNegotiationRequestId(request.getRequestId());
            room.setVersion(room.getVersion() + 1);
            return negotiationView(room);
        });
    }

    public JSONObject lease(DirectorRequests.Lease request, Long user) {
        return lease(request, user, null);
    }

    public JSONObject lease(DirectorRequests.Lease request, Long user, String expectedConnection) {
        store.rate("lease:" + request.getSessionId(), 8, 10);
        return store.mutate(request.getSessionId(), room -> {
            assertHost(room, user); live(room);
            if (expectedConnection != null && !expectedConnection.equals(room.getHostConnection())) throw DirectorException.denied();
            if (request.getPeerEpoch() != room.getPeerEpoch() || request.getBindingEpoch() != room.getBindingEpoch())
                throw DirectorException.conflict("STALE_EPOCH");
            writable(room);
            if (!Set.of("READY", "NEGOTIATING").contains(room.getState()) || room.getHostConnection() == null
                    || room.getGuestConnection() == null || System.currentTimeMillis() - room.getHostSeenAt() > 30000
                    || System.currentTimeMillis() - room.getGuestSeenAt() > 30000) throw DirectorException.conflict("PEER_OFFLINE");
            if ("NEGOTIATING".equals(room.getState()) && room.getNegotiationUntil() < System.currentTimeMillis())
                throw DirectorException.conflict("RTC_NEGOTIATION_FAILED");
            room.setLeaseSeq(room.getLeaseSeq() + 1);
            room.setControlAuthorized("READY".equals(room.getState()));
            room.setLeaseUntil(Math.min(room.getExpiresAt(), System.currentTimeMillis() + Math.min(5, Math.max(1, properties.getLeaseSeconds())) * 1000L));
            return leaseView(room);
        });
    }

    public JSONObject close(String id, Long user) {
        DirectorRoom room = store.mutate(id, current -> {
            assertHost(current, user);
            if ("CLOSED".equals(current.getState())) return current;
            // 捕获旧端点用于CAS后的关闭通知；终态自身立即解除端点绑定，
            // socket异步清理回调不再推进终态版本。
            bus.both(current, JSONObject.of("type", "SESSION_CLOSED", "state", "CLOSED"));
            current.setState("CLOSED"); current.setGrantHash(null); current.setPairingHash(null);
            current.setHostConnection(null); current.setHostInstance(null);
            current.setGuestConnection(null); current.setGuestInstance(null);
            current.setControlAuthorized(false);
            current.setLeaseUntil(0); current.setVersion(current.getVersion() + 1); return current;
        });
        scenes.release(room.getCameraRowId(), room.getId(), room.getEpoch(), user);
        store.releaseCapacity(room);
        return view(room, true);
    }

    public void announce(String id) {
        DirectorRoom room = store.get(id); bus.both(room, JSONObject.of("type", "SESSION_STATE", "data",
                "NEGOTIATING".equals(room.getState()) ? negotiationView(room) : view(room, false)));
    }

    public DirectorRoom host(String id, Long user) { DirectorRoom room = store.get(id); assertHost(room, user); return room; }
    public DirectorRoom writableHost(String id, Long user) { DirectorRoom room = host(id, user); live(room); writable(room); return room; }

    public void writable(DirectorRoom room) {
        properties.requireEnabled();
        Scene scene = scenes.require(room.getProjectId(), room.getWorkspaceId(), room.getSceneKey(), room.getHostUserId(), true);
        scenes.assertFence(scenes.camera(scene.getId(), room.getCameraKey()), room.getFence(), room.getId(), room.getEpoch());
    }

    public DirectorRoom access(String id, Long user, String grant) {
        properties.requireEnabled(); DirectorRoom room = store.get(id);
        if (user != null) assertHost(room, user);
        else {
            live(room);
            if (grant == null || room.getGrantHash() == null || !room.getGrantHash().equals(DirectorIds.hash(grant)))
                throw new DirectorException("INVALID_GRANT", 401, "配对凭证已失效");
        }
        return room;
    }

    public static void live(DirectorRoom room) {
        if (room.getExpiresAt() <= System.currentTimeMillis()) throw new DirectorException("SESSION_EXPIRED", 410, "连接已到期");
        if (TERMINAL.contains(room.getState())) throw new DirectorException("SESSION_UNAVAILABLE", 404, "会话不可用");
    }

    public static void assertHost(DirectorRoom room, Long user) {
        if (user == null || !user.equals(room.getHostUserId())) throw DirectorException.denied();
    }

    public void resetPeer(DirectorRoom room) {
        room.setPeerEpoch(room.getPeerEpoch() + 1); room.setNegotiationId(null); room.setPeerNonce(null);
        room.setNegotiationRequestId(null); room.setHostReady(false); room.setGuestReady(false);
        room.setOfferReceived(false); room.setAnswerReceived(false); room.setHostCandidates(0); room.setGuestCandidates(0);
        room.setLeaseUntil(0); room.getSignalHashes().clear(); room.setState("WAITING");
        room.setControlAuthorized(false);
    }

    public JSONObject view(DirectorRoom room, boolean host) {
        JSONObject data = JSONObject.of("sessionId", room.getId(), "roomEpoch", room.getEpoch(), "state", room.getState(),
                "stateVersion", room.getVersion(), "peerEpoch", room.getPeerEpoch(), "bindingEpoch", room.getBindingEpoch(),
                "cameraId", room.getCameraKey(), "hostOnline", room.getHostConnection() != null,
                "guestOnline", room.getGuestConnection() != null, "expiresAtMs", room.getExpiresAt(),
                "serverTimeMs", System.currentTimeMillis(), "networkPolicy", "LAN_DIRECT_ONLY",
                "signalingPath", properties.getSignalingPath(), "rtc", JSONObject.of("iceServers", java.util.List.of(),
                        "mediaDelivery", "WEBRTC_DIRECT", "cloudRelayAllowed", false),
                "limits", JSONObject.of("controlHz", 30, "chunkFrames", 150, "maxRecordingSeconds", 600, "authorizationLeaseSeconds", 5));
        if (host) data.put("controlFence", String.valueOf(room.getFence()));
        return data;
    }

    public JSONObject negotiationView(DirectorRoom room) {
        JSONObject data = view(room, false); data.put("negotiationId", room.getNegotiationId());
        data.put("peerBindingNonce", room.getPeerNonce()); data.put("negotiationUntilMs", room.getNegotiationUntil());
        return data;
    }

    public JSONObject leaseView(DirectorRoom room) {
        return JSONObject.of("leaseSeq", room.getLeaseSeq(), "expiresAtMs", room.getLeaseUntil(),
                "serverTimeMs", System.currentTimeMillis(), "roomEpoch", room.getEpoch(), "peerEpoch", room.getPeerEpoch(),
                "bindingEpoch", room.getBindingEpoch(), "permissions", room.isControlAuthorized()
                        ? java.util.List.of("PREVIEW", "CONTROL", "RECORD") : java.util.List.of("PREVIEW"));
    }

    private String issueTicket(DirectorRoom room, boolean host) {
        return store.ticket(JSONObject.of("sessionId", room.getId(), "roomEpoch", room.getEpoch(),
                "role", host ? "HOST" : "GUEST", "hostUserId", String.valueOf(room.getHostUserId()),
                "guestId", host ? "" : room.getGuestId()));
    }

    private void pairing(JSONObject result, DirectorRoom room, String pairing) {
        result.put("pairingCode", pairing); result.put("pairingExpiresAtMs", room.getPairingUntil());
        String entry = properties.getMobileEntryUrl();
        if (entry != null && !entry.isBlank()) {
            URI uri = URI.create(entry);
            if (!"https".equals(uri.getScheme()) || uri.getFragment() != null || uri.getUserInfo() != null)
                throw new IllegalStateException("mobile entry must be trusted HTTPS");
            result.put("pairingUrl", entry + "#s=" + room.getId() + "&t=" + pairing);
        }
    }
}
