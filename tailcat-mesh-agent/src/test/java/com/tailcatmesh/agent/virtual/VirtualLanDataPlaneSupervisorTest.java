package com.tailcatmesh.agent.virtual;

import com.tailcatmesh.agent.forward.PeerSocksEndpoint;
import com.tailcatmesh.agent.tailcat.SupervisorFixture;
import com.tailcatmesh.protocol.agent.AgentVirtualNetwork;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VirtualLanDataPlaneSupervisorTest {

    @Test
    void reconcilesOnlyMeshCidrsAndKeepsUnchangedSidecar() throws Exception {
        RecordingTunRuntime tun = new RecordingTunRuntime();
        RecordingRouteManager routes = new RecordingRouteManager();
        String classPath = System.getProperty("java.class.path");
        VirtualLanDataPlaneConfig config = new VirtualLanDataPlaneConfig(
                "tailcat-mesh", null, null, javaExecutable(),
                List.of("-cp", classPath, SupervisorFixture.class.getName(), "--tun=${tun}"),
                Path.of(".").toAbsolutePath().normalize(), Map.of(),
                Duration.ofSeconds(1), Duration.ofSeconds(5));
        UUID networkId = UUID.randomUUID();
        AgentVirtualNetwork network = new AgentVirtualNetwork(
                networkId, "home", "10.77.0.0/24", "10.77.0.2", true, List.of());

        try (Tun2SocksSupervisor sidecars = new Tun2SocksSupervisor();
             VirtualLanDataPlaneSupervisor dataPlane = new VirtualLanDataPlaneSupervisor(
                     config, tun, routes, sidecars)) {
            PeerSocksEndpoint router = new PeerSocksEndpoint("127.0.0.1", 41_001);
            dataPlane.reconcile(List.of(network), router);
            assertTrue(waitFor(dataPlane::isRunning, Duration.ofSeconds(5)));
            assertEquals(1, tun.opens.size());
            assertEquals(List.of("10.77.0.0/24"), routes.routes.stream()
                    .map(OsRoute::networkCidr).toList());
            assertEquals("10.77.0.2/24", tun.opens.get(0).localAddresses().get(0).value());

            dataPlane.reconcile(List.of(network), router);
            assertEquals(1, tun.opens.size(), "unchanged data plane is idempotent");
            assertEquals(2, routes.reconcileCount,
                    "the composition layer may reconcile; the route adapter makes it idempotent");
            dataPlane.stop();
            assertTrue(routes.snapshot().isEmpty());
            assertTrue(tun.handles.get(0).isClosed());
        }
    }

    private static Path javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win")
                        ? "java.exe" : "java");
    }

    private static boolean waitFor(java.util.function.BooleanSupplier condition, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(25);
        }
        return condition.getAsBoolean();
    }

    private static final class RecordingTunRuntime implements TunRuntime {
        private final List<TunConfig> opens = new ArrayList<>();
        private final List<TunHandle> handles = new ArrayList<>();

        @Override
        public TunHandle open(TunConfig config) {
            opens.add(config);
            TunHandle handle = new TunHandle(config.interfaceName(), config.adapterGuid(), true, () -> { });
            handles.add(handle);
            return handle;
        }

        @Override
        public void close() {
            handles.forEach(TunHandle::close);
        }
    }

    private static final class RecordingRouteManager implements OsRouteManager {
        private List<OsRoute> routes = List.of();
        private int reconcileCount;

        @Override
        public void reconcile(java.util.Collection<OsRoute> desiredRoutes) {
            reconcileCount++;
            routes = List.copyOf(desiredRoutes);
        }

        @Override
        public void removeAll() {
            routes = List.of();
        }

        @Override
        public List<OsRoute> snapshot() {
            return routes;
        }
    }
}
