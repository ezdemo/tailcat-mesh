package com.tailcatmesh.agent.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLocalStatusServerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    @Test
    void exposesAuthenticatedStatusNetworksAndReconnectOverLoopback() throws Exception {
        UUID networkId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        LocalAgentStatus expected = new LocalAgentStatus(
                "CONNECTED",
                "CONNECTED",
                UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
                "DESKTOP-M8",
                "https://mesh.example.test",
                "RUNNING",
                ProcessHandle.current().pid(),
                "0.3.0",
                "RUNNING",
                List.of(new LocalNetworkStatus(
                        networkId,
                        "Engineering",
                        "100.64.10.0/24",
                        "100.64.10.2",
                        "UP",
                        null,
                        null)),
                null,
                Instant.now());
        AtomicInteger reconnectCount = new AtomicInteger();

        AgentLocalStatusServer server = new AgentLocalStatusServer(
                temporaryDirectory,
                () -> expected,
                () -> reconnectCount.incrementAndGet(),
                () -> {
                });

        try (server) {
            server.start();
            JsonNode descriptor = objectMapper.readTree(
                    Files.readString(server.descriptorPath()));
            int port = descriptor.path("port").asInt();
            String token = descriptor.path("token").asText();
            assertTrue(port > 0);
            assertTrue(token.length() >= 40);

            HttpClient client = HttpClient.newHttpClient();
            URI base = URI.create("http://127.0.0.1:" + port);

            HttpResponse<String> unauthorized = client.send(
                    request(base.resolve("/local/status"), null).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, unauthorized.statusCode());

            HttpResponse<String> status = client.send(
                    request(base.resolve("/local/status"), token).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, status.statusCode());
            JsonNode statusJson = objectMapper.readTree(status.body());
            assertEquals("CONNECTED", statusJson.path("status").asText());
            assertEquals("DESKTOP-M8", statusJson.path("deviceName").asText());
            assertEquals(networkId.toString(),
                    statusJson.path("networks").get(0).path("networkId").asText());

            HttpResponse<String> networks = client.send(
                    request(base.resolve("/local/networks"), token).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, networks.statusCode());
            assertTrue(objectMapper.readTree(networks.body()).isArray());
            assertEquals("Engineering",
                    objectMapper.readTree(networks.body()).get(0).path("name").asText());

            HttpResponse<String> reconnect = client.send(
                    request(base.resolve("/local/reconnect"), token).POST(
                            HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, reconnect.statusCode());
            assertTrue(objectMapper.readTree(reconnect.body()).path("accepted").asBoolean());
            assertEquals(1, reconnectCount.get());
        }

        assertFalse(Files.exists(server.descriptorPath()));
    }

    private static HttpRequest.Builder request(URI uri, String token) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(5));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder;
    }
}
