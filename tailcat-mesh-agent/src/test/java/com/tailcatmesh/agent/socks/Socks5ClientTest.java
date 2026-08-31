package com.tailcatmesh.agent.socks;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Socks5ClientTest {

    @Test
    void writesNoAuthDomainConnectHandshakeBytes() throws Exception {
        try (ServerSocket server = loopbackServer();
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<byte[]> transcript = executor.submit(() -> acceptAndReply(server, false));

            try (Socket connected = new Socks5Client().connect(
                    "127.0.0.1", server.getLocalPort(), "server.tailcat", 8081,
                    Duration.ofSeconds(5))) {
                assertEquals('o', connected.getInputStream().read());
                assertEquals('k', connected.getInputStream().read());
            }

            assertArrayEquals(new byte[]{
                    0x05, 0x01, 0x00,
                    0x05, 0x01, 0x00, 0x03, 0x0e,
                    's', 'e', 'r', 'v', 'e', 'r', '.', 't', 'a', 'i', 'l', 'c', 'a', 't',
                    0x1f, (byte) 0x91
            }, transcript.get());
        }
    }

    @Test
    void writesIpv4ConnectRequestAndSurfacesErrorReply() throws Exception {
        try (ServerSocket server = loopbackServer();
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> serverTask = executor.submit(() -> {
                try (Socket socket = server.accept()) {
                    InputStream input = socket.getInputStream();
                    OutputStream output = socket.getOutputStream();
                    readFully(input, 3);
                    output.write(new byte[]{0x05, 0x00});
                    output.write(new byte[]{0x05, 0x05, 0x00, 0x01, 127, 0, 0, 1, 0, 80});
                    output.flush();
                    readFully(input, 10);
                } catch (IOException exception) {
                    throw new RuntimeException(exception);
                }
            });

            Socks5Exception exception = assertThrows(Socks5Exception.class, () ->
                    new Socks5Client().connect(
                            "127.0.0.1", server.getLocalPort(), "127.0.0.1", 80,
                            Duration.ofSeconds(5)));

            assertEquals("TM-AGENT-006", exception.code());
            serverTask.get();
        }
    }

    private static byte[] acceptAndReply(ServerSocket server, boolean ignored) {
        try (Socket socket = server.accept()) {
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();
            ByteArrayOutputStream transcript = new ByteArrayOutputStream();
            byte[] greeting = readFully(input, 3);
            transcript.write(greeting);
            output.write(new byte[]{0x05, 0x00});
            output.flush();

            byte[] header = readFully(input, 4);
            transcript.write(header);
            int addressLength = header[3] == 0x03 ? input.read() : header[3] == 0x01 ? 4 : 16;
            transcript.write(addressLength);
            transcript.write(readFully(input, addressLength));
            transcript.write(readFully(input, 2));
            output.write(new byte[]{0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0x12, 0x34});
            output.write(new byte[]{'o', 'k'});
            output.flush();
            return transcript.toByteArray();
        } catch (IOException exception) {
            throw new RuntimeException(exception);
        }
    }

    private static ServerSocket loopbackServer() throws IOException {
        return new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
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
