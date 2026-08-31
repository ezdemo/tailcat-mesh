package com.tailcatmesh.agent.virtual;

import com.tailcatmesh.agent.config.AgentConfig;
import com.tailcatmesh.agent.tailcat.TailcatEngine;
import com.tailcatmesh.agent.tailcat.TailcatEngineException;
import com.tailcatmesh.agent.tailcat.model.ProcessState;
import com.tailcatmesh.agent.tailcat.model.TailcatRuntimeStatus;
import com.tailcatmesh.agent.tailcat.model.TailcatServerHandle;
import com.tailcatmesh.agent.tailcat.model.TailcatVirtualNetworkServerConfig;
import com.tailcatmesh.protocol.agent.AgentVirtualNetwork;
import com.tailcatmesh.protocol.agent.AgentVirtualNetworkPeer;
import com.tailcatmesh.protocol.agent.AgentVirtualNetworkRuntime;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Reconciles independent Device x MeshNetwork Tailcat server processes.
 *
 * <p>The supervisor owns lifecycle state only. It never constructs a shell
 * command or touches {@link ProcessBuilder}; all Tailcat interaction crosses
 * the {@link TailcatEngine} boundary.</p>
 */
public final class VirtualNetworkRuntimeSupervisor implements AutoCloseable {

    private static final Pattern PUBLIC_KEY = Pattern.compile("nodekey:[0-9a-fA-F]{64}");
    private static final int MAX_ERROR_LENGTH = 2_000;

    private final Path dataDir;
    private final TailcatEngine tailcatEngine;
    private final boolean fullAddress;
    private final String derpMapUrl;
    private final Object lock = new Object();
    private final Map<UUID, AppliedRuntime> applied = new HashMap<>();

    public VirtualNetworkRuntimeSupervisor(AgentConfig config, TailcatEngine tailcatEngine) {
        this(Objects.requireNonNull(config, "config").dataDir(), tailcatEngine,
                config.fullAddress(), config.derpMapUrl());
    }

    public VirtualNetworkRuntimeSupervisor(Path dataDir, TailcatEngine tailcatEngine,
                                           boolean fullAddress, String derpMapUrl) {
        this.dataDir = Objects.requireNonNull(dataDir, "dataDir").toAbsolutePath().normalize();
        this.tailcatEngine = Objects.requireNonNull(tailcatEngine, "tailcatEngine");
        this.fullAddress = fullAddress;
        this.derpMapUrl = derpMapUrl == null || derpMapUrl.isBlank() ? null : derpMapUrl.trim();
    }

    /** Applies a complete network-scoped desired-state snapshot idempotently. */
    public List<AgentVirtualNetworkRuntime> reconcile(List<AgentVirtualNetwork> desiredNetworks) {
        List<AgentVirtualNetwork> desired = desiredNetworks == null ? List.of() : List.copyOf(desiredNetworks);
        Map<UUID, AgentVirtualNetwork> desiredById = new HashMap<>();
        for (AgentVirtualNetwork network : desired) {
            if (network == null || desiredById.put(network.networkId(), network) != null) {
                throw new IllegalArgumentException("virtual network desired state contains duplicate entries");
            }
        }

        synchronized (lock) {
            for (UUID networkId : new HashSet<>(applied.keySet())) {
                if (!desiredById.containsKey(networkId)) {
                    stopLocked(networkId);
                }
            }
            for (AgentVirtualNetwork network : desired) {
                if (!network.enabled()) {
                    stopLocked(network.networkId());
                    continue;
                }
                reconcileOneLocked(network);
            }
            return snapshotLocked();
        }
    }

    public List<AgentVirtualNetworkRuntime> snapshot() {
        synchronized (lock) {
            return snapshotLocked();
        }
    }

    /** Stops every virtual-network process and returns the stopped report. */
    public List<AgentVirtualNetworkRuntime> stopAll() {
        synchronized (lock) {
            List<UUID> networkIds = new ArrayList<>(applied.keySet());
            networkIds.sort(Comparator.naturalOrder());
            List<AgentVirtualNetworkRuntime> stopped = new ArrayList<>();
            for (UUID networkId : networkIds) {
                try {
                    tailcatEngine.stopVirtualNetworkServer(networkId);
                } catch (RuntimeException ignored) {
                    // Shutdown is best effort; the engine owns the process cleanup.
                }
                stopped.add(new AgentVirtualNetworkRuntime(networkId, "STOPPED", null, null, null));
            }
            applied.clear();
            return List.copyOf(stopped);
        }
    }

    @Override
    public void close() {
        stopAll();
    }

    private void reconcileOneLocked(AgentVirtualNetwork desired) {
        Path keyPath = serverKeyPath(desired.networkId());
        TailcatVirtualNetworkServerConfig nextConfig = new TailcatVirtualNetworkServerConfig(
                keyPath, allowedClientKeys(desired.peers()), fullAddress, derpMapUrl);
        AppliedRuntime current = applied.get(desired.networkId());
        boolean configurationChanged = current == null || !nextConfig.equals(current.config);
        boolean running = current != null && isRunning(desired.networkId());
        if (!configurationChanged && running) {
            current.desired = desired;
            current.runtime = runtime(desired.networkId(), current);
            return;
        }
        if (current != null) {
            stopLocked(desired.networkId());
        }

        AppliedRuntime replacement = new AppliedRuntime(desired, nextConfig);
        applied.put(desired.networkId(), replacement);
        try {
            tailcatEngine.ensureVirtualNetworkServerKey(desired.networkId(), keyPath);
            TailcatServerHandle handle = tailcatEngine.startVirtualNetworkServer(
                    desired.networkId(), nextConfig);
            replacement.handle = handle;
            replacement.runtime = new AgentVirtualNetworkRuntime(
                    desired.networkId(), "READY", handle.listenAddress(), null, null);
        } catch (RuntimeException exception) {
            String errorCode = exception instanceof TailcatEngineException engineException
                    ? engineException.code() : "TM-AGENT-003";
            replacement.runtime = new AgentVirtualNetworkRuntime(
                    desired.networkId(), "ERROR", null, errorCode, boundedError(exception));
        }
    }

    private boolean isRunning(UUID networkId) {
        TailcatRuntimeStatus status = tailcatEngine.getVirtualNetworkRuntimeStatus(networkId);
        return status != null && status.state() == ProcessState.RUNNING
                && status.listenAddress() != null && !status.listenAddress().isBlank();
    }

    private AgentVirtualNetworkRuntime runtime(UUID networkId, AppliedRuntime current) {
        TailcatRuntimeStatus status = tailcatEngine.getVirtualNetworkRuntimeStatus(networkId);
        if (status == null) {
            return current.runtime;
        }
        if (status.state() == ProcessState.RUNNING && status.listenAddress() != null
                && !status.listenAddress().isBlank()) {
            return new AgentVirtualNetworkRuntime(networkId, "READY", status.listenAddress(), null, null);
        }
        if (status.state() == ProcessState.STARTING) {
            return new AgentVirtualNetworkRuntime(networkId, "STARTING", status.listenAddress(), null, null);
        }
        if (status.state() == ProcessState.STOPPED && current.handle == null) {
            return current.runtime == null
                    ? new AgentVirtualNetworkRuntime(networkId, "STOPPED", null, null, null)
                    : current.runtime;
        }
        String error = status.stderrTail();
        return new AgentVirtualNetworkRuntime(networkId, "ERROR", null, "TM-AGENT-003",
                boundedError(error));
    }

    private void stopLocked(UUID networkId) {
        AppliedRuntime removed = applied.remove(networkId);
        if (removed != null) {
            try {
                tailcatEngine.stopVirtualNetworkServer(networkId);
            } catch (RuntimeException ignored) {
                // Reconcile remains idempotent even if a process already exited.
            }
        }
    }

    private List<AgentVirtualNetworkRuntime> snapshotLocked() {
        return applied.keySet().stream()
                .sorted()
                .map(networkId -> {
                    AppliedRuntime current = applied.get(networkId);
                    current.runtime = runtime(networkId, current);
                    return current.runtime;
                })
                .toList();
    }

    private Path serverKeyPath(UUID networkId) {
        return dataDir.resolve("identity")
                .resolve("virtual-networks")
                .resolve(networkId.toString())
                .resolve("server.private.json");
    }

    private static List<String> allowedClientKeys(List<AgentVirtualNetworkPeer> peers) {
        return peers.stream()
                .map(AgentVirtualNetworkPeer::clientPublicKey)
                .filter(Objects::nonNull)
                .filter(key -> PUBLIC_KEY.matcher(key).matches())
                .distinct()
                .sorted()
                .toList();
    }

    private static String boundedError(Throwable exception) {
        return boundedError(exception == null ? null : exception.getMessage());
    }

    private static String boundedError(String value) {
        if (value == null || value.isBlank()) {
            return "virtual-network Tailcat runtime is not running";
        }
        String normalized = value.replace('\r', ' ').replace('\n', ' ').trim();
        return normalized.length() <= MAX_ERROR_LENGTH
                ? normalized : normalized.substring(0, MAX_ERROR_LENGTH);
    }

    private static final class AppliedRuntime {
        private AgentVirtualNetwork desired;
        private final TailcatVirtualNetworkServerConfig config;
        private TailcatServerHandle handle;
        private AgentVirtualNetworkRuntime runtime;

        private AppliedRuntime(AgentVirtualNetwork desired,
                               TailcatVirtualNetworkServerConfig config) {
            this.desired = desired;
            this.config = config;
            this.runtime = new AgentVirtualNetworkRuntime(
                    desired.networkId(), "STARTING", null, null, null);
        }
    }
}
