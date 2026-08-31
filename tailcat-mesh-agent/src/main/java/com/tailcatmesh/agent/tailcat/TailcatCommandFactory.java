package com.tailcatmesh.agent.tailcat;

import com.tailcatmesh.agent.tailcat.model.TailcatServerConfig;
import com.tailcatmesh.agent.tailcat.model.TailcatVirtualNetworkServerConfig;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Builds argument lists for the official Tailcat v0.3.x CLI.
 *
 * <p>Every command is returned as individual arguments for
 * {@link ProcessBuilder}; this class never creates a shell command string.</p>
 */
public final class TailcatCommandFactory {

    private static final Pattern CONN_BLOB = Pattern.compile("tc[A-Za-z0-9_-]+");
    private static final Pattern PUBLIC_KEY = Pattern.compile("nodekey:[0-9a-fA-F]{64}");

    private final Path binary;

    public TailcatCommandFactory(Path binary) {
        this.binary = Objects.requireNonNull(binary, "binary").toAbsolutePath().normalize();
    }

    public List<String> versionCommand() {
        return List.of(binary.toString(), "--version");
    }

    public List<String> serverCommand(TailcatServerConfig config) {
        Objects.requireNonNull(config, "config");
        List<String> command = new ArrayList<>();
        command.add(binary.toString());
        command.add("--key=" + pathArgument(config.serverKeyPath()));
        if (!config.servedPorts().isEmpty()) {
            List<Integer> sortedPorts = new ArrayList<>(config.servedPorts());
            Collections.sort(sortedPorts);
            command.add("--serve=" + sortedPorts.stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining(",")));
        }
        // An empty or invalid allowlist fails closed. The flag is never omitted.
        command.add("--allow=" + allowListArgument(config.allowedClientPublicKeys()));
        if (config.fullAddress()) {
            command.add("--full-address");
        }
        command.add("--json");
        if (config.derpMapUrl() != null) {
            command.add("--derpmap-url=" + validateDerpMapUrl(config.derpMapUrl()));
        }
        return List.copyOf(command);
    }

    /**
     * Builds the isolated virtual-network server command required by M7.
     * Virtual-network servers deliberately serve all TCP ports and never use
     * Tailcat's exit-node mode.
     */
    public List<String> virtualNetworkServerCommand(TailcatVirtualNetworkServerConfig config) {
        Objects.requireNonNull(config, "config");
        List<String> command = new ArrayList<>();
        command.add(binary.toString());
        command.add("--key=" + pathArgument(config.serverKeyPath()));
        command.add("--serve=all");
        command.add("--allow=" + allowListArgument(config.allowedClientPublicKeys()));
        if (config.fullAddress()) {
            command.add("--full-address");
        }
        command.add("--json");
        if (config.derpMapUrl() != null) {
            command.add("--derpmap-url=" + validateDerpMapUrl(config.derpMapUrl()));
        }
        return List.copyOf(command);
    }

    public List<String> genKeyCommand(Path keyPath, boolean client) {
        return genKeyCommand(keyPath, client, false);
    }

    /**
     * Builds the server-key generation command. A fixed DERP region is
     * required for a reusable server ConnBlob; client keys must not carry a
     * region.
     */
    public List<String> genKeyCommand(Path keyPath, boolean client, boolean fixedRegion) {
        return genKeyCommand(keyPath, client, fixedRegion, false);
    }

    /** Builds a key-generation command that may explicitly replace a stale key. */
    public List<String> genKeyCommand(Path keyPath, boolean client, boolean fixedRegion, boolean force) {
        List<String> command = new ArrayList<>(List.of(
                binary.toString(),
                "genkey",
                "--key=" + pathArgument(keyPath)
        ));
        if (client) {
            command.add("--client");
        } else if (fixedRegion) {
            command.add("--fixed-region");
        }
        if (force) {
            command.add("--force");
        }
        return List.copyOf(command);
    }

    public List<String> printPublicKeyCommand(Path clientKeyPath) {
        return List.of(
                binary.toString(),
                "--key=" + pathArgument(clientKeyPath),
                "printpub"
        );
    }

    public List<String> parseTokenCommand(String connBlob) {
        return List.of(binary.toString(), "parse", validateConnBlob(connBlob));
    }

    public List<String> pingCommand(Path clientKeyPath, String connBlob, Duration timeout,
                                    boolean untilDirect) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        List<String> command = new ArrayList<>(List.of(
                binary.toString(),
                "--key=" + pathArgument(clientKeyPath),
                "ping"
        ));
        if (untilDirect) {
            command.add("--until-direct");
        }
        command.add("--timeout=" + formatGoDuration(timeout));
        command.add(validateConnBlob(connBlob));
        return List.copyOf(command);
    }

    /** Builds the official v0.3.0 command for one persistent peer SOCKS process. */
    public List<String> peerSocksCommand(Path clientKeyPath, String connBlob, String listenAddress) {
        if (listenAddress == null || listenAddress.isBlank() || listenAddress.contains("\n")
                || listenAddress.contains("\r")) {
            throw new IllegalArgumentException("listenAddress must be a single non-blank value");
        }
        return List.of(
                binary.toString(),
                "--key=" + pathArgument(clientKeyPath),
                "socks",
                "--listen=" + listenAddress,
                validateConnBlob(connBlob)
        );
    }

    static String formatGoDuration(Duration duration) {
        long millis = duration.toMillis();
        if (millis <= 0) {
            throw new IllegalArgumentException("duration must be at least one millisecond");
        }
        if (millis % 1_000 == 0) {
            return (millis / 1_000) + "s";
        }
        return millis + "ms";
    }

    private static String pathArgument(Path path) {
        return Objects.requireNonNull(path, "path").toAbsolutePath().normalize().toString();
    }

    private static String validateConnBlob(String connBlob) {
        if (connBlob == null || !CONN_BLOB.matcher(connBlob).matches()) {
            throw new IllegalArgumentException("connBlob must be a tc-prefixed Tailcat token");
        }
        return connBlob;
    }

    private static String allowListArgument(List<String> publicKeys) {
        if (publicKeys == null || publicKeys.isEmpty()) {
            return "none";
        }
        List<String> validKeys = new ArrayList<>();
        for (String publicKey : publicKeys) {
            if (publicKey == null || !PUBLIC_KEY.matcher(publicKey).matches()) {
                return "none";
            }
            validKeys.add(publicKey);
        }
        return String.join(",", validKeys);
    }

    private static String validateDerpMapUrl(String value) {
        String trimmed = value.trim();
        try {
            java.net.URI uri = java.net.URI.create(trimmed);
            if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null) {
                throw new IllegalArgumentException("DERP map URL must be an HTTP(S) URL");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid DERP map URL", exception);
        }
        return trimmed;
    }
}
