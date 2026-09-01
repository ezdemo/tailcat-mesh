package com.tailcatmesh.agent.config;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/** Validated runtime configuration shared by the CLI bootstrap and Agent runtime. */
public record AgentConfig(
        URI serverUrl,
        Path tailcatBinary,
        Path dataDir,
        Path serverKeyPath,
        Path clientKeyPath,
        boolean fullAddress,
        String derpMapUrl,
        Duration heartbeatInterval,
        Duration peerPingInterval,
        VirtualLanAgentConfig virtualLan,
        boolean tailcatAutoDownload,
        String tailcatVersion,
        String deviceName,
        NetworkProxyConfig proxy
) {
    public static final String DEFAULT_TAILCAT_VERSION = "0.3.0";

    public AgentConfig(URI serverUrl, Path tailcatBinary, Path dataDir,
                       Path serverKeyPath, Path clientKeyPath, boolean fullAddress,
                       String derpMapUrl, Duration heartbeatInterval,
                       Duration peerPingInterval) {
        this(serverUrl, tailcatBinary, dataDir, serverKeyPath, clientKeyPath, fullAddress,
                derpMapUrl, heartbeatInterval, peerPingInterval,
                VirtualLanAgentConfig.disabled(), false, DEFAULT_TAILCAT_VERSION, null, null);
    }

    /** Backwards-compatible constructor for callers that provide Virtual LAN settings. */
    public AgentConfig(URI serverUrl, Path tailcatBinary, Path dataDir,
                       Path serverKeyPath, Path clientKeyPath, boolean fullAddress,
                       String derpMapUrl, Duration heartbeatInterval,
                       Duration peerPingInterval, VirtualLanAgentConfig virtualLan) {
        this(serverUrl, tailcatBinary, dataDir, serverKeyPath, clientKeyPath, fullAddress,
                derpMapUrl, heartbeatInterval, peerPingInterval, virtualLan,
                false, DEFAULT_TAILCAT_VERSION, null, null);
    }

    public AgentConfig {
        serverUrl = validateServerUrl(serverUrl);
        tailcatBinary = normalize(Objects.requireNonNull(tailcatBinary, "tailcatBinary"));
        dataDir = normalize(Objects.requireNonNull(dataDir, "dataDir"));
        serverKeyPath = normalize(Objects.requireNonNull(serverKeyPath, "serverKeyPath"));
        clientKeyPath = normalize(Objects.requireNonNull(clientKeyPath, "clientKeyPath"));
        if (serverKeyPath.equals(clientKeyPath)) {
            throw new AgentConfigException("TM-AGENT-010", "server and client key paths must differ");
        }
        derpMapUrl = derpMapUrl == null || derpMapUrl.isBlank() ? null : derpMapUrl.trim();
        heartbeatInterval = positive(heartbeatInterval, "heartbeatInterval");
        peerPingInterval = positive(peerPingInterval, "peerPingInterval");
        virtualLan = virtualLan == null ? VirtualLanAgentConfig.disabled() : virtualLan;
        tailcatVersion = normalizeTailcatVersion(tailcatVersion);
        deviceName = normalizeDeviceName(deviceName);
    }

    public URI endpoint(String path) {
        Objects.requireNonNull(path, "path");
        String base = serverUrl.toString();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + (path.startsWith("/") ? path : "/" + path));
    }

    public URI websocketEndpoint() {
        String scheme = "https".equalsIgnoreCase(serverUrl.getScheme()) ? "wss" : "ws";
        URI httpEndpoint = endpoint("/api/v1/agent/ws");
        try {
            return new URI(scheme, httpEndpoint.getUserInfo(), httpEndpoint.getHost(), httpEndpoint.getPort(),
                    httpEndpoint.getPath(), httpEndpoint.getQuery(), httpEndpoint.getFragment());
        } catch (java.net.URISyntaxException exception) {
            throw new AgentConfigException("TM-AGENT-010", "unable to build Agent WebSocket URL", exception);
        }
    }

    private static URI validateServerUrl(URI value) {
        Objects.requireNonNull(value, "serverUrl");
        if (!value.isAbsolute() || value.getHost() == null
                || !("http".equalsIgnoreCase(value.getScheme())
                || "https".equalsIgnoreCase(value.getScheme()))) {
            throw new AgentConfigException("TM-AGENT-010", "server URL must be an absolute HTTP(S) URL");
        }
        return value;
    }

    private static Path normalize(Path value) {
        return value.toAbsolutePath().normalize();
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new AgentConfigException("TM-AGENT-010", name + " must be positive");
        }
        return value;
    }

    private static String normalizeTailcatVersion(String value) {
        String normalized = value == null || value.isBlank()
                ? DEFAULT_TAILCAT_VERSION : value.trim();
        if (normalized.startsWith("v") || normalized.startsWith("V")) {
            normalized = normalized.substring(1);
        }
        if (!normalized.matches("\\d+\\.\\d+\\.\\d+")) {
            throw new AgentConfigException("TM-AGENT-010",
                    "tailcat.version must be a semantic version such as 0.3.0");
        }
        return normalized;
    }

    private static String normalizeDeviceName(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > 255 || normalized.indexOf('\r') >= 0 || normalized.indexOf('\n') >= 0) {
            throw new AgentConfigException("TM-AGENT-010",
                    "device.name must be at most 255 characters and must not contain newlines");
        }
        return normalized;
    }
}
