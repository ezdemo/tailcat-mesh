package com.tailcatmesh.server.agentws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tailcatmesh.protocol.ProtocolEnvelope;
import com.tailcatmesh.protocol.agent.AgentDesiredState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Authenticated Agent WebSocket endpoint for state-change notifications. */
@Component
public final class AgentWebSocketHandler extends TextWebSocketHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentWebSocketHandler.class);

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final Map<UUID, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final AgentDesiredStateService desiredStateService;

    /** Keeps the lightweight unit tests independent of Spring persistence. */
    public AgentWebSocketHandler() {
        this.desiredStateService = null;
    }

    @Autowired
    public AgentWebSocketHandler(AgentDesiredStateService desiredStateService) {
        this.desiredStateService = desiredStateService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        AgentPrincipal principal = principal(session);
        WebSocketSession previous = sessions.put(principal.deviceId(), session);
        if (previous != null && previous.isOpen()) {
            previous.close(CloseStatus.NORMAL);
        }
        sendDesiredState(session, principal.deviceId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        try {
            JsonNode tree = objectMapper.readTree(message.getPayload());
            ProtocolEnvelope envelope = objectMapper.treeToValue(tree, ProtocolEnvelope.class);
            if ("HELLO".equals(envelope.type())) {
                sendDesiredState(session, principal(session).deviceId());
            }
        } catch (IOException | RuntimeException exception) {
            LOGGER.debug("ignored malformed Agent WebSocket message: {}", exception.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        AgentPrincipal principal = principalOrNull(session);
        if (principal != null) {
            sessions.remove(principal.deviceId(), session);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        if (session.isOpen()) {
            session.close(CloseStatus.SERVER_ERROR);
        }
    }

    public void notifyDesiredStateChanged(UUID deviceId, long revision) {
        WebSocketSession session = sessions.get(deviceId);
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            sendDesiredState(session, deviceId);
        } catch (IOException | RuntimeException exception) {
            LOGGER.debug("failed to notify Agent {} over WebSocket", deviceId);
        }
    }

    @EventListener
    public void onDesiredStateChanged(DesiredStateChangedEvent event) {
        if (desiredStateService == null) {
            return;
        }
        if (event.deviceId() != null) {
            AgentDesiredState current = currentState(event.deviceId());
            if (current != null) {
                notifyDesiredStateChanged(event.deviceId(), current.revision());
            }
            return;
        }
        for (UUID deviceId : desiredStateService.deviceIdsInNetwork(event.networkId())) {
            AgentDesiredState current = currentState(deviceId);
            if (current != null) {
                notifyDesiredStateChanged(deviceId, current.revision());
            }
        }
    }

    private void sendDesiredState(WebSocketSession session, UUID deviceId) throws IOException {
        AgentDesiredState desiredState = currentState(deviceId);
        if (desiredState == null) {
            desiredState = new AgentDesiredState(
                    deviceId, 0, java.util.List.of(), java.util.List.of(), java.util.List.of(),
                    java.util.Map.of(), java.util.Map.of());
        }
        JsonNode payload = objectMapper.valueToTree(desiredState);
        ProtocolEnvelope envelope = ProtocolEnvelope.of("SYNC_DESIRED_STATE", payload);
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(envelope)));
    }

    private AgentDesiredState currentState(UUID deviceId) {
        return desiredStateService == null ? null : desiredStateService.get(deviceId);
    }

    private static AgentPrincipal principal(WebSocketSession session) {
        AgentPrincipal principal = principalOrNull(session);
        if (principal == null) {
            throw new IllegalStateException("authenticated Agent principal is missing");
        }
        return principal;
    }

    private static AgentPrincipal principalOrNull(WebSocketSession session) {
        Object value = session.getAttributes().get(AgentWebSocketHandshakeInterceptor.PRINCIPAL_ATTRIBUTE);
        return value instanceof AgentPrincipal principal ? principal : null;
    }
}
