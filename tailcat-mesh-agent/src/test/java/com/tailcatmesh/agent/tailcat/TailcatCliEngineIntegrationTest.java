package com.tailcatmesh.agent.tailcat;

import com.tailcatmesh.agent.tailcat.model.ProcessState;
import com.tailcatmesh.agent.tailcat.model.TailcatIdentity;
import com.tailcatmesh.agent.tailcat.model.TailcatIdentityConfig;
import com.tailcatmesh.agent.tailcat.model.TailcatPathType;
import com.tailcatmesh.agent.tailcat.model.TailcatPingResult;
import com.tailcatmesh.agent.tailcat.model.TailcatServerConfig;
import com.tailcatmesh.agent.tailcat.model.TailcatServerHandle;
import com.tailcatmesh.agent.tailcat.model.TailcatTokenInfo;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Requires the real official Tailcat v0.3.0 executable. It is skipped for a
 * normal source build when no executable has been configured.
 */
class TailcatCliEngineIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void startsParsesAndStopsOfficialTailcatV030() throws IOException {
        Path binary = configuredBinary();
        Assumptions.assumeTrue(binary != null && Files.isRegularFile(binary),
                "Set -Dtailcat.binary=<path to official tailcat v0.3.0> to run this integration test");

        Path workingDirectory = Files.createDirectories(tempDir.resolve("work"));
        TailcatCliEngineConfig engineConfig = new TailcatCliEngineConfig(
                binary,
                workingDirectory,
                Map.of(),
                Duration.ofSeconds(15),
                Duration.ofSeconds(20),
                false
        );

        try (TailcatCliEngine engine = new TailcatCliEngine(engineConfig)) {
            assertEquals("0.3.0", engine.getVersion().toString());

            TailcatIdentity identity = engine.ensureIdentity(new TailcatIdentityConfig(
                    tempDir.resolve("identity/server.private.json"),
                    tempDir.resolve("identity/client.private.json")
            ));
            assertTrue(Files.isRegularFile(identity.serverKeyPath()));
            assertTrue(Files.isRegularFile(identity.clientKeyPath()));
            assertTrue(identity.clientPublicKey().startsWith("nodekey:"));

            int servedPort = findFreePort();
            TailcatServerHandle server = engine.startServer(new TailcatServerConfig(
                    identity.serverKeyPath(),
                    List.of(servedPort),
                    List.of(),
                    true,
                    null
            ));
            assertTrue(server.listenAddress().startsWith("tc"));
            assertEquals(ProcessState.RUNNING, engine.getRuntimeStatus().state());

            TailcatTokenInfo tokenInfo = engine.parseToken(server.listenAddress());
            assertNotNull(tokenInfo.serverPublicKey());
            assertTrue(tokenInfo.serverPublicKey().startsWith("nodekey:"));
            assertNotNull(tokenInfo.region());

            engine.stopServer();
            assertEquals(ProcessState.STOPPED, engine.getRuntimeStatus().state());
            assertFalse(((TailcatProcessSupervisor.ManagedProcessHandle) server.process()).isAlive());

            TailcatServerHandle restarted = engine.startServer(new TailcatServerConfig(
                    identity.serverKeyPath(),
                    List.of(servedPort),
                    List.of(),
                    true,
                    null
            ));
            assertEquals(server.listenAddress(), restarted.listenAddress(),
                    "a stable Tailcat server key must preserve the ConnBlob across restarts");
            engine.stopServer();
        }
    }

    @Test
    void enforcesDenyAllAndAcceptsOnlyTheStableClientKey() throws IOException {
        Path binary = configuredBinary();
        Assumptions.assumeTrue(binary != null && Files.isRegularFile(binary),
                "Set -Dtailcat.binary=<path to official tailcat v0.3.0> to run this integration test");

        TailcatCliEngineConfig engineConfig = new TailcatCliEngineConfig(
                binary,
                Files.createDirectories(tempDir.resolve("allowlist-work")),
                Map.of("TS_DEBUG_TAILCAT_LOCAL_DERP", "1"),
                Duration.ofSeconds(15),
                Duration.ofSeconds(20),
                false
        );

        try (TailcatCliEngine engine = new TailcatCliEngine(engineConfig)) {
            TailcatIdentity identity = engine.ensureIdentity(new TailcatIdentityConfig(
                    tempDir.resolve("allowlist-identity/server.private.json"),
                    tempDir.resolve("allowlist-identity/client.private.json")
            ));
            TailcatServerConfig base = new TailcatServerConfig(
                    identity.serverKeyPath(), List.of(), List.of(), true, null);
            TailcatServerHandle denied = engine.startServer(base);
            try {
                TailcatPingResult result = engine.ping(denied.listenAddress(), Duration.ofSeconds(3));
                assertTrue(result.pathType() != TailcatPathType.DIRECT
                                && result.pathType() != TailcatPathType.DERP,
                        "--allow=none must reject an unlisted client");
            } finally {
                engine.stopServer();
            }

            TailcatServerHandle allowed = engine.startServer(new TailcatServerConfig(
                    identity.serverKeyPath(), List.of(), List.of(identity.clientPublicKey()), true, null));
            try {
                TailcatPingResult result = engine.ping(allowed.listenAddress(), Duration.ofSeconds(5));
                assertTrue(result.pathType() == TailcatPathType.DIRECT
                                || result.pathType() == TailcatPathType.DERP,
                        "a client in --allow must be able to reach the server");
            } finally {
                engine.stopServer();
            }
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

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
