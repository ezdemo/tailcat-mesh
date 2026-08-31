package com.tailcatmesh.agent.virtual;

import com.tailcatmesh.agent.config.AgentConfig;
import com.tailcatmesh.agent.forward.PeerSocksEndpoint;
import com.tailcatmesh.agent.tailcat.TailcatEngine;
import com.tailcatmesh.agent.tailcat.model.ProcessState;
import com.tailcatmesh.agent.tailcat.model.TailcatPeerProxyConfig;
import com.tailcatmesh.agent.tailcat.model.TailcatPeerProxyHandle;
import com.tailcatmesh.protocol.agent.AgentVirtualNetwork;
import com.tailcatmesh.protocol.agent.AgentVirtualNetworkPeer;
import com.tailcatmesh.protocol.agent.AgentVirtualNetworkRuntime;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Composes one Agent's virtual-network runtimes, peer SOCKS adapters, route
 * table, and Java SOCKS router without merging networks into global state.
 */
public final class VirtualNetworkManager implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(VirtualNetworkManager.class.getName());
    private static final Duration PEER_STOP_TIMEOUT = Duration.ofSeconds(5);

    private final AgentConfig config;
    private final TailcatEngine tailcatEngine;
    private final VirtualNetworkRuntimeSupervisor runtimeSupervisor;
    private final VirtualIpRouteTable routeTable = new VirtualIpRouteTable();
    private final MeshSocksRouter socksRouter;
    private final VirtualLanDataPlaneSupervisor dataPlaneSupervisor;
    private final Object lock = new Object();
    private final Map<PeerKey, AppliedPeer> appliedPeers = new HashMap<>();

    public VirtualNetworkManager(AgentConfig config, TailcatEngine tailcatEngine) {
        this(config, tailcatEngine, VirtualLanDataPlaneFactory.create(config));
    }

    public VirtualNetworkManager(AgentConfig config, TailcatEngine tailcatEngine,
                                 VirtualLanDataPlaneSupervisor dataPlaneSupervisor) {
        this.config = Objects.requireNonNull(config, "config");
        this.tailcatEngine = Objects.requireNonNull(tailcatEngine, "tailcatEngine");
        this.runtimeSupervisor = new VirtualNetworkRuntimeSupervisor(config, tailcatEngine);
        this.socksRouter = new MeshSocksRouter(routeTable, 0, Duration.ofSeconds(5));
        this.dataPlaneSupervisor = dataPlaneSupervisor;
    }

    /** Applies the complete desired state for all Mesh Networks. */
    public List<AgentVirtualNetworkRuntime> reconcile(List<AgentVirtualNetwork> desiredNetworks) {
        List<AgentVirtualNetwork> desired = desiredNetworks == null
                ? List.of() : List.copyOf(desiredNetworks);
        Map<UUID, AgentVirtualNetwork> desiredByNetwork = new HashMap<>();
        for (AgentVirtualNetwork network : desired) {
            if (network == null || desiredByNetwork.put(network.networkId(), network) != null) {
                throw new IllegalArgumentException("virtual network desired state contains duplicate entries");
            }
        }

        synchronized (lock) {
            List<AgentVirtualNetworkRuntime> runtimes = runtimeSupervisor.reconcile(desired);
            boolean hasEnabledNetwork = desiredByNetwork.values().stream()
                    .anyMatch(AgentVirtualNetwork::enabled);
            if (!hasEnabledNetwork) {
                stopDataPlaneLocked();
                stopRouterLocked();
                stopAllPeerProxiesLocked();
                routeTable.clear();
                return runtimes;
            }

            reconcilePeerProxiesLocked(desiredByNetwork);
            rebuildRoutesLocked(desired);
            ensureRouterStartedLocked();
            if (dataPlaneSupervisor != null) {
                dataPlaneSupervisor.reconcile(desired, socksRouter.listenEndpoint());
            }
            return runtimes;
        }
    }

    public List<AgentVirtualNetworkRuntime> snapshot() {
        return runtimeSupervisor.snapshot();
    }

    public VirtualIpRouteTable routeTable() {
        return routeTable;
    }

    /** Returns the OS routes currently owned by the opt-in system data plane. */
    public List<OsRoute> dataPlaneRouteSnapshot() {
        synchronized (lock) {
            return dataPlaneSupervisor == null ? List.of() :
                    List.copyOf(dataPlaneSupervisor.routeSnapshot());
        }
    }

    /** Returns bounded diagnostics for the opt-in system data plane. */
    public String dataPlaneDiagnostics() {
        synchronized (lock) {
            return dataPlaneSupervisor == null
                    ? "virtual-lan-data-plane=disabled"
                    : dataPlaneSupervisor.diagnostics();
        }
    }

    public Optional<PeerSocksEndpoint> routerEndpoint() {
        synchronized (lock) {
            if (!socksRouter.isRunning()) {
                return Optional.empty();
            }
            return Optional.of(socksRouter.listenEndpoint());
        }
    }

    /** Returns whether an explicitly enabled system data plane needs reconcile. */
    public boolean isDataPlaneHealthy(List<AgentVirtualNetwork> desiredNetworks) {
        if (dataPlaneSupervisor == null) {
            return true;
        }
        boolean enabled = desiredNetworks != null && desiredNetworks.stream()
                .filter(Objects::nonNull)
                .anyMatch(AgentVirtualNetwork::enabled);
        return !enabled || dataPlaneSupervisor.isRunning();
    }

    /** Stops the Java router, all network-scoped peer SOCKS processes, and servers. */
    public List<AgentVirtualNetworkRuntime> stopAll() {
        synchronized (lock) {
            stopDataPlaneLocked();
            stopRouterLocked();
            stopAllPeerProxiesLocked();
            routeTable.clear();
            return runtimeSupervisor.stopAll();
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            stopDataPlaneLocked();
            stopRouterLocked();
            stopAllPeerProxiesLocked();
            routeTable.clear();
            runtimeSupervisor.stopAll();
            if (dataPlaneSupervisor != null) {
                dataPlaneSupervisor.close();
            }
        }
    }

    private void reconcilePeerProxiesLocked(Map<UUID, AgentVirtualNetwork> desiredByNetwork) {
        Map<PeerKey, AgentVirtualNetworkPeer> desiredPeers = new HashMap<>();
        for (AgentVirtualNetwork network : desiredByNetwork.values()) {
            if (!network.enabled()) {
                continue;
            }
            for (AgentVirtualNetworkPeer peer : network.peers()) {
                if (peer.connBlob() != null) {
                    desiredPeers.put(new PeerKey(network.networkId(), peer.peerDeviceId()), peer);
                }
            }
        }

        for (PeerKey key : new HashSet<>(appliedPeers.keySet())) {
            AgentVirtualNetworkPeer desired = desiredPeers.get(key);
            AppliedPeer current = appliedPeers.get(key);
            if (desired == null || !desired.connBlob().equals(current.connBlob())
                    || current.handle.process().state() != ProcessState.RUNNING) {
                stopPeerProxyLocked(key);
            }
        }

        for (Map.Entry<PeerKey, AgentVirtualNetworkPeer> entry : desiredPeers.entrySet()) {
            if (appliedPeers.containsKey(entry.getKey())) {
                continue;
            }
            PeerKey key = entry.getKey();
            AgentVirtualNetworkPeer peer = entry.getValue();
            try {
                TailcatPeerProxyHandle handle = tailcatEngine.startVirtualNetworkPeerProxy(
                        key.networkId(), key.peerDeviceId(), peer.connBlob(),
                        new TailcatPeerProxyConfig(config.clientKeyPath(), "127.0.0.1", 0));
                if (handle.process().state() == ProcessState.RUNNING) {
                    appliedPeers.put(key, new AppliedPeer(peer.connBlob(), handle));
                }
            } catch (RuntimeException exception) {
                LOGGER.log(Level.FINE, "virtual-network peer SOCKS unavailable for " + key, exception);
            }
        }
    }

    private void rebuildRoutesLocked(List<AgentVirtualNetwork> desired) {
        List<VirtualIpRouteTable.Route> routes = desired.stream()
                .filter(AgentVirtualNetwork::enabled)
                .flatMap(network -> network.peers().stream()
                        .map(peer -> routeFor(network, peer)))
                .filter(Objects::nonNull)
                .toList();
        routeTable.replace(routes);
    }

    private VirtualIpRouteTable.Route routeFor(AgentVirtualNetwork network,
                                               AgentVirtualNetworkPeer peer) {
        if (peer.connBlob() == null) {
            return null;
        }
        AppliedPeer applied = appliedPeers.get(new PeerKey(network.networkId(), peer.peerDeviceId()));
        if (applied == null || applied.handle.process().state() != ProcessState.RUNNING) {
            return null;
        }
        return new VirtualIpRouteTable.Route(
                network.networkId(), peer.peerDeviceId(), peer.virtualIpv4(),
                new PeerSocksEndpoint(applied.handle.localSocksHost(), applied.handle.localSocksPort()));
    }

    private void ensureRouterStartedLocked() {
        if (!socksRouter.isRunning()) {
            socksRouter.start();
        }
    }

    private void stopRouterLocked() {
        socksRouter.stop();
    }

    private void stopDataPlaneLocked() {
        if (dataPlaneSupervisor != null) {
            dataPlaneSupervisor.stop();
        }
    }

    private void stopAllPeerProxiesLocked() {
        for (PeerKey key : new HashSet<>(appliedPeers.keySet())) {
            stopPeerProxyLocked(key);
        }
    }

    private void stopPeerProxyLocked(PeerKey key) {
        AppliedPeer removed = appliedPeers.remove(key);
        if (removed != null) {
            try {
                tailcatEngine.stopVirtualNetworkPeerProxy(key.networkId(), key.peerDeviceId());
            } catch (RuntimeException exception) {
                LOGGER.log(Level.FINE, "could not stop virtual-network peer SOCKS for " + key, exception);
            }
        }
    }

    private record PeerKey(UUID networkId, UUID peerDeviceId) {
    }

    private record AppliedPeer(String connBlob, TailcatPeerProxyHandle handle) {
    }
}
