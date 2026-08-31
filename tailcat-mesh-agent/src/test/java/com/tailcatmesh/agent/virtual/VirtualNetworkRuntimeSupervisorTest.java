package com.tailcatmesh.agent.virtual;

import com.tailcatmesh.agent.tailcat.TailcatEngine;
import com.tailcatmesh.agent.tailcat.model.ManagedProcess;
import com.tailcatmesh.agent.tailcat.model.ProcessState;
import com.tailcatmesh.agent.tailcat.model.TailcatIdentity;
import com.tailcatmesh.agent.tailcat.model.TailcatIdentityConfig;
import com.tailcatmesh.agent.tailcat.model.TailcatPeerProxyConfig;
import com.tailcatmesh.agent.tailcat.model.TailcatPeerProxyHandle;
import com.tailcatmesh.agent.tailcat.model.TailcatPingResult;
import com.tailcatmesh.agent.tailcat.model.TailcatRuntimeStatus;
import com.tailcatmesh.agent.tailcat.model.TailcatServerConfig;
import com.tailcatmesh.agent.tailcat.model.TailcatServerHandle;
import com.tailcatmesh.agent.tailcat.model.TailcatTokenInfo;
import com.tailcatmesh.agent.tailcat.model.TailcatVersion;
import com.tailcatmesh.agent.tailcat.model.TailcatVirtualNetworkServerConfig;
import com.tailcatmesh.protocol.agent.AgentVirtualNetwork;
import com.tailcatmesh.protocol.agent.AgentVirtualNetworkPeer;
import com.tailcatmesh.protocol.agent.AgentVirtualNetworkRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VirtualNetworkRuntimeSupervisorTest {

    private static final String KEY_A = "nodekey:" + "a".repeat(64);
    private static final String KEY_B = "nodekey:" + "b".repeat(64);
    private static final String KEY_C = "nodekey:" + "c".repeat(64);

    @TempDir
    Path temporaryDirectory;

    @Test
    void keepsRuntimeAndAllowlistIndependentPerNetwork() {
        RecordingEngine engine = new RecordingEngine();
        UUID firstNetwork = UUID.randomUUID();
        UUID secondNetwork = UUID.randomUUID();
        AgentVirtualNetwork first = network(firstNetwork, "10.77.0.2",
                peer(UUID.randomUUID(), "10.77.0.3", KEY_A));
        AgentVirtualNetwork second = network(secondNetwork, "10.78.0.2",
                peer(UUID.randomUUID(), "10.78.0.3", KEY_C));

        try (VirtualNetworkRuntimeSupervisor supervisor = new VirtualNetworkRuntimeSupervisor(
                temporaryDirectory, engine, true, null)) {
            List<AgentVirtualNetworkRuntime> firstSnapshot = supervisor.reconcile(List.of(first, second));

            assertEquals(2, firstSnapshot.size());
            assertEquals(2, engine.started.size());
            assertEquals(List.of(KEY_A), engine.configs.get(firstNetwork).allowedClientPublicKeys());
            assertEquals(List.of(KEY_C), engine.configs.get(secondNetwork).allowedClientPublicKeys());
            assertTrue(engine.keyPaths.get(firstNetwork).toString().contains(firstNetwork.toString()));
            assertTrue(engine.keyPaths.get(secondNetwork).toString().contains(secondNetwork.toString()));

            supervisor.reconcile(List.of(first, second));
            assertEquals(2, engine.started.size(), "same desired state must not restart runtimes");

            AgentVirtualNetwork changedFirst = network(firstNetwork, "10.77.0.2",
                    peer(UUID.randomUUID(), "10.77.0.4", KEY_B));
            supervisor.reconcile(List.of(changedFirst, second));
            assertEquals(3, engine.started.size());
            assertEquals(1, engine.stopped.stream().filter(firstNetwork::equals).count());
            assertEquals(0, engine.stopped.stream().filter(secondNetwork::equals).count(),
                    "the second network is not restarted by a first-network change");
            assertEquals(List.of(KEY_B), engine.configs.get(firstNetwork).allowedClientPublicKeys());
        }
        assertEquals(3, engine.stopped.stream().filter(id -> id.equals(firstNetwork)
                || id.equals(secondNetwork)).count());
    }

    @Test
    void removingANetworkStopsOnlyThatRuntime() {
        RecordingEngine engine = new RecordingEngine();
        UUID firstNetwork = UUID.randomUUID();
        UUID secondNetwork = UUID.randomUUID();
        AgentVirtualNetwork first = network(firstNetwork, "10.77.0.2");
        AgentVirtualNetwork second = network(secondNetwork, "10.78.0.2");

        try (VirtualNetworkRuntimeSupervisor supervisor = new VirtualNetworkRuntimeSupervisor(
                temporaryDirectory, engine, false, null)) {
            supervisor.reconcile(List.of(first, second));
            supervisor.reconcile(List.of(second));

            assertEquals(List.of(secondNetwork), supervisor.snapshot().stream()
                    .map(AgentVirtualNetworkRuntime::networkId).toList());
            assertEquals(List.of(firstNetwork), engine.stopped);
        }
    }

    @Test
    void networkWithNoPeersUsesAnEmptyAllowlist() {
        RecordingEngine engine = new RecordingEngine();
        UUID networkId = UUID.randomUUID();
        AgentVirtualNetwork network = network(networkId, "10.77.0.2");

        try (VirtualNetworkRuntimeSupervisor supervisor = new VirtualNetworkRuntimeSupervisor(
                temporaryDirectory, engine, true, null)) {
            supervisor.reconcile(List.of(network));

            assertTrue(engine.configs.get(networkId).allowedClientPublicKeys().isEmpty());
            assertEquals(List.of(), engine.configs.get(networkId).allowedClientPublicKeys());
        }
    }

    private static AgentVirtualNetwork network(UUID networkId, String virtualIp,
                                                AgentVirtualNetworkPeer... peers) {
        return new AgentVirtualNetwork(networkId, "network-" + networkId, "10.77.0.0/24",
                virtualIp, true, List.of(peers));
    }

    private static AgentVirtualNetworkPeer peer(UUID deviceId, String virtualIp, String publicKey) {
        return new AgentVirtualNetworkPeer(deviceId, "peer-" + deviceId, virtualIp,
                "tcpeer" + deviceId.toString().replace("-", ""), publicKey);
    }

    private static final class RecordingEngine implements TailcatEngine {
        private final Map<UUID, TailcatServerHandle> handles = new HashMap<>();
        private final Map<UUID, TailcatVirtualNetworkServerConfig> configs = new HashMap<>();
        private final Map<UUID, Path> keyPaths = new HashMap<>();
        private final List<UUID> started = new ArrayList<>();
        private final List<UUID> stopped = new ArrayList<>();

        @Override
        public TailcatVersion getVersion() {
            return new TailcatVersion(0, 3, 0, "tailcat v0.3.0");
        }

        @Override
        public TailcatIdentity ensureIdentity(TailcatIdentityConfig config) {
            return new TailcatIdentity(config.serverKeyPath(), config.clientKeyPath(), KEY_A);
        }

        @Override
        public TailcatServerHandle startServer(TailcatServerConfig config) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void stopServer() {
        }

        @Override
        public void restartServer(TailcatServerConfig config) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TailcatPeerProxyHandle startPeerProxy(UUID peerDeviceId, String connBlob,
                                                      TailcatPeerProxyConfig config) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void stopPeerProxy(UUID peerDeviceId) {
        }

        @Override
        public TailcatPingResult ping(String connBlob, Duration timeout) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TailcatTokenInfo parseToken(String connBlob) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TailcatRuntimeStatus getRuntimeStatus() {
            return new TailcatRuntimeStatus(ProcessState.STOPPED, null, null, "", 0);
        }

        @Override
        public void ensureVirtualNetworkServerKey(UUID networkId, Path serverKeyPath) {
            keyPaths.put(networkId, serverKeyPath);
        }

        @Override
        public TailcatServerHandle startVirtualNetworkServer(
                UUID networkId, TailcatVirtualNetworkServerConfig config) {
            FakeProcess process = new FakeProcess();
            process.state = ProcessState.RUNNING;
            TailcatServerHandle handle = new TailcatServerHandle(
                    process, "tcvirtual" + networkId.toString().replace("-", ""), Instant.now());
            configs.put(networkId, config);
            handles.put(networkId, handle);
            started.add(networkId);
            return handle;
        }

        @Override
        public void stopVirtualNetworkServer(UUID networkId) {
            TailcatServerHandle handle = handles.remove(networkId);
            if (handle != null) {
                handle.process().stop(Duration.ofSeconds(1));
                stopped.add(networkId);
            }
        }

        @Override
        public TailcatRuntimeStatus getVirtualNetworkRuntimeStatus(UUID networkId) {
            TailcatServerHandle handle = handles.get(networkId);
            if (handle == null) {
                return new TailcatRuntimeStatus(ProcessState.STOPPED, null, null, "", 0);
            }
            return new TailcatRuntimeStatus(handle.process().state(), handle.listenAddress(), null, "", 0);
        }

        @Override
        public void shutdown() {
            handles.clear();
        }
    }

    private static final class FakeProcess implements ManagedProcess {
        private volatile ProcessState state = ProcessState.STOPPED;

        @Override
        public ProcessState state() {
            return state;
        }

        @Override
        public long pid() {
            return 1;
        }

        @Override
        public Instant startedAt() {
            return Instant.now();
        }

        @Override
        public int restartCount() {
            return 0;
        }

        @Override
        public void stop(Duration timeout) {
            state = ProcessState.STOPPED;
        }
    }
}
