package com.tailcatmesh.agent.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentConfigLoaderTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsDocumentedYamlAndResolvesRelativePaths() throws Exception {
        Path configPath = temporaryDirectory.resolve("agent.yml");
        Files.writeString(configPath, """
                server:
                  url: https://mesh.example.test/base
                tailcat:
                  binary: tools/tailcat.exe
                  supportedVersion: 0.3.x
                  serverKey: server.key
                  clientKey: client.key
                  fullAddress: true
                  derpMapUrl: https://mesh.example.test/derp-map.json
                agent:
                  dataDir: state
                  heartbeatSeconds: 20
                  peerPingSeconds: 40
                """);

        AgentConfig config = new AgentConfigLoader().load(configPath, null);

        assertEquals(URI.create("https://mesh.example.test/base"), config.serverUrl());
        assertEquals(temporaryDirectory.resolve("tools/tailcat.exe").toAbsolutePath().normalize(),
                config.tailcatBinary());
        Path dataDir = temporaryDirectory.resolve("state").toAbsolutePath().normalize();
        assertEquals(dataDir, config.dataDir());
        assertEquals(dataDir.resolve("identity/server.key"), config.serverKeyPath());
        assertEquals(dataDir.resolve("identity/client.key"), config.clientKeyPath());
        assertEquals(20, config.heartbeatInterval().toSeconds());
        assertEquals(40, config.peerPingInterval().toSeconds());
        assertEquals(URI.create("wss://mesh.example.test/base/api/v1/agent/ws"),
                config.websocketEndpoint());
    }

    @Test
    void cliOverridesAllowOneLineBootstrapWithoutAConfigFile() {
        AgentConfig config = new AgentConfigLoader().load(
                temporaryDirectory.resolve("missing.yml"),
                new AgentConfigOverrides(
                        URI.create("http://127.0.0.1:8080"),
                        temporaryDirectory.resolve("tailcat.exe"),
                        temporaryDirectory.resolve("data")));

        assertEquals(URI.create("http://127.0.0.1:8080"), config.serverUrl());
        assertEquals(temporaryDirectory.resolve("tailcat.exe").toAbsolutePath().normalize(),
                config.tailcatBinary());
    }

    @Test
    void missingServerAndUnsupportedVersionAreRejected() throws Exception {
        Path missingServer = temporaryDirectory.resolve("missing-server.yml");
        Files.writeString(missingServer, "tailcat:\n  supportedVersion: 0.3.x\n");
        assertThrows(AgentConfigException.class,
                () -> new AgentConfigLoader().load(missingServer, null));

        Path unsupported = temporaryDirectory.resolve("unsupported.yml");
        Files.writeString(unsupported, """
                server:
                  url: http://127.0.0.1:8080
                tailcat:
                  supportedVersion: 0.4.x
                """);
        assertThrows(AgentConfigException.class,
                () -> new AgentConfigLoader().load(unsupported, null));
    }
}
