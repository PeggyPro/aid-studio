package com.aid.previz;

import org.springframework.web.socket.WebSocketHandler;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.socket.config.annotation.*;

/** Standalone route. Authorization is the existing one-use, session-bound realtime ticket. */
@Configuration
@EnableWebSocket
@ConditionalOnProperty(prefix="aid.previz.runtime",name="enabled",havingValue="true")
public class PrevizRealtimeWebSocketConfig implements WebSocketConfigurer {
    private final WebSocketHandler handler;
    public PrevizRealtimeWebSocketConfig(@Qualifier("assistantRealtimeWebSocketHandler") WebSocketHandler handler) {
        this.handler = handler;
    }
    @org.springframework.beans.factory.annotation.Autowired
    private com.aid.common.aid.core.service.RealtimeOriginSettings realtimeOrigins;

    @Value("${aid.previz.websocket.allowed-origins:}") private String allowedOrigins;
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        var registration=registry.addHandler(handler,"/ws/previz/assistant");

        registration.setAllowedOrigins("*").addInterceptors(new com.aid.common.realtime.DynamicOriginHandshakeInterceptor(realtimeOrigins, allowedOrigins));

    }

    @Bean @Order(5)
    public SecurityFilterChain previzRealtimeSecurity(HttpSecurity http) throws Exception {
        return http.securityMatcher("/ws/previz/assistant")
                .csrf(csrf->csrf.disable())
                .sessionManagement(session->session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests->requests.anyRequest().permitAll()).build();
    }
}
