package com.aid.common.realtime;

import com.aid.common.aid.core.service.RealtimeOriginSettings;
import com.aid.common.utils.RealtimeOrigins;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.WebUtils;
import java.util.Map;

/** Origin gate evaluated at every handshake. Ticket and business authorization remain in their existing handlers. */
public final class DynamicOriginHandshakeInterceptor implements HandshakeInterceptor {
    private final RealtimeOriginSettings settings;
    private final String environmentDefault;

    public DynamicOriginHandshakeInterceptor(RealtimeOriginSettings settings, String environmentDefault) {
        this.settings = settings;
        this.environmentDefault = environmentDefault;
        settings.registerDefault(environmentDefault);
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler handler, Map<String, Object> attributes) {
        try {
            String origin = request.getHeaders().getOrigin();
            if (origin == null || WebUtils.isSameOrigin(request)
                    || settings.allowedOrigins(environmentDefault).contains(RealtimeOrigins.normalize(origin))) return true;
            response.setStatusCode(HttpStatus.FORBIDDEN);
        } catch (IllegalArgumentException invalid) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
        } catch (RuntimeException unavailable) {
            response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        }
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler handler, Exception exception) { }
}
