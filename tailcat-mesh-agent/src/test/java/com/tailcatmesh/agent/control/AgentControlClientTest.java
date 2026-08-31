package com.tailcatmesh.agent.control;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.tailcatmesh.agent.config.AgentConfig;
import com.tailcatmesh.protocol.agent.AgentEnrollmentResponse;
import com.tailcatmesh.protocol.agent.AgentHeartbeatRequest;
import com.tailcatmesh.protocol.agent.AgentHeartbeatResponse;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentControlClientTest {

    @TempDir
    Path temporaryDirectory;

    private HttpServer server;
    private AgentConfig config;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        config = new AgentConfig(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                temporaryDirectory.resolve("tailcat.exe"),
                temporaryDirectory.resolve("data"),
                temporaryDirectory.resolve("data/server.key"),
                temporaryDirectory.resolve("data/client.key"),
                true,
                null,
                Duration.ofSeconds(15),
                Duration.ofSeconds(30));
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void sendsEnrollmentAndAuthenticatedHeartbeatAsJson() {
        AtomicReference<String> enrollmentBody = new AtomicReference<>();
        AtomicReference<String> heartbeatAuthorization = new AtomicReference<>();
        UUID deviceId = UUID.randomUUID();
        server.createContext("/api/v1/agent/enroll", exchange -> {
            enrollmentBody.set(readBody(exchange));
            send(exchange, 200, "{\"deviceId\":\"" + deviceId
                    + "\",\"agentCredential\":\"tm_agent_test\",\"status\":\"PENDING\"}");
        });
        server.createContext("/api/v1/agent/heartbeat", exchange -> {
            heartbeatAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            send(exchange, 200, "{\"deviceId\":\"" + deviceId
                    + "\",\"status\":\"ONLINE\",\"desiredRevision\":0,\"accepted\":true}");
        });

        AgentControlClient client = new AgentControlClient(config, Duration.ofSeconds(5));
        AgentEnrollmentResponse enrolled = client.enroll(
                "tm_enroll_test", "DESKTOP-A", "windows", "amd64", "0.1.0", "0.3.0",
                "nodekey:" + "a".repeat(64));
        AgentHeartbeatResponse heartbeat = client.heartbeat(
                enrolled.agentCredential(),
                new AgentHeartbeatRequest(
                        "0.1.0", "0.3.0", 0, true, "sha256:test", 0, 0, null));

        assertEquals(deviceId, enrolled.deviceId());
        assertEquals("PENDING", enrolled.status());
        assertEquals("ONLINE", heartbeat.status());
        assertEquals("Bearer tm_agent_test", heartbeatAuthorization.get());
        assertTrue(enrollmentBody.get().contains("\"enrollmentToken\":\"tm_enroll_test\""));
        assertTrue(enrollmentBody.get().contains("\"clientPublicKey\":\"nodekey:"));
    }

    @Test
    void mapsControlPlaneErrorPayload() {
        server.createContext("/api/v1/agent/heartbeat", exchange ->
                send(exchange, 401, "{\"code\":\"TM-CTRL-001\",\"message\":\"agent authentication failed\"}"));

        AgentControlException exception = org.junit.jupiter.api.Assertions.assertThrows(
                AgentControlException.class,
                () -> new AgentControlClient(config, Duration.ofSeconds(5)).heartbeat(
                        "tm_agent_bad",
                        new AgentHeartbeatRequest(
                                "0.1.0", "0.3.0", 0, false, null, 0, 0, null)));

        assertEquals("TM-CTRL-001", exception.code());
        assertEquals(401, exception.status());
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (var input = exchange.getRequestBody()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (exchange; var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
