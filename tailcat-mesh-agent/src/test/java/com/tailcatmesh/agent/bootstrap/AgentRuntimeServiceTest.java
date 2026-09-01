package com.tailcatmesh.agent.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tailcatmesh.agent.config.AgentConfig;
import com.tailcatmesh.agent.control.AgentControlClient;
import com.tailcatmesh.agent.identity.AgentStateStore;
import com.tailcatmesh.agent.service.ServiceBridgeHandle;
import com.tailcatmesh.agent.service.TcpServiceBridge;
import com.tailcatmesh.agent.tailcat.TailcatEngine;
import com.tailcatmesh.agent.tailcat.model.ManagedProcess;
import com.tailcatmesh.agent.tailcat.model.ProcessState;
import com.tailcatmesh.agent.tailcat.model.TailcatIdentity;
import com.tailcatmesh.agent.tailcat.model.TailcatIdentityConfig;
import com.tailcatmesh.agent.tailcat.model.TailcatPeerProxyConfig;
import com.tailcatmesh.agent.tailcat.model.TailcatPeerProxyHandle;
import com.tailcatmesh.agent.tailcat.model.TailcatPingResult;
import com.tailcatmesh.agent.tailcat.model.TailcatRuntimeStatus;
import com.tailcatmesh.agent.tailcat.model.TailcatServerConfig;
import com.tailcatmesh.agent.tailcat.model.TailcatServerHandle;
import com.tailcatmesh.agent.tailcat.model.TailcatTokenInfo;
import com.tailcatmesh.agent.tailcat.model.TailcatVersion;
import com.tailcatmesh.protocol.agent.AgentServiceRuntimeReport;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M4 acceptance test for Agent desired-state ServiceBridge reconciliation. */
class AgentRuntimeServiceTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    @Test
    void startsBridgeUsesDynamicPortForTailcatAndReportsRuntime() throws Exception {
        UUID deviceId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();
        List<String> serviceReports = new CopyOnWriteArrayList<>();
        AtomicReference<TailcatServerConfig> serverConfig = new AtomicReference<>();

        try (ServerSocket upstream = new ServerSocket(0)) {
            HttpServer controlPlane = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            try {
            Thread upstreamThread = Thread.ofVirtual().start(() -> serveOnce(upstream));
            installControlPlane(controlPlane, deviceId, serviceId, upstream.getLocalPort(), serviceReports);
            controlPlane.start();

            Path dataDir = temporaryDirectory.resolve("agent-data");
            AgentConfig config = new AgentConfig(
                    URI.create("http://127.0.0.1:" + controlPlane.getAddress().getPort()),
                    temporaryDirectory.resolve("tailcat.exe"),
                    dataDir,
                    dataDir.resolve("identity/server.private.json"),
                    dataDir.resolve("identity/client.private.json"),
                    true,
                    null,
                    Duration.ofSeconds(5),
                    Duration.ofSeconds(5));
            RecordingTailcatEngine engine = new RecordingTailcatEngine(serverConfig);
            AgentRuntime runtime = new AgentRuntime(
                    config,
                    engine,
                    new AgentControlClient(config, Duration.ofSeconds(5)),
                    new AgentStateStore(dataDir),
                    new TcpServiceBridge(),
                    "0.1.0");
            try {
                runtime.start("tm_enroll_m4_test", new PrintWriter(new StringWriter(), true));

                assertTrue(waitFor(() -> serviceReports.stream()
                        .anyMatch(body -> body.contains("\"status\":\"READY\"")),
                        Duration.ofSeconds(5)),
                        "Agent did not report a READY ServiceBridge: " + serviceReports);
                String readyReportJson = serviceReports.stream()
                        .filter(body -> body.contains("\"status\":\"READY\""))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("Agent did not report a READY ServiceBridge"));
                JsonNode readyReport = OBJECT_MAPPER.readTree(readyReportJson);
                JsonNode runtimeService = readyReport.path("services").get(0);
                int bridgePort = runtimeService.path("bridgePort").asInt(0);
                assertTrue(bridgePort > 0);
                assertEquals(List.of(bridgePort), serverConfig.get().servedPorts());

                String response;
                try (Socket client = new Socket("127.0.0.1", bridgePort)) {
                    client.getOutputStream().write("m4".getBytes(StandardCharsets.UTF_8));
                    client.getOutputStream().flush();
                    client.shutdownOutput();
                    response = new String(client.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                }
                assertEquals("ok", response);
            } finally {
                runtime.close();
            }
            upstreamThread.join(5_000);
            } finally {
                controlPlane.stop(0);
            }
        }

        assertTrue(serviceReports.stream().anyMatch(body -> body.contains("\"status\":\"STOPPED\"")));
    }

    @Test
    void publishesLocalStatusBeforeSlowDesiredStateReconcile() throws Exception {
        UUID deviceId = UUID.randomUUID();
        CountDownLatch desiredStateRequested = new CountDownLatch(1);
        CountDownLatch releaseDesiredState = new CountDownLatch(1);
        HttpServer controlPlane = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            controlPlane.createContext("/api/v1/agent/enroll", exchange -> {
                drain(exchange);
                send(exchange, 200, "{\"deviceId\":\"" + deviceId
                        + "\",\"agentCredential\":\"tm_startup_test\",\"status\":\"ONLINE\"}");
            });
            controlPlane.createContext("/api/v1/agent/desired-state", exchange -> {
                drain(exchange);
                desiredStateRequested.countDown();
                try {
                    releaseDesiredState.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                send(exchange, 200, "{\"deviceId\":\"" + deviceId
                        + "\",\"revision\":0,\"allowedClientPublicKeys\":[],"
                        + "\"services\":[],\"peers\":[],\"forwards\":[],\"derp\":{},"
                        + "\"settings\":{},\"virtualNetworks\":[]}");
            });
            controlPlane.createContext("/api/v1/agent/heartbeat", exchange -> {
                drain(exchange);
                send(exchange, 200, "{\"deviceId\":\"" + deviceId
                        + "\",\"status\":\"ONLINE\",\"desiredRevision\":0,\"accepted\":true}");
            });
            controlPlane.createContext("/api/v1/agent/ws", exchange -> send(exchange, 404, ""));
            controlPlane.start();

            Path dataDir = temporaryDirectory.resolve("startup-agent-data");
            AgentConfig config = new AgentConfig(
                    URI.create("http://127.0.0.1:" + controlPlane.getAddress().getPort()),
                    temporaryDirectory.resolve("tailcat-startup.exe"),
                    dataDir,
                    dataDir.resolve("identity/server.private.json"),
                    dataDir.resolve("identity/client.private.json"),
                    true,
                    null,
                    Duration.ofSeconds(5),
                    Duration.ofSeconds(5));
            RecordingTailcatEngine engine = new RecordingTailcatEngine(new AtomicReference<>());
            AgentRuntime runtime = new AgentRuntime(
                    config, engine, new AgentControlClient(config, Duration.ofSeconds(5)),
                    new AgentStateStore(dataDir), new TcpServiceBridge(), "0.1.0");
            try {
                long startedAt = System.nanoTime();
                runtime.start("tm_startup_test", new PrintWriter(new StringWriter(), true));
                long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

                assertTrue(elapsedMillis < 1_000,
                        "Agent bootstrap waited for desired state: " + elapsedMillis + "ms");
                assertTrue(desiredStateRequested.await(2, TimeUnit.SECONDS),
                        "background desired-state request was not started");
                assertEquals("RUNNING", runtime.localStatus().agentState());
                assertEquals("RECONNECTING", runtime.localStatus().status());
            } finally {
                releaseDesiredState.countDown();
                runtime.close();
            }
        } finally {
            controlPlane.stop(0);
        }
    }

    private static boolean waitFor(java.util.function.BooleanSupplier condition, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }

    private static void installControlPlane(HttpServer server, UUID deviceId, UUID serviceId,
                                             int upstreamPort, List<String> serviceReports) {
        server.createContext("/api/v1/agent/enroll", exchange -> {
            drain(exchange);
            send(exchange, 200, "{\"deviceId\":\"" + deviceId
                    + "\",\"agentCredential\":\"tm_agent_m4_test\",\"status\":\"ONLINE\"}");
        });
        server.createContext("/api/v1/agent/desired-state", exchange -> {
            drain(exchange);
            send(exchange, 200, "{\"deviceId\":\"" + deviceId + "\",\"revision\":1,"
                    + "\"allowedClientPublicKeys\":[],\"services\":[{\"serviceId\":\"" + serviceId
                    + "\",\"name\":\"m4-http\",\"protocol\":\"TCP\",\"targetHost\":\"127.0.0.1\","
                    + "\"targetPort\":" + upstreamPort
                    + ",\"enabled\":true}],\"forwards\":[],\"derp\":{},\"settings\":{}}");
        });
        server.createContext("/api/v1/agent/runtime/server", exchange -> {
            drain(exchange);
            send(exchange, 204, "");
        });
        server.createContext("/api/v1/agent/runtime/services", exchange -> {
            serviceReports.add(drain(exchange));
            send(exchange, 204, "");
        });
        server.createContext("/api/v1/agent/heartbeat", exchange -> {
            drain(exchange);
            send(exchange, 200, "{\"deviceId\":\"" + deviceId
                    + "\",\"status\":\"ONLINE\",\"desiredRevision\":1,\"accepted\":true}");
        });
        server.createContext("/api/v1/agent/ws", exchange -> send(exchange, 404, ""));
    }

    private static void serveOnce(ServerSocket upstream) {
        try (Socket socket = upstream.accept()) {
            socket.getInputStream().readAllBytes();
            socket.getOutputStream().write("ok".getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
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

    private static final class RecordingTailcatEngine implements TailcatEngine {

        private final AtomicReference<TailcatServerConfig> serverConfig;
        private final FakeManagedProcess process = new FakeManagedProcess();

        private RecordingTailcatEngine(AtomicReference<TailcatServerConfig> serverConfig) {
            this.serverConfig = serverConfig;
        }

        @Override
        public TailcatVersion getVersion() {
            return new TailcatVersion(0, 3, 0, "tailcat v0.3.0");
        }

        @Override
        public TailcatIdentity ensureIdentity(TailcatIdentityConfig config) {
            return new TailcatIdentity(config.serverKeyPath(), config.clientKeyPath(),
                    "nodekey:" + "f".repeat(64));
        }

        @Override
        public TailcatServerHandle startServer(TailcatServerConfig config) {
            this.serverConfig.set(config);
            process.state = ProcessState.RUNNING;
            return new TailcatServerHandle(process, "tc_m4_runtime", Instant.now());
        }

        @Override
        public void stopServer() {
            process.state = ProcessState.STOPPED;
        }

        @Override
        public void restartServer(TailcatServerConfig config) {
            stopServer();
            startServer(config);
        }

        @Override
        public TailcatPeerProxyHandle startPeerProxy(UUID peerDeviceId, String connBlob,
                                                      TailcatPeerProxyConfig config) {
            throw new UnsupportedOperationException("M5 is not part of this test");
        }

        @Override
        public void stopPeerProxy(UUID peerDeviceId) {
        }

        @Override
        public TailcatPingResult ping(String connBlob, Duration timeout) {
            throw new UnsupportedOperationException("M5 is not part of this test");
        }

        @Override
        public TailcatTokenInfo parseToken(String connBlob) {
            throw new UnsupportedOperationException("M5 is not part of this test");
        }

        @Override
        public TailcatRuntimeStatus getRuntimeStatus() {
            return new TailcatRuntimeStatus(process.state, process.state == ProcessState.RUNNING
                    ? "tc_m4_runtime" : null, null, "", 0);
        }

        @Override
        public void shutdown() {
            process.state = ProcessState.STOPPED;
        }
    }

    private static final class FakeManagedProcess implements ManagedProcess {

        private volatile ProcessState state = ProcessState.STOPPED;

        @Override
        public ProcessState state() {
            return state;
        }

        @Override
        public long pid() {
            return 1;
        }

        @Override
        public Instant startedAt() {
            return Instant.now();
        }

        @Override
        public int restartCount() {
            return 0;
        }

        @Override
        public void stop(Duration timeout) {
            state = ProcessState.STOPPED;
        }
    }
}
