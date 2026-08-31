package com.tailcatmesh.server.mesh;

import com.tailcatmesh.server.device.DeviceRecord;
import com.tailcatmesh.server.device.DeviceStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MeshAllowlistCalculatorTest {

    private static final UUID NETWORK_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.now();

    @Test
    void includesOnlyOtherApprovedDevicesAndSortsKeys() {
        DeviceRecord target = device(DeviceStatus.OFFLINE, "nodekey:" + "a".repeat(64));
        DeviceRecord onlinePeer = device(DeviceStatus.ONLINE, "nodekey:" + "c".repeat(64));
        DeviceRecord offlinePeer = device(DeviceStatus.OFFLINE, "nodekey:" + "b".repeat(64));
        DeviceRecord pending = device(DeviceStatus.PENDING, "nodekey:" + "d".repeat(64));
        DeviceRecord disabled = device(DeviceStatus.DISABLED, "nodekey:" + "e".repeat(64));
        DeviceRecord malformed = device(DeviceStatus.ONLINE, "not-a-key");

        assertEquals(List.of(
                        "nodekey:" + "b".repeat(64),
                        "nodekey:" + "c".repeat(64)),
                new MeshAllowlistCalculator().allowedClientPublicKeys(
                        target, List.of(target, onlinePeer, offlinePeer, pending, disabled, malformed)));
    }

    @Test
    void pendingAndDisabledTargetsAreAlwaysDenyAll() {
        DeviceRecord pending = device(DeviceStatus.PENDING, "nodekey:" + "a".repeat(64));
        DeviceRecord approvedPeer = device(DeviceStatus.ONLINE, "nodekey:" + "b".repeat(64));

        assertEquals(List.of(), new MeshAllowlistCalculator()
                .allowedClientPublicKeys(pending, List.of(pending, approvedPeer)));
    }

    private static DeviceRecord device(DeviceStatus status, String clientPublicKey) {
        return new DeviceRecord(
                UUID.randomUUID(), NETWORK_ID, "device", "host", "windows", "amd64", status,
                "0.1.0", "0.3.0", clientPublicKey, null, null, null, 0, NOW, NOW);
    }
}
