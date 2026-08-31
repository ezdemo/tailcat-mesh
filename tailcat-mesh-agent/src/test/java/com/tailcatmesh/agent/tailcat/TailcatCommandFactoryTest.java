package com.tailcatmesh.agent.tailcat;

import com.tailcatmesh.agent.tailcat.model.TailcatServerConfig;
import com.tailcatmesh.agent.tailcat.model.TailcatVirtualNetworkServerConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TailcatCommandFactoryTest {

    private static final String CLIENT_KEY = "nodekey:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void buildsServerCommandWithExplicitDenyByDefaultAndJson() {
        Path binary = Path.of("bin", "tailcat.exe").toAbsolutePath().normalize();
        Path key = Path.of("data", "identity", "server.private.json").toAbsolutePath().normalize();
        TailcatCommandFactory factory = new TailcatCommandFactory(binary);

        List<String> command = factory.serverCommand(new TailcatServerConfig(
                key,
                List.of(45101, 45102),
                List.of(CLIENT_KEY),
                true,
                "https://mesh.example.com/derpmap.json"
        ));

        assertEquals(List.of(
                binary.toString(),
                "--key=" + key,
                "--serve=45101,45102",
                "--allow=" + CLIENT_KEY,
                "--full-address",
                "--json",
                "--derpmap-url=https://mesh.example.com/derpmap.json"
        ), command);
    }

    @Test
    void invalidOrEmptyAllowlistFailsClosed() {
        Path binary = Path.of("tailcat").toAbsolutePath().normalize();
        Path key = Path.of("server.private.json").toAbsolutePath().normalize();
        TailcatCommandFactory factory = new TailcatCommandFactory(binary);

        assertEquals("--allow=none", factory.serverCommand(new TailcatServerConfig(
                key, List.of(1), List.of(), false, null
        )).get(3));
        assertEquals("--allow=none", factory.serverCommand(new TailcatServerConfig(
                key, List.of(1), List.of("not-a-public-key"), false, null
        )).get(3));
    }

    @Test
    void buildsIsolatedVirtualNetworkServerWithServeAll() {
        Path binary = Path.of("tailcat").toAbsolutePath().normalize();
        Path key = Path.of("identity", "virtual-networks", "network-id", "server.private.json")
                .toAbsolutePath().normalize();
        TailcatCommandFactory factory = new TailcatCommandFactory(binary);

        assertEquals(List.of(
                binary.toString(),
                "--key=" + key,
                "--serve=all",
                "--allow=" + CLIENT_KEY,
                "--full-address",
                "--json"
        ), factory.virtualNetworkServerCommand(new TailcatVirtualNetworkServerConfig(
                key, List.of(CLIENT_KEY), true, null)));
    }

    @Test
    void virtualNetworkWithoutPeersFailsClosedAndNeverUsesExitNode() {
        Path binary = Path.of("tailcat").toAbsolutePath().normalize();
        Path key = Path.of("identity", "virtual-networks", "network-id", "server.private.json")
                .toAbsolutePath().normalize();
        TailcatCommandFactory factory = new TailcatCommandFactory(binary);

        List<String> command = factory.virtualNetworkServerCommand(
                new TailcatVirtualNetworkServerConfig(key, List.of(), false, null));

        assertEquals("--serve=all", command.get(2));
        assertEquals("--allow=none", command.get(3));
        assertEquals("--json", command.get(4));
        assertEquals(false, command.stream().anyMatch(argument -> argument.contains("exit-node")));
    }

    @Test
    void placesGlobalKeyBeforeSubcommands() {
        Path binary = Path.of("tailcat").toAbsolutePath().normalize();
        Path clientKey = Path.of("client.private.json").toAbsolutePath().normalize();
        TailcatCommandFactory factory = new TailcatCommandFactory(binary);

        assertEquals(List.of(binary.toString(), "genkey", "--key=" + clientKey, "--client"),
                factory.genKeyCommand(clientKey, true));
        assertEquals(List.of(binary.toString(), "--key=" + clientKey, "printpub"),
                factory.printPublicKeyCommand(clientKey));
        assertEquals(List.of(binary.toString(), "parse", "tcABC_def-1234567890"),
                factory.parseTokenCommand("tcABC_def-1234567890"));
        assertEquals(List.of(
                        binary.toString(), "--key=" + clientKey, "ping", "--timeout=1200ms",
                        "tcABC_def-1234567890"
                ),
                factory.pingCommand(clientKey, "tcABC_def-1234567890", Duration.ofMillis(1_200), false));
    }

    @Test
    void pinsMeshServerKeysToARegionAndCanForceMigrateAnAutoRegionKey() {
        Path binary = Path.of("tailcat").toAbsolutePath().normalize();
        Path serverKey = Path.of("server.private.json").toAbsolutePath().normalize();
        TailcatCommandFactory factory = new TailcatCommandFactory(binary);

        assertEquals(List.of(
                        binary.toString(), "genkey", "--key=" + serverKey, "--fixed-region", "--force"),
                factory.genKeyCommand(serverKey, false, true, true));
    }
}
