package com.tailcatmesh.agent.tailcat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tailcatmesh.agent.tailcat.model.TailcatCompatibility;
import com.tailcatmesh.agent.tailcat.model.TailcatPingResult;
import com.tailcatmesh.agent.tailcat.model.TailcatPathType;
import com.tailcatmesh.agent.tailcat.model.TailcatTokenInfo;
import com.tailcatmesh.agent.tailcat.model.TailcatVersion;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for the small, documented subset of official Tailcat CLI output
 * consumed by the Agent.
 */
public final class TailcatCliParser {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern VERSION = Pattern.compile(
            "(?i)(?<![0-9A-Za-z])v?(\\d+)\\.(\\d+)\\.(\\d+)(?:[-+][0-9A-Za-z.-]+)?(?![0-9A-Za-z])"
    );
    private static final Pattern PING = Pattern.compile(
            "^pong in ([0-9]+(?:\\.[0-9]+)?)(ns|us|µs|μs|ms|s|m) via (.+)$"
    );
    private static final Pattern SOCKS_LISTEN = Pattern.compile(
            "(?i).*\\bSOCKS running at socks5h?://(127\\.0\\.0\\.1):([0-9]{1,5})\\s*$"
    );
    private static final Pattern CONN_BLOB = Pattern.compile("tc[A-Za-z0-9_-]+");
    private static final int MAX_UNEXPECTED_OUTPUT = 4_096;

    public TailcatVersion parseVersion(String output) {
        if (output == null) {
            throw new TailcatEngineException("TM-AGENT-002", "tailcat --version returned no output");
        }
        Matcher matcher = VERSION.matcher(output.trim());
        if (!matcher.find()) {
            throw new TailcatEngineException("TM-AGENT-002", "unable to parse tailcat version");
        }
        return new TailcatVersion(
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3)),
                matcher.group(0)
        );
    }

    public TailcatCompatibility classify(TailcatVersion version) {
        if (version == null) {
            return TailcatCompatibility.UNKNOWN;
        }
        if (version.isV03x()) {
            return TailcatCompatibility.SUPPORTED;
        }
        if (version.major() == 0 && version.minor() < 3) {
            return TailcatCompatibility.UNSUPPORTED_OLDER;
        }
        return TailcatCompatibility.UNSUPPORTED_NEWER;
    }

    public String parseServerListenAddress(String jsonOutput) {
        JsonNode root = readObject(jsonOutput, "invalid Tailcat server JSON");
        JsonNode listenAddress = root.get("listenAddr");
        if (listenAddress == null || !listenAddress.isTextual()
                || !CONN_BLOB.matcher(listenAddress.textValue()).matches()) {
            throw new TailcatEngineException(
                    "TM-AGENT-004",
                    "Tailcat server JSON did not contain a valid listenAddr"
            );
        }
        return listenAddress.textValue();
    }

    public TailcatTokenInfo parseTokenJson(String jsonOutput) {
        JsonNode root = readObject(jsonOutput, "invalid Tailcat token JSON");
        JsonNode serverPublic = root.get("ServerPublic");
        if (serverPublic == null || !serverPublic.isTextual() || serverPublic.textValue().isBlank()) {
            throw new TailcatEngineException(
                    "TM-AGENT-004",
                    "Tailcat token JSON did not contain ServerPublic"
            );
        }
        Integer regionId = null;
        JsonNode regionIdNode = root.get("RegionID");
        if (regionIdNode != null && !regionIdNode.isNull()) {
            if (!regionIdNode.canConvertToInt()) {
                throw new TailcatEngineException("TM-AGENT-004", "Tailcat token RegionID is invalid");
            }
            regionId = regionIdNode.intValue();
        }
        JsonNode region = root.get("Region");
        return new TailcatTokenInfo(
                serverPublic.textValue(),
                regionId,
                region == null ? null : region.deepCopy(),
                root.deepCopy()
        );
    }

    /**
     * Parses the official human-readable ping line. Unknown output is a
     * non-fatal UNKNOWN result, as required by the Agent boundary.
     */
    public TailcatPingResult parsePingOutput(String output) {
        String raw = truncate(output == null ? "" : output.trim());
        if (output == null) {
            return TailcatPingResult.unknown(raw);
        }
        for (String line : output.split("\\R")) {
            Matcher matcher = PING.matcher(line.trim());
            if (!matcher.matches()) {
                continue;
            }
            double latency;
            try {
                latency = latencyMillis(matcher.group(1), matcher.group(2));
            } catch (NumberFormatException exception) {
                return TailcatPingResult.unknown(raw);
            }
            if (Double.isNaN(latency) || Double.isInfinite(latency)) {
                return TailcatPingResult.unknown(raw);
            }
            String via = matcher.group(3).trim();
            if (via.startsWith("DERP(") && via.endsWith(")")) {
                String region = via.substring("DERP(".length(), via.length() - 1).trim();
                if (region.isEmpty()) {
                    return TailcatPingResult.unknown(raw);
                }
                return new TailcatPingResult(
                        TailcatPathType.DERP,
                        latency,
                        region,
                        null,
                        raw
                );
            }
            if (!via.isEmpty()) {
                return new TailcatPingResult(
                        TailcatPathType.DIRECT,
                        latency,
                        null,
                        via,
                        raw
                );
            }
        }
        return TailcatPingResult.unknown(raw);
    }

    private static double latencyMillis(String value, String unit) {
        double number = Double.parseDouble(value);
        return switch (unit) {
            case "ns" -> number / 1_000_000d;
            case "us", "µs", "μs" -> number / 1_000d;
            case "ms" -> number;
            case "s" -> number * 1_000d;
            case "m" -> number * 60_000d;
            default -> Double.NaN;
        };
    }

    /** Parses the official v0.3.0 SOCKS readiness line emitted on stderr. */
    public SocksListenAddress parseSocksListenAddress(String output) {
        if (output == null || output.isBlank()) {
            throw new TailcatEngineException("TM-AGENT-005", "Tailcat SOCKS did not report a listen address");
        }
        for (String line : output.split("\\R")) {
            Matcher matcher = SOCKS_LISTEN.matcher(line.trim());
            if (!matcher.matches()) {
                continue;
            }
            int port;
            try {
                port = Integer.parseInt(matcher.group(2));
            } catch (NumberFormatException exception) {
                throw new TailcatEngineException("TM-AGENT-005", "Tailcat SOCKS reported an invalid port", exception);
            }
            if (port < 1 || port > 65_535) {
                throw new TailcatEngineException("TM-AGENT-005", "Tailcat SOCKS reported an invalid port");
            }
            return new SocksListenAddress(matcher.group(1), port);
        }
        throw new TailcatEngineException("TM-AGENT-005", "Tailcat SOCKS did not report a listen address");
    }

    public record SocksListenAddress(String host, int port) {
        public SocksListenAddress {
            if (host == null || host.isBlank()) {
                throw new IllegalArgumentException("host must not be blank");
            }
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException("port must be between 1 and 65535");
            }
        }
    }

    private static JsonNode readObject(String jsonOutput, String message) {
        if (jsonOutput == null || jsonOutput.isBlank()) {
            throw new TailcatEngineException("TM-AGENT-004", message);
        }
        try {
            JsonNode root = JSON.readTree(jsonOutput);
            if (root == null || !root.isObject()) {
                throw new TailcatEngineException("TM-AGENT-004", message);
            }
            return root;
        } catch (JsonProcessingException exception) {
            throw new TailcatEngineException("TM-AGENT-004", message, exception);
        }
    }

    private static String truncate(String value) {
        if (value.length() <= MAX_UNEXPECTED_OUTPUT) {
            return value;
        }
        return value.substring(0, MAX_UNEXPECTED_OUTPUT);
    }
}
