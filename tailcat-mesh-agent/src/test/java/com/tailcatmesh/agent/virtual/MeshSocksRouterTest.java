package com.tailcatmesh.agent.virtual;

import com.tailcatmesh.agent.forward.PeerSocksEndpoint;
import com.tailcatmesh.agent.socks.Socks5Client;
import com.tailcatmesh.agent.socks.Socks5Exception;
import org.junit.jupiter.api.Test;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MeshSocksRouterTest {

    @Test
    void translatesVirtualIpv4ToNetworkPeerTailcatTarget() throws Exception {
        VirtualIpRouteTable routeTable = new VirtualIpRouteTable();
        AtomicReference<String> upstreamHost = new AtomicReference<>();
        AtomicReference<Integer> upstreamPort = new AtomicReference<>();
        try (ServerSocket peerSocks = new ServerSocket(0)) {
            Thread peerThread = Thread.ofVirtual().start(() -> runPeerSocksEcho(
                    peerSocks, upstreamHost, upstreamPort));
            routeTable.replace(List.of(new VirtualIpRouteTable.Route(
                    UUID.randomUUID(), UUID.randomUUID(), "10.77.0.3",
                    new PeerSocksEndpoint("127.0.0.1", peerSocks.getLocalPort()))));

            try (MeshSocksRouter router = new MeshSocksRouter(
                    routeTable, 0, Duration.ofSeconds(3))) {
                router.start();
                PeerSocksEndpoint routerEndpoint = router.listenEndpoint();
                try (Socket client = new Socks5Client().connect(
                        routerEndpoint.host(), routerEndpoint.port(),
                        "10.77.0.3", 445, Duration.ofSeconds(3))) {
                    client.getOutputStream().write("hello".getBytes(StandardCharsets.UTF_8));
                    client.getOutputStream().flush();
                    byte[] response = client.getInputStream().readNBytes(5);
                    assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), response);
                }
            }
            peerThread.join(3_000);
        }

        assertEquals("server.tailcat", upstreamHost.get());
        assertEquals(445, upstreamPort.get());
    }

    @Test
    void rejectsUnmappedVirtualIpv4() throws IOException {
        VirtualIpRouteTable routeTable = new VirtualIpRouteTable();
        try (MeshSocksRouter router = new MeshSocksRouter(routeTable, 0, Duration.ofSeconds(2))) {
            router.start();
            PeerSocksEndpoint endpoint = router.listenEndpoint();
            assertThrows(Socks5Exception.class, () -> new Socks5Client().connect(
                    endpoint.host(), endpoint.port(), "10.77.0.99", 445, Duration.ofSeconds(2)));
        }
    }

    @Test
    void rejectsUdpAssociateAndBindCommands() throws Exception {
        VirtualIpRouteTable routeTable = new VirtualIpRouteTable();
        try (MeshSocksRouter router = new MeshSocksRouter(routeTable, 0, Duration.ofSeconds(2))) {
            router.start();
            PeerSocksEndpoint endpoint = router.listenEndpoint();
            assertEquals(0x07, rawReplyCode(endpoint, new byte[]{0x05, 0x02, 0x00,
                    0x01, 10, 77, 0, 3, 0x01, (byte) 0xbb}));
            assertEquals(0x07, rawReplyCode(endpoint, new byte[]{0x05, 0x03, 0x00,
                    0x01, 10, 77, 0, 3, 0x01, (byte) 0xbb}));
        }
    }

    @Test
    void rejectsDomainAndIpv6Destinations() throws Exception {
        VirtualIpRouteTable routeTable = new VirtualIpRouteTable();
        try (MeshSocksRouter router = new MeshSocksRouter(routeTable, 0, Duration.ofSeconds(2))) {
            router.start();
            PeerSocksEndpoint endpoint = router.listenEndpoint();
            byte[] domain = new byte[]{0x05, 0x01, 0x00, 0x03, 0x08,
                    'v', 'i', 'r', 't', 'u', 'a', 'l', 'x', 0x01, (byte) 0xbb};
            assertEquals(0x08, rawReplyCode(endpoint, domain));
            byte[] ipv6 = new byte[4 + 16 + 2];
            ipv6[0] = 0x05;
            ipv6[1] = 0x01;
            ipv6[2] = 0x00;
            ipv6[3] = 0x04;
            ipv6[ipv6.length - 2] = 0x01;
            ipv6[ipv6.length - 1] = (byte) 0xbb;
            assertEquals(0x08, rawReplyCode(endpoint, ipv6));
        }
    }

    @Test
    void rejectsAuthenticationMethodsOtherThanNoAuth() throws Exception {
        VirtualIpRouteTable routeTable = new VirtualIpRouteTable();
        try (MeshSocksRouter router = new MeshSocksRouter(routeTable, 0, Duration.ofSeconds(2))) {
            router.start();
            PeerSocksEndpoint endpoint = router.listenEndpoint();
            try (Socket socket = new Socket(endpoint.host(), endpoint.port())) {
                socket.getOutputStream().write(new byte[]{0x05, 0x01, 0x02});
                socket.getOutputStream().flush();
                assertEquals(0x05, socket.getInputStream().read());
                assertEquals(0xff, socket.getInputStream().read());
            }
        }
    }

    private static int rawReplyCode(PeerSocksEndpoint endpoint, byte[] request) throws IOException {
        try (Socket socket = new Socket(endpoint.host(), endpoint.port())) {
            socket.setSoTimeout(2_000);
            OutputStream output = socket.getOutputStream();
            output.write(new byte[]{0x05, 0x01, 0x00});
            output.flush();
            assertEquals(0x05, socket.getInputStream().read());
            assertEquals(0x00, socket.getInputStream().read());
            output.write(request);
            output.flush();
            assertEquals(0x05, socket.getInputStream().read());
            return socket.getInputStream().read();
        }
    }

    private static void runPeerSocksEcho(ServerSocket server,
                                         AtomicReference<String> host,
                                         AtomicReference<Integer> port) {
        try (Socket socket = server.accept()) {
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();
            assertByte(input, 0x05);
            assertByte(input, 0x01);
            assertByte(input, 0x00);
            output.write(new byte[]{0x05, 0x00});
            output.flush();

            assertByte(input, 0x05);
            assertByte(input, 0x01);
            assertByte(input, 0x00);
            assertByte(input, 0x03);
            int hostLength = input.read();
            if (hostLength < 0) {
                throw new EOFException("peer SOCKS request ended");
            }
            host.set(new String(readFully(input, hostLength), StandardCharsets.UTF_8));
            port.set((readByte(input) << 8) | readByte(input));
            output.write(new byte[]{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
            output.flush();

            byte[] buffer = new byte[1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
                output.flush();
            }
        } catch (IOException exception) {
            throw new RuntimeException(exception);
        }
    }

    private static void assertByte(InputStream input, int expected) throws IOException {
        int actual = input.read();
        if (actual != expected) {
            throw new IOException("expected byte " + expected + " but received " + actual);
        }
    }

    private static int readByte(InputStream input) throws IOException {
        int value = input.read();
        if (value < 0) {
            throw new EOFException("unexpected EOF");
        }
        return value;
    }

    private static byte[] readFully(InputStream input, int length) throws IOException {
        byte[] value = input.readNBytes(length);
        if (value.length != length) {
            throw new EOFException("unexpected EOF");
        }
        return value;
    }
}
