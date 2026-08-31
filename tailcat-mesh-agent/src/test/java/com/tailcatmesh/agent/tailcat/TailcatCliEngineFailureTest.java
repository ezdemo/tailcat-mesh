package com.tailcatmesh.agent.tailcat;

import com.tailcatmesh.agent.tailcat.model.TailcatPeerProxyConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TailcatCliEngineFailureTest {

    private static final String PEER_BLOB = "tcDiagnosticPeerBlob_123456789";

    @TempDir
    Path temporaryDirectory;

    @Test
    void includesChildDiagnosticsWhenPeerSocksExitsBeforeReady() throws Exception {
        Path java = Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java"
        );
        Path workDirectory = Files.createDirectories(temporaryDirectory.resolve("work"));
        Path clientKey = temporaryDirectory.resolve("identity/client.private.json");
        Files.createDirectories(clientKey.getParent());
        Files.writeString(clientKey, "test-key");

        try (TailcatCliEngine engine = new TailcatCliEngine(new TailcatCliEngineConfig(
                java,
                workDirectory,
                Map.of(),
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                true))) {
            TailcatEngineException failure = assertThrows(TailcatEngineException.class,
                    () -> engine.startPeerProxy(
                            UUID.randomUUID(),
                            PEER_BLOB,
                            new TailcatPeerProxyConfig(clientKey, "127.0.0.1", 0)));

            assertTrue(failure.getMessage().contains("state=STOPPED"));
            assertTrue(failure.getMessage().contains("exitCode="));
            assertTrue(failure.getMessage().contains("stderr="));
            assertInstanceOf(TimeoutException.class, failure.getCause());
            assertFalse(failure.getMessage().contains(PEER_BLOB));
        }
    }
}
