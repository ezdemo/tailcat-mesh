package com.tailcatmesh.agent.forward;

import com.tailcatmesh.agent.socks.Socks5Client;
import com.tailcatmesh.protocol.agent.AgentForward;
import com.tailcatmesh.protocol.agent.AgentForwardRuntime;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Starts and stops the Agent-owned fixed-port Local Forward listeners. */
public final class LocalForwardManager implements AutoCloseable {

    private static final String BIND_ERROR = "TM-AGENT-007";
    private final PeerSocksResolver peerSocksResolver;
    private final Socks5Client socks5Client;
    private final Map<UUID, LocalForwardHandle> handles = new ConcurrentHashMap<>();

    public LocalForwardManager(PeerSocksResolver peerSocksResolver) {
        this(peerSocksResolver, new Socks5Client());
    }

    LocalForwardManager(PeerSocksResolver peerSocksResolver, Socks5Client socks5Client) {
        this.peerSocksResolver = Objects.requireNonNull(peerSocksResolver, "peerSocksResolver");
        this.socks5Client = Objects.requireNonNull(socks5Client, "socks5Client");
    }

    public LocalForwardHandle start(AgentForward config) {
        Objects.requireNonNull(config, "config");
        stop(config.forwardId());
        ServerSocket serverSocket = null;
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(config.localBindHost(), config.localBindPort()));
            LocalForwardHandle handle = new LocalForwardHandle(
                    config, serverSocket, peerSocksResolver, socks5Client);
            handles.put(config.forwardId(), handle);
            return handle;
        } catch (IOException | RuntimeException exception) {
            closeQuietly(serverSocket);
            if (exception instanceof LocalForwardException localForwardException) {
                throw localForwardException;
            }
            throw new LocalForwardException(
                    BIND_ERROR, "Local Forward could not bind its fixed loopback port", exception);
        }
    }

    public Optional<LocalForwardHandle> handle(UUID forwardId) {
        return Optional.ofNullable(handles.get(forwardId));
    }

    public void stop(UUID forwardId) {
        if (forwardId == null) {
            return;
        }
        LocalForwardHandle handle = handles.remove(forwardId);
        if (handle != null) {
            handle.stop(Duration.ofSeconds(5));
        }
    }

    public List<AgentForwardRuntime> snapshot() {
        return handles.values().stream()
                .map(LocalForwardHandle::runtime)
                .sorted(java.util.Comparator.comparing(AgentForwardRuntime::forwardId))
                .toList();
    }

    public int readyCount() {
        return (int) handles.values().stream()
                .filter(handle -> handle.isRunning() && "READY".equals(handle.runtime().status()))
                .count();
    }

    @Override
    public void close() {
        for (UUID forwardId : handles.keySet()) {
            stop(forwardId);
        }
    }

    @FunctionalInterface
    public interface PeerSocksResolver {
        Optional<PeerSocksEndpoint> resolve(UUID peerDeviceId);
    }

    private static void closeQuietly(ServerSocket socket) {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
            // The socket may already be closed after a bind failure.
        }
    }
}
