package com.tailcatmesh.server.mesh;

import com.tailcatmesh.server.device.DeviceRecord;
import com.tailcatmesh.server.device.DeviceStatus;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Calculates the Tailcat client public keys that one device may accept.
 *
 * <p>The v0.1 policy is intentionally simple: an approved device accepts all
 * other approved, non-disabled devices in the same mesh network. Invalid or
 * missing keys are omitted so a malformed registration cannot widen the
 * allowlist.</p>
 */
@Component
public final class MeshAllowlistCalculator {

    private static final Pattern PUBLIC_KEY = Pattern.compile("nodekey:[0-9a-fA-F]{64}");

    public List<String> allowedClientPublicKeys(DeviceRecord target, List<DeviceRecord> networkDevices) {
        Objects.requireNonNull(target, "target");
        if (!isApproved(target.status())) {
            return List.of();
        }
        if (networkDevices == null || networkDevices.isEmpty()) {
            return List.of();
        }
        return networkDevices.stream()
                .filter(Objects::nonNull)
                .filter(device -> !target.id().equals(device.id()))
                .filter(device -> isApproved(device.status()))
                .map(DeviceRecord::clientPublicKey)
                .filter(key -> key != null && PUBLIC_KEY.matcher(key).matches())
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    private static boolean isApproved(DeviceStatus status) {
        return status == DeviceStatus.ONLINE || status == DeviceStatus.OFFLINE;
    }
}
