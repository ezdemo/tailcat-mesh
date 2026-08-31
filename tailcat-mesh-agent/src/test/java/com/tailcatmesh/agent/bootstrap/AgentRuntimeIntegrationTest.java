package com.tailcatmesh.agent.bootstrap;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.tailcatmesh.agent.config.AgentConfig;
import com.tailcatmesh.agent.control.AgentControlClient;
import com.tailcatmesh.agent.identity.AgentStateStore;
import com.tailcatmesh.agent.tailcat.TailcatBinaryLocator;
import com.tailcatmesh.agent.tailcat.TailcatCliEngine;
import com.tailcatmesh.agent.tailcat.TailcatCliEngineConfig;
import com.tailcatmesh.agent.tailcat.model.ProcessState;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Optional E2E test that composes the Java runtime with official Tailcat v0.3.0. */
class AgentRuntimeIntegrationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void startsReportsAndStopsOfficialTailcatThroughAgentRuntime() throws Exception {
        Path binary = configuredBinary();
        Assumptions.assumeTrue(binary != null && Files.isRegularFile(binary),
                "Set -Dtailcat.binary=<path to official tailcat v0.3.0> to run this integration test");

        UUID deviceId = UUID.randomUUID();
        String agentCredential = "tm_agent_runtime_test";
        StringWriter output = new StringWriter();
        AtomicInteger enrollments = new AtomicInteger();
        AtomicInteger heartbeats = new AtomicInteger();
        AtomicInteger desiredStateRequests = new AtomicInteger();
        AtomicInteger runtimeReports = new AtomicInteger();
        AtomicReference<String> runtimeConnBlob = new AtomicReference<>();
        AtomicReference<String> heartbeatBody = new AtomicReference<>();

        HttpServer controlPlane = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        controlPlane.createContext("/api/v1/agent/enroll", exchange -> {
            enrollments.incrementAndGet();
            drain(exchange);
            send(exchange, 200, "{\"deviceId\":\"" + deviceId
                    + "\",\"agentCredential\":\"" + agentCredential
                    + "\",\"status\":\"PENDING\"}");
        });
        controlPlane.createContext("/api/v1/agent/desired-state", exchange -> {
            desiredStateRequests.incrementAndGet();
            drain(exchange);
            send(exchange, 200, "{\"deviceId\":\"" + deviceId
                    + "\",\"revision\":7,\"allowedClientPublicKeys\":[\"nodekey:"
                    + "b".repeat(64) + "\"],\"services\":[],\"forwards\":[],"
                    + "\"derp\":{},\"settings\":{}}");
        });
        controlPlane.createContext("/api/v1/agent/heartbeat", exchange -> {
            heartbeats.incrementAndGet();
            heartbeatBody.set(drain(exchange));
            send(exchange, 200, "{\"deviceId\":\"" + deviceId
                    + "\",\"status\":\"PENDING\",\"desiredRevision\":7,\"accepted\":true}");
        });
        controlPlane.createContext("/api/v1/agent/runtime/server", exchange -> {
            runtimeReports.incrementAndGet();
            String body = drain(exchange);
            if (body.contains("\"running\":true")) {
                runtimeConnBlob.set(extractJsonString(body, "connBlob"));
            }
            send(exchange, 204, "");
        });
        controlPlane.start();

        Path dataDir = temporaryDirectory.resolve("agent-data");
        AgentConfig agentConfig = new AgentConfig(
                URI.create("http://127.0.0.1:" + controlPlane.getAddress().getPort()),
                binary,
                dataDir,
                dataDir.resolve("identity/server.private.json"),
                dataDir.resolve("identity/client.private.json"),
                true,
                null,
                Duration.ofSeconds(15),
                Duration.ofSeconds(30));
        TailcatCliEngineConfig engineConfig = new TailcatCliEngineConfig(
                binary,
                Files.createDirectories(temporaryDirectory.resolve("work")),
                Map.of("TS_DEBUG_TAILCAT_LOCAL_DERP", "1"),
                Duration.ofSeconds(15),
                Duration.ofSeconds(20),
                false);

        try (TailcatCliEngine engine = new TailcatCliEngine(engineConfig)) {
            AgentRuntime runtime = new AgentRuntime(
                    agentConfig,
                    engine,
                    new AgentControlClient(agentConfig, Duration.ofSeconds(15)),
                    new AgentStateStore(dataDir),
                    "0.1.0");
            try {
                assertEquals(0, runtime.run("tm_enroll_runtime_test", true,
                        new PrintWriter(output, true)));
                assertNotNull(runtime.state().orElse(null));
                assertEquals(deviceId, runtime.state().orElseThrow().deviceId());
            } finally {
                runtime.close();
            }
            assertEquals(ProcessState.STOPPED, engine.getRuntimeStatus().state());
        } finally {
            controlPlane.stop(0);
        }

        assertEquals(1, enrollments.get());
        assertEquals(1, desiredStateRequests.get());
        assertEquals(1, heartbeats.get());
        assertTrue(heartbeatBody.get().contains("\"desiredRevision\":7"));
        assertEquals(2, runtimeReports.get());
        assertNotNull(runtimeConnBlob.get());
        assertTrue(runtimeConnBlob.get().startsWith("tc"));
        assertTrue(output.toString().contains("device=" + deviceId));
        assertTrue(output.toString().contains("hash=sha256:"));
        assertFalse(output.toString().contains(runtimeConnBlob.get()));
    }

    private static Path configuredBinary() {
        String configured = System.getProperty(TailcatBinaryLocator.BINARY_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        String configuredEnvironment = System.getenv(TailcatBinaryLocator.BINARY_ENVIRONMENT);
        if (configuredEnvironment != null && !configuredEnvironment.isBlank()) {
            return Path.of(configuredEnvironment).toAbsolutePath().normalize();
        }
        return TailcatBinaryLocator.locate().orElse(null);
    }

    private static String drain(HttpExchange exchange) throws IOException {
        try (var input = exchange.getRequestBody()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (status != 204) {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
        }
        exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
        try (exchange; var output = exchange.getResponseBody()) {
            if (status != 204) {
                output.write(bytes);
            }
        }
    }

    private static String extractJsonString(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            return null;
        }
        start += marker.length();
        int end = json.indexOf('"', start);
        return end < 0 ? null : json.substring(start, end);
    }
}
