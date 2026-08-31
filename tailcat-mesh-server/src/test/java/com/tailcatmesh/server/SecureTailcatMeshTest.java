package com.tailcatmesh.server;

import com.tailcatmesh.protocol.agent.AgentDesiredState;
import com.tailcatmesh.protocol.agent.AgentEnrollmentRequest;
import com.tailcatmesh.protocol.agent.AgentEnrollmentResponse;
import com.tailcatmesh.server.device.DeviceService;
import com.tailcatmesh.server.enrollment.EnrollmentService;
import com.tailcatmesh.server.mesh.MeshNetworkRecord;
import com.tailcatmesh.server.mesh.MeshNetworkRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M3 acceptance test for membership-driven deny-by-default allowlists. */
@SpringBootTest
@ActiveProfiles("test")
class SecureTailcatMeshTest {

    @Autowired
    private EnrollmentService enrollmentService;

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private MeshNetworkRepository networkRepository;

    @Autowired
    private com.tailcatmesh.server.agentws.AgentDesiredStateService desiredStateService;

    @Test
    void approvedDevicesReceiveOnlyApprovedPeerKeysAndDisableRevokesAccess() {
        UUID networkId = UUID.randomUUID();
        Instant now = Instant.now();
        networkRepository.insert(new MeshNetworkRecord(networkId, "M3 Network", "m3-" + networkId,
                now, now));

        String keyA = "nodekey:" + "a".repeat(64);
        String keyB = "nodekey:" + "b".repeat(64);
        AgentEnrollmentResponse deviceA = enroll(networkId, keyA, "M3-A");
        AgentEnrollmentResponse deviceB = enroll(networkId, keyB, "M3-B");

        assertTrue(desiredStateService.get(deviceA.deviceId()).allowedClientPublicKeys().isEmpty());
        assertTrue(desiredStateService.get(deviceB.deviceId()).allowedClientPublicKeys().isEmpty());

        deviceService.approve(deviceA.deviceId());
        assertTrue(desiredStateService.get(deviceA.deviceId()).allowedClientPublicKeys().isEmpty());
        assertTrue(desiredStateService.get(deviceB.deviceId()).allowedClientPublicKeys().isEmpty(),
                "a pending device must remain deny-all");

        deviceService.approve(deviceB.deviceId());
        AgentDesiredState stateA = desiredStateService.get(deviceA.deviceId());
        AgentDesiredState stateB = desiredStateService.get(deviceB.deviceId());
        assertEquals(2, stateA.revision());
        assertEquals(2, stateB.revision());
        assertEquals(java.util.List.of(keyB), stateA.allowedClientPublicKeys());
        assertEquals(java.util.List.of(keyA), stateB.allowedClientPublicKeys());

        deviceService.disable(deviceB.deviceId());
        assertEquals(3, desiredStateService.get(deviceA.deviceId()).revision());
        assertTrue(desiredStateService.get(deviceA.deviceId()).allowedClientPublicKeys().isEmpty());
        assertTrue(desiredStateService.get(deviceB.deviceId()).allowedClientPublicKeys().isEmpty());
    }

    private AgentEnrollmentResponse enroll(UUID networkId, String clientPublicKey, String hostname) {
        EnrollmentService.EnrollmentTokenCreated token = enrollmentService.createToken(
                new EnrollmentService.CreateEnrollmentTokenRequest(networkId, 1, 1));
        return enrollmentService.enroll(new AgentEnrollmentRequest(
                token.token(), hostname, "windows", "amd64", "0.1.0", "0.3.0", clientPublicKey));
    }
}
