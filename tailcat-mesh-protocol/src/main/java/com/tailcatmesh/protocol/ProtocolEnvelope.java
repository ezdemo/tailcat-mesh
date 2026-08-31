package com.tailcatmesh.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Transport-neutral envelope shared by the Server and Agent.
 *
 * <p>The envelope intentionally has no persistence or Spring dependencies.
 * Command-specific payload models will be added with the control-channel
 * milestone.</p>
 */
public record ProtocolEnvelope(
        UUID id,
        String type,
        Instant timestamp,
        JsonNode payload
) {
    public ProtocolEnvelope {
        Objects.requireNonNull(id, "id");
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type must not be blank");
        }
        Objects.requireNonNull(timestamp, "timestamp");
        payload = payload == null ? NullNode.getInstance() : payload;
    }

    public static ProtocolEnvelope of(String type, JsonNode payload) {
        return new ProtocolEnvelope(UUID.randomUUID(), type, Instant.now(), payload);
    }
}
