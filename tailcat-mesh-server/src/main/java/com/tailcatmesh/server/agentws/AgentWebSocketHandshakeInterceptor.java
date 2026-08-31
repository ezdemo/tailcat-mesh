package com.tailcatmesh.server.agentws;

import com.tailcatmesh.server.auth.AdminAuthenticationFilter;
import com.tailcatmesh.server.common.ControlPlaneException;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Authenticates the WebSocket upgrade using the same Agent bearer credential as REST. */
@Component
public final class AgentWebSocketHandshakeInterceptor implements HandshakeInterceptor {

    public static final String PRINCIPAL_ATTRIBUTE = AgentAuthenticationFilter.PRINCIPAL_ATTRIBUTE;

    private final AgentAuthenticationService authenticationService;

    public AgentWebSocketHandshakeInterceptor(AgentAuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        try {
            AgentPrincipal principal = authenticationService.authenticate(
                    AdminAuthenticationFilter.bearerToken(request.getHeaders().getFirst("Authorization")));
            attributes.put(PRINCIPAL_ATTRIBUTE, principal);
            return true;
        } catch (ControlPlaneException exception) {
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // No raw credential is retained in the WebSocket session.
    }
}
