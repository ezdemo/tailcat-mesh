package com.tailcatmesh.agent.forward;

import com.tailcatmesh.agent.socks.Socks5Client;
import com.tailcatmesh.protocol.agent.AgentForward;
import com.tailcatmesh.protocol.agent.AgentForwardRuntime;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** One fixed-port loopback listener owned by the Agent. */
public final class LocalForwardHandle implements AutoCloseable {

    private static final String BIND_ERROR = "TM-AGENT-007";
    private static final String UPSTREAM_ERROR = "TM-AGENT-008";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private final AgentForward config;
    private final ServerSocket serverSocket;
    private final LocalForwardManager.PeerSocksResolver peerSocksResolver;
    private final Socks5Client socks5Client;
    private final Thread acceptThread;
    private final Set<Socket> activeSockets = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private volatile String status = "STARTING";
    private volatile String errorCode;
    private volatile String lastError;

    LocalForwardHandle(AgentForward config, ServerSocket serverSocket,
                       LocalForwardManager.PeerSocksResolver peerSocksResolver,
                       Socks5Client socks5Client) {
        this.config = Objects.requireNonNull(config, "config");
        this.serverSocket = Objects.requireNonNull(serverSocket, "serverSocket");
        this.peerSocksResolver = Objects.requireNonNull(peerSocksResolver, "peerSocksResolver");
        this.socks5Client = Objects.requireNonNull(socks5Client, "socks5Client");
        this.acceptThread = Thread.ofVirtual()
                .name("tailcat-forward-" + config.forwardId())
                .start(this::acceptLoop);
    }

    public UUID forwardId() {
        return config.forwardId();
    }

    public AgentForward config() {
        return config;
    }

    public String bindHost() {
        return config.localBindHost();
    }

    public int localPort() {
        return serverSocket.getLocalPort();
    }

    public boolean isRunning() {
        return !stopped.get() && !serverSocket.isClosed() && acceptThread.isAlive();
    }

    public AgentForwardRuntime runtime() {
        if (stopped.get()) {
            return new AgentForwardRuntime(config.forwardId(), "STOPPED", null, null);
        }
        return new AgentForwardRuntime(config.forwardId(), status, errorCode, lastError);
    }

    @Override
    public void close() {
        stop(Duration.ofSeconds(5));
    }

    public void stop(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        status = "STOPPED";
        closeQuietly(serverSocket);
        for (Socket socket : activeSockets) {
            closeQuietly(socket);
        }
        acceptThread.interrupt();
        try {
            acceptThread.join(Math.max(1L, timeout.toMillis()));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void acceptLoop() {
        status = "READY";
        try {
            while (!stopped.get()) {
                Socket client = serverSocket.accept();
                activeSockets.add(client);
                Thread.ofVirtual()
                        .name("tailcat-forward-connection-" + config.forwardId())
                        .start(() -> proxy(client));
            }
        } catch (IOException exception) {
            if (!stopped.get()) {
                status = "ERROR";
                rememberError(BIND_ERROR, exception);
            }
        }
    }

    private void proxy(Socket client) {
        Socket upstream = null;
        try {
            PeerSocksEndpoint peerSocks = peerSocksResolver.resolve(config.peerDeviceId())
                    .orElseThrow(() -> new LocalForwardException(
                            UPSTREAM_ERROR, "Peer SOCKS is not available"));
            Integer remoteBridgePort = config.remoteBridgePort();
            if (remoteBridgePort == null) {
                throw new LocalForwardException(
                        UPSTREAM_ERROR, "remote ServiceBridge is not ready");
            }
            upstream = socks5Client.connect(
                    peerSocks.host(), peerSocks.port(), "server.tailcat", remoteBridgePort,
                    CONNECT_TIMEOUT);
            activeSockets.add(upstream);
            clearError();
            copyBidirectionally(client, upstream);
        } catch (LocalForwardException exception) {
            rememberError(exception.code(), exception);
        } catch (RuntimeException exception) {
            rememberError(UPSTREAM_ERROR, exception);
        } finally {
            activeSockets.remove(client);
            if (upstream != null) {
                activeSockets.remove(upstream);
            }
            closeQuietly(client);
            closeQuietly(upstream);
        }
    }

    private void copyBidirectionally(Socket client, Socket upstream) {
        AtomicBoolean copyFailed = new AtomicBoolean();
        Thread clientToUpstream = Thread.ofVirtual()
                .name("tailcat-forward-copy-up-" + config.forwardId())
                .start(() -> copy(client, upstream, copyFailed));
        Thread upstreamToClient = Thread.ofVirtual()
                .name("tailcat-forward-copy-down-" + config.forwardId())
                .start(() -> copy(upstream, client, copyFailed));
        try {
            clientToUpstream.join();
            upstreamToClient.join();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            closeQuietly(client);
            closeQuietly(upstream);
        }
    }

    private void copy(Socket source, Socket destination, AtomicBoolean copyFailed) {
        try {
            source.getInputStream().transferTo(destination.getOutputStream());
            destination.getOutputStream().flush();
            destination.shutdownOutput();
        } catch (IOException exception) {
            if (!stopped.get() && copyFailed.compareAndSet(false, true)) {
                rememberError(UPSTREAM_ERROR, exception);
            }
            closeQuietly(source);
            closeQuietly(destination);
        }
    }

    private void rememberError(String code, Throwable exception) {
        errorCode = code;
        String message = exception == null ? "Local Forward connection failed" : exception.getMessage();
        if (message == null || message.isBlank()) {
            message = exception == null ? "Local Forward connection failed"
                    : exception.getClass().getSimpleName();
        }
        lastError = message.length() <= 2_000 ? message : message.substring(0, 2_000);
    }

    private void clearError() {
        errorCode = null;
        lastError = null;
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            if (closeable != null) {
                closeable.close();
            }
        } catch (Exception ignored) {
            // Connection cleanup is best effort.
        }
    }
}
