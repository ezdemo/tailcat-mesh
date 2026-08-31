package com.tailcatmesh.agent.virtual;

import com.tailcatmesh.agent.config.AgentConfig;
import com.tailcatmesh.agent.config.VirtualLanAgentConfig;
import com.tailcatmesh.agent.forward.PeerSocksEndpoint;
import com.tailcatmesh.agent.socks.Socks5Client;
import com.tailcatmesh.agent.tailcat.TailcatBinaryLocator;
import com.tailcatmesh.agent.tailcat.TailcatCliEngine;
import com.tailcatmesh.agent.tailcat.TailcatCliEngineConfig;
import com.tailcatmesh.agent.tailcat.model.TailcatIdentity;
import com.tailcatmesh.agent.tailcat.model.TailcatIdentityConfig;
import com.tailcatmesh.agent.tailcat.model.TailcatPathType;
import com.tailcatmesh.agent.tailcat.model.TailcatPingResult;
import com.tailcatmesh.agent.tailcat.model.TailcatServerHandle;
import com.tailcatmesh.agent.tailcat.model.TailcatVirtualNetworkServerConfig;
import com.tailcatmesh.protocol.agent.AgentVirtualNetwork;
import com.tailcatmesh.protocol.agent.AgentVirtualNetworkPeer;
import com.tailcatmesh.protocol.agent.AgentVirtualNetworkRuntime;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in M7.10 acceptance test using three logical Devices and the official
 * Tailcat binary. It exercises the real TUN/route/tun2socks path on the host
 * platform; no default route is ever requested by this fixture.
 */
class TailcatVirtualLanE2eTest {

    private static final UUID TEST_ADAPTER_GUID =
            UUID.fromString("b7e21070-7a10-4e2e-9a70-1a2b3c4d5e6f");
    private static final String HOME_CIDR = "10.252.10.0/24";
    private static final String DEV_CIDR = "10.252.11.0/24";
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);

    @TempDir
    Path temporaryDirectory;

    @Test
    void threeDevicesAreIsolatedAndAReachesBothTcpServicesThroughVirtualIps() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("tailcat.m7.e2e"),
                "set -Dtailcat.m7.e2e=true to run the privileged M7.10 acceptance test");

        HostPlatform platform = HostPlatform.detect();
        Path tailcatBinary = configuredBinary();
        Path tun2socksBinary = configuredTun2SocksBinary();
        Assumptions.assumeTrue(tailcatBinary != null && Files.isRegularFile(tailcatBinary),
                "official Tailcat v0.3.0 binary is required");
        Assumptions.assumeTrue(tun2socksBinary != null && Files.isRegularFile(tun2socksBinary),
                "a compatible tun2socks binary is required");

        Path wintunDll = null;
        if (platform == HostPlatform.WINDOWS) {
            wintunDll = configuredWintunDll();
            Assumptions.assumeTrue(wintunDll != null && Files.isRegularFile(wintunDll),
                    "a Wintun DLL is required on Windows");
            Assumptions.assumeTrue(isWindowsAdministrator(),
                    "run the opt-in Windows E2E test from an elevated terminal");
        } else if (platform == HostPlatform.LINUX) {
            Assumptions.assumeTrue(Files.isReadable(LinuxTunRuntime.DEFAULT_TUN_DEVICE),
                    "/dev/net/tun is not available");
            Assumptions.assumeTrue(isRoot(),
                    "the Linux E2E test requires root or equivalent CAP_NET_ADMIN setup");
        } else {
            Assumptions.abort("M7.10 E2E supports Windows and Linux only");
        }

        UUID homeNetwork = UUID.randomUUID();
        UUID devNetwork = UUID.randomUUID();
        UUID deviceA = UUID.randomUUID();
        UUID deviceB = UUID.randomUUID();
        UUID deviceC = UUID.randomUUID();

        try (TailcatCliEngine engineA = newEngine(tailcatBinary, "a");
             TailcatCliEngine engineB = newEngine(tailcatBinary, "b");
             TailcatCliEngine engineC = newEngine(tailcatBinary, "c");
             OneShotTcpService directServiceB = new OneShotTcpService("direct-B", "direct".length());
             OneShotTcpService serviceB = new OneShotTcpService("from-B", "home".length());
             OneShotTcpService serviceC = new OneShotTcpService("from-C", "dev".length())) {
            TailcatIdentity identityA = ensureIdentity(engineA, "a");
            TailcatIdentity identityB = ensureIdentity(engineB, "b");
            TailcatIdentity identityC = ensureIdentity(engineC, "c");

            TailcatServerHandle serverBHome = startVirtualServer(engineB, homeNetwork,
                    "b-home", identityA.clientPublicKey());
            TailcatServerHandle serverCDev = startVirtualServer(engineC, devNetwork,
                    "c-dev", identityA.clientPublicKey());

            TailcatPingResult pathToB = engineA.ping(serverBHome.listenAddress(), Duration.ofSeconds(10));
            TailcatPingResult pathToC = engineA.ping(serverCDev.listenAddress(), Duration.ofSeconds(10));
            System.out.println("M7.10 A->B path=" + pathToB.pathType()
                    + ", A->C path=" + pathToC.pathType());
            assertUsablePath(pathToB);
            assertUsablePath(pathToC);
            assertDenied(engineB.ping(serverCDev.listenAddress(), Duration.ofSeconds(4)));
            assertDenied(engineC.ping(serverBHome.listenAddress(), Duration.ofSeconds(4)));

            AgentConfig agentConfig = agentConfig(tailcatBinary, tun2socksBinary, wintunDll,
                    identityA, platform);
            AgentVirtualNetwork homeForA = network(homeNetwork, "home", HOME_CIDR, "10.252.10.2",
                    new AgentVirtualNetworkPeer(deviceB, "B", "10.252.10.3",
                            serverBHome.listenAddress(), identityB.clientPublicKey()));
            AgentVirtualNetwork devForA = network(devNetwork, "dev", DEV_CIDR, "10.252.11.2",
                    new AgentVirtualNetworkPeer(deviceC, "C", "10.252.11.3",
                            serverCDev.listenAddress(), identityC.clientPublicKey()));
            String stableHomeVirtualIp = homeForA.peers().get(0).virtualIpv4();

            try (VirtualNetworkManager manager = new VirtualNetworkManager(agentConfig, engineA)) {
                List<AgentVirtualNetworkRuntime> runtimes = manager.reconcile(
                        List.of(homeForA, devForA));
                assertEquals(2, runtimes.size());
                assertTrue(waitFor(() -> manager.isDataPlaneHealthy(List.of(homeForA, devForA)),
                        STARTUP_TIMEOUT), "the real TUN data plane did not become healthy");
                assertTrue(manager.isDataPlaneHealthy(List.of(homeForA, devForA)),
                        manager.dataPlaneDiagnostics());
                assertEquals(stableHomeVirtualIp,
                        manager.routeTable().resolve(stableHomeVirtualIp).orElseThrow().virtualIpv4());
                try (OneShotTcpService peerServiceB = new OneShotTcpService("peer-B", "peer".length())) {
                    PeerSocksEndpoint peer = manager.routeTable().resolve("10.252.10.3")
                            .orElseThrow().peerSocks();
                    assertEquals("peer-B", connectViaSocks(peer, "server.tailcat",
                            peerServiceB.port(), "peer"));
                    assertEquals("peer", peerServiceB.awaitRequest());
                }
                PeerSocksEndpoint router = manager.routerEndpoint().orElseThrow();
                assertEquals("direct-B", connectViaSocks(router, "10.252.10.3",
                        directServiceB.port(), "direct"));
                assertEquals("direct", directServiceB.awaitRequest());
                assertEquals(2, manager.routeTable().size());
                assertEquals(2, manager.dataPlaneRouteSnapshot().size());
                assertTrue(manager.dataPlaneRouteSnapshot().stream()
                        .noneMatch(route -> route.cidr().isDefaultRoute()));

                String homeResponse;
                try {
                    homeResponse = connectToVirtualIp("10.252.10.3", serviceB.port(), "home");
                } catch (IOException exception) {
                    System.out.println("M7.10 virtual-home failure: " + manager.dataPlaneDiagnostics()
                            + ", routes=" + manager.dataPlaneRouteSnapshot());
                    throw exception;
                }
                assertEquals("from-B", homeResponse);
                assertEquals("home", serviceB.awaitRequest());
                String devResponse;
                try {
                    devResponse = connectToVirtualIp("10.252.11.3", serviceC.port(), "dev");
                } catch (IOException exception) {
                    System.out.println("M7.10 virtual-dev failure: " + manager.dataPlaneDiagnostics()
                            + ", routes=" + manager.dataPlaneRouteSnapshot());
                    throw exception;
                }
                assertEquals("from-C", devResponse);
                assertEquals("dev", serviceC.awaitRequest());

                // Removing A's Home membership removes the peer SOCKS route,
                // the Home server runtime, and the Home CIDR route on A.
                manager.reconcile(List.of(devForA));
                assertTrue(manager.routeTable().resolve("10.252.10.3").isEmpty());
                assertTrue(manager.dataPlaneRouteSnapshot().stream()
                        .noneMatch(route -> HOME_CIDR.equals(route.networkCidr())));
                assertFalse(manager.snapshot().stream()
                        .anyMatch(runtime -> runtime.networkId().equals(homeNetwork)));
            }

            // Recreate the Agent-side manager. The same desired-state IP must
            // remain usable after all network-scoped Tailcat processes restart.
            try (VirtualNetworkManager restarted = new VirtualNetworkManager(agentConfig, engineA);
                 OneShotTcpService restartServiceB = new OneShotTcpService(
                         "after-restart-B", "restart".length())) {
                List<AgentVirtualNetworkRuntime> restartedRuntimes = restarted.reconcile(
                        List.of(homeForA, devForA));
                assertEquals(2, restartedRuntimes.size());
                assertTrue(waitFor(() -> restarted.isDataPlaneHealthy(List.of(homeForA, devForA)),
                        STARTUP_TIMEOUT), "the restarted TUN data plane did not become healthy");
                assertEquals(stableHomeVirtualIp,
                        restarted.routeTable().resolve(stableHomeVirtualIp).orElseThrow().virtualIpv4());
                String restartResponse;
                try {
                    restartResponse = connectToVirtualIp(stableHomeVirtualIp,
                            restartServiceB.port(), "restart");
                } catch (IOException exception) {
                    System.out.println("M7.10 restart virtual-home failure: "
                            + restarted.dataPlaneDiagnostics()
                            + ", routes=" + restarted.dataPlaneRouteSnapshot());
                    throw exception;
                }
                assertEquals("after-restart-B", restartResponse);
                assertEquals("restart", restartServiceB.awaitRequest());

                // Removing B from A's desired Network membership tears down
                // the peer route and the local virtual-network runtime.
                restarted.reconcile(List.of(devForA));
                assertTrue(restarted.routeTable().resolve(stableHomeVirtualIp).isEmpty());
                assertTrue(restarted.dataPlaneRouteSnapshot().stream()
                        .noneMatch(route -> HOME_CIDR.equals(route.networkCidr())));
                assertFalse(restarted.snapshot().stream()
                        .anyMatch(runtime -> runtime.networkId().equals(homeNetwork)));

                // The server-side allowlist is revoked as well. A's client
                // key must no longer be able to use the Home server directly.
                engineB.stopVirtualNetworkServer(homeNetwork);
                try {
                    TailcatServerHandle revokedHome = startVirtualServer(engineB, homeNetwork,
                            "b-home", List.of());
                    assertDenied(engineA.ping(revokedHome.listenAddress(), Duration.ofSeconds(4)));
                } finally {
                    engineB.stopVirtualNetworkServer(homeNetwork);
                }
            }
        }
    }

    private TailcatCliEngine newEngine(Path binary, String name) {
        Path workingDirectory = temporaryDirectory.resolve(name + "-tailcat-work");
        try {
            Files.createDirectories(workingDirectory);
        } catch (IOException exception) {
            throw new IllegalStateException("could not create Tailcat working directory", exception);
        }
        return new TailcatCliEngine(new TailcatCliEngineConfig(
                binary,
                workingDirectory,
                Map.of(),
                Duration.ofSeconds(15),
                Duration.ofSeconds(30),
                false));
    }

    private TailcatIdentity ensureIdentity(TailcatCliEngine engine, String name) {
        Path dataDirectory = temporaryDirectory.resolve(name + "-data");
        return engine.ensureIdentity(new TailcatIdentityConfig(
                dataDirectory.resolve("identity/server.private.json"),
                dataDirectory.resolve("identity/client.private.json")));
    }

    private TailcatServerHandle startVirtualServer(TailcatCliEngine engine, UUID networkId,
                                                    String name, String allowedClientKey) {
        return startVirtualServer(engine, networkId, name, List.of(allowedClientKey));
    }

    private TailcatServerHandle startVirtualServer(TailcatCliEngine engine, UUID networkId,
                                                    String name, List<String> allowedClientKeys) {
        Path key = temporaryDirectory.resolve(name).resolve("server.private.json");
        engine.ensureVirtualNetworkServerKey(networkId, key);
        return engine.startVirtualNetworkServer(networkId,
                new TailcatVirtualNetworkServerConfig(
                        key, allowedClientKeys, true, null));
    }

    private AgentConfig agentConfig(Path tailcatBinary, Path tun2socksBinary, Path wintunDll,
                                     TailcatIdentity identity, HostPlatform platform) {
        String interfaceName = platform == HostPlatform.WINDOWS
                ? "TailcatMeshM7E2E" : "tailcat-mesh-m7-e2e";
        List<String> tun2socksArguments = platform == HostPlatform.WINDOWS
                ? List.of("--device", "tun://${tun}?guid={" + TEST_ADAPTER_GUID + "}",
                "--proxy", "${proxy}", "--loglevel", "debug")
                : List.of("--device", "tun://${tun}", "--proxy", "${proxy}",
                "--loglevel", "debug");
        Path tunWorkingDirectory = platform == HostPlatform.WINDOWS && wintunDll != null
                ? wintunDll.getParent() : temporaryDirectory.resolve("a-tun-work");
        try {
            Files.createDirectories(tunWorkingDirectory);
        } catch (IOException exception) {
            throw new IllegalStateException("could not create tun2socks working directory", exception);
        }
        VirtualLanAgentConfig virtualLan = new VirtualLanAgentConfig(
                true,
                interfaceName,
                platform == HostPlatform.WINDOWS ? TEST_ADAPTER_GUID : null,
                wintunDll,
                tun2socksBinary,
                tun2socksArguments,
                tunWorkingDirectory,
                Map.of("TS_DEBUG_TAILCAT_LOCAL_DERP", "1"),
                Duration.ofSeconds(15),
                Duration.ofSeconds(20));
        Path dataDirectory = temporaryDirectory.resolve("a-data");
        return new AgentConfig(
                java.net.URI.create("http://127.0.0.1:8080"),
                tailcatBinary,
                dataDirectory,
                identity.serverKeyPath(),
                identity.clientKeyPath(),
                true,
                null,
                Duration.ofSeconds(15),
                Duration.ofSeconds(30),
                virtualLan);
    }

    private static AgentVirtualNetwork network(UUID networkId, String name, String cidr,
                                                String localIp, AgentVirtualNetworkPeer peer) {
        return new AgentVirtualNetwork(networkId, name, cidr, localIp, true, List.of(peer));
    }

    private static String connectToVirtualIp(String virtualIp, int port, String request)
            throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(virtualIp, port), (int) CONNECT_TIMEOUT.toMillis());
            socket.setSoTimeout((int) CONNECT_TIMEOUT.toMillis());
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String connectViaSocks(PeerSocksEndpoint proxy, String targetHost, int port,
                                          String request) throws IOException {
        try (Socket socket = new Socks5Client().connect(proxy.host(), proxy.port(), targetHost,
                port, CONNECT_TIMEOUT)) {
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void assertUsablePath(TailcatPingResult result) {
        assertNotNull(result);
        assertTrue(result.pathType() == TailcatPathType.DIRECT
                        || result.pathType() == TailcatPathType.DERP,
                "expected Direct or DERP, got " + result.pathType());
    }

    private static void assertDenied(TailcatPingResult result) {
        assertNotNull(result);
        assertFalse(result.pathType() == TailcatPathType.DIRECT
                        || result.pathType() == TailcatPathType.DERP,
                "a cross-network client unexpectedly reached the server");
    }

    private static boolean waitFor(java.util.function.BooleanSupplier condition, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(100);
        }
        return condition.getAsBoolean();
    }

    private static Path configuredBinary() {
        String configured = System.getProperty(TailcatBinaryLocator.BINARY_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        return TailcatBinaryLocator.locate().orElse(null);
    }

    private static Path configuredTun2SocksBinary() {
        String configured = System.getProperty("tailcat.tun2socks.binary");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        Path current = Path.of(System.getProperty("user.dir"));
        HostPlatform platform = HostPlatform.detect();
        List<Path> candidates = platform == HostPlatform.WINDOWS
                ? List.of(
                current.resolve(".local/m7-e2e/tun2socks-windows-amd64-v3.exe"),
                current.resolve("../.local/m7-e2e/tun2socks-windows-amd64-v3.exe"))
                : List.of(
                current.resolve(".local/m7-e2e/tun2socks-linux-amd64-v3"),
                current.resolve("../.local/m7-e2e/tun2socks-linux-amd64-v3"));
        return candidates.stream().map(Path::toAbsolutePath).map(Path::normalize)
                .filter(Files::isRegularFile).findFirst().orElse(null);
    }

    private static Path configuredWintunDll() {
        String configured = System.getProperty("tailcat.wintun.dll");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        String programFiles = System.getenv("ProgramFiles");
        if (programFiles == null || programFiles.isBlank()) {
            return null;
        }
        return Path.of(programFiles, "Tailscale", "wintun.dll").toAbsolutePath().normalize();
    }

    private static boolean isWindowsAdministrator() {
        try {
            Process process = new ProcessBuilder("net", "session")
                    .redirectErrorStream(true).start();
            boolean finished = process.waitFor(3, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException exception) {
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean isRoot() {
        return "root".equals(System.getProperty("user.name"));
    }

    private static final class OneShotTcpService implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final String response;
        private final int expectedRequestLength;
        private final CompletableFuture<String> request = new CompletableFuture<>();
        private final Thread worker;

        private OneShotTcpService(String response, int expectedRequestLength) throws IOException {
            this.serverSocket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            this.response = response;
            this.expectedRequestLength = expectedRequestLength;
            this.worker = Thread.ofVirtual().name("tailcat-m7-e2e-service").start(this::serve);
        }

        private int port() {
            return serverSocket.getLocalPort();
        }

        private String awaitRequest() throws Exception {
            return request.get(CONNECT_TIMEOUT.toSeconds() + 5, TimeUnit.SECONDS);
        }

        private void serve() {
            try (Socket socket = serverSocket.accept()) {
                ByteArrayOutputStream received = new ByteArrayOutputStream();
                byte[] requestBytes = socket.getInputStream().readNBytes(expectedRequestLength);
                received.write(requestBytes);
                if (requestBytes.length != expectedRequestLength) {
                    throw new IOException("test TCP service received an incomplete request");
                }
                socket.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
                socket.getOutputStream().flush();
                request.complete(received.toString(StandardCharsets.UTF_8));
            } catch (IOException exception) {
                if (!serverSocket.isClosed()) {
                    request.completeExceptionally(exception);
                }
            }
        }

        @Override
        public void close() throws InterruptedException {
            try {
                serverSocket.close();
            } catch (IOException exception) {
                throw new IllegalStateException("could not close test TCP service", exception);
            }
            worker.join(2_000);
            request.cancel(false);
        }
    }
}
