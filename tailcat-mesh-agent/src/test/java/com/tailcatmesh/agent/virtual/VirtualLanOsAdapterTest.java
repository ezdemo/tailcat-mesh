package com.tailcatmesh.agent.virtual;

import com.tailcatmesh.agent.forward.PeerSocksEndpoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VirtualLanOsAdapterTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void ipv4AndRouteCommandsNormalizeNetworksAndRejectDefaultRoute() {
        Ipv4Cidr address = Ipv4Cidr.parse("10.77.0.2/24");
        assertEquals("10.77.0.0/24", address.networkValue());
        assertEquals("255.255.255.0", address.netmask());

        OsRoute route = new OsRoute(UUID.randomUUID(), address, "Tailcat Mesh", null);
        assertEquals(List.of("ip", "route", "replace", "10.77.0.0/24", "dev", "Tailcat Mesh"),
                new OsRouteCommandFactory(HostPlatform.LINUX).add(route));
        assertEquals(List.of("netsh", "interface", "ipv4", "add", "route",
                        "prefix=10.77.0.0/24", "interface=Tailcat Mesh", "store=active"),
                new OsRouteCommandFactory(HostPlatform.WINDOWS).add(route));
        OsRoute routed = new OsRoute(UUID.randomUUID(), address, "Tailcat Mesh", null, "10.77.0.2");
        assertEquals(List.of("netsh", "interface", "ipv4", "add", "route",
                        "prefix=10.77.0.0/24", "interface=Tailcat Mesh", "nexthop=10.77.0.2",
                        "store=active"), new OsRouteCommandFactory(HostPlatform.WINDOWS).add(routed));
        assertThrows(IllegalArgumentException.class,
                () -> new OsRoute(UUID.randomUUID(), Ipv4Cidr.parse("0.0.0.0/0"),
                        "Tailcat Mesh", null));
    }

    @Test
    void routeManagerIsIdempotentAndOnlyDeletesItsOwnRoutes() {
        RecordingExecutor executor = new RecordingExecutor();
        SystemOsRouteManager manager = new SystemOsRouteManager(
                HostPlatform.LINUX, executor, Duration.ofSeconds(1));
        OsRoute route = new OsRoute(UUID.randomUUID(), Ipv4Cidr.parse("10.77.0.0/24"),
                "tailcat-mesh", null);

        manager.reconcile(List.of(route));
        manager.reconcile(List.of(route));
        assertEquals(1, executor.commands.size(), "unchanged route must not be re-added");
        assertEquals(List.of("ip", "route", "replace", "10.77.0.0/24", "dev", "tailcat-mesh"),
                executor.commands.get(0));

        manager.reconcile(List.of());
        assertEquals(2, executor.commands.size());
        assertEquals(List.of("ip", "route", "del", "10.77.0.0/24", "dev", "tailcat-mesh"),
                executor.commands.get(1));
        assertTrue(manager.snapshot().isEmpty());
    }

    @Test
    void linuxTunCreatesConfiguresAndCleansAnAgentOwnedInterface() throws Exception {
        Path tunDevice = temporaryDirectory.resolve("tun");
        Files.writeString(tunDevice, "device");
        RecordingExecutor executor = new RecordingExecutor();
        executor.linkExists = false;
        LinuxTunRuntime runtime = new LinuxTunRuntime(
                executor, HostPlatform.LINUX, tunDevice, Duration.ofSeconds(1));
        TunConfig config = TunConfig.linux("tailcat-mesh", List.of(Ipv4Cidr.parse("10.77.0.2/24")));

        TunHandle handle = runtime.open(config);
        assertEquals("tailcat-mesh", handle.interfaceName());
        assertTrue(handle.createdByAgent());
        assertSame(handle, runtime.open(config), "opening the same TUN is idempotent");
        assertTrue(executor.commands.contains(List.of("ip", "tuntap", "add", "dev",
                "tailcat-mesh", "mode", "tun")));
        assertTrue(executor.commands.contains(List.of("ip", "address", "replace",
                "10.77.0.2/24", "dev", "tailcat-mesh")));

        handle.close();
        assertTrue(executor.commands.contains(List.of("ip", "address", "del",
                "10.77.0.2/24", "dev", "tailcat-mesh")));
        assertTrue(executor.commands.contains(List.of("ip", "link", "delete", "dev", "tailcat-mesh")));
        assertTrue(handle.isClosed());
        runtime.close();
    }

    @Test
    void windowsWintunUsesStableGuidAndAddressLifecycleWithoutNativeCallInUnitTest() {
        RecordingExecutor executor = new RecordingExecutor();
        RecordingWintunProvider provider = new RecordingWintunProvider();
        WindowsWintunRuntime runtime = new WindowsWintunRuntime(
                provider, executor, HostPlatform.WINDOWS, Duration.ofSeconds(1));
        TunConfig config = TunConfig.windows(
                WindowsWintunRuntime.DEFAULT_INTERFACE_NAME,
                WindowsWintunRuntime.DEFAULT_ADAPTER_GUID,
                List.of(Ipv4Cidr.parse("10.77.0.2/24")), null);

        runtime.prepare(config);
        assertTrue(executor.commands.stream().anyMatch(command -> command.get(0).equals("powershell.exe")
                && command.contains("-Command") && command.get(command.size() - 1).contains("pnputil.exe")));
        TunHandle handle = runtime.open(config);
        assertEquals(WindowsWintunRuntime.DEFAULT_INTERFACE_NAME, provider.interfaceName);
        assertEquals(WindowsWintunRuntime.DEFAULT_ADAPTER_GUID, provider.guid);
        assertSame(handle, runtime.open(config));
        assertEquals(1, provider.openCount);
        assertTrue(executor.commands.contains(List.of("netsh", "interface", "ipv4", "set", "address",
                "name=" + WindowsWintunRuntime.DEFAULT_INTERFACE_NAME,
                "source=static", "address=10.77.0.2", "mask=255.255.255.0")));

        runtime.close();
        assertEquals(1, provider.closeCount);
        assertTrue(executor.commands.contains(List.of("netsh", "interface", "ipv4", "delete", "address",
                "name=" + WindowsWintunRuntime.DEFAULT_INTERFACE_NAME,
                "address=10.77.0.2", "store=active")));
    }

    private static final class RecordingExecutor implements OsCommandExecutor {
        private final List<List<String>> commands = new ArrayList<>();
        private boolean linkExists;

        @Override
        public CommandResult execute(List<String> command, Path workingDirectory,
                                     java.util.Map<String, String> environment, Duration timeout) {
            commands.add(List.copyOf(command));
            if (command.equals(List.of("ip", "link", "show", "dev", "tailcat-mesh"))) {
                return new CommandResult(linkExists ? 0 : 1, "", "not found");
            }
            return new CommandResult(0, "", "");
        }
    }

    private static final class RecordingWintunProvider implements WintunAdapterProvider {
        private String interfaceName;
        private UUID guid;
        private int openCount;
        private int closeCount;
        private final Adapter adapter = new Adapter("Tailcat Mesh",
                WindowsWintunRuntime.DEFAULT_ADAPTER_GUID, new Object(), true);

        @Override
        public Adapter openOrCreate(String interfaceName, UUID requestedGuid) {
            this.interfaceName = interfaceName;
            this.guid = requestedGuid;
            openCount++;
            return adapter;
        }

        @Override
        public void close(Adapter adapter) {
            closeCount++;
        }
    }
}
