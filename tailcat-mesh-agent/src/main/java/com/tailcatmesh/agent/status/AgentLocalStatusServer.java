package com.tailcatmesh.agent.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.FileStore;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Loopback-only status and lifecycle control channel for the desktop shell.
 * The descriptor is deliberately stored beside the Agent state so Electron
 * never has to invent a second identity or runtime model.
 */
public final class AgentLocalStatusServer implements AutoCloseable {

    public static final String DESCRIPTOR_FILE_NAME = "local-status.json";

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentLocalStatusServer.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Path dataDir;
    private final Path descriptorPath;
    private final Supplier<LocalAgentStatus> statusSupplier;
    private final Runnable reconnectAction;
    private final Runnable shutdownAction;
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final Object lifecycleLock = new Object();

    private HttpServer server;
    private ExecutorService executor;
    private String token;

    public AgentLocalStatusServer(Path dataDir,
                                  Supplier<LocalAgentStatus> statusSupplier,
                                  Runnable reconnectAction,
                                  Runnable shutdownAction) {
        this.dataDir = Objects.requireNonNull(dataDir, "dataDir").toAbsolutePath().normalize();
        this.descriptorPath = this.dataDir.resolve(DESCRIPTOR_FILE_NAME);
        this.statusSupplier = Objects.requireNonNull(statusSupplier, "statusSupplier");
        this.reconnectAction = Objects.requireNonNull(reconnectAction, "reconnectAction");
        this.shutdownAction = Objects.requireNonNull(shutdownAction, "shutdownAction");
    }

    public void start() {
        synchronized (lifecycleLock) {
            if (server != null) {
                return;
            }
            try {
                Files.createDirectories(dataDir);
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                executor = Executors.newVirtualThreadPerTaskExecutor();
                server.setExecutor(executor);
                server.createContext("/local/status", this::handleStatus);
                server.createContext("/local/device", this::handleDevice);
                server.createContext("/local/networks", this::handleNetworks);
                server.createContext("/local/reconnect", this::handleReconnect);
                server.createContext("/local/shutdown", this::handleShutdown);
                token = createToken();
                server.start();
                writeDescriptor(new EndpointDescriptor(
                        server.getAddress().getPort(), token, ProcessHandle.current().pid()));
            } catch (IOException | RuntimeException exception) {
                closeLocked();
                throw new IllegalStateException("unable to start the local Agent status API", exception);
            }
        }
    }

    public Path descriptorPath() {
        return descriptorPath;
    }

    public boolean isStarted() {
        synchronized (lifecycleLock) {
            return server != null;
        }
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            closeLocked();
        }
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        if (!authorize(exchange)) {
            return;
        }
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "GET");
            return;
        }
        sendJson(exchange, 200, statusSupplier.get());
    }

    private void handleDevice(HttpExchange exchange) throws IOException {
        if (!authorize(exchange)) {
            return;
        }
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "GET");
            return;
        }
        LocalAgentStatus status = statusSupplier.get();
        ObjectNode response = objectMapper.createObjectNode();
        if (status.deviceId() != null) {
            response.put("id", status.deviceId().toString());
        }
        response.put("name", status.deviceName());
        response.put("status", status.status());
        response.put("controlPlaneStatus", status.controlPlaneStatus());
        response.put("serverUrl", status.serverUrl());
        sendJson(exchange, 200, response);
    }

    private void handleNetworks(HttpExchange exchange) throws IOException {
        if (!authorize(exchange)) {
            return;
        }
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "GET");
            return;
        }
        List<LocalNetworkStatus> networks = statusSupplier.get().networks();
        ArrayNode response = objectMapper.valueToTree(networks);
        sendJson(exchange, 200, response);
    }

    private void handleReconnect(HttpExchange exchange) throws IOException {
        if (!authorize(exchange)) {
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "POST");
            return;
        }
        closeRequestBody(exchange);
        // Reconnect can reconcile several long-lived Peer SOCKS processes and
        // may legitimately take longer than a desktop IPC request timeout.
        // A local control request should acknowledge intent immediately while
        // the Agent continues the refresh in the background.
        sendJson(exchange, 202, objectMapper.createObjectNode().put("accepted", true));
        Thread.startVirtualThread(() -> {
            try {
                reconnectAction.run();
            } catch (RuntimeException exception) {
                LOGGER.warn("local reconnect failed; the next heartbeat will retry", exception);
            }
        });
    }

    private void handleShutdown(HttpExchange exchange) throws IOException {
        if (!authorize(exchange)) {
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "POST");
            return;
        }
        closeRequestBody(exchange);
        sendJson(exchange, 202, objectMapper.createObjectNode().put("accepted", true));
        Thread.startVirtualThread(() -> {
            try {
                shutdownAction.run();
            } catch (RuntimeException ignored) {
                // The Agent shutdown path is best effort after the response is sent.
            }
        });
    }

    private boolean authorize(HttpExchange exchange) throws IOException {
        if (exchange.getRemoteAddress() == null
                || exchange.getRemoteAddress().getAddress() == null
                || !exchange.getRemoteAddress().getAddress().isLoopbackAddress()) {
            sendError(exchange, 403, "loopback access required");
            return false;
        }
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (authorization == null || !authorization.equals("Bearer " + token)) {
            sendError(exchange, 401, "local Agent token required");
            return false;
        }
        return true;
    }

    private void methodNotAllowed(HttpExchange exchange, String method) throws IOException {
        exchange.getResponseHeaders().set("Allow", method);
        sendError(exchange, 405, "method not allowed");
    }

    private void sendError(HttpExchange exchange, int status, String message) throws IOException {
        sendJson(exchange, status, objectMapper.createObjectNode().put("error", message));
    }

    private void sendJson(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] body = objectMapper.writeValueAsBytes(value);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (exchange; OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static void closeRequestBody(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().close();
    }

    private void closeLocked() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        token = null;
        try {
            Files.deleteIfExists(descriptorPath);
        } catch (IOException ignored) {
            // The descriptor is harmless without a live process and will be overwritten on next start.
        }
    }

    private void writeDescriptor(EndpointDescriptor descriptor) throws IOException {
        Path temporary = Files.createTempFile(dataDir, "local-status-", ".tmp");
        try {
            Files.writeString(temporary, objectMapper.writeValueAsString(descriptor), StandardCharsets.UTF_8);
            restrict(temporary);
            try {
                Files.move(temporary, descriptorPath, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, descriptorPath, StandardCopyOption.REPLACE_EXISTING);
            }
            restrict(descriptorPath);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String createToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static void restrict(Path path) {
        try {
            FileStore store = Files.getFileStore(path);
            if (store.supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(path, EnumSet.of(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            }
        } catch (IOException | UnsupportedOperationException ignored) {
            // Windows ACLs are inherited from the per-user Agent directory.
        }
    }

    private record EndpointDescriptor(int port, String token, long pid) {
    }
}
