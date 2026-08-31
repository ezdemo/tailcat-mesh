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
        VirtualLanAgentConfig virtualLan
) {
    public AgentConfig(URI serverUrl, Path tailcatBinary, Path dataDir,
                       Path serverKeyPath, Path clientKeyPath, boolean fullAddress,
                       String derpMapUrl, Duration heartbeatInterval,
                       Duration peerPingInterval) {
        this(serverUrl, tailcatBinary, dataDir, serverKeyPath, clientKeyPath, fullAddress,
                derpMapUrl, heartbeatInterval, peerPingInterval, VirtualLanAgentConfig.disabled());
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
}
