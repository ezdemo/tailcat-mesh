package com.tailcatmesh.agent.tailcat;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Locates an administrator-provided official Tailcat executable. */
public final class TailcatBinaryLocator {

    public static final String BINARY_PROPERTY = "tailcat.binary";
    public static final String BINARY_ENVIRONMENT = "TAILCAT_BINARY";

    private TailcatBinaryLocator() {
    }

    public static Optional<Path> locate() {
        List<Path> candidates = new ArrayList<>();
        addConfigured(candidates, System.getProperty(BINARY_PROPERTY));
        addConfigured(candidates, System.getenv(BINARY_ENVIRONMENT));

        boolean windows = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");
        String executable = windows ? "tailcat.exe" : "tailcat";
        candidates.add(Path.of("bin", executable));
        candidates.add(Path.of(executable));
        candidates.addAll(pathCandidates(executable));
        if (windows) {
            candidates.addAll(pathCandidates("tailcat"));
        }

        Set<Path> seen = new LinkedHashSet<>();
        for (Path candidate : candidates) {
            Path normalized = candidate.toAbsolutePath().normalize();
            if (seen.add(normalized) && isUsable(normalized)) {
                return Optional.of(normalized);
            }
        }
        return Optional.empty();
    }

    public static Path require() {
        return locate().orElseThrow(() -> new TailcatEngineException(
                "TM-AGENT-001",
                "official tailcat binary not found; configure -D" + BINARY_PROPERTY
                        + " or " + BINARY_ENVIRONMENT
        ));
    }

    private static void addConfigured(List<Path> candidates, String value) {
        if (value != null && !value.isBlank()) {
            candidates.add(Path.of(value.trim()));
        }
    }

    private static List<Path> pathCandidates(String executable) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return List.of();
        }
        List<Path> candidates = new ArrayList<>();
        for (String entry : path.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (!entry.isBlank()) {
                candidates.add(Path.of(entry, executable));
            }
        }
        return candidates;
    }

    private static boolean isUsable(Path path) {
        return Files.isRegularFile(path)
                && (isWindows() || Files.isExecutable(path));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");
    }
}
