package com.tailcatmesh.server.agentws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tailcatmesh.protocol.ProtocolEnvelope;
import com.tailcatmesh.server.device.DeviceStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.TextMessage;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentWebSocketHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void sendsInitialDesiredStateEnvelopeAfterAuthenticatedConnection() throws Exception {
        UUID deviceId = UUID.randomUUID();
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(AgentWebSocketHandshakeInterceptor.PRINCIPAL_ATTRIBUTE,
                new AgentPrincipal(UUID.randomUUID(), deviceId, DeviceStatus.OFFLINE));
        when(session.getAttributes()).thenReturn(attributes);
        when(session.isOpen()).thenReturn(true);

        AgentWebSocketHandler handler = new AgentWebSocketHandler();
        handler.afterConnectionEstablished(session);

        ArgumentCaptor<WebSocketMessage<?>> message = ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(session).sendMessage(message.capture());
        JsonNode root = objectMapper.readTree(((TextMessage) message.getValue()).getPayload());
        ProtocolEnvelope envelope = objectMapper.treeToValue(root, ProtocolEnvelope.class);

        assertEquals("SYNC_DESIRED_STATE", envelope.type());
        assertEquals(deviceId.toString(), envelope.payload().path("deviceId").asText());
        assertEquals(0, envelope.payload().path("revision").asLong());
    }

    @Test
    void closesPreviousSessionWhenTheSameDeviceReconnects() throws Exception {
        UUID deviceId = UUID.randomUUID();
        WebSocketSession previous = session(deviceId);
        WebSocketSession current = session(deviceId);
        when(previous.isOpen()).thenReturn(true);
        when(current.isOpen()).thenReturn(true);

        AgentWebSocketHandler handler = new AgentWebSocketHandler();
        handler.afterConnectionEstablished(previous);
        handler.afterConnectionEstablished(current);

        verify(previous).close(CloseStatus.NORMAL);
    }

    private static WebSocketSession session(UUID deviceId) {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(AgentWebSocketHandshakeInterceptor.PRINCIPAL_ATTRIBUTE,
                new AgentPrincipal(UUID.randomUUID(), deviceId, DeviceStatus.OFFLINE));
        when(session.getAttributes()).thenReturn(attributes);
        return session;
    }
}
