package com.tailcatmesh.agent.bootstrap;

import com.tailcatmesh.agent.config.AgentConfig;
import com.tailcatmesh.agent.control.AgentControlClient;
import com.tailcatmesh.agent.identity.AgentStateStore;
import com.tailcatmesh.agent.service.TcpServiceBridge;
import com.tailcatmesh.agent.tailcat.TailcatEngine;
import com.tailcatmesh.agent.tailcat.model.ManagedProcess;
import com.tailcatmesh.agent.tailcat.model.ProcessState;
import com.tailcatmesh.agent.tailcat.model.TailcatIdentity;
import com.tailcatmesh.agent.tailcat.model.TailcatIdentityConfig;
import com.tailcatmesh.agent.tailcat.model.TailcatPeerProxyConfig;
import com.tailcatmesh.agent.tailcat.model.TailcatPeerProxyHandle;
import com.tailcatmesh.agent.tailcat.model.TailcatPingResult;
import com.tailcatmesh.agent.tailcat.model.TailcatPathType;
import com.tailcatmesh.agent.tailcat.model.TailcatRuntimeStatus;
import com.tailcatmesh.agent.tailcat.model.TailcatServerConfig;
import com.tailcatmesh.agent.tailcat.model.TailcatServerHandle;
import com.tailcatmesh.agent.tailcat.model.TailcatTokenInfo;
import com.tailcatmesh.agent.tailcat.model.TailcatVersion;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M6 Agent acceptance test for desired-state Local Forward lifecycle. */
class AgentRuntimeForwardTest {

    private static final String PEER_BLOB = "tcPeerBlobForM6_123456789";

    @TempDir
    Path temporaryDirectory;

    @Test
    void startsFixedLocalListenerReportsReadyAndStopsIt() throws Exception {
        UUID deviceId = UUID.randomUUID();
        UUID peerDeviceId = UUID.randomUUID();
        UUID forwardId = UUID.randomUUID();
        int localPort = unusedLoopbackPort();
        List<String> heartbeats = new CopyOnWriteArrayList<>();
        List<String> forwardReports = new CopyOnWriteArrayList<>();
        RecordingTailcatEngine engine = new RecordingTailcatEngine();
        HttpServer controlPlane = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        installControlPlane(controlPlane, deviceId, peerDeviceId, forwardId, localPort,
                heartbeats, forwardReports);
        controlPlane.start();

        Path dataDir = temporaryDirectory.resolve("agent-data");
        AgentConfig config = new AgentConfig(
                URI.create("http://127.0.0.1:" + controlPlane.getAddress().getPort()),
                temporaryDirectory.resolve("tailcat.exe"), dataDir,
                dataDir.resolve("identity/server.private.json"),
                dataDir.resolve("identity/client.private.json"), true, null,
                Duration.ofSeconds(1), Duration.ofSeconds(1));
        AgentRuntime runtime = new AgentRuntime(
                config, engine, new AgentControlClient(config, Duration.ofSeconds(5)),
                new AgentStateStore(dataDir), new TcpServiceBridge(), "0.1.0");
        try {
            runtime.start("tm_enroll_m6_test", new PrintWriter(new StringWriter(), true));

            assertTrue(waitFor(() -> heartbeats.stream().anyMatch(body -> body.contains("\"forwardsUp\":1")),
                    Duration.ofSeconds(5)), "Agent did not report one ready Local Forward: " + heartbeats);
            assertTrue(waitFor(() -> forwardReports.stream().anyMatch(body ->
                    body.contains("\"status\":\"READY\"")), Duration.ofSeconds(5)),
                    "Agent did not report Local Forward READY: " + forwardReports);
            try (java.net.Socket listenerProbe = new java.net.Socket("127.0.0.1", localPort)) {
                assertTrue(listenerProbe.isConnected());
            }
        } finally {
            runtime.close();
            controlPlane.stop(0);
        }

        assertTrue(forwardReports.stream().anyMatch(body -> body.contains("\"status\":\"STOPPED\"")),
                "Agent did not report Local Forward STOPPED: " + forwardReports);
        assertTrue(engine.stoppedPeers);
    }

    private static void installControlPlane(HttpServer server, UUID deviceId, UUID peerDeviceId,
                                             UUID forwardId, int localPort, List<String> heartbeats,
                                             List<String> forwardReports) {
        server.createContext("/api/v1/agent/enroll", exchange -> {
            drain(exchange);
            send(exchange, 200, "{\"deviceId\":\"" + deviceId
                    + "\",\"agentCredential\":\"tm_agent_m6_test\",\"status\":\"ONLINE\"}");
        });
        server.createContext("/api/v1/agent/desired-state", exchange -> {
            drain(exchange);
            send(exchange, 200, "{\"deviceId\":\"" + deviceId + "\",\"revision\":1,"
                    + "\"allowedClientPublicKeys\":[],\"services\":[],\"peers\":[{"
                    + "\"peerDeviceId\":\"" + peerDeviceId + "\",\"name\":\"M6 peer\","
                    + "\"connBlob\":\"" + PEER_BLOB + "\"}],\"forwards\":[{"
                    + "\"forwardId\":\"" + forwardId + "\",\"name\":\"M6 forward\","
                    + "\"peerDeviceId\":\"" + peerDeviceId + "\",\"remoteServiceId\":\""
                    + UUID.randomUUID() + "\",\"localBindHost\":\"127.0.0.1\","
                    + "\"localBindPort\":" + localPort + ",\"remoteBridgePort\":45123,"
                    + "\"enabled\":true}],\"derp\":{},\"settings\":{}}");
        });
        server.createContext("/api/v1/agent/runtime/server", exchange -> {
            drain(exchange);
            send(exchange, 204, "");
        });
        server.createContext("/api/v1/agent/runtime/services", exchange -> {
            drain(exchange);
            send(exchange, 204, "");
        });
        server.createContext("/api/v1/agent/runtime/peers", exchange -> {
            drain(exchange);
            send(exchange, 204, "");
        });
        server.createContext("/api/v1/agent/runtime/forwards", exchange -> {
            forwardReports.add(drain(exchange));
            send(exchange, 204, "");
        });
        server.createContext("/api/v1/agent/heartbeat", exchange -> {
            heartbeats.add(drain(exchange));
            send(exchange, 200, "{\"deviceId\":\"" + deviceId
                    + "\",\"status\":\"ONLINE\",\"desiredRevision\":1,\"accepted\":true}");
        });
        server.createContext("/api/v1/agent/ws", exchange -> send(exchange, 404, ""));
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

    private static int unusedLoopbackPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
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

        private final FakeManagedProcess serverProcess = new FakeManagedProcess();
        private final FakeManagedProcess peerProcess = new FakeManagedProcess();
        private volatile boolean stoppedPeers;

        @Override
        public TailcatVersion getVersion() {
            return new TailcatVersion(0, 3, 0, "tailcat v0.3.0");
        }

        @Override
        public TailcatIdentity ensureIdentity(TailcatIdentityConfig config) {
            return new TailcatIdentity(config.serverKeyPath(), config.clientKeyPath(), "nodekey:" + "f".repeat(64));
        }

        @Override
        public TailcatServerHandle startServer(TailcatServerConfig config) {
            serverProcess.state = ProcessState.RUNNING;
            return new TailcatServerHandle(serverProcess, "tc_m6_runtime", Instant.now());
        }

        @Override
        public void stopServer() {
            serverProcess.state = ProcessState.STOPPED;
        }

        @Override
        public void restartServer(TailcatServerConfig config) {
            stopServer();
            startServer(config);
        }

        @Override
        public TailcatPeerProxyHandle startPeerProxy(UUID peerDeviceId, String connBlob,
                                                      TailcatPeerProxyConfig config) {
            peerProcess.state = ProcessState.RUNNING;
            return new TailcatPeerProxyHandle(peerDeviceId, peerProcess, config.listenHost(),
                    46_101, connBlob, Instant.now());
        }

        @Override
        public void stopPeerProxy(UUID peerDeviceId) {
            stoppedPeers = true;
            peerProcess.state = ProcessState.STOPPED;
        }

        @Override
        public TailcatPingResult ping(String connBlob, Duration timeout) {
            return new TailcatPingResult(TailcatPathType.DERP, 1, "test", null, "pong");
        }

        @Override
        public TailcatTokenInfo parseToken(String connBlob) {
            return null;
        }

        @Override
        public TailcatRuntimeStatus getRuntimeStatus() {
            return new TailcatRuntimeStatus(serverProcess.state,
                    serverProcess.state == ProcessState.RUNNING ? "tc_m6_runtime" : null,
                    null, "", 0);
        }

        @Override
        public void shutdown() {
            serverProcess.state = ProcessState.STOPPED;
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
