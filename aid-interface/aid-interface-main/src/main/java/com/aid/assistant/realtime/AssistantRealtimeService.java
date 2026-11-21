package com.aid.assistant.realtime;

import com.aid.assistant.dto.AssistantRealtimeRequests;
import com.aid.assistant.vo.AssistantRealtimeViews;

import java.util.function.Consumer;

/** Manages authorized assistant realtime connections. */
public interface AssistantRealtimeService
{
    AssistantRealtimeViews.Ticket createTicket(Long userId, AssistantRealtimeRequests.Ticket request);

    ConnectionView consumeTicket(String ticket, String clientIp, Consumer<String> outbound);
    default ConnectionView consumeTicket(String ticket, String clientIp, Consumer<String> outbound, String endpointPath) {
        if (!"/ws/canvas/assistant".equals(endpointPath)) throw new IllegalArgumentException("Realtime endpoint unavailable");
        return consumeTicket(ticket, clientIp, outbound);
    }

    HeartbeatView heartbeat(String connectionId, Long acknowledgedSeq);

    void closeConnection(String connectionId, String reason);

    record ConnectionView(String connectionId, Long sessionId, Long userId, long afterSeq,
                          long lastEventSeq) { }

    record HeartbeatView(Long sessionId, long lastEventSeq) { }
}
