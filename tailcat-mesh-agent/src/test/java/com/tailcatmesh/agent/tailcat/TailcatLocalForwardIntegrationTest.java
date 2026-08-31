package com.tailcatmesh.agent.tailcat;

import com.tailcatmesh.agent.forward.LocalForwardHandle;
import com.tailcatmesh.agent.forward.LocalForwardManager;
import com.tailcatmesh.agent.forward.PeerSocksEndpoint;
import com.tailcatmesh.protocol.agent.AgentForward;
import com.tailcatmesh.agent.tailcat.model.ProcessState;
import com.tailcatmesh.agent.tailcat.model.TailcatIdentity;
import com.tailcatmesh.agent.tailcat.model.TailcatIdentityConfig;
import com.tailcatmesh.agent.tailcat.model.TailcatPeerProxyConfig;
import com.tailcatmesh.agent.tailcat.model.TailcatPeerProxyHandle;
import com.tailcatmesh.agent.tailcat.model.TailcatPathType;
import com.tailcatmesh.agent.tailcat.model.TailcatServerConfig;
import com.tailcatmesh.agent.tailcat.model.TailcatServerHandle;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M6 acceptance test: a normal local TCP client reaches a remote service through official Tailcat. */
class TailcatLocalForwardIntegrationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void reachesRemoteServiceThroughFixedLocalForwardPort() throws Exception {
        Path binary = configuredBinary();
        Assumptions.assumeTrue(binary != null && Files.isRegularFile(binary),
                "Set -Dtailcat.binary=<path to official tailcat v0.3.0> to run this integration test");

        try (ServerSocket upstream = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             TailcatCliEngine serverEngine = new TailcatCliEngine(new TailcatCliEngineConfig(
                     binary, Files.createDirectories(temporaryDirectory.resolve("server-work")),
                     Map.of("TS_DEBUG_TAILCAT_LOCAL_DERP", "1"), Duration.ofSeconds(15),
                     Duration.ofSeconds(30), false));
             TailcatCliEngine clientEngine = new TailcatCliEngine(new TailcatCliEngineConfig(
                     binary, Files.createDirectories(temporaryDirectory.resolve("client-work")),
                     Map.of("TS_DEBUG_TAILCAT_LOCAL_DERP", "1"), Duration.ofSeconds(15),
                     Duration.ofSeconds(30), false))) {
            AtomicBoolean upstreamAccepted = new AtomicBoolean();
            Thread upstreamThread = Thread.ofVirtual().start(() -> serveOnce(upstream, upstreamAccepted));
            TailcatIdentity serverIdentity = serverEngine.ensureIdentity(new TailcatIdentityConfig(
                    temporaryDirectory.resolve("server-identity/server.private.json"),
                    temporaryDirectory.resolve("server-identity/client.private.json")));
            TailcatIdentity clientIdentity = clientEngine.ensureIdentity(new TailcatIdentityConfig(
                    temporaryDirectory.resolve("client-identity/server.private.json"),
                    temporaryDirectory.resolve("client-identity/client.private.json")));
            TailcatServerHandle server = serverEngine.startServer(new TailcatServerConfig(
                    serverIdentity.serverKeyPath(), List.of(upstream.getLocalPort()),
                    List.of(clientIdentity.clientPublicKey()), true, null));
            TailcatPeerProxyHandle peer = null;
            try {
                var ping = clientEngine.ping(server.listenAddress(), Duration.ofSeconds(15));
                assertTrue(ping.pathType() != TailcatPathType.UNKNOWN
                                && ping.pathType() != TailcatPathType.OFFLINE,
                        ping + " server=" + ((TailcatProcessSupervisor.ManagedProcessHandle) server.process()).stderrTail());
                peer = clientEngine.startPeerProxy(
                        UUID.randomUUID(), server.listenAddress(),
                        new TailcatPeerProxyConfig(clientIdentity.clientKeyPath(), "127.0.0.1", 0));
                TailcatPeerProxyHandle activePeer = peer;

                try (LocalForwardManager manager = new LocalForwardManager(ignored -> Optional.of(
                        new PeerSocksEndpoint(activePeer.localSocksHost(), activePeer.localSocksPort())))) {
                    int localPort = unusedLoopbackPort();
                    LocalForwardHandle forward = manager.start(new AgentForward(
                            UUID.randomUUID(), "official M6 forward", activePeer.peerDeviceId(), UUID.randomUUID(),
                            "127.0.0.1", localPort, upstream.getLocalPort(), true));
                    assertTrue(waitFor(() -> "READY".equals(forward.runtime().status()), Duration.ofSeconds(5)));
                    try (Socket connection = new Socket("127.0.0.1", localPort)) {
                        connection.setSoTimeout(15_000);
                        connection.getOutputStream().write("m6".getBytes(StandardCharsets.UTF_8));
                        connection.getOutputStream().flush();
                        String response = new String(connection.getInputStream().readNBytes(5), StandardCharsets.UTF_8);
                        assertEquals("m6-ok", response, () -> "server="
                                + ((TailcatProcessSupervisor.ManagedProcessHandle) server.process()).stderrTail()
                                + " peer=" + ((TailcatProcessSupervisor.ManagedProcessHandle) activePeer.process()).stderrTail()
                                + " upstreamAccepted=" + upstreamAccepted.get());
                    }
                    assertTrue(forward.isRunning());
                }
            } finally {
                if (peer != null) {
                    clientEngine.stopPeerProxy(peer.peerDeviceId());
                    assertEquals(ProcessState.STOPPED, peer.process().state());
                }
                serverEngine.stopServer();
                assertTrue(!((TailcatProcessSupervisor.ManagedProcessHandle) server.process()).isAlive());
                upstreamThread.join(10_000);
            }
        }
    }

    private static void serveOnce(ServerSocket upstream, AtomicBoolean accepted) {
        try (Socket socket = upstream.accept()) {
            accepted.set(true);
            byte[] request = socket.getInputStream().readNBytes(2);
            if (request.length != 2) {
                throw new IOException("upstream received an incomplete request");
            }
            socket.getOutputStream().write("m6-ok".getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
        } catch (IOException exception) {
            throw new AssertionError(exception);
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

    private static int unusedLoopbackPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
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
}
