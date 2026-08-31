package com.tailcatmesh.agent.tailcat.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/** Parsed JSON metadata returned by the official {@code tailcat parse} command. */
public record TailcatTokenInfo(
        String serverPublicKey,
        Integer regionId,
        JsonNode region,
        JsonNode rawJson
) {
    public TailcatTokenInfo {
        if (serverPublicKey == null || serverPublicKey.isBlank()) {
            throw new IllegalArgumentException("serverPublicKey must not be blank");
        }
        Objects.requireNonNull(rawJson, "rawJson");
    }
}
