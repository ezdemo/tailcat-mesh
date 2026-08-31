package com.tailcatmesh.agent.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceBridgeTest {

    @Test
    void copiesBothDirectionsAndPreservesTcpHalfClose() throws Exception {
        AtomicReference<String> request = new AtomicReference<>();
        try (ServerSocket upstream = new ServerSocket(0);
             TcpServiceBridge bridge = new TcpServiceBridge()) {
            Thread upstreamThread = Thread.ofVirtual().start(() -> {
                try (Socket socket = upstream.accept()) {
                    request.set(new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
                    socket.getOutputStream().write("pong".getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream().flush();
                } catch (IOException exception) {
                    throw new AssertionError(exception);
                }
            });

            ServiceBridgeHandle handle = bridge.start(new ServiceRuntimeConfig(
                    UUID.randomUUID(), "127.0.0.1", 0, "127.0.0.1", upstream.getLocalPort(),
                    Duration.ofSeconds(2), Duration.ofSeconds(5)));
            assertTrue(handle.isRunning());
            assertTrue(handle.bridgePort() > 0);

            String response;
            try (Socket client = new Socket("127.0.0.1", handle.bridgePort())) {
                client.getOutputStream().write("ping".getBytes(StandardCharsets.UTF_8));
                client.getOutputStream().flush();
                client.shutdownOutput();
                response = new String(client.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            }

            upstreamThread.join(5_000);
            assertEquals("ping", request.get());
            assertEquals("pong", response);
        }
    }

    @Test
    void onlyAllowsLoopbackBinding() {
        assertThrows(IllegalArgumentException.class, () -> new ServiceRuntimeConfig(
                UUID.randomUUID(), "0.0.0.0", 0, "127.0.0.1", 80,
                Duration.ofSeconds(1), Duration.ofSeconds(1)));
    }

    @Test
    void stopsBridgeAndReleasesPort() throws Exception {
        try (ServerSocket upstream = new ServerSocket(0);
             TcpServiceBridge bridge = new TcpServiceBridge()) {
            ServiceBridgeHandle handle = bridge.start(new ServiceRuntimeConfig(
                    UUID.randomUUID(), "127.0.0.1", 0, "127.0.0.1", upstream.getLocalPort(),
                    Duration.ofSeconds(1), Duration.ofSeconds(1)));
            int port = handle.bridgePort();
            bridge.stop(handle.serviceId());
            assertFalse(handle.isRunning());
            assertEquals("STOPPED", handle.status());
            assertThrows(IOException.class, () -> new Socket("127.0.0.1", port));
        }
    }
}
