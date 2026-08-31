package com.tailcatmesh.server;

import com.tailcatmesh.protocol.agent.AgentEnrollmentRequest;
import com.tailcatmesh.protocol.agent.AgentEnrollmentResponse;
import com.tailcatmesh.protocol.agent.AgentVirtualNetwork;
import com.tailcatmesh.protocol.agent.AgentVirtualNetworkRuntime;
import com.tailcatmesh.protocol.agent.AgentVirtualNetworkRuntimeReport;
import com.tailcatmesh.server.agentws.AgentDesiredStateService;
import com.tailcatmesh.server.device.DeviceService;
import com.tailcatmesh.server.enrollment.EnrollmentService;
import com.tailcatmesh.server.mesh.MeshNetworkMemberRepository;
import com.tailcatmesh.server.mesh.MeshNetworkService;
import com.tailcatmesh.server.mesh.VirtualNetworkRuntimeRepository;
import com.tailcatmesh.server.mesh.VirtualNetworkRuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M7.2 acceptance tests for isolated runtime reports and network-scoped desired state. */
@SpringBootTest
@ActiveProfiles("test")
class VirtualNetworkRuntimeTest {

    @Autowired
    private MeshNetworkService meshNetworkService;

    @Autowired
    private EnrollmentService enrollmentService;

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private VirtualNetworkRuntimeService runtimeService;

    @Autowired
    private VirtualNetworkRuntimeRepository runtimeRepository;

    @Autowired
    private MeshNetworkMemberRepository memberRepository;

    @Autowired
    private AgentDesiredStateService desiredStateService;

    @Test
    void projectsRuntimeOnlyToTheMatchingNetworkAndRevokesItOnRemoval() {
        var home = meshNetworkService.create(
                new MeshNetworkService.NetworkRequest("M7.2 Home " + UUID.randomUUID(), null));
        var dev = meshNetworkService.create(
                new MeshNetworkService.NetworkRequest("M7.2 Dev " + UUID.randomUUID(), null));
        AgentEnrollmentResponse source = enrollOnDefault("M7.2-source", "a");
        AgentEnrollmentResponse peer = enrollOnDefault("M7.2-peer", "b");
        deviceService.approve(source.deviceId());
        deviceService.approve(peer.deviceId());

        meshNetworkService.addMember(home.id(), new MeshNetworkService.MemberRequest(source.deviceId(), null));
        meshNetworkService.addMember(home.id(), new MeshNetworkService.MemberRequest(peer.deviceId(), null));
        meshNetworkService.addMember(dev.id(), new MeshNetworkService.MemberRequest(source.deviceId(), null));
        meshNetworkService.addMember(dev.id(), new MeshNetworkService.MemberRequest(peer.deviceId(), null));

        String homeConnBlob = "tchome" + "1".repeat(24);
        runtimeService.recordRuntime(peer.deviceId(), new AgentVirtualNetworkRuntimeReport(
                List.of(new AgentVirtualNetworkRuntime(home.id(), "READY", homeConnBlob, null, null)),
                Instant.now()));
        long revisionAfterFirstReport = deviceService.find(source.deviceId()).desiredRevision();

        AgentVirtualNetwork homeDesired = desiredStateService.get(source.deviceId()).virtualNetworks().stream()
                .filter(network -> network.networkId().equals(home.id()))
                .findFirst().orElseThrow();
        AgentVirtualNetwork devDesired = desiredStateService.get(source.deviceId()).virtualNetworks().stream()
                .filter(network -> network.networkId().equals(dev.id()))
                .findFirst().orElseThrow();
        assertEquals(1, homeDesired.peers().size());
        assertEquals(homeConnBlob, homeDesired.peers().getFirst().connBlob());
        assertEquals(1, devDesired.peers().size());
        assertNull(devDesired.peers().getFirst().connBlob(),
                "a runtime from Home must not leak into Dev");

        var stored = runtimeRepository.findByNetworkAndDevice(home.id(), peer.deviceId()).orElseThrow();
        assertEquals("READY", stored.status());
        assertNotNull(stored.connBlobHash());

        runtimeService.recordRuntime(peer.deviceId(), new AgentVirtualNetworkRuntimeReport(
                List.of(new AgentVirtualNetworkRuntime(home.id(), "READY", homeConnBlob, null, null)),
                Instant.now()));
        assertEquals(revisionAfterFirstReport, deviceService.find(source.deviceId()).desiredRevision(),
                "repeating the same runtime is idempotent");

        meshNetworkService.removeMember(home.id(), peer.deviceId());
        assertTrue(runtimeRepository.findByNetworkAndDevice(home.id(), peer.deviceId()).isEmpty());
        AgentVirtualNetwork afterRemoval = desiredStateService.get(source.deviceId()).virtualNetworks().stream()
                .filter(network -> network.networkId().equals(home.id()))
                .findFirst().orElseThrow();
        assertEquals(List.of(), afterRemoval.peers());

        // A late report from the removed member cannot recreate its capability.
        runtimeService.recordRuntime(peer.deviceId(), new AgentVirtualNetworkRuntimeReport(
                List.of(new AgentVirtualNetworkRuntime(home.id(), "READY", homeConnBlob, null, null)),
                Instant.now()));
        assertTrue(runtimeRepository.findByNetworkAndDevice(home.id(), peer.deviceId()).isEmpty());
    }

    @Test
    void deletingNetworkCascadesRuntimeAndMembershipState() {
        var network = meshNetworkService.create(
                new MeshNetworkService.NetworkRequest("M7.9 Delete " + UUID.randomUUID(), null));
        AgentEnrollmentResponse device = enrollOnDefault("M7.9-delete", "c");
        deviceService.approve(device.deviceId());
        meshNetworkService.addMember(network.id(),
                new MeshNetworkService.MemberRequest(device.deviceId(), null));

        runtimeService.recordRuntime(device.deviceId(), new AgentVirtualNetworkRuntimeReport(
                List.of(new AgentVirtualNetworkRuntime(
                        network.id(), "READY", "tcdelete" + "1".repeat(24), null, null)),
                Instant.now()));
        assertTrue(runtimeRepository.findByNetworkAndDevice(network.id(), device.deviceId()).isPresent());

        meshNetworkService.delete(network.id());

        assertTrue(networkRepository.findById(network.id()).isEmpty());
        assertTrue(memberRepository.findByNetworkAndDevice(network.id(), device.deviceId()).isEmpty());
        assertTrue(runtimeRepository.findByNetworkAndDevice(network.id(), device.deviceId()).isEmpty());
    }

    private AgentEnrollmentResponse enrollOnDefault(String hostname, String keyPrefix) {
        UUID defaultNetworkId = networkRepository.findBySlug("default").orElseThrow().id();
        EnrollmentService.EnrollmentTokenCreated token = enrollmentService.createToken(
                new EnrollmentService.CreateEnrollmentTokenRequest(defaultNetworkId, 1, 1));
        return enrollmentService.enroll(new AgentEnrollmentRequest(
                token.token(), hostname, "windows", "amd64", "0.1.0", "0.3.0",
                "nodekey:" + keyPrefix.repeat(64)));
    }

    @Autowired
    private com.tailcatmesh.server.mesh.MeshNetworkRepository networkRepository;
}
