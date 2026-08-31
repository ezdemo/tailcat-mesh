package com.tailcatmesh.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tailcatmesh.protocol.agent.AgentEnrollmentRequest;
import com.tailcatmesh.protocol.agent.AgentEnrollmentResponse;
import com.tailcatmesh.server.device.DeviceService;
import com.tailcatmesh.server.enrollment.EnrollmentService;
import com.tailcatmesh.server.mesh.MeshNetworkService;
import com.tailcatmesh.server.mesh.MeshNetworkView;
import com.tailcatmesh.server.peer.PeerStatus;
import com.tailcatmesh.server.peer.PeerStatusRecord;
import com.tailcatmesh.server.peer.PeerStatusRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** M7.1 acceptance tests for Network, membership, IPAM, and REST projection. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MeshNetworkManagementTest {

    @Autowired
    private MeshNetworkService meshNetworkService;

    @Autowired
    private EnrollmentService enrollmentService;

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private com.tailcatmesh.server.mesh.MeshNetworkRepository networkRepository;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PeerStatusRepository peerStatusRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void assignsStablePerNetworkIpsAndRevokesRemovedMembership() {
        MeshNetworkView network = meshNetworkService.create(
                new MeshNetworkService.NetworkRequest("M7 Home " + UUID.randomUUID(), null));
        AgentEnrollmentResponse first = enrollOnDefault("M7-A", "a");
        AgentEnrollmentResponse second = enrollOnDefault("M7-B", "b");
        deviceService.approve(first.deviceId());
        deviceService.approve(second.deviceId());

        long firstRevision = deviceService.find(first.deviceId()).desiredRevision();
        var firstMember = meshNetworkService.addMember(network.id(),
                new MeshNetworkService.MemberRequest(first.deviceId(), null));
        var secondMember = meshNetworkService.addMember(network.id(),
                new MeshNetworkService.MemberRequest(second.deviceId(), null));
        var repeatedFirst = meshNetworkService.addMember(network.id(),
                new MeshNetworkService.MemberRequest(first.deviceId(), null));

        assertEquals(firstMember.id(), repeatedFirst.id());
        assertEquals(firstMember.virtualIpv4(), repeatedFirst.virtualIpv4());
        assertNotEquals(firstMember.virtualIpv4(), secondMember.virtualIpv4());
        assertTrue(firstMember.virtualIpv4().endsWith(".2"));
        assertTrue(secondMember.virtualIpv4().endsWith(".3"));
        assertTrue(deviceService.find(first.deviceId()).desiredRevision() > firstRevision);

        meshNetworkService.removeMember(network.id(), first.deviceId());
        var afterRemoval = meshNetworkService.get(network.id()).members().stream()
                .filter(member -> member.deviceId().equals(first.deviceId()))
                .findFirst().orElseThrow();
        assertFalse(afterRemoval.enabled());
        assertEquals(firstMember.virtualIpv4(), afterRemoval.virtualIpv4());

        // Re-adding the same persisted membership is idempotent and stable.
        var rejoined = meshNetworkService.addMember(network.id(),
                new MeshNetworkService.MemberRequest(first.deviceId(), null));
        assertEquals(firstMember.id(), rejoined.id());
        assertEquals(firstMember.virtualIpv4(), rejoined.virtualIpv4());

        MeshNetworkView anotherNetwork = meshNetworkService.create(
                new MeshNetworkService.NetworkRequest("M7 Dev " + UUID.randomUUID(), null));
        var secondNetworkMember = meshNetworkService.addMember(anotherNetwork.id(),
                new MeshNetworkService.MemberRequest(first.deviceId(), null));
        assertNotEquals(firstMember.virtualIpv4(), secondNetworkMember.virtualIpv4());
    }

    @Test
    void rejectsOverlappingNetworksAndExcludesPendingDevices() {
        MeshNetworkView first = meshNetworkService.create(
                new MeshNetworkService.NetworkRequest("M7 Overlap " + UUID.randomUUID(), null));
        assertThrows(com.tailcatmesh.server.common.ControlPlaneException.class,
                () -> meshNetworkService.create(new MeshNetworkService.NetworkRequest(
                        "M7 Overlap Conflict " + UUID.randomUUID(), first.cidr())));

        AgentEnrollmentResponse pending = enrollOnDefault("M7-PENDING", "c");
        assertThrows(com.tailcatmesh.server.common.ControlPlaneException.class,
                () -> meshNetworkService.addMember(first.id(),
                        new MeshNetworkService.MemberRequest(pending.deviceId(), null)));
    }

    @Test
    void exposesAdminNetworkRestProjection() throws Exception {
        JsonNode login = objectMapper.readTree(mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"test-password\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        String adminToken = login.path("accessToken").asText();
        String name = "M7 REST " + UUID.randomUUID();

        JsonNode created = objectMapper.readTree(mockMvc.perform(post("/api/v1/networks")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new MeshNetworkService.NetworkRequest(name, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.cidr").isNotEmpty())
                .andExpect(jsonPath("$.members").isArray())
                .andReturn().getResponse().getContentAsString());
        String networkId = created.path("id").asText();

        mockMvc.perform(get("/api/v1/networks")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + networkId + "')].slug").isNotEmpty());

        mockMvc.perform(delete("/api/v1/networks/" + networkId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());
    }

    @Test
    void projectsPeerPathsAndVirtualNetworksForAdminDetails() throws Exception {
        MeshNetworkView network = meshNetworkService.create(
                new MeshNetworkService.NetworkRequest("M7 UI " + UUID.randomUUID(), null));
        AgentEnrollmentResponse first = enrollOnDefault("M7-UI-A", "c");
        AgentEnrollmentResponse second = enrollOnDefault("M7-UI-B", "d");
        deviceService.approve(first.deviceId());
        deviceService.approve(second.deviceId());
        var firstMember = meshNetworkService.addMember(network.id(),
                new MeshNetworkService.MemberRequest(first.deviceId(), null));
        meshNetworkService.addMember(network.id(),
                new MeshNetworkService.MemberRequest(second.deviceId(), null));

        peerStatusRepository.upsert(new PeerStatusRecord(
                first.deviceId(), second.deviceId(), PeerStatus.ONLINE, "DIRECT",
                2.5, null, "192.0.2.10:1234", Instant.now(), null));

        MeshNetworkView view = meshNetworkService.get(network.id());
        assertEquals(2, view.peerPaths().size(), "the projection keeps both source directions visible");
        assertEquals("DIRECT", view.peerPaths().stream()
                .filter(path -> path.sourceDeviceId().equals(first.deviceId())
                        && path.peerDeviceId().equals(second.deviceId()))
                .findFirst().orElseThrow().pathType());
        assertEquals(firstMember.virtualIpv4(), deviceService.get(first.deviceId())
                .virtualNetworks().stream().filter(item -> item.networkId().equals(network.id()))
                .findFirst().orElseThrow().virtualIpv4());

        mockMvc.perform(get("/api/v1/devices/" + first.deviceId() + "/virtual-networks")
                        .header("Authorization", "Bearer " + adminToken(mockMvc)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].networkId").value(network.id().toString()))
                .andExpect(jsonPath("$[0].virtualIpv4").value(firstMember.virtualIpv4()));

        // The test profile keeps the in-memory database alive across Spring
        // contexts; do not leak this projection row into the M1-M6 tests.
        peerStatusRepository.deleteBySourceDeviceId(first.deviceId());
    }

    private String adminToken(MockMvc mvc) throws Exception {
        JsonNode login = objectMapper.readTree(mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"test-password\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        return login.path("accessToken").asText();
    }

    private AgentEnrollmentResponse enrollOnDefault(String hostname, String keyPrefix) {
        UUID defaultNetworkId = networkRepository.findBySlug("default").orElseThrow().id();
        EnrollmentService.EnrollmentTokenCreated token = enrollmentService.createToken(
                new EnrollmentService.CreateEnrollmentTokenRequest(defaultNetworkId, 1, 1));
        return enrollmentService.enroll(new AgentEnrollmentRequest(
                token.token(), hostname, "windows", "amd64", "0.1.0", "0.3.0",
                "nodekey:" + keyPrefix.repeat(64)));
    }
}
