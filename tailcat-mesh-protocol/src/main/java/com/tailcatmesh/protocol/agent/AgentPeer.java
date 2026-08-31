package com.tailcatmesh.protocol.agent;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Approved remote device metadata delivered to an Agent. */
public record AgentPeer(
        UUID peerDeviceId,
        String name,
        String connBlob
) {
    private static final Pattern CONN_BLOB = Pattern.compile("tc[A-Za-z0-9_-]+");

    public AgentPeer {
        Objects.requireNonNull(peerDeviceId, "peerDeviceId");
        name = requiredText(name, "name", 255);
        if (connBlob != null && !connBlob.isBlank() && !CONN_BLOB.matcher(connBlob).matches()) {
            throw new IllegalArgumentException("connBlob must be a valid Tailcat token or null");
        }
        connBlob = connBlob == null || connBlob.isBlank() ? null : connBlob;
    }

    private static String requiredText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(field + " is required and must be a single short value");
        }
        return value.trim();
    }
}
