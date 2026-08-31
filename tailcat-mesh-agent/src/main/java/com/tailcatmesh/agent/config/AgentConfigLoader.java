package com.tailcatmesh.agent.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.tailcatmesh.agent.tailcat.TailcatBinaryLocator;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** Loads the documented agent.yml shape without making YAML part of the Tailcat boundary. */
public final class AgentConfigLoader {

    private static final int DEFAULT_HEARTBEAT_SECONDS = 15;
    private static final int DEFAULT_PEER_PING_SECONDS = 30;

    private final ObjectMapper objectMapper = new ObjectMapper(new YAMLFactory());

    public AgentConfig load(Path configPath, AgentConfigOverrides overrides) {
        Path normalizedConfig = configPath == null
                ? Path.of("agent.yml").toAbsolutePath().normalize()
                : configPath.toAbsolutePath().normalize();
        JsonNode root = readConfig(normalizedConfig);
        Path baseDir = normalizedConfig.getParent() == null
                ? Path.of(".").toAbsolutePath().normalize()
                : normalizedConfig.getParent();

        URI serverUrl = overrides == null || overrides.serverUrl() == null
                ? uri(root, "server.url") : overrides.serverUrl();
        Path dataDir = overrides == null || overrides.dataDir() == null
                ? resolve(baseDir, Path.of(text(root, "agent.dataDir", "data"))) : overrides.dataDir();

        String supportedVersion = text(root, "tailcat.supportedVersion", "0.3.x");
        if (!supportedVersion.isBlank() && !"0.3.x".equalsIgnoreCase(supportedVersion.trim())) {
            throw new AgentConfigException("TM-AGENT-010", "only tailcat.supportedVersion=0.3.x is supported");
        }
        Path binary = overrides == null || overrides.tailcatBinary() == null
                ? configuredBinary(root, baseDir) : resolve(baseDir, overrides.tailcatBinary());
        Path serverKey = resolveKey(dataDir, text(root, "tailcat.serverKey", "tailcat-mesh-server"));
        Path clientKey = resolveKey(dataDir, text(root, "tailcat.clientKey", "tailcat-mesh-client"));
        boolean fullAddress = bool(root, "tailcat.fullAddress", true);
        String derpMapUrl = text(root, "tailcat.derpMapUrl", "");
        int heartbeatSeconds = boundedSeconds(root, "agent.heartbeatSeconds", DEFAULT_HEARTBEAT_SECONDS);
        int peerPingSeconds = boundedSeconds(root, "agent.peerPingSeconds", DEFAULT_PEER_PING_SECONDS);

        return new AgentConfig(
                serverUrl,
                binary,
                dataDir,
                serverKey,
                clientKey,
                fullAddress,
                derpMapUrl,
                Duration.ofSeconds(heartbeatSeconds),
                Duration.ofSeconds(peerPingSeconds)
        );
    }

    private JsonNode readConfig(Path configPath) {
        if (!Files.isRegularFile(configPath)) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode root = objectMapper.readTree(Files.readString(configPath));
            return root == null || !root.isObject() ? objectMapper.createObjectNode() : root;
        } catch (IOException | RuntimeException exception) {
            throw new AgentConfigException("TM-AGENT-010", "unable to read agent configuration", exception);
        }
    }

    private Path configuredBinary(JsonNode root, Path baseDir) {
        String configured = text(root, "tailcat.binary", "");
        return configured.isBlank() ? TailcatBinaryLocator.require() : resolve(baseDir, Path.of(configured));
    }

    private static Path resolveKey(Path dataDir, String value) {
        Path candidate = Path.of(value);
        if (candidate.isAbsolute()) {
            return candidate;
        }
        return dataDir.resolve("identity").resolve(candidate).normalize();
    }

    private static Path resolve(Path baseDir, Path value) {
        return value.isAbsolute() ? value : baseDir.resolve(value).normalize();
    }

    private static String text(JsonNode root, String dottedPath, String defaultValue) {
        JsonNode current = root;
        for (String segment : dottedPath.split("\\.")) {
            current = current == null ? null : current.path(segment);
        }
        return current == null || current.isMissingNode() || current.isNull()
                ? defaultValue : current.asText(defaultValue);
    }

    private static URI uri(JsonNode root, String path) {
        String value = text(root, path, "");
        if (value.isBlank()) {
            throw new AgentConfigException("TM-AGENT-010", "server.url is required");
        }
        try {
            return URI.create(value.trim());
        } catch (IllegalArgumentException exception) {
            throw new AgentConfigException("TM-AGENT-010", "server.url is invalid", exception);
        }
    }

    private static boolean bool(JsonNode root, String path, boolean defaultValue) {
        JsonNode current = root;
        for (String segment : path.split("\\.")) {
            current = current == null ? null : current.path(segment);
        }
        return current == null || current.isMissingNode() ? defaultValue : current.asBoolean(defaultValue);
    }

    private static int boundedSeconds(JsonNode root, String path, int defaultValue) {
        JsonNode current = root;
        for (String segment : path.split("\\.")) {
            current = current == null ? null : current.path(segment);
        }
        int value = current == null || current.isMissingNode() ? defaultValue : current.asInt(defaultValue);
        if (value < 1 || value > 86_400) {
            throw new AgentConfigException("TM-AGENT-010", path + " must be between 1 and 86400");
        }
        return value;
    }
}
