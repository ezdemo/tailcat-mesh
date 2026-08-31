package com.tailcatmesh.agent.tailcat.model;

import java.util.Objects;

/** Structured result of the official Tailcat ping text output. */
public record TailcatPingResult(
        TailcatPathType pathType,
        double latencyMs,
        String derpRegion,
        String endpoint,
        String rawOutput
) {
    public TailcatPingResult {
        Objects.requireNonNull(pathType, "pathType");
        if (Double.isNaN(latencyMs) || latencyMs < -1) {
            throw new IllegalArgumentException("latencyMs must be -1 or non-negative");
        }
        rawOutput = rawOutput == null ? "" : rawOutput;
    }

    public static TailcatPingResult unknown(String rawOutput) {
        return new TailcatPingResult(TailcatPathType.UNKNOWN, -1, null, null, rawOutput);
    }

    public static TailcatPingResult offline(String rawOutput) {
        return new TailcatPingResult(TailcatPathType.OFFLINE, -1, null, null, rawOutput);
    }
}
