package com.aid.director;

import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.Set;

/** 经配对认证的低频信令状态机。 */
@Service
@RequiredArgsConstructor
public class DirectorSignalingService {
    private final DirectorRoomStore store;
    private final DirectorSessionService sessions;
    private final DirectorSignalBus bus;

    public record Endpoint(String sessionId, String role, String connectionId) { }

    public Endpoint connect(String ticket, String connectionId) {
        JSONObject identity = store.consumeTicket(ticket);
        String id = identity.getString("sessionId"), role = identity.getString("role");
        store.mutate(id, room -> {
            DirectorSessionService.live(room);
            if (!room.getEpoch().equals(identity.getString("roomEpoch"))) throw DirectorException.denied();
            if ("HOST".equals(role)) {
                if (!String.valueOf(room.getHostUserId()).equals(identity.getString("hostUserId"))) throw DirectorException.denied();
                sessions.writable(room);
                if (room.getHostConnection() != null || !"WAITING".equals(room.getState())) sessions.resetPeer(room);
                bus.send(room.getHostInstance(), room.getHostConnection(), JSONObject.of("type", "ENDPOINT_REPLACED"));
                room.setHostConnection(connectionId); room.setHostInstance(bus.instanceId()); room.setHostSeenAt(System.currentTimeMillis());
            } else if ("GUEST".equals(role)) {
                if (room.getGuestId() == null || !room.getGuestId().equals(identity.getString("guestId")) || room.getGrantHash() == null)
                    throw DirectorException.denied();
                if (room.getGuestConnection() == null && room.getGuestReservedUntil() < System.currentTimeMillis())
                    throw DirectorException.denied();
                if (room.getGuestConnection() != null || !"WAITING".equals(room.getState())) sessions.resetPeer(room);
                bus.send(room.getGuestInstance(), room.getGuestConnection(), JSONObject.of("type", "ENDPOINT_REPLACED"));
                room.setGuestConnection(connectionId); room.setGuestInstance(bus.instanceId()); room.setGuestSeenAt(System.currentTimeMillis());
                room.setGuestReservedUntil(room.getExpiresAt());
            } else throw DirectorException.denied();
            room.setVersion(room.getVersion() + 1);
            return null;
        });
        sessions.announce(id);
        return new Endpoint(id, role, connectionId);
    }

    public JSONObject connected(Endpoint endpoint) {
        return JSONObject.of("type", "CONNECTED", "role", endpoint.role(),
                "data", sessions.view(store.get(endpoint.sessionId()), "HOST".equals(endpoint.role())));
    }

    public void receive(Endpoint endpoint, JSONObject message) {
        store.rate("signal:" + endpoint.connectionId(), 30, 2);
        if (!Set.of("protocolVersion", "type", "messageId", "sessionId", "roomEpoch", "peerEpoch", "negotiationId", "payload").containsAll(message.keySet()))
            throw DirectorException.invalid();
        String type = message.getString("type");
        if (type == null) throw DirectorException.invalid();
        if ("LEASE_RENEW".equals(type)) {
            if (!"HOST".equals(endpoint.role())) throw DirectorException.denied();
            DirectorRoom current = store.get(endpoint.sessionId()); verifyEndpoint(current, endpoint);
            JSONObject payload = payload(message);
            com.aid.director.dto.DirectorRequests.Lease request = new com.aid.director.dto.DirectorRequests.Lease();
            request.setSessionId(endpoint.sessionId()); request.setPeerEpoch(payload.getLong("peerEpoch"));
            request.setBindingEpoch(payload.getLong("bindingEpoch"));
            if (request.getPeerEpoch() == null || request.getBindingEpoch() == null) throw DirectorException.invalid();
            JSONObject lease = sessions.lease(request, current.getHostUserId(), endpoint.connectionId());
            bus.send(current.getHostInstance(), current.getHostConnection(), JSONObject.of("type", "AUTH_LEASE", "data", lease));
            return;
        }
        boolean changed = store.mutate(endpoint.sessionId(), room -> {
            DirectorSessionService.live(room); verifyEndpoint(room, endpoint);
            if ("HOST".equals(endpoint.role())) room.setHostSeenAt(System.currentTimeMillis()); else room.setGuestSeenAt(System.currentTimeMillis());
            if ("PING".equals(type)) {
                sendSelf(room, endpoint, JSONObject.of("type", "PONG", "serverTimeMs", System.currentTimeMillis())); return false;
            }
            if (!endpoint.sessionId().equals(message.getString("sessionId")) || !room.getEpoch().equals(message.getString("roomEpoch")))
                throw DirectorException.denied();
            if ("NEGOTIATION_REQUEST".equals(type)) {
                if (!"GUEST".equals(endpoint.role())) throw DirectorException.denied();
                if (!payload(message).isEmpty()) throw DirectorException.invalid();
                store.rate("negotiation-request:" + room.getId(), 3, 60); relay(room, endpoint, message); return false;
            }
            if (message.getIntValue("protocolVersion") != 1 || message.getLongValue("peerEpoch") != room.getPeerEpoch()
                    || room.getNegotiationId() == null || !room.getNegotiationId().equals(message.getString("negotiationId")))
                throw DirectorException.conflict("STALE_EPOCH");
            JSONObject payload = payload(message);
            if ("PEER_FAILED".equals(type)) {
                if (!Set.of("reason").containsAll(payload.keySet()) || payload.toJSONString().length() > 100) throw DirectorException.invalid();
                room.setState("SUSPENDED"); room.setLeaseUntil(0); room.setHostReady(false); room.setGuestReady(false);
                room.setVersion(room.getVersion() + 1); relay(room, endpoint, JSONObject.of("type", "PEER_FAILED", "peerEpoch", room.getPeerEpoch()));
                return true;
            }
            if ("PEER_READY".equals(type)) {
                if (!Set.of("videoReady", "channelsReady", "candidateType").equals(payload.keySet())) throw DirectorException.invalid();
                if ("NEGOTIATING".equals(room.getState()) && System.currentTimeMillis() > room.getNegotiationUntil())
                    throw DirectorException.conflict("RTC_NEGOTIATION_FAILED");
                if (!room.isOfferReceived() || !room.isAnswerReceived() || !Boolean.TRUE.equals(payload.getBoolean("videoReady"))
                        || !Boolean.TRUE.equals(payload.getBoolean("channelsReady")) || !"host".equals(payload.getString("candidateType")))
                    throw DirectorException.conflict("VIDEO_NOT_READY");
                if (!Set.of("NEGOTIATING", "READY").contains(room.getState())) throw DirectorException.conflict("STALE_STATE");
                if ("HOST".equals(endpoint.role())) room.setHostReady(true); else room.setGuestReady(true);
                if (room.isHostReady() && room.isGuestReady()) room.setState("READY");
                room.setVersion(room.getVersion() + 1); return true;
            }
            if ("SIGNAL_ACK".equals(type)) {
                if (!Set.of("messageId").equals(payload.keySet())) throw DirectorException.invalid();
                String ackId = DirectorIds.key(payload.getString("messageId"));
                if (!room.getSignalHashes().containsKey(ackId)) throw DirectorException.invalid();
                relay(room, endpoint, message); return false;
            }
            if (!"NEGOTIATING".equals(room.getState()) || System.currentTimeMillis() > room.getNegotiationUntil())
                throw DirectorException.conflict("RTC_NEGOTIATION_FAILED");
            String messageId = DirectorIds.key(message.getString("messageId"));
            String hash = DirectorIds.requestHash(message), previous = room.getSignalHashes().get(messageId);
            if (previous != null && !previous.equals(hash)) throw DirectorException.conflict("IDEMPOTENCY_CONFLICT");
            switch (type) {
                case "RTC_OFFER" -> {
                    if (!"HOST".equals(endpoint.role()) || previous == null && room.isOfferReceived()) throw DirectorException.denied();
                    DirectorRtcPolicy.sdp(payload, true); room.setOfferReceived(true);
                }
                case "RTC_ANSWER" -> {
                    if (!"GUEST".equals(endpoint.role()) || !room.isOfferReceived() || previous == null && room.isAnswerReceived())
                        throw DirectorException.denied();
                    DirectorRtcPolicy.sdp(payload, false); room.setAnswerReceived(true);
                }
                case "ICE_CANDIDATE" -> {
                    DirectorRtcPolicy.candidate(payload);
                    if (previous == null) {
                        if ("HOST".equals(endpoint.role())) room.setHostCandidates(room.getHostCandidates() + 1);
                        else room.setGuestCandidates(room.getGuestCandidates() + 1);
                        if (room.getHostCandidates() > 64 || room.getGuestCandidates() > 64) throw DirectorException.invalid();
                    }
                }
                case "ICE_END" -> { if (!payload.isEmpty()) throw DirectorException.invalid(); }
                default -> throw new DirectorException("INVALID_MESSAGE", 400, "消息类型不允许");
            }
            if (previous == null && room.getSignalHashes().size() >= 132) throw DirectorException.invalid();
            room.getSignalHashes().put(messageId, hash); relay(room, endpoint, message);
            sendSelf(room, endpoint, JSONObject.of("type", "SIGNAL_ACK", "phase", "ACCEPTED", "messageId", messageId));
            return false;
        });
        if (changed) sessions.announce(endpoint.sessionId());
    }

    public void disconnect(Endpoint endpoint) {
        if (endpoint == null) return;
        bus.unregister(endpoint.connectionId());
        store.mutate(endpoint.sessionId(), room -> {
            boolean current = "HOST".equals(endpoint.role()) ? endpoint.connectionId().equals(room.getHostConnection())
                    : endpoint.connectionId().equals(room.getGuestConnection());
            if (!current) return null;
            if ("HOST".equals(endpoint.role())) { room.setHostConnection(null); room.setHostInstance(null); }
            else { room.setGuestConnection(null); room.setGuestInstance(null); }
            if (!Set.of("CLOSED", "REVOKED").contains(room.getState())) room.setState("SUSPENDED");
            room.setLeaseUntil(0); room.setVersion(room.getVersion() + 1); return null;
        });
        sessions.announce(endpoint.sessionId());
    }

    private void verifyEndpoint(DirectorRoom room, Endpoint endpoint) {
        String id = "HOST".equals(endpoint.role()) ? room.getHostConnection() : room.getGuestConnection();
        if (!endpoint.connectionId().equals(id)) throw DirectorException.denied();
    }

    private JSONObject payload(JSONObject message) {
        JSONObject payload = message.getJSONObject("payload");
        if (payload == null) throw DirectorException.invalid(); return payload;
    }

    private void sendSelf(DirectorRoom room, Endpoint endpoint, JSONObject message) {
        if ("HOST".equals(endpoint.role())) bus.send(room.getHostInstance(), endpoint.connectionId(), message);
        else bus.send(room.getGuestInstance(), endpoint.connectionId(), message);
    }

    private void relay(DirectorRoom room, Endpoint sender, JSONObject message) {
        if ("HOST".equals(sender.role())) bus.send(room.getGuestInstance(), room.getGuestConnection(), message);
        else bus.send(room.getHostInstance(), room.getHostConnection(), message);
    }
}
