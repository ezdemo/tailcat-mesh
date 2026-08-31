package com.tailcatmesh.agent.tailcat;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TailcatBinaryDownloaderTest {

    @TempDir
    Path temporaryDirectory;

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void downloadsVerifiedZipAndUsesCachedExecutableOnSecondCall() throws Exception {
        byte[] executable = "windows-tailcat".getBytes(StandardCharsets.UTF_8);
        byte[] archive = zip("tailcat_0.3.0/tailcat.exe", executable);
        AtomicInteger requests = new AtomicInteger();
        URI base = serve(archive, requests);
        TailcatBinaryDownloader downloader = downloader(base, TailcatBinaryDownloader.Platform.WINDOWS_AMD64,
                new TailcatBinaryDownloader.Artifact("tailcat.zip", sha256(archive),
                        TailcatBinaryDownloader.ArchiveType.ZIP, "tailcat.exe"));
        Path target = temporaryDirectory.resolve("tailcat.exe");

        assertEquals(target, downloader.ensure(target, "v0.3.0", true));
        assertArrayEquals(executable, Files.readAllBytes(target));
        assertEquals(target, downloader.ensure(target, "0.3.0", true));
        assertEquals(1, requests.get());
    }

    @Test
    void downloadsAndExtractsLinuxTarGz() throws Exception {
        byte[] executable = "linux-tailcat".getBytes(StandardCharsets.UTF_8);
        byte[] archive = tarGz("tailcat", executable);
        URI base = serve(archive, new AtomicInteger());
        TailcatBinaryDownloader downloader = downloader(base, TailcatBinaryDownloader.Platform.LINUX_AMD64,
                new TailcatBinaryDownloader.Artifact("tailcat.tar.gz", sha256(archive),
                        TailcatBinaryDownloader.ArchiveType.TAR_GZ, "tailcat"));
        Path target = temporaryDirectory.resolve("tailcat");

        downloader.ensure(target, "0.3.0", true);

        assertArrayEquals(executable, Files.readAllBytes(target));
        assertTrue(Files.isRegularFile(target));
    }

    @Test
    void rejectsChecksumMismatchAndLeavesNoExecutable() throws Exception {
        byte[] archive = zip("tailcat.exe", "payload".getBytes(StandardCharsets.UTF_8));
        URI base = serve(archive, new AtomicInteger());
        TailcatBinaryDownloader downloader = downloader(base, TailcatBinaryDownloader.Platform.WINDOWS_AMD64,
                new TailcatBinaryDownloader.Artifact("tailcat.zip", "0".repeat(64),
                        TailcatBinaryDownloader.ArchiveType.ZIP, "tailcat.exe"));
        Path target = temporaryDirectory.resolve("tailcat.exe");

        TailcatEngineException exception = assertThrows(TailcatEngineException.class,
                () -> downloader.ensure(target, "0.3.0", true));

        assertEquals("TM-AGENT-001", exception.code());
        assertFalse(Files.exists(target));
    }

    @Test
    void doesNotDownloadWhenAutoDownloadIsDisabled() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        URI base = serve(new byte[0], requests);
        TailcatBinaryDownloader downloader = downloader(base, TailcatBinaryDownloader.Platform.WINDOWS_AMD64,
                new TailcatBinaryDownloader.Artifact("tailcat.zip", "0".repeat(64),
                        TailcatBinaryDownloader.ArchiveType.ZIP, "tailcat.exe"));
        Path target = temporaryDirectory.resolve("tailcat.exe");

        assertEquals(target, downloader.ensure(target, "0.3.0", false));
        assertFalse(Files.exists(target));
        assertEquals(0, requests.get());
    }

    private TailcatBinaryDownloader downloader(URI base, TailcatBinaryDownloader.Platform platform,
                                               TailcatBinaryDownloader.Artifact artifact) {
        return new TailcatBinaryDownloader(
                HttpClient.newHttpClient(),
                base,
                Map.of("0.3.0", Map.of(platform, artifact)),
                platform);
    }

    private URI serve(byte[] body, AtomicInteger requests) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    private static byte[] zip(String entryName, byte[] payload) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(payload);
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static byte[] tarGz(String entryName, byte[] payload) throws IOException {
        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        byte[] header = new byte[512];
        writeAscii(header, 0, 100, entryName);
        writeAscii(header, 100, 8, "0000755");
        writeAscii(header, 124, 12, String.format("%011o", payload.length));
        header[156] = '0';
        writeAscii(header, 257, 6, "ustar");
        writeAscii(header, 263, 2, "00");
        for (int i = 148; i < 156; i++) {
            header[i] = ' ';
        }
        long checksum = 0;
        for (byte value : header) {
            checksum += value & 0xff;
        }
        writeAscii(header, 148, 6, String.format("%06o", checksum));
        header[154] = 0;
        header[155] = ' ';
        tar.write(header);
        tar.write(payload);
        tar.write(new byte[(int) ((512 - payload.length % 512) % 512)]);
        tar.write(new byte[1024]);

        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write(tar.toByteArray());
        }
        return compressed.toByteArray();
    }

    private static void writeAscii(byte[] target, int offset, int length, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, target, offset, Math.min(bytes.length, length));
    }

    private static String sha256(byte[] content) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value));
        }
        return result.toString();
    }
}
