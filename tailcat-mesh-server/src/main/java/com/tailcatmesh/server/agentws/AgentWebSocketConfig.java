package com.tailcatmesh.server.agentws;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** Registers the authenticated Agent control WebSocket endpoint. */
@Configuration(proxyBeanMethods = false)
@EnableWebSocket
public final class AgentWebSocketConfig implements WebSocketConfigurer {

    private final AgentWebSocketHandler handler;
    private final AgentWebSocketHandshakeInterceptor interceptor;

    public AgentWebSocketConfig(AgentWebSocketHandler handler,
                                AgentWebSocketHandshakeInterceptor interceptor) {
        this.handler = handler;
        this.interceptor = interceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/api/v1/agent/ws")
                .addInterceptors(interceptor);
    }
}
