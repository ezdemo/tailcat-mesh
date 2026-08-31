package com.tailcatmesh.server;

import com.tailcatmesh.protocol.agent.AgentEnrollmentResponse;
import com.tailcatmesh.protocol.agent.AgentPeer;
import com.tailcatmesh.protocol.agent.AgentPeerRuntime;
import com.tailcatmesh.protocol.agent.AgentPeerRuntimeReport;
import com.tailcatmesh.protocol.agent.AgentRuntimeServerRequest;
import com.tailcatmesh.server.agentws.AgentDesiredStateService;
import com.tailcatmesh.server.device.DeviceService;
import com.tailcatmesh.server.enrollment.EnrollmentService;
import com.tailcatmesh.server.mesh.MeshNetworkRecord;
import com.tailcatmesh.server.mesh.MeshNetworkRepository;
import com.tailcatmesh.server.peer.PeerService;
import com.tailcatmesh.server.peer.PeerStatus;
import com.tailcatmesh.server.peer.PeerStatusView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M5 acceptance test for peer metadata, path reports, and status snapshots. */
@SpringBootTest
@ActiveProfiles("test")
class PeerStatusManagementTest {

    private static final String B_SERVER_BLOB = "tcPeerServerBlobB_123456789";

    @Autowired
    private EnrollmentService enrollmentService;

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private MeshNetworkRepository networkRepository;

    @Autowired
    private AgentDesiredStateService desiredStateService;

    @Autowired
    private PeerService peerService;

    @Test
    void approvedPeersAreDeliveredAndRuntimeSnapshotIsReplaced() {
        UUID networkId = createNetwork();
        AgentEnrollmentResponse deviceA = enroll(networkId, "M5-A", "a");
        AgentEnrollmentResponse deviceB = enroll(networkId, "M5-B", "b");
        AgentEnrollmentResponse pendingDevice = enroll(networkId, "M5-PENDING", "c");

        deviceService.approve(deviceA.deviceId());
        deviceService.approve(deviceB.deviceId());
        deviceService.runtimeServer(deviceB.deviceId(), new AgentRuntimeServerRequest(
                true, B_SERVER_BLOB, B_SERVER_BLOB, Instant.now()));

        List<AgentPeer> peers = desiredStateService.get(deviceA.deviceId()).peers();
        assertEquals(1, peers.size());
        assertEquals(deviceB.deviceId(), peers.get(0).peerDeviceId());
        assertEquals("M5-B", peers.get(0).name());
        assertEquals(B_SERVER_BLOB, peers.get(0).connBlob());
        assertTrue(peers.stream().noneMatch(peer -> peer.peerDeviceId().equals(pendingDevice.deviceId())));

        Instant checkedAt = Instant.parse("2026-08-31T06:00:00Z");
        peerService.recordRuntime(deviceA.deviceId(), new AgentPeerRuntimeReport(
                List.of(new AgentPeerRuntime(deviceB.deviceId(), "online", "derp", 42.1,
                        "sfo", null, null)), checkedAt));

        List<PeerStatusView> statuses = peerService.list();
        assertEquals(1, statuses.size());
        PeerStatusView status = statuses.get(0);
        assertEquals(deviceA.deviceId(), status.sourceDeviceId());
        assertEquals("M5-A", status.sourceDeviceName());
        assertEquals(deviceB.deviceId(), status.peerDeviceId());
        assertEquals("M5-B", status.peerDeviceName());
        assertEquals(PeerStatus.ONLINE, status.status());
        assertEquals("DERP", status.pathType());
        assertEquals(42.1, status.latencyMs());
        assertEquals("sfo", status.derpRegion());
        assertNull(status.directEndpoint());
        assertEquals(checkedAt, status.lastCheckAt());

        peerService.recordRuntime(deviceA.deviceId(), new AgentPeerRuntimeReport(List.of(), Instant.now()));
        assertTrue(peerService.list().isEmpty(), "an empty Agent snapshot clears stale peer rows");
    }

    private UUID createNetwork() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        networkRepository.insert(new MeshNetworkRecord(id, "M5 Network", "m5-" + id, now, now));
        return id;
    }

    private AgentEnrollmentResponse enroll(UUID networkId, String hostname, String keyPrefix) {
        EnrollmentService.EnrollmentTokenCreated token = enrollmentService.createToken(
                new EnrollmentService.CreateEnrollmentTokenRequest(networkId, 1, 1));
        return enrollmentService.enroll(new com.tailcatmesh.protocol.agent.AgentEnrollmentRequest(
                token.token(), hostname, "windows", "amd64", "0.1.0", "0.3.0",
                "nodekey:" + keyPrefix.repeat(64)));
    }
}
