package com.tailcatmesh.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** REST-level M2 acceptance test for login, enrollment, approval and heartbeat. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ControlPlaneRegistrationTest {

    private static final String CLIENT_PUBLIC_KEY = "nodekey:" + "a".repeat(64);

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void agentCanEnrollThenBecomeOnlineAfterAdminApproval() throws Exception {
        JsonNode login = objectMapper.readTree(mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"test-password\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        String adminToken = login.path("accessToken").asText();

        JsonNode createdToken = objectMapper.readTree(mockMvc.perform(post("/api/v1/enrollment-tokens")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxUses\":1,\"expiresInHours\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andReturn().getResponse().getContentAsString());
        String enrollmentToken = createdToken.path("token").asText();

        JsonNode enrolled = objectMapper.readTree(mockMvc.perform(post("/api/v1/agent/enroll")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AgentEnrollmentPayload(
                                enrollmentToken, "TEST-DESKTOP", "windows", "amd64",
                                "0.1.0", "0.3.0", CLIENT_PUBLIC_KEY))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.agentCredential").isNotEmpty())
                .andReturn().getResponse().getContentAsString());
        String deviceId = enrolled.path("deviceId").asText();
        String agentCredential = enrolled.path("agentCredential").asText();

        mockMvc.perform(post("/api/v1/devices/" + deviceId + "/approve")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OFFLINE"));

        mockMvc.perform(post("/api/v1/agent/heartbeat")
                        .header("Authorization", "Bearer " + agentCredential)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentVersion\":\"0.1.0\",\"tailcatVersion\":\"0.3.0\","
                                + "\"desiredRevision\":0,\"tailcatServerRunning\":true,"
                                + "\"serverConnBlobHash\":\"sha256:test\",\"servicesUp\":0,"
                                + "\"forwardsUp\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ONLINE"))
                .andExpect(jsonPath("$.accepted").value(true));

        mockMvc.perform(get("/api/v1/devices")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + deviceId + "')].status").value("ONLINE"));

        mockMvc.perform(get("/api/v1/connections")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        mockMvc.perform(get("/api/v1/forwards")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        mockMvc.perform(get("/api/v1/agent/desired-state")
                        .header("Authorization", "Bearer " + agentCredential))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceId").value(deviceId))
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.allowedClientPublicKeys").isArray());

        JsonNode service = objectMapper.readTree(mockMvc.perform(post("/api/v1/services")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"" + deviceId
                                + "\",\"name\":\"test-http\",\"protocol\":\"TCP\","
                                + "\"targetHost\":\"127.0.0.1\",\"targetPort\":8080,\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("STOPPED"))
                .andExpect(jsonPath("$.bridgePort").value(org.hamcrest.Matchers.nullValue()))
                .andReturn().getResponse().getContentAsString());
        String serviceId = service.path("id").asText();

        mockMvc.perform(put("/api/v1/services/" + serviceId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetPort\":8081}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetPort").value(8081))
                .andExpect(jsonPath("$.status").value("STOPPED"));
        mockMvc.perform(get("/api/v1/services/" + serviceId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("test-http"));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                        "/api/v1/services/" + serviceId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/v1/enrollment-tokens/" + createdToken.path("id").asText())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());
    }

    @Test
    void adminAndAgentRoutesRejectMissingCredentials() throws Exception {
        mockMvc.perform(get("/api/v1/devices"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/connections"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/forwards"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/agent/heartbeat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    private record AgentEnrollmentPayload(
            String enrollmentToken,
            String hostname,
            String os,
            String arch,
            String agentVersion,
            String tailcatVersion,
            String clientPublicKey
    ) {
    }
}
