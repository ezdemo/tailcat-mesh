package com.tailcatmesh.server;

import com.tailcatmesh.server.device.DeviceService;
import com.tailcatmesh.server.enrollment.EnrollmentService;
import com.tailcatmesh.protocol.agent.AgentEnrollmentRequest;
import com.tailcatmesh.protocol.agent.AgentEnrollmentResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Live HTTP/WebSocket acceptance test for the authenticated Agent channel. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AgentWebSocketIntegrationTest {

    private static final String CLIENT_PUBLIC_KEY = "nodekey:" + "b".repeat(64);
    private static final String PEER_CLIENT_PUBLIC_KEY = "nodekey:" + "c".repeat(64);

    @LocalServerPort
    private int port;

    @Autowired
    private EnrollmentService enrollmentService;

    @Autowired
    private DeviceService deviceService;

    @Test
    void acceptsBearerCredentialAndSendsInitialDesiredState() throws Exception {
        EnrollmentService.EnrollmentTokenCreated token = enrollmentService.createToken(
                new EnrollmentService.CreateEnrollmentTokenRequest(null, 1, 1));
        AgentEnrollmentResponse enrolled = enrollmentService.enroll(
                new AgentEnrollmentRequest(
                        token.token(), "WS-TEST", "windows", "amd64", "0.1.0", "0.3.0",
                        CLIENT_PUBLIC_KEY));
        EnrollmentService.EnrollmentTokenCreated peerToken = enrollmentService.createToken(
                new EnrollmentService.CreateEnrollmentTokenRequest(null, 1, 1));
        AgentEnrollmentResponse peer = enrollmentService.enroll(
                new AgentEnrollmentRequest(
                        peerToken.token(), "WS-PEER", "windows", "amd64", "0.1.0", "0.3.0",
                        PEER_CLIENT_PUBLIC_KEY));
        deviceService.approve(enrolled.deviceId());
        deviceService.approve(peer.deviceId());

        CompletableFuture<String> firstMessage = new CompletableFuture<>();
        WebSocket.Listener listener = new WebSocket.Listener() {
            private final StringBuilder message = new StringBuilder();

            @Override
            public void onOpen(WebSocket webSocket) {
                webSocket.request(1);
            }

            @Override
            public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                message.append(data);
                if (last) {
                    firstMessage.complete(message.toString());
                    message.setLength(0);
                }
                webSocket.request(1);
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public void onError(WebSocket webSocket, Throwable error) {
                firstMessage.completeExceptionally(error);
            }
        };

        WebSocket socket = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build()
                .newWebSocketBuilder()
                .header("Authorization", "Bearer " + enrolled.agentCredential())
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/api/v1/agent/ws"), listener)
                .get(10, TimeUnit.SECONDS);
        try {
            String message = firstMessage.get(10, TimeUnit.SECONDS);
            assertTrue(message.contains("\"type\":\"SYNC_DESIRED_STATE\""));
            assertTrue(message.contains(enrolled.deviceId().toString()));
            assertTrue(message.contains(PEER_CLIENT_PUBLIC_KEY));
        } finally {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "test complete").get(5, TimeUnit.SECONDS);
        }
    }
}
