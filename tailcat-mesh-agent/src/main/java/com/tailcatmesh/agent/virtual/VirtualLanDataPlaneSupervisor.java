package com.tailcatmesh.agent.virtual;

import com.tailcatmesh.agent.forward.PeerSocksEndpoint;
import com.tailcatmesh.protocol.agent.AgentVirtualNetwork;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Composes the TUN adapter, Mesh CIDR route manager, and tun2socks sidecar.
 *
 * <p>The supervisor has no knowledge of Tailcat protocol details. Its only
 * upstream is the Java MeshSocksRouter loopback endpoint.</p>
 */
public final class VirtualLanDataPlaneSupervisor implements AutoCloseable {

    private static final Duration WINDOWS_ROUTE_SETTLE_TIME = Duration.ofSeconds(3);

    private final VirtualLanDataPlaneConfig config;
    private final TunRuntime tunRuntime;
    private final OsRouteManager routeManager;
    private final Tun2SocksSupervisor tun2SocksSupervisor;
    private final VirtualLanRouteStateStore routeStateStore;
    private final List<AutoCloseable> ownedResources;
    private final Tun2SocksCommandFactory commandFactory = new Tun2SocksCommandFactory();
    private final Object lock = new Object();
    private TunHandle tunHandle;
    private TunConfig appliedTunConfig;
    private Tun2SocksSupervisor.ManagedSidecarHandle tun2SocksHandle;
    private boolean routeRecoveryAttempted;

    public VirtualLanDataPlaneSupervisor(VirtualLanDataPlaneConfig config,
                                         TunRuntime tunRuntime,
                                         OsRouteManager routeManager,
                                         Tun2SocksSupervisor tun2SocksSupervisor) {
        this(config, tunRuntime, routeManager, tun2SocksSupervisor, List.of());
    }

    public VirtualLanDataPlaneSupervisor(VirtualLanDataPlaneConfig config,
                                         TunRuntime tunRuntime,
                                         OsRouteManager routeManager,
                                         Tun2SocksSupervisor tun2SocksSupervisor,
                                         List<? extends AutoCloseable> ownedResources) {
        this.config = Objects.requireNonNull(config, "config");
        this.tunRuntime = Objects.requireNonNull(tunRuntime, "tunRuntime");
        this.routeManager = Objects.requireNonNull(routeManager, "routeManager");
        this.tun2SocksSupervisor = Objects.requireNonNull(tun2SocksSupervisor, "tun2SocksSupervisor");
        this.ownedResources = ownedResources == null ? List.of() : List.copyOf(ownedResources);
        this.routeStateStore = new VirtualLanRouteStateStore(config.routeStateFile());
    }

    /**
     * Reconciles all enabled Networks against one Java router endpoint.
     * Disabled/empty state stops the data plane and removes its routes.
     */
    public void reconcile(Collection<AgentVirtualNetwork> desiredNetworks,
                          PeerSocksEndpoint routerEndpoint) {
        Objects.requireNonNull(desiredNetworks, "desiredNetworks");
        List<AgentVirtualNetwork> enabled = desiredNetworks.stream()
                .filter(Objects::nonNull)
                .filter(AgentVirtualNetwork::enabled)
                .toList();
        if (enabled.isEmpty() || routerEndpoint == null) {
            stop();
            return;
        }

        TunConfig desiredTun = buildTunConfig(enabled);
        List<OsRoute> desiredRoutes = buildRoutes(enabled);
        Tun2SocksConfig desiredSidecar = new Tun2SocksConfig(
                config.tun2socksBinary(), config.interfaceName(), routerEndpoint,
                config.tun2socksArgumentTemplate(), config.workingDirectory(),
                config.environment(), config.tun2socksStartupTimeout());
        List<String> desiredCommand = commandFactory.build(desiredSidecar);

        synchronized (lock) {
            boolean platformStateChanged = false;
            try {
                recoverStaleRoutesLocked();
                if (appliedTunConfig != null && !appliedTunConfig.equals(desiredTun)) {
                    stopLocked();
                    platformStateChanged = true;
                }
                if (tun2SocksHandle == null
                        || tun2SocksHandle.state() != com.tailcatmesh.agent.tailcat.model.ProcessState.RUNNING
                        || !tun2SocksHandle.command().equals(desiredCommand)) {
                    if (tun2SocksHandle != null) {
                        tun2SocksSupervisor.stop(tun2SocksHandle);
                    }
                    // The official tun2socks process owns creation of the TUN
                    // device. Java opens/configures that device below.
                    tun2SocksHandle = tun2SocksSupervisor.start(desiredSidecar);
                    platformStateChanged = true;
                }
                if (tunHandle == null || tunHandle.isClosed()) {
                    tunHandle = tunRuntime.open(desiredTun);
                    appliedTunConfig = desiredTun;
                    platformStateChanged = true;
                }
                if (!new HashSet<>(routeManager.snapshot()).equals(new HashSet<>(desiredRoutes))) {
                    platformStateChanged = true;
                }
                List<OsRoute> ownedBeforeReconcile = new ArrayList<>(routeManager.snapshot());
                ownedBeforeReconcile.addAll(desiredRoutes);
                routeStateStore.save(ownedBeforeReconcile);
                routeManager.reconcile(desiredRoutes);
                routeStateStore.save(desiredRoutes);
                if (platformStateChanged) {
                    awaitWindowsRouteSettle();
                }
            } catch (RuntimeException exception) {
                stopLocked(false);
                throw exception;
            }
        }
    }

    public boolean isRunning() {
        synchronized (lock) {
            return tunHandle != null && !tunHandle.isClosed()
                    && tun2SocksHandle != null
                    && tun2SocksHandle.state() == com.tailcatmesh.agent.tailcat.model.ProcessState.RUNNING;
        }
    }

    public List<OsRoute> routeSnapshot() {
        return routeManager.snapshot();
    }

    /** Returns bounded sidecar diagnostics for runtime status and acceptance tests. */
    public String diagnostics() {
        synchronized (lock) {
            if (tun2SocksHandle == null) {
                return "tun2socks=not-started";
            }
            return "tun2socks=" + tun2SocksHandle.state()
                    + ", pid=" + tun2SocksHandle.pid()
                    + ", stdout=" + tun2SocksHandle.stdoutTail()
                    + ", stderr=" + tun2SocksHandle.stderrTail();
        }
    }

    @Override
    public void close() {
        stop();
        tun2SocksSupervisor.close();
        tunRuntime.close();
        for (AutoCloseable resource : ownedResources) {
            try {
                resource.close();
            } catch (Exception ignored) {
                // The process executor is closed on a best-effort shutdown path.
            }
        }
    }

    public void stop() {
        synchronized (lock) {
            // A clean Agent shutdown must also remove routes left by a prior
            // crash before clearing the ownership record.
            recoverStaleRoutesLocked();
            stopLocked(true);
        }
    }

    private TunConfig buildTunConfig(List<AgentVirtualNetwork> enabled) {
        List<Ipv4Cidr> localAddresses = new ArrayList<>();
        for (AgentVirtualNetwork network : enabled) {
            Ipv4Cidr networkCidr = Ipv4Cidr.parse(network.cidr());
            localAddresses.add(new Ipv4Cidr(network.virtualIpv4(), networkCidr.prefixLength()));
        }
        return config.tunConfig(localAddresses);
    }

    private List<OsRoute> buildRoutes(List<AgentVirtualNetwork> enabled) {
        return enabled.stream()
                .map(network -> new OsRoute(
                        network.networkId(), Ipv4Cidr.parse(network.cidr()),
                        config.interfaceName(), null))
                .toList();
    }

    private void stopLocked() {
        stopLocked(true);
    }

    private void stopLocked(boolean clearRouteState) {
        if (tun2SocksHandle != null) {
            tun2SocksSupervisor.stop(tun2SocksHandle);
            tun2SocksHandle = null;
        }
        routeManager.removeAll();
        if (tunHandle != null) {
            tunHandle.close();
            tunHandle = null;
        }
        appliedTunConfig = null;
        if (clearRouteState) {
            routeStateStore.clear();
        }
    }

    private void recoverStaleRoutesLocked() {
        if (routeRecoveryAttempted) {
            return;
        }
        List<OsRoute> stale = routeStateStore.load();
        if (stale.isEmpty()) {
            routeRecoveryAttempted = true;
            return;
        }
        routeManager.removeKnown(stale);
        routeStateStore.clear();
        routeRecoveryAttempted = true;
    }

    /**
     * Windows may report a newly-created Wintun route before its L3 neighbor
     * state is usable by Winsock. Keep this bounded and Windows-specific; it
     * is not a protocol retry and never changes the route set.
     */
    private void awaitWindowsRouteSettle() {
        if (config.adapterGuid() == null) {
            return;
        }
        try {
            Thread.sleep(WINDOWS_ROUTE_SETTLE_TIME.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new TunRuntimeException("interrupted while waiting for Windows Mesh routes", exception);
        }
    }
}
