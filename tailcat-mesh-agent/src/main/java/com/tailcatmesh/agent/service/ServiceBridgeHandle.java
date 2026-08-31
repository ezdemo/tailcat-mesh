package com.tailcatmesh.agent.service;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicBoolean;

/** Running local TCP bridge and its Agent-visible runtime projection. */
public final class ServiceBridgeHandle implements AutoCloseable {

    private final ServiceRuntimeConfig config;
    private final ServerSocket serverSocket;
    private final Thread acceptThread;
    private final Instant startedAt;
    private final Set<Socket> activeSockets = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private volatile String status = "READY";
    private volatile String lastError;

    ServiceBridgeHandle(ServiceRuntimeConfig config, ServerSocket serverSocket) {
        this.config = config;
        this.serverSocket = serverSocket;
        this.startedAt = Instant.now();
        this.acceptThread = Thread.ofVirtual()
                .name("tailcat-service-bridge-" + config.serviceId())
                .start(this::acceptLoop);
    }

    public UUID serviceId() {
        return config.serviceId();
    }

    public String bindHost() {
        return config.bindHost();
    }

    public int bridgePort() {
        return serverSocket.getLocalPort();
    }

    public Instant startedAt() {
        return startedAt;
    }

    public String status() {
        return status;
    }

    public String lastError() {
        return lastError;
    }

    public boolean isRunning() {
        return !stopped.get() && !serverSocket.isClosed() && acceptThread.isAlive();
    }

    void markReady() {
        if (!stopped.get()) {
            status = "READY";
        }
    }

    void markFailed(Throwable exception) {
        status = "FAILED";
        lastError = safeError(exception);
    }

    void rememberConnectionError(Throwable exception) {
        lastError = safeError(exception);
    }

    void clearConnectionError() {
        lastError = null;
    }

    void track(Socket socket) {
        activeSockets.add(socket);
    }

    void untrack(Socket socket) {
        activeSockets.remove(socket);
    }

    ServiceRuntimeConfig config() {
        return config;
    }

    @Override
    public void close() {
        stop(Duration.ofSeconds(5));
    }

    public void stop(Duration timeout) {
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
            acceptThread.join(timeout.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void acceptLoop() {
        markReady();
        try {
            while (!stopped.get()) {
                Socket client = serverSocket.accept();
                track(client);
                Thread.ofVirtual()
                        .name("tailcat-service-connection-" + config.serviceId())
                        .start(() -> proxy(client));
            }
        } catch (IOException exception) {
            if (!stopped.get()) {
                markFailed(exception);
            }
        }
    }

    private void proxy(Socket client) {
        Socket upstream = new Socket();
        track(upstream);
        try (client; upstream) {
            int connectTimeoutMillis = timeoutMillis(config.connectTimeout());
            upstream.connect(new java.net.InetSocketAddress(
                    config.upstreamHost(), config.upstreamPort()), connectTimeoutMillis);
            int idleTimeoutMillis = timeoutMillis(config.idleTimeout());
            client.setSoTimeout(idleTimeoutMillis);
            upstream.setSoTimeout(idleTimeoutMillis);
            clearConnectionError();
            AtomicBoolean copyFailed = new AtomicBoolean();

            Thread clientToUpstream = Thread.ofVirtual()
                    .name("tailcat-service-copy-up-" + config.serviceId())
                    .start(() -> copy(client, upstream, copyFailed));
            Thread upstreamToClient = Thread.ofVirtual()
                    .name("tailcat-service-copy-down-" + config.serviceId())
                    .start(() -> copy(upstream, client, copyFailed));
            try {
                clientToUpstream.join();
                upstreamToClient.join();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        } catch (IOException exception) {
            rememberConnectionError(exception);
        } finally {
            untrack(client);
            untrack(upstream);
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
            if (copyFailed.compareAndSet(false, true) && !stopped.get()) {
                rememberConnectionError(exception);
            }
            closeQuietly(source);
            closeQuietly(destination);
        } finally {
            // The proxy owns final socket closure after both directions have
            // completed. Normal EOF is half-closed so a response can still
            // travel in the opposite direction.
        }
    }

    private static int timeoutMillis(Duration timeout) {
        long millis = timeout.toMillis();
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1, millis));
    }

    private static String safeError(Throwable exception) {
        String message = exception == null ? "unknown ServiceBridge failure" : exception.getMessage();
        if (message == null || message.isBlank()) {
            message = exception == null ? "unknown ServiceBridge failure" : exception.getClass().getSimpleName();
        }
        return message.length() <= 2_000 ? message : message.substring(0, 2_000);
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            if (closeable != null) {
                closeable.close();
            }
        } catch (Exception ignored) {
            // Closing a peer socket is best effort during shutdown.
        }
    }
}
