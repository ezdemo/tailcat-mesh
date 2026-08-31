package com.tailcatmesh.agent.virtual;

import com.tailcatmesh.agent.forward.PeerSocksEndpoint;
import com.tailcatmesh.agent.socks.Socks5Client;
import com.tailcatmesh.agent.socks.Socks5Exception;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Java SOCKS5 router used by a future tun2socks sidecar.
 *
 * <p>Only no-authentication TCP CONNECT to a mapped virtual IPv4 is
 * supported. The destination is translated to {@code server.tailcat:<port>}
 * through the target Network x Peer Tailcat SOCKS endpoint. UDP, BIND,
 * IPv6, and arbitrary domain routing are deliberately rejected.</p>
 */
public final class MeshSocksRouter implements AutoCloseable {

    private static final String BIND_ERROR = "TM-AGENT-012";
    private static final String ROUTE_ERROR = "TM-AGENT-013";
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(5);

    private final VirtualIpRouteTable routeTable;
    private final Socks5Client socks5Client;
    private final Duration connectTimeout;
    private final int listenPort;
    private final Set<Socket> activeSockets = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean stopped = new AtomicBoolean(true);
    private final Object lifecycleLock = new Object();

    private volatile ServerSocket serverSocket;
    private volatile Thread acceptThread;

    public MeshSocksRouter(VirtualIpRouteTable routeTable, int listenPort, Duration connectTimeout) {
        this(routeTable, new Socks5Client(), listenPort, connectTimeout);
    }

    MeshSocksRouter(VirtualIpRouteTable routeTable, Socks5Client socks5Client,
                    int listenPort, Duration connectTimeout) {
        this.routeTable = java.util.Objects.requireNonNull(routeTable, "routeTable");
        this.socks5Client = java.util.Objects.requireNonNull(socks5Client, "socks5Client");
        this.connectTimeout = positive(connectTimeout);
        if (listenPort < 0 || listenPort > 65_535) {
            throw new IllegalArgumentException("listenPort must be 0 or between 1 and 65535");
        }
        this.listenPort = listenPort;
    }

    /** Binds only loopback; port 0 requests an ephemeral local listener. */
    public void start() {
        synchronized (lifecycleLock) {
            if (!stopped.get()) {
                return;
            }
            ServerSocket bound = null;
            try {
                bound = new ServerSocket();
                bound.setReuseAddress(true);
                bound.bind(new InetSocketAddress("127.0.0.1", listenPort));
                serverSocket = bound;
                stopped.set(false);
                acceptThread = Thread.ofVirtual().name("tailcat-mesh-socks-router")
                        .start(this::acceptLoop);
            } catch (IOException | RuntimeException exception) {
                closeQuietly(bound);
                throw new MeshSocksRouterException(BIND_ERROR,
                        "MeshSocksRouter could not bind its loopback listener", exception);
            }
        }
    }

    public boolean isRunning() {
        ServerSocket bound = serverSocket;
        Thread thread = acceptThread;
        return !stopped.get() && bound != null && !bound.isClosed()
                && thread != null && thread.isAlive();
    }

    public PeerSocksEndpoint listenEndpoint() {
        ServerSocket bound = serverSocket;
        if (bound == null || bound.isClosed()) {
            throw new IllegalStateException("MeshSocksRouter is not started");
        }
        return new PeerSocksEndpoint("127.0.0.1", bound.getLocalPort());
    }

    public void stop() {
        stop(STOP_TIMEOUT);
    }

    public void stop(Duration timeout) {
        java.util.Objects.requireNonNull(timeout, "timeout");
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        closeQuietly(serverSocket);
        for (Socket socket : activeSockets) {
            closeQuietly(socket);
        }
        Thread thread = acceptThread;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(Math.max(1L, timeout.toMillis()));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        serverSocket = null;
        acceptThread = null;
    }

    @Override
    public void close() {
        stop();
    }

    private void acceptLoop() {
        ServerSocket bound = serverSocket;
        try {
            while (!stopped.get() && bound != null) {
                Socket client = bound.accept();
                activeSockets.add(client);
                Thread.ofVirtual().name("tailcat-mesh-socks-connection")
                        .start(() -> route(client));
            }
        } catch (IOException exception) {
            if (!stopped.get()) {
                closeQuietly(bound);
            }
        }
    }

    private void route(Socket client) {
        Socket upstream = null;
        try {
            client.setSoTimeout(timeoutMillis(connectTimeout));
            InputStream input = client.getInputStream();
            OutputStream output = client.getOutputStream();
            negotiate(input, output);
            Request request = readConnectRequest(input);
            VirtualIpRouteTable.Route route = routeTable.resolve(request.virtualIpv4())
                    .orElseThrow(() -> new MeshSocksRouterException(ROUTE_ERROR,
                            "no virtual network route for " + request.virtualIpv4()));
            PeerSocksEndpoint peerSocks = route.peerSocks();
            upstream = socks5Client.connect(peerSocks.host(), peerSocks.port(),
                    "server.tailcat", request.targetPort(), connectTimeout);
            writeReply(output, 0x00);
            client.setSoTimeout(0);
            upstream.setSoTimeout(0);
            activeSockets.add(upstream);
            copyBidirectionally(client, upstream);
        } catch (MeshSocksRouterException exception) {
            sendFailure(client, replyCode(exception));
        } catch (Socks5Exception exception) {
            sendFailure(client, 0x05);
        } catch (IOException | RuntimeException exception) {
            sendFailure(client, 0x01);
        } finally {
            activeSockets.remove(client);
            if (upstream != null) {
                activeSockets.remove(upstream);
            }
            closeQuietly(client);
            closeQuietly(upstream);
        }
    }

    private static void negotiate(InputStream input, OutputStream output) throws IOException {
        int version = readUnsignedByte(input);
        int methodCount = readUnsignedByte(input);
        if (version != 0x05 || methodCount < 1) {
            throw new MeshSocksRouterException(ROUTE_ERROR, "SOCKS5 greeting is invalid");
        }
        byte[] methods = readFully(input, methodCount);
        boolean noAuth = false;
        for (byte method : methods) {
            if ((method & 0xff) == 0x00) {
                noAuth = true;
                break;
            }
        }
        output.write(new byte[]{0x05, noAuth ? 0x00 : (byte) 0xff});
        output.flush();
        if (!noAuth) {
            throw new MeshSocksRouterException(ROUTE_ERROR,
                    "SOCKS5 no-authentication is required");
        }
    }

    private static Request readConnectRequest(InputStream input) throws IOException {
        int version = readUnsignedByte(input);
        int command = readUnsignedByte(input);
        int reserved = readUnsignedByte(input);
        int addressType = readUnsignedByte(input);
        if (version != 0x05 || reserved != 0x00) {
            throw new MeshSocksRouterException(ROUTE_ERROR, "SOCKS5 request header is invalid");
        }
        if (command != 0x01) {
            throw new MeshSocksRouterException("TM-AGENT-014", "only SOCKS5 CONNECT is supported");
        }
        if (addressType != 0x01) {
            throw new MeshSocksRouterException("TM-AGENT-015",
                    "only virtual IPv4 SOCKS5 destinations are supported");
        }
        byte[] address = readFully(input, 4);
        int targetPort = (readUnsignedByte(input) << 8) | readUnsignedByte(input);
        if (targetPort < 1) {
            throw new MeshSocksRouterException(ROUTE_ERROR, "target port must be between 1 and 65535");
        }
        return new Request((address[0] & 0xff) + "." + (address[1] & 0xff) + "."
                + (address[2] & 0xff) + "." + (address[3] & 0xff), targetPort);
    }

    private static void writeReply(OutputStream output, int replyCode) throws IOException {
        output.write(new byte[]{0x05, (byte) replyCode, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00});
        output.flush();
    }

    private static void sendFailure(Socket client, int replyCode) {
        try {
            if (!client.isClosed() && client.isConnected()) {
                writeReply(client.getOutputStream(), replyCode);
            }
        } catch (IOException ignored) {
            // The caller may have already closed the connection.
        }
    }

    private static int replyCode(MeshSocksRouterException exception) {
        return switch (exception.code()) {
            case "TM-AGENT-014" -> 0x07; // command not supported
            case "TM-AGENT-015" -> 0x08; // address type not supported
            case ROUTE_ERROR -> 0x04; // host unreachable / no virtual route
            default -> 0x01;
        };
    }

    private void copyBidirectionally(Socket client, Socket upstream) {
        AtomicBoolean failed = new AtomicBoolean();
        Thread up = Thread.ofVirtual().name("tailcat-mesh-socks-copy-up")
                .start(() -> copy(client, upstream, failed));
        Thread down = Thread.ofVirtual().name("tailcat-mesh-socks-copy-down")
                .start(() -> copy(upstream, client, failed));
        try {
            up.join();
            down.join();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            closeQuietly(client);
            closeQuietly(upstream);
        }
    }

    private static void copy(Socket source, Socket destination, AtomicBoolean failed) {
        try {
            source.getInputStream().transferTo(destination.getOutputStream());
            destination.getOutputStream().flush();
            destination.shutdownOutput();
        } catch (IOException exception) {
            failed.compareAndSet(false, true);
            closeQuietly(source);
            closeQuietly(destination);
        }
    }

    private static byte[] readFully(InputStream input, int length) throws IOException {
        byte[] result = new byte[length];
        int offset = 0;
        while (offset < result.length) {
            int read = input.read(result, offset, result.length - offset);
            if (read < 0) {
                throw new EOFException("SOCKS5 peer closed during handshake");
            }
            offset += read;
        }
        return result;
    }

    private static int readUnsignedByte(InputStream input) throws IOException {
        int value = input.read();
        if (value < 0) {
            throw new EOFException("SOCKS5 peer closed during handshake");
        }
        return value;
    }

    private static int timeoutMillis(Duration timeout) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, timeout.toMillis()));
    }

    private static Duration positive(Duration value) {
        java.util.Objects.requireNonNull(value, "connectTimeout");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("connectTimeout must be positive");
        }
        return value;
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            if (closeable != null) {
                closeable.close();
            }
        } catch (Exception ignored) {
            // Cleanup is best effort when a peer has already disconnected.
        }
    }

    private record Request(String virtualIpv4, int targetPort) {
    }
}
