package com.aid.director.signaling;

import com.aid.director.DirectorProperties;
import com.aid.director.DirectorRoomStore;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.HttpStatus;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 明确Origin白名单的独立信令入口。 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
@ConditionalOnProperty(name = "director.vcam.enabled", havingValue = "true")
public class DirectorWebSocketConfig implements WebSocketConfigurer {
    private final DirectorSocketHandler handler;
    private final DirectorProperties properties;
    private final DirectorRoomStore store;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        Set<String> origins = Arrays.stream(properties.getAllowedOrigins().split(",")).map(String::trim)
                .filter(value -> !value.isEmpty()).collect(Collectors.toSet());
        registry.addHandler(handler, "/api/user/studio/director/virtual-camera/ws")
                .setAllowedOrigins(origins.toArray(String[]::new)).addInterceptors(new HandshakeInterceptor() {
                    @Override
                    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                                   WebSocketHandler socketHandler, Map<String, Object> attributes) {
                        String origin = request.getHeaders().getOrigin();
                        String query = request.getURI().getRawQuery();
                        if (origin == null || !origins.contains(origin) || query != null && !query.isBlank()) {
                            response.setStatusCode(HttpStatus.FORBIDDEN); return false;
                        }
                        try {
                            var remote = request.getRemoteAddress();
                            String address = com.aid.director.controller.DirectorClientAddress.resolve(
                                    remote == null ? null : remote.getAddress().getHostAddress(), request.getHeaders().getFirst("X-Real-IP"));
                            store.rate("ws-ip:" + address, 500, 60);
                            return true;
                        } catch (RuntimeException error) { response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS); return false; }
                    }
                    @Override
                    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                               WebSocketHandler socketHandler, Exception exception) { }
                });
    }
}
