package com.tailcatmesh.agent.tailcat;

import com.tailcatmesh.agent.config.NetworkProxyConfig;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Downloads and verifies the pinned official Tailcat release when it is absent. */
public final class TailcatBinaryDownloader {

    public static final String DEFAULT_VERSION = "0.3.0";
    private static final URI DEFAULT_RELEASE_BASE =
            URI.create("https://github.com/tailscale/tailcat/releases/download/");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);
    private static final long MAX_ARCHIVE_BYTES = 100L * 1024 * 1024;
    private static final long MAX_EXECUTABLE_BYTES = 50L * 1024 * 1024;
    private static final ConcurrentMap<Path, Object> JVM_LOCKS = new ConcurrentHashMap<>();
    private static final Map<String, Map<Platform, Artifact>> DEFAULT_ARTIFACTS = defaultArtifacts();

    private final HttpClient httpClient;
    private final URI releaseBase;
    private final Map<String, Map<Platform, Artifact>> artifactsByVersion;
    private final Platform platform;

    public TailcatBinaryDownloader() {
        this((NetworkProxyConfig) null);
    }

    public TailcatBinaryDownloader(NetworkProxyConfig proxy) {
        this(createHttpClient(proxy),
                DEFAULT_RELEASE_BASE, DEFAULT_ARTIFACTS, Platform.detect());
    }

    private static HttpClient createHttpClient(NetworkProxyConfig proxy) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL);
        if (proxy != null) {
            builder.proxy(proxy.proxySelector());
        }
        return builder.build();
    }

    TailcatBinaryDownloader(HttpClient httpClient, URI releaseBase,
                            Map<String, Map<Platform, Artifact>> artifactsByVersion,
                            Platform platform) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.releaseBase = normalizeBaseUri(releaseBase);
        this.artifactsByVersion = copyArtifacts(artifactsByVersion);
        this.platform = Objects.requireNonNull(platform, "platform");
    }

    /** Returns the platform-specific executable name for the current host. */
    public static String defaultExecutableName() {
        return Platform.detect().executableName();
    }

    /** Returns a short platform identifier such as {@code windows-amd64}. */
    public static String currentPlatform() {
        return Platform.detect().id();
    }

    /** Returns {@code ~/.tailcat-mesh}, the shared per-user Tailcat cache. */
    public static Path defaultCacheDirectory() {
        String userHome = System.getProperty("user.home");
        if (userHome == null || userHome.isBlank()) {
            throw new TailcatEngineException("TM-AGENT-001",
                    "cannot determine the current user's home directory (user.home is empty)");
        }
        return Path.of(userHome).toAbsolutePath().normalize().resolve(".tailcat-mesh").normalize();
    }

    /** Returns the default cache path used when no binary path is configured. */
    public static Path defaultBinaryPath(String version) {
        String normalizedVersion = normalizeVersion(version);
        return defaultCacheDirectory()
                .resolve(Path.of("tailcat", "v" + normalizedVersion, defaultExecutableName()))
                .normalize();
    }

    /**
     * Legacy overload retained for source compatibility. The cache is now
     * always per-user, so the project directory is intentionally ignored.
     */
    @Deprecated
    public static Path defaultBinaryPath(Path baseDirectory, String version) {
        Objects.requireNonNull(baseDirectory, "baseDirectory");
        return defaultBinaryPath(version);
    }

    /**
     * Returns an existing binary unchanged. If it is missing and auto-download
     * is enabled, downloads and extracts the verified platform artifact to it.
     */
    public Path ensure(Path target, String version, boolean autoDownload) {
        Path normalizedTarget = Objects.requireNonNull(target, "target")
                .toAbsolutePath().normalize();
        if (Files.isRegularFile(normalizedTarget)) {
            return normalizedTarget;
        }
        if (!autoDownload) {
            return normalizedTarget;
        }
        if (Files.isSymbolicLink(normalizedTarget)) {
            throw new TailcatEngineException("TM-AGENT-001",
                    "refusing to download through symbolic link " + normalizedTarget);
        }
        return download(normalizedTarget, normalizeVersion(version));
    }

    private Path download(Path target, String version) {
        Map<Platform, Artifact> versionArtifacts = artifactsByVersion.get(version);
        if (versionArtifacts == null) {
            throw new TailcatEngineException("TM-AGENT-002",
                    "no downloadable official Tailcat release is configured for version " + version);
        }
        Artifact artifact = versionArtifacts.get(platform);
        if (artifact == null) {
            throw new TailcatEngineException("TM-AGENT-002",
                    "no official Tailcat " + version + " binary for " + platform.id());
        }

        Path parent = target.getParent();
        if (parent == null) {
            throw new TailcatEngineException("TM-AGENT-001",
                    "unable to determine download directory for " + target);
        }
        try {
            Files.createDirectories(parent);
        } catch (IOException exception) {
            throw downloadFailure(target, exception);
        }

        Object jvmLock = JVM_LOCKS.computeIfAbsent(target, ignored -> new Object());
        synchronized (jvmLock) {
            Path lockPath = target.resolveSibling(target.getFileName() + ".download.lock");
            try (FileChannel lockChannel = FileChannel.open(lockPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = lockChannel.lock()) {
                if (Files.isRegularFile(target)) {
                    return target;
                }
                if (Files.exists(target)) {
                    throw new IOException("download target is not a regular file: " + target);
                }

                Path archive = Files.createTempFile(parent, ".tailcat-", ".archive");
                Path extracted = Files.createTempFile(parent, ".tailcat-", ".tmp");
                try {
                    URI assetUri = releaseBase.resolve("v" + version + "/" + artifact.assetName());
                    downloadArchive(assetUri, archive);
                    verifySha256(archive, artifact.sha256());
                    extract(archive, extracted, artifact);
                    makeExecutable(extracted);
                    moveIntoPlace(extracted, target);
                    return target;
                } finally {
                    deleteQuietly(archive);
                    deleteQuietly(extracted);
                }
            } catch (TailcatEngineException exception) {
                throw exception;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw downloadFailure(target, exception);
            } catch (IOException exception) {
                throw downloadFailure(target, exception);
            }
        }
    }

    private void downloadArchive(URI assetUri, Path archive)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(assetUri)
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", "tailcat-mesh-agent")
                .header("Accept", "application/octet-stream")
                .GET()
                .build();
        HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(archive));
        if (response.statusCode() != 200) {
            throw new IOException("download returned HTTP " + response.statusCode());
        }
        if (Files.size(archive) > MAX_ARCHIVE_BYTES) {
            throw new IOException("downloaded archive exceeds the maximum supported size");
        }
    }

    private static void verifySha256(Path file, String expected) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            String actual = hex(digest.digest());
            if (!actual.equalsIgnoreCase(expected)) {
                throw new IOException("SHA-256 mismatch; expected " + expected + " but got " + actual);
            }
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is not available in this JRE", exception);
        }
    }

    private static void extract(Path archive, Path output, Artifact artifact) throws IOException {
        switch (artifact.archiveType()) {
            case ZIP -> extractZip(archive, output, artifact.executableName());
            case TAR_GZ -> extractTarGz(archive, output, artifact.executableName());
        }
    }

    private static void extractZip(Path archive, Path output, String executableName) throws IOException {
        try (ZipInputStream input = new ZipInputStream(
                new BufferedInputStream(Files.newInputStream(archive)))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (!entry.isDirectory() && executableName.equals(leafName(entry.getName()))) {
                    try (OutputStream target = Files.newOutputStream(output)) {
                        copyUntilEnd(input, target, MAX_EXECUTABLE_BYTES);
                    }
                    return;
                }
            }
        }
        throw new IOException("archive does not contain " + executableName);
    }

    private static void extractTarGz(Path archive, Path output, String executableName) throws IOException {
        boolean found = false;
        try (InputStream file = Files.newInputStream(archive);
             GZIPInputStream gzip = new GZIPInputStream(new BufferedInputStream(file));
             DataInputStream input = new DataInputStream(gzip)) {
            byte[] header = new byte[512];
            while (true) {
                try {
                    input.readFully(header);
                } catch (EOFException exception) {
                    throw new IOException("truncated tar header", exception);
                }
                if (isZeroBlock(header)) {
                    break;
                }

                String name = tarString(header, 0, 100);
                long size = tarOctal(header, 124, 12);
                int type = header[156] & 0xff;
                if ((type == 0 || type == '0') && executableName.equals(leafName(name))) {
                    if (size > MAX_EXECUTABLE_BYTES) {
                        throw new IOException("Tailcat executable exceeds the maximum supported size");
                    }
                    try (OutputStream target = Files.newOutputStream(output)) {
                        copyEntryExactly(input, target, size);
                    }
                    skipExactly(input, tarPadding(size));
                    found = true;
                    break;
                }
                skipExactly(input, size + tarPadding(size));
            }
        }
        if (!found) {
            throw new IOException("archive does not contain " + executableName);
        }
    }

    private static void copyEntryExactly(InputStream input, OutputStream output, long length) throws IOException {
        if (length == 0) {
            return;
        }
        long copied = 0;
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer, 0, (int) Math.min(buffer.length, length - copied))) != -1) {
            output.write(buffer, 0, read);
            copied += read;
            if (copied == length) {
                return;
            }
        }
        if (copied != length) {
            throw new IOException("truncated archive entry");
        }
    }

    private static void copyUntilEnd(InputStream input, OutputStream output, long maximumLength)
            throws IOException {
        long copied = 0;
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            copied += read;
            if (copied > maximumLength) {
                throw new IOException("archive entry exceeds the maximum supported size");
            }
            output.write(buffer, 0, read);
        }
    }

    private static void skipExactly(InputStream input, long length) throws IOException {
        long remaining = length;
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped > 0) {
                remaining -= skipped;
                continue;
            }
            if (input.read() == -1) {
                throw new IOException("truncated archive");
            }
            remaining--;
        }
    }

    private void makeExecutable(Path file) throws IOException {
        if (platform == Platform.WINDOWS_AMD64 || platform == Platform.WINDOWS_ARM64) {
            return;
        }
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file);
            permissions.add(PosixFilePermission.OWNER_READ);
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
            permissions.add(PosixFilePermission.GROUP_READ);
            permissions.add(PosixFilePermission.GROUP_EXECUTE);
            permissions.add(PosixFilePermission.OTHERS_READ);
            permissions.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(file, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Some non-Windows filesystems do not expose POSIX permissions.
        }
    }

    private static void moveIntoPlace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private static String normalizeVersion(String value) {
        String normalized = value == null || value.isBlank() ? DEFAULT_VERSION : value.trim();
        if (normalized.startsWith("v") || normalized.startsWith("V")) {
            normalized = normalized.substring(1);
        }
        if (!normalized.matches("\\d+\\.\\d+\\.\\d+")) {
            throw new TailcatEngineException("TM-AGENT-002", "invalid Tailcat version " + value);
        }
        return normalized;
    }

    private static String leafName(String value) {
        int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        return slash < 0 ? value : value.substring(slash + 1);
    }

    private static String tarString(byte[] header, int offset, int length) {
        int end = offset;
        while (end < offset + length && header[end] != 0) {
            end++;
        }
        return new String(header, offset, end - offset, java.nio.charset.StandardCharsets.US_ASCII).trim();
    }

    private static long tarOctal(byte[] header, int offset, int length) throws IOException {
        String value = tarString(header, offset, length);
        if (value.isBlank()) {
            return 0;
        }
        try {
            long parsed = Long.parseLong(value, 8);
            if (parsed < 0) {
                throw new IOException("negative tar entry size");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IOException("invalid tar entry size", exception);
        }
    }

    private static long tarPadding(long size) {
        return (512 - (size % 512)) % 512;
    }

    private static boolean isZeroBlock(byte[] block) {
        for (byte value : block) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    private static URI normalizeBaseUri(URI value) {
        URI uri = Objects.requireNonNull(value, "releaseBase");
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("releaseBase must use HTTP(S)");
        }
        String text = uri.toString();
        return URI.create(text.endsWith("/") ? text : text + "/");
    }

    private static Map<String, Map<Platform, Artifact>> copyArtifacts(
            Map<String, Map<Platform, Artifact>> source) {
        Objects.requireNonNull(source, "artifactsByVersion");
        Map<String, Map<Platform, Artifact>> copy = new HashMap<>();
        source.forEach((version, artifacts) ->
                copy.put(normalizeVersion(version), Map.copyOf(Objects.requireNonNull(artifacts, "artifacts"))));
        return Map.copyOf(copy);
    }

    private static Map<String, Map<Platform, Artifact>> defaultArtifacts() {
        Map<Platform, Artifact> v030 = new EnumMap<>(Platform.class);
        v030.put(Platform.WINDOWS_AMD64, new Artifact(
                "tailcat_0.3.0_windows_amd64.zip",
                "fd385c3dacb22248d6eed6c57c1dbfb56f413b1f0577e4ea0fd2b95374d2c9a1",
                ArchiveType.ZIP, "tailcat.exe"));
        v030.put(Platform.WINDOWS_ARM64, new Artifact(
                "tailcat_0.3.0_windows_arm64.zip",
                "194a39e45d8475a15684ec70dfb9745185b68610c98ee3b369a9bd996fe29165",
                ArchiveType.ZIP, "tailcat.exe"));
        v030.put(Platform.LINUX_AMD64, new Artifact(
                "tailcat_0.3.0_linux_amd64.tar.gz",
                "42ee6acb92ac0a6d778bf803aab1dc76fbc3f576c6489ca1be854efcb4641899",
                ArchiveType.TAR_GZ, "tailcat"));
        v030.put(Platform.LINUX_ARM64, new Artifact(
                "tailcat_0.3.0_linux_arm64.tar.gz",
                "b88d8ca36d0aff233987a2551237d63d51f4f7bf1b4f6542c7d721a7eebb4969",
                ArchiveType.TAR_GZ, "tailcat"));
        v030.put(Platform.LINUX_ARMV7, new Artifact(
                "tailcat_0.3.0_linux_armv7.tar.gz",
                "b16e1386473f55c63d2e423df6f91a0151e64cee12df486ba072fe76170245d4",
                ArchiveType.TAR_GZ, "tailcat"));
        return Map.of(DEFAULT_VERSION, Map.copyOf(v030));
    }

    private static TailcatEngineException downloadFailure(Path target, Exception cause) {
        return new TailcatEngineException("TM-AGENT-001",
                "unable to download official Tailcat to " + target + ": " + cause.getMessage(), cause);
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Preserve the original download/extraction result.
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.ROOT, "%02x", value));
        }
        return result.toString();
    }

    enum ArchiveType {
        ZIP,
        TAR_GZ
    }

    enum Platform {
        WINDOWS_AMD64("windows-amd64", "tailcat.exe"),
        WINDOWS_ARM64("windows-arm64", "tailcat.exe"),
        LINUX_AMD64("linux-amd64", "tailcat"),
        LINUX_ARM64("linux-arm64", "tailcat"),
        LINUX_ARMV7("linux-armv7", "tailcat");

        private final String id;
        private final String executableName;

        Platform(String id, String executableName) {
            this.id = id;
            this.executableName = executableName;
        }

        String id() {
            return id;
        }

        String executableName() {
            return executableName;
        }

        static Platform detect() {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT)
                    .replace("_", "").replace("-", "");
            boolean windows = os.contains("win");
            boolean linux = os.contains("linux");
            boolean amd64 = arch.equals("amd64") || arch.equals("x8664") || arch.equals("x64");
            boolean arm64 = arch.equals("aarch64") || arch.equals("arm64");
            if (windows && amd64) {
                return WINDOWS_AMD64;
            }
            if (windows && arm64) {
                return WINDOWS_ARM64;
            }
            if (linux && amd64) {
                return LINUX_AMD64;
            }
            if (linux && arm64) {
                return LINUX_ARM64;
            }
            if (linux && (arch.equals("arm") || arch.equals("armv7") || arch.equals("armv7l"))) {
                return LINUX_ARMV7;
            }
            throw new TailcatEngineException("TM-AGENT-002",
                    "unsupported host platform for automatic Tailcat download: " + os + "/" + arch);
        }
    }

    record Artifact(String assetName, String sha256, ArchiveType archiveType, String executableName) {
        Artifact {
            Objects.requireNonNull(assetName, "assetName");
            Objects.requireNonNull(sha256, "sha256");
            Objects.requireNonNull(archiveType, "archiveType");
            Objects.requireNonNull(executableName, "executableName");
        }
    }
}
