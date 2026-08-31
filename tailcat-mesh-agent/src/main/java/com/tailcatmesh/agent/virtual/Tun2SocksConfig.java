package com.tailcatmesh.agent.virtual;

import com.tailcatmesh.agent.forward.PeerSocksEndpoint;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Explicit command-template configuration for one tun2socks sidecar.
 *
 * <p>Because tun2socks implementations use different CLI spellings, the
 * adapter never guesses flags. The template must contain the exact arguments
 * required by the selected binary and may use {@code ${tun}},
 * {@code ${proxy}}, {@code ${proxy-host}}, and {@code ${proxy-port}}.</p>
 */
public record Tun2SocksConfig(
        Path binary,
        String interfaceName,
        PeerSocksEndpoint proxy,
        List<String> argumentTemplate,
        Path workingDirectory,
        Map<String, String> environment,
        Duration startupTimeout
) {

    public Tun2SocksConfig {
        binary = Objects.requireNonNull(binary, "binary").toAbsolutePath().normalize();
        interfaceName = Objects.requireNonNull(interfaceName, "interfaceName").trim();
        if (interfaceName.isBlank() || interfaceName.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("interfaceName must not be blank");
        }
        proxy = Objects.requireNonNull(proxy, "proxy");
        argumentTemplate = argumentTemplate == null ? List.of() : List.copyOf(argumentTemplate);
        for (String argument : argumentTemplate) {
            if (argument == null || argument.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("tun2socks argument template contains an invalid value");
            }
        }
        if (workingDirectory != null) {
            workingDirectory = workingDirectory.toAbsolutePath().normalize();
        }
        environment = environment == null ? Map.of() : Map.copyOf(environment);
        startupTimeout = requirePositive(startupTimeout);
    }

    private static Duration requirePositive(Duration timeout) {
        Objects.requireNonNull(timeout, "startupTimeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("startupTimeout must be positive");
        }
        return timeout;
    }
}
