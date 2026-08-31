package com.tailcatmesh.agent.socks;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/**
 * Small SOCKS5 CONNECT client for the official Tailcat SOCKS process.
 *
 * <p>Only no-auth CONNECT with DOMAIN and IPv4 destinations is supported.
 * The returned socket is ready for bidirectional application traffic and is
 * owned by the caller.</p>
 */
public final class Socks5Client {

    private static final String ERROR_CODE = "TM-AGENT-006";

    public Socket connect(String socksHost, int socksPort, String targetHost, int targetPort,
                          Duration timeout) {
        if (socksHost == null || socksHost.isBlank()) {
            throw new IllegalArgumentException("socksHost must not be blank");
        }
        validatePort(socksPort, "socksPort");
        if (targetHost == null || targetHost.isBlank() || targetHost.length() > 255
                || targetHost.indexOf('\r') >= 0 || targetHost.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("targetHost must be a short non-blank value");
        }
        validatePort(targetPort, "targetPort");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }

        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(socksHost, socksPort), timeoutMillis(timeout));
            socket.setSoTimeout(timeoutMillis(timeout));
            performHandshake(socket, targetHost, targetPort);
            socket.setSoTimeout(0);
            return socket;
        } catch (Socks5Exception exception) {
            closeQuietly(socket);
            throw exception;
        } catch (IOException | RuntimeException exception) {
            closeQuietly(socket);
            throw new Socks5Exception(ERROR_CODE, "SOCKS5 CONNECT handshake failed", exception);
        }
    }

    private static void performHandshake(Socket socket, String targetHost, int targetPort)
            throws IOException {
        InputStream input = socket.getInputStream();
        OutputStream output = socket.getOutputStream();

        output.write(new byte[]{0x05, 0x01, 0x00});
        output.flush();
        int version = readUnsignedByte(input);
        int method = readUnsignedByte(input);
        if (version != 0x05 || method != 0x00) {
            throw new Socks5Exception(ERROR_CODE, "SOCKS5 no-authentication negotiation failed");
        }

        byte[] ipv4 = parseIpv4(targetHost);
        if (ipv4 != null) {
            output.write(new byte[]{0x05, 0x01, 0x00, 0x01});
            output.write(ipv4);
        } else {
            byte[] domain = targetHost.getBytes(StandardCharsets.UTF_8);
            if (domain.length == 0 || domain.length > 255) {
                throw new Socks5Exception(ERROR_CODE, "SOCKS5 target domain is too long");
            }
            output.write(new byte[]{0x05, 0x01, 0x00, 0x03, (byte) domain.length});
            output.write(domain);
        }
        output.write((targetPort >>> 8) & 0xff);
        output.write(targetPort & 0xff);
        output.flush();

        int replyVersion = readUnsignedByte(input);
        int replyCode = readUnsignedByte(input);
        int reserved = readUnsignedByte(input);
        int addressType = readUnsignedByte(input);
        if (replyVersion != 0x05 || reserved != 0x00) {
            throw new Socks5Exception(ERROR_CODE, "SOCKS5 CONNECT response header is invalid");
        }
        consumeBoundAddress(input, addressType);
        readUnsignedByte(input);
        readUnsignedByte(input);
        if (replyCode != 0x00) {
            throw new Socks5Exception(ERROR_CODE,
                    "SOCKS5 CONNECT rejected with reply code 0x" + Integer.toHexString(replyCode));
        }
    }

    private static void consumeBoundAddress(InputStream input, int addressType) throws IOException {
        switch (addressType) {
            case 0x01 -> readFully(input, 4);
            case 0x03 -> {
                int length = readUnsignedByte(input);
                readFully(input, length);
            }
            case 0x04 -> readFully(input, 16);
            default -> throw new Socks5Exception(ERROR_CODE, "SOCKS5 response address type is unsupported");
        }
    }

    private static byte[] parseIpv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        byte[] address = new byte[4];
        try {
            for (int index = 0; index < parts.length; index++) {
                if (parts[index].isEmpty() || (parts[index].length() > 1 && parts[index].startsWith("0"))) {
                    return null;
                }
                int value = Integer.parseInt(parts[index]);
                if (value < 0 || value > 255) {
                    return null;
                }
                address[index] = (byte) value;
            }
            return address;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static int readUnsignedByte(InputStream input) throws IOException {
        int value = input.read();
        if (value < 0) {
            throw new EOFException("SOCKS5 peer closed during handshake");
        }
        return value;
    }

    private static void readFully(InputStream input, int length) throws IOException {
        byte[] buffer = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = input.read(buffer, offset, length - offset);
            if (read < 0) {
                throw new EOFException("SOCKS5 peer closed during handshake");
            }
            offset += read;
        }
    }

    private static int timeoutMillis(Duration timeout) {
        long millis = timeout.toMillis();
        if (millis < 1) {
            return 1;
        }
        return (int) Math.min(Integer.MAX_VALUE, millis);
    }

    private static void validatePort(int port, String name) {
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException(name + " must be between 1 and 65535");
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Best-effort cleanup after a failed handshake.
        }
    }
}
