package com.tailcatmesh.agent.config;

import com.tailcatmesh.agent.tailcat.TailcatBinaryDownloader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertFalse(config.tailcatAutoDownload());
        assertEquals("0.3.0", config.tailcatVersion());
        assertNull(config.proxy());
        assertEquals(URI.create("wss://mesh.example.test/base/api/v1/agent/ws"),
                config.websocketEndpoint());
    }

    @Test
    void loadsOptionalHttpProxyConfiguration() throws Exception {
        Path configPath = temporaryDirectory.resolve("proxy.yml");
        Files.writeString(configPath, """
                server:
                  url: https://mesh.example.test
                tailcat:
                  binary: tailcat.exe
                proxy:
                  type: http
                  host: 127.0.0.1
                  port: 7890
                """);

        AgentConfig config = new AgentConfigLoader().load(configPath, null);

        assertEquals(NetworkProxyConfig.Type.HTTP, config.proxy().type());
        assertEquals("127.0.0.1", config.proxy().host());
        assertEquals(7890, config.proxy().port());
        assertEquals("http://127.0.0.1:7890", config.proxy().endpoint());
    }

    @Test
    void autoDownloadUsesVersionedPlatformCacheWhenBinaryIsOmitted() throws Exception {
        Path configPath = temporaryDirectory.resolve("auto-download.yml");
        Files.writeString(configPath, """
                server:
                  url: http://127.0.0.1:8080
                tailcat:
                  version: v0.3.0
                  autoDownload: true
                """);

        AgentConfig config = new AgentConfigLoader().load(configPath, null);

        assertTrue(config.tailcatAutoDownload());
        assertEquals("0.3.0", config.tailcatVersion());
        assertEquals(
                TailcatBinaryDownloader.defaultBinaryPath("0.3.0"),
                config.tailcatBinary());
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

    @Test
    void loadsExplicitOptInVirtualLanConfiguration() throws Exception {
        Path configPath = temporaryDirectory.resolve("virtual-lan.yml");
        Files.writeString(configPath, """
                server:
                  url: http://127.0.0.1:8080
                tailcat:
                  binary: tailcat.exe
                virtualLan:
                  enabled: true
                  interfaceName: Tailcat Mesh Test
                  adapterGuid: 11111111-2222-3333-4444-555555555555
                  wintunDll: tools/wintun.dll
                  tun2socksBinary: tools/tun2socks.exe
                  tun2socksArguments:
                    - --device
                    - ${tun}
                    - --proxy
                    - ${proxy}
                """);

        AgentConfig config = new AgentConfigLoader().load(configPath, null);

        assertTrue(config.virtualLan().enabled());
        assertEquals("Tailcat Mesh Test", config.virtualLan().interfaceName());
        assertEquals(temporaryDirectory.resolve("tools/tun2socks.exe").toAbsolutePath().normalize(),
                config.virtualLan().tun2socksBinary());
        assertEquals(List.of("--device", "${tun}", "--proxy", "${proxy}"),
                config.virtualLan().tun2socksArgumentTemplate());
    }

    @Test
    void expandsUserHomeInVirtualLanPaths() throws Exception {
        Path configPath = temporaryDirectory.resolve("virtual-lan-home.yml");
        Files.writeString(configPath, """
                server:
                  url: http://127.0.0.1:8080
                tailcat:
                  binary: tailcat.exe
                virtualLan:
                  enabled: true
                  wintunDll: ~/.tailcat-mesh/virtual-lan/windows/wintun.dll
                  tun2socksBinary: ~/.tailcat-mesh/virtual-lan/windows/tun2socks.exe
                  workingDirectory: ~/.tailcat-mesh/virtual-lan/windows
                  tun2socksArguments:
                    - --device
                    - ${tun}
                    - --proxy
                    - ${proxy}
                """);

        AgentConfig config = new AgentConfigLoader().load(configPath, null);
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();

        assertEquals(home.resolve(".tailcat-mesh/virtual-lan/windows/wintun.dll").normalize(),
                config.virtualLan().wintunDll());
        assertEquals(home.resolve(".tailcat-mesh/virtual-lan/windows/tun2socks.exe").normalize(),
                config.virtualLan().tun2socksBinary());
        assertEquals(home.resolve(".tailcat-mesh/virtual-lan/windows").normalize(),
                config.virtualLan().workingDirectory());
    }
}
