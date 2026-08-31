package com.tailcatmesh.agent.tailcat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/** Runtime options for {@link TailcatCliEngine}. */
public record TailcatCliEngineConfig(
        Path binary,
        Path workingDirectory,
        Map<String, String> environment,
        Duration commandTimeout,
        Duration startupTimeout,
        boolean allowUnsupportedTailcat
) {
    public TailcatCliEngineConfig {
        binary = Objects.requireNonNull(binary, "binary").toAbsolutePath().normalize();
        if (workingDirectory != null) {
            workingDirectory = workingDirectory.toAbsolutePath().normalize();
        }
        environment = environment == null ? Map.of() : Map.copyOf(environment);
        commandTimeout = requirePositive(commandTimeout, "commandTimeout");
        startupTimeout = requirePositive(startupTimeout, "startupTimeout");
    }

    public static TailcatCliEngineConfig defaults(Path binary) {
        return new TailcatCliEngineConfig(
                binary,
                null,
                Map.of(),
                Duration.ofSeconds(15),
                Duration.ofSeconds(15),
                false
        );
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
