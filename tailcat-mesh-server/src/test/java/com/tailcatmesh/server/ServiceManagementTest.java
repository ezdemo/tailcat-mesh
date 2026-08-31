package com.tailcatmesh.server;

import com.tailcatmesh.protocol.agent.AgentDesiredState;
import com.tailcatmesh.protocol.agent.AgentEnrollmentRequest;
import com.tailcatmesh.protocol.agent.AgentEnrollmentResponse;
import com.tailcatmesh.protocol.agent.AgentServiceRuntime;
import com.tailcatmesh.protocol.agent.AgentServiceRuntimeReport;
import com.tailcatmesh.server.device.DeviceService;
import com.tailcatmesh.server.enrollment.EnrollmentService;
import com.tailcatmesh.server.mesh.MeshNetworkRecord;
import com.tailcatmesh.server.mesh.MeshNetworkRepository;
import com.tailcatmesh.server.service.ServiceService;
import com.tailcatmesh.server.service.ServiceStatus;
import com.tailcatmesh.server.service.ServiceView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** M4 acceptance test for Service CRUD, desired state, and runtime projection. */
@SpringBootTest
@ActiveProfiles("test")
class ServiceManagementTest {

    @Autowired
    private EnrollmentService enrollmentService;

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private MeshNetworkRepository networkRepository;

    @Autowired
    private ServiceService serviceService;

    @Autowired
    private com.tailcatmesh.server.agentws.AgentDesiredStateService desiredStateService;

    @Test
    void publishesTcpServiceAndMovesItAcrossNetworks() {
        UUID firstNetwork = createNetwork("m4-first");
        UUID secondNetwork = createNetwork("m4-second");
        AgentEnrollmentResponse firstDevice = enroll(firstNetwork, "M4-FIRST", "d");
        AgentEnrollmentResponse secondDevice = enroll(secondNetwork, "M4-SECOND", "e");
        deviceService.approve(firstDevice.deviceId());
        deviceService.approve(secondDevice.deviceId());

        long firstRevisionBeforeCreate = deviceService.find(firstDevice.deviceId()).desiredRevision();
        long secondRevisionBeforeMove = deviceService.find(secondDevice.deviceId()).desiredRevision();
        ServiceView created = serviceService.create(new ServiceService.ServiceRequest(
                firstDevice.deviceId(), "internal-http", "tcp", "127.0.0.1", 18_080, true));

        assertEquals(ServiceStatus.STOPPED.name(), created.status());
        assertNull(created.bridgePort());
        assertEquals(firstRevisionBeforeCreate + 1,
                deviceService.find(firstDevice.deviceId()).desiredRevision());
        assertServiceIds(desiredStateService.get(firstDevice.deviceId()), created.id());

        serviceService.recordRuntime(firstDevice.deviceId(), new AgentServiceRuntimeReport(
                List.of(new AgentServiceRuntime(created.id(), 45_123, "READY", null)), Instant.now()));
        ServiceView ready = serviceService.get(created.id());
        assertEquals(ServiceStatus.READY.name(), ready.status());
        assertEquals(45_123, ready.bridgePort());

        ServiceView moved = serviceService.update(created.id(), new ServiceService.ServiceRequest(
                secondDevice.deviceId(), "internal-http", "TCP", "127.0.0.1", 18_081, true));
        assertEquals(secondDevice.deviceId(), moved.deviceId());
        assertEquals(18_081, moved.targetPort());
        assertEquals(ServiceStatus.STOPPED.name(), moved.status());
        assertNull(moved.bridgePort());
        assertEquals(firstRevisionBeforeCreate + 2,
                deviceService.find(firstDevice.deviceId()).desiredRevision());
        assertEquals(secondRevisionBeforeMove + 1,
                deviceService.find(secondDevice.deviceId()).desiredRevision());
        assertEquals(0, desiredStateService.get(firstDevice.deviceId()).services().size());
        assertServiceIds(desiredStateService.get(secondDevice.deviceId()), created.id());

        serviceService.delete(created.id());
        assertEquals(secondRevisionBeforeMove + 2,
                deviceService.find(secondDevice.deviceId()).desiredRevision());
        assertEquals(0, desiredStateService.get(secondDevice.deviceId()).services().size());
    }

    private UUID createNetwork(String prefix) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        networkRepository.insert(new MeshNetworkRecord(id, prefix, prefix + "-" + id, now, now));
        return id;
    }

    private AgentEnrollmentResponse enroll(UUID networkId, String hostname, String keyPrefix) {
        EnrollmentService.EnrollmentTokenCreated token = enrollmentService.createToken(
                new EnrollmentService.CreateEnrollmentTokenRequest(networkId, 1, 1));
        return enrollmentService.enroll(new AgentEnrollmentRequest(
                token.token(), hostname, "windows", "amd64", "0.1.0", "0.3.0",
                "nodekey:" + keyPrefix.repeat(64)));
    }

    private static void assertServiceIds(AgentDesiredState state, UUID expectedId) {
        assertEquals(List.of(expectedId), state.services().stream()
                .map(service -> service.serviceId()).toList());
    }
}
