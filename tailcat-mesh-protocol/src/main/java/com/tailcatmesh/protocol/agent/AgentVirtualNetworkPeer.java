package com.tailcatmesh.protocol.agent;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** A peer that belongs to the same virtual Mesh Network as this Agent. */
public record AgentVirtualNetworkPeer(
        UUID peerDeviceId,
        String name,
        String virtualIpv4,
        String connBlob,
        String clientPublicKey
) {

    private static final Pattern CONN_BLOB = Pattern.compile("tc[A-Za-z0-9_-]+");
    private static final Pattern PUBLIC_KEY = Pattern.compile("nodekey:[0-9a-fA-F]{64}");

    public AgentVirtualNetworkPeer {
        Objects.requireNonNull(peerDeviceId, "peerDeviceId");
        name = requiredText(name, "name", 255);
        virtualIpv4 = requiredText(virtualIpv4, "virtualIpv4", 15);
        if (connBlob != null && !connBlob.isBlank() && !CONN_BLOB.matcher(connBlob).matches()) {
            throw new IllegalArgumentException("connBlob must be a valid Tailcat token or null");
        }
        connBlob = connBlob == null || connBlob.isBlank() ? null : connBlob;
        if (clientPublicKey != null && !clientPublicKey.isBlank()
                && !PUBLIC_KEY.matcher(clientPublicKey).matches()) {
            throw new IllegalArgumentException("clientPublicKey must be a valid Tailcat public key or null");
        }
        clientPublicKey = clientPublicKey == null || clientPublicKey.isBlank() ? null : clientPublicKey;
    }

    private static String requiredText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(field + " is required and must be a single short value");
        }
        return value.trim();
    }
}
