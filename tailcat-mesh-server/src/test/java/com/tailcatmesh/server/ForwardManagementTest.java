package com.tailcatmesh.server;

import com.tailcatmesh.protocol.agent.AgentDesiredState;
import com.tailcatmesh.protocol.agent.AgentEnrollmentRequest;
import com.tailcatmesh.protocol.agent.AgentEnrollmentResponse;
import com.tailcatmesh.protocol.agent.AgentForward;
import com.tailcatmesh.protocol.agent.AgentForwardRuntime;
import com.tailcatmesh.protocol.agent.AgentForwardRuntimeReport;
import com.tailcatmesh.protocol.agent.AgentServiceRuntime;
import com.tailcatmesh.protocol.agent.AgentServiceRuntimeReport;
import com.tailcatmesh.server.device.DeviceService;
import com.tailcatmesh.server.enrollment.EnrollmentService;
import com.tailcatmesh.server.forward.ForwardService;
import com.tailcatmesh.server.forward.ForwardStatus;
import com.tailcatmesh.server.forward.ForwardView;
import com.tailcatmesh.server.mesh.MeshNetworkRecord;
import com.tailcatmesh.server.mesh.MeshNetworkRepository;
import com.tailcatmesh.server.service.ServiceService;
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

/** M6 acceptance test for Forward CRUD, desired-state projection and runtime state. */
@SpringBootTest
@ActiveProfiles("test")
class ForwardManagementTest {

    @Autowired
    private EnrollmentService enrollmentService;

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private MeshNetworkRepository networkRepository;

    @Autowired
    private ServiceService serviceService;

    @Autowired
    private ForwardService forwardService;

    @Autowired
    private com.tailcatmesh.server.agentws.AgentDesiredStateService desiredStateService;

    @Test
    void projectsRemoteBridgePortAndTracksLocalForwardRuntime() {
        UUID networkId = createNetwork("m6-forward");
        AgentEnrollmentResponse source = enroll(networkId, "M6-SOURCE", "a");
        AgentEnrollmentResponse target = enroll(networkId, "M6-TARGET", "b");
        deviceService.approve(source.deviceId());
        deviceService.approve(target.deviceId());

        ServiceView remoteService = serviceService.create(new ServiceService.ServiceRequest(
                target.deviceId(), "remote-http", "TCP", "127.0.0.1", 18_080, true));
        serviceService.recordRuntime(target.deviceId(), new AgentServiceRuntimeReport(
                List.of(new AgentServiceRuntime(remoteService.id(), 45_123, "READY", null)), Instant.now()));

        long revisionBefore = deviceService.find(source.deviceId()).desiredRevision();
        ForwardView created = forwardService.create(new ForwardService.ForwardRequest(
                source.deviceId(), remoteService.id(), "desktop-http", "127.0.0.1", 18_888, true));

        assertEquals(ForwardStatus.STOPPED.name(), created.status());
        assertEquals(revisionBefore + 1, deviceService.find(source.deviceId()).desiredRevision());
        AgentDesiredState desired = desiredStateService.get(source.deviceId());
        assertEquals(1, desired.forwards().size());
        AgentForward projected = desired.forwards().getFirst();
        assertEquals(created.id(), projected.forwardId());
        assertEquals(target.deviceId(), projected.peerDeviceId());
        assertEquals(remoteService.id(), projected.remoteServiceId());
        assertEquals(45_123, projected.remoteBridgePort());
        assertEquals(18_888, projected.localBindPort());

        forwardService.recordRuntime(source.deviceId(), new AgentForwardRuntimeReport(
                List.of(new AgentForwardRuntime(created.id(), "READY", null, null)), Instant.now()));
        ForwardView ready = forwardService.get(created.id());
        assertEquals(ForwardStatus.READY.name(), ready.status());
        assertNull(ready.errorCode());

        ForwardView disabled = forwardService.update(created.id(), new ForwardService.ForwardRequest(
                null, null, null, null, null, false));
        assertEquals(false, disabled.enabled());
        assertEquals(ForwardStatus.STOPPED.name(), disabled.status());
        assertEquals(1, forwardService.list().size());

        forwardService.recordRuntime(source.deviceId(), new AgentForwardRuntimeReport(List.of(), Instant.now()));
        assertEquals(ForwardStatus.STOPPED.name(), forwardService.get(created.id()).status());
        forwardService.delete(created.id());
        assertEquals(0, forwardService.list().size());
        assertEquals(0, desiredStateService.get(source.deviceId()).forwards().size());
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
}
