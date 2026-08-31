package com.tailcatmesh.agent.forward;

import com.tailcatmesh.protocol.agent.AgentForward;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalForwardTest {

    @Test
    void bindsFixedLoopbackPortAndCopiesTrafficThroughPeerSocks() throws Exception {
        try (ServerSocket upstream = loopbackServer();
             ServerSocket socks = loopbackServer();
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
             LocalForwardManager manager = new LocalForwardManager(
                     ignored -> Optional.of(new PeerSocksEndpoint("127.0.0.1", socks.getLocalPort())))) {
            Future<String> upstreamRequest = executor.submit(() -> acceptUpstream(upstream));
            Future<String> socksTarget = executor.submit(() -> acceptSocksAndRelay(socks, upstream.getLocalPort()));
            int localPort = unusedLoopbackPort();
            UUID forwardId = UUID.randomUUID();
            LocalForwardHandle handle = manager.start(new AgentForward(
                    forwardId, "local test", UUID.randomUUID(), UUID.randomUUID(),
                    "127.0.0.1", localPort, upstream.getLocalPort(), true));

            assertTrue(waitFor(() -> "READY".equals(handle.runtime().status()), Duration.ofSeconds(2)));
            assertEquals(localPort, handle.localPort());
            String response;
            try (Socket client = new Socket("127.0.0.1", localPort)) {
                client.getOutputStream().write("ping".getBytes(StandardCharsets.UTF_8));
                client.getOutputStream().flush();
                client.shutdownOutput();
                response = new String(client.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            }

            assertEquals("ping", upstreamRequest.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("server.tailcat:" + upstream.getLocalPort(),
                    socksTarget.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("pong", response);
            assertTrue(handle.isRunning());
        }
    }

    @Test
    void keepsListenerWhenPeerSocksIsUnavailable() throws Exception {
        try (LocalForwardManager manager = new LocalForwardManager(ignored -> Optional.empty())) {
            LocalForwardHandle handle = manager.start(new AgentForward(
                    UUID.randomUUID(), "unavailable", UUID.randomUUID(), UUID.randomUUID(),
                    "127.0.0.1", unusedLoopbackPort(), 45_123, true));
            try (Socket client = new Socket("127.0.0.1", handle.localPort())) {
                assertEquals(-1, client.getInputStream().read());
            }
            assertTrue(waitFor(() -> "TM-AGENT-008".equals(handle.runtime().errorCode()),
                    Duration.ofSeconds(2)));
            assertTrue(handle.isRunning());
            assertEquals("READY", handle.runtime().status());
        }
    }

    @Test
    void reportsFixedPortConflictWithoutChangingRequestedPort() throws Exception {
        int port = unusedLoopbackPort();
        try (ServerSocket occupied = new ServerSocket(port, 1, InetAddress.getLoopbackAddress());
             LocalForwardManager manager = new LocalForwardManager(ignored -> Optional.empty())) {
            LocalForwardException exception = assertThrows(LocalForwardException.class, () ->
                    manager.start(new AgentForward(
                            UUID.randomUUID(), "conflict", UUID.randomUUID(), UUID.randomUUID(),
                            "127.0.0.1", port, 45_123, true)));
            assertEquals("TM-AGENT-007", exception.code());
            assertFalse(manager.handle(UUID.randomUUID()).isPresent());
        }
    }

    private static String acceptUpstream(ServerSocket upstream) {
        try (Socket socket = upstream.accept()) {
            String request = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            socket.getOutputStream().write("pong".getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            return request;
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private static String acceptSocksAndRelay(ServerSocket socks, int upstreamPort) {
        try (Socket proxy = socks.accept()) {
            InputStream input = proxy.getInputStream();
            OutputStream output = proxy.getOutputStream();
            readFully(input, 3);
            output.write(new byte[]{0x05, 0x00});
            output.flush();
            byte[] header = readFully(input, 4);
            assertEquals(0x03, header[3] & 0xff);
            int length = input.read();
            String target = new String(readFully(input, length), StandardCharsets.UTF_8);
            byte[] portBytes = readFully(input, 2);
            int targetPort = (portBytes[0] & 0xff) * 256 + (portBytes[1] & 0xff);
            try (Socket upstream = new Socket("127.0.0.1", upstreamPort)) {
                output.write(new byte[]{0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0x12, 0x34});
                output.flush();
                Thread proxyToUpstream = Thread.ofVirtual().start(() -> copy(proxy, upstream));
                Thread upstreamToProxy = Thread.ofVirtual().start(() -> copy(upstream, proxy));
                proxyToUpstream.join();
                upstreamToProxy.join();
            }
            return target + ":" + targetPort;
        } catch (IOException exception) {
            throw new AssertionError(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static void copy(Socket source, Socket destination) {
        try {
            source.getInputStream().transferTo(destination.getOutputStream());
            destination.getOutputStream().flush();
            destination.shutdownOutput();
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private static ServerSocket loopbackServer() throws IOException {
        return new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
    }

    private static int unusedLoopbackPort() throws IOException {
        try (ServerSocket socket = loopbackServer()) {
            return socket.getLocalPort();
        }
    }

    private static boolean waitFor(java.util.function.BooleanSupplier condition, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }

    private static byte[] readFully(InputStream input, int length) throws IOException {
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < bytes.length) {
            int read = input.read(bytes, offset, bytes.length - offset);
            if (read < 0) {
                throw new IOException("unexpected end of stream");
            }
            offset += read;
        }
        return bytes;
    }
}
