package com.tailcatmesh.agent.virtual;

import com.tailcatmesh.agent.config.AgentConfig;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VirtualNetworkManagerTest {

    private static final String KEY_A = "nodekey:" + "a".repeat(64);
    private static final String KEY_B = "nodekey:" + "b".repeat(64);

    @TempDir
    Path temporaryDirectory;

    @Test
    void reconcilesServersPeersAndRoutesWithoutCrossNetworkMerging() {
        RecordingEngine engine = new RecordingEngine();
        UUID homeId = UUID.randomUUID();
        UUID devId = UUID.randomUUID();
        AgentConfig config = new AgentConfig(
                URI.create("http://127.0.0.1:8080"), Path.of("tailcat"), temporaryDirectory,
                temporaryDirectory.resolve("identity/server.private.json"),
                temporaryDirectory.resolve("identity/client.private.json"),
                true, null, Duration.ofSeconds(15), Duration.ofSeconds(30));
        AgentVirtualNetwork home = network(homeId, "10.77.0.0/24", "10.77.0.2", "10.77.0.3", KEY_A);
        AgentVirtualNetwork dev = network(devId, "10.78.0.0/24", "10.78.0.2", "10.78.0.3", KEY_B);

        try (VirtualNetworkManager manager = new VirtualNetworkManager(config, engine)) {
            manager.reconcile(List.of(home, dev));
            assertEquals(2, engine.startedServers.size());
            assertEquals(2, engine.startedPeers.size());
            assertEquals(2, manager.routeTable().size());
            assertTrue(manager.routerEndpoint().orElseThrow().port() > 0);
            assertEquals(homeId, manager.routeTable().resolve("10.77.0.3").orElseThrow().networkId());
            assertEquals(devId, manager.routeTable().resolve("10.78.0.3").orElseThrow().networkId());

            manager.reconcile(List.of(home, dev));
            assertEquals(2, engine.startedServers.size());
            assertEquals(2, engine.startedPeers.size(), "unchanged desired state is idempotent");

            AgentVirtualNetwork changedHome = network(
                    homeId, "10.77.0.0/24", "10.77.0.2", "10.77.0.3", KEY_B);
            manager.reconcile(List.of(changedHome, dev));
            assertEquals(3, engine.startedPeers.size());
            assertEquals(1, engine.stoppedPeers.stream().filter(key -> key.networkId().equals(homeId)).count());
            assertEquals(0, engine.stoppedPeers.stream().filter(key -> key.networkId().equals(devId)).count());
            assertEquals(List.of(KEY_B), engine.peerConfigs.get(new PeerKey(homeId, PEER_ID)));

            manager.reconcile(List.of(dev));
            assertEquals(1, manager.routeTable().size());
            assertTrue(manager.routeTable().resolve("10.77.0.3").isEmpty());
            assertEquals(2, engine.stoppedServers.stream().filter(homeId::equals).count());
        }
    }

    private static AgentVirtualNetwork network(UUID networkId, String cidr, String localIp,
                                               String peerIp, String key) {
        return new AgentVirtualNetwork(networkId, "network-" + networkId,
                cidr,
                localIp, true, List.of(new AgentVirtualNetworkPeer(
                        PEER_ID, "peer", peerIp, "tcpeer" + key.substring(8, 16), key)));
    }

    private static final UUID PEER_ID = UUID.randomUUID();

    private static final class RecordingEngine implements TailcatEngine {
        private final Map<UUID, TailcatServerHandle> servers = new HashMap<>();
        private final Map<PeerKey, TailcatPeerProxyHandle> peers = new HashMap<>();
        private final List<UUID> startedServers = new ArrayList<>();
        private final List<UUID> stoppedServers = new ArrayList<>();
        private final List<PeerKey> startedPeers = new ArrayList<>();
        private final List<PeerKey> stoppedPeers = new ArrayList<>();
        private final Map<PeerKey, List<String>> peerConfigs = new HashMap<>();
        private int nextPort = 47_000;

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
        }

        @Override
        public TailcatServerHandle startVirtualNetworkServer(
                UUID networkId, TailcatVirtualNetworkServerConfig config) {
            FakeProcess process = new FakeProcess();
            process.state = ProcessState.RUNNING;
            TailcatServerHandle handle = new TailcatServerHandle(
                    process, "tcserver" + networkId.toString().replace("-", ""), Instant.now());
            servers.put(networkId, handle);
            startedServers.add(networkId);
            return handle;
        }

        @Override
        public void stopVirtualNetworkServer(UUID networkId) {
            TailcatServerHandle handle = servers.remove(networkId);
            if (handle != null) {
                handle.process().stop(Duration.ofSeconds(1));
                stoppedServers.add(networkId);
            }
        }

        @Override
        public TailcatRuntimeStatus getVirtualNetworkRuntimeStatus(UUID networkId) {
            TailcatServerHandle handle = servers.get(networkId);
            return handle == null
                    ? new TailcatRuntimeStatus(ProcessState.STOPPED, null, null, "", 0)
                    : new TailcatRuntimeStatus(handle.process().state(), handle.listenAddress(), null, "", 0);
        }

        @Override
        public TailcatPeerProxyHandle startVirtualNetworkPeerProxy(
                UUID networkId, UUID peerDeviceId, String connBlob, TailcatPeerProxyConfig config) {
            PeerKey key = new PeerKey(networkId, peerDeviceId);
            FakeProcess process = new FakeProcess();
            process.state = ProcessState.RUNNING;
            TailcatPeerProxyHandle handle = new TailcatPeerProxyHandle(
                    peerDeviceId, process, "127.0.0.1", nextPort++, connBlob, Instant.now());
            peers.put(key, handle);
            startedPeers.add(key);
            peerConfigs.put(key, List.of(connBlob.equals("tcpeer" + KEY_A.substring(8, 16)) ? KEY_A : KEY_B));
            return handle;
        }

        @Override
        public void stopVirtualNetworkPeerProxy(UUID networkId, UUID peerDeviceId) {
            PeerKey key = new PeerKey(networkId, peerDeviceId);
            TailcatPeerProxyHandle handle = peers.remove(key);
            if (handle != null) {
                handle.process().stop(Duration.ofSeconds(1));
                stoppedPeers.add(key);
            }
        }

        @Override
        public void shutdown() {
            servers.clear();
            peers.clear();
        }
    }

    private record PeerKey(UUID networkId, UUID peerDeviceId) {
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
