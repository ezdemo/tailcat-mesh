package com.tailcatmesh.agent.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tailcatmesh.agent.config.AgentConfig;
import com.tailcatmesh.protocol.agent.AgentEnrollmentRequest;
import com.tailcatmesh.protocol.agent.AgentEnrollmentResponse;
import com.tailcatmesh.protocol.agent.AgentDesiredState;
import com.tailcatmesh.protocol.agent.AgentHeartbeatRequest;
import com.tailcatmesh.protocol.agent.AgentHeartbeatResponse;
import com.tailcatmesh.protocol.agent.AgentRuntimeServerRequest;
import com.tailcatmesh.protocol.agent.AgentForwardRuntimeReport;
import com.tailcatmesh.protocol.agent.AgentPeerRuntimeReport;
import com.tailcatmesh.protocol.agent.AgentServiceRuntimeReport;
import com.tailcatmesh.protocol.agent.AgentVirtualNetworkRuntimeReport;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Java 21 HTTP/WebSocket client for the documented Agent control endpoints. */
public final class AgentControlClient {

    private final AgentConfig config;
    private final Duration requestTimeout;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public AgentControlClient(AgentConfig config, Duration requestTimeout) {
        this.config = Objects.requireNonNull(config, "config");
        this.requestTimeout = positive(requestTimeout);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(this.requestTimeout)
                .build();
    }

    public AgentEnrollmentResponse enroll(String enrollmentToken, String hostname, String os, String arch,
                                          String agentVersion, String tailcatVersion, String clientPublicKey) {
        return enroll(enrollmentToken, hostname, os, arch, agentVersion, tailcatVersion,
                clientPublicKey, null);
    }

    public AgentEnrollmentResponse enroll(String enrollmentToken, String hostname, String os, String arch,
                                          String agentVersion, String tailcatVersion, String clientPublicKey,
                                          String deviceName) {
        return sendJson("POST", "/api/v1/agent/enroll", new AgentEnrollmentRequest(
                enrollmentToken, hostname, os, arch, agentVersion, tailcatVersion, clientPublicKey, deviceName), null,
                AgentEnrollmentResponse.class);
    }

    public AgentHeartbeatResponse heartbeat(String credential, AgentHeartbeatRequest heartbeat) {
        return sendJson("POST", "/api/v1/agent/heartbeat", heartbeat, credential, AgentHeartbeatResponse.class);
    }

    public void reportRuntimeServer(String credential, AgentRuntimeServerRequest runtime) {
        sendJson("POST", "/api/v1/agent/runtime/server", runtime, credential, JsonNode.class);
    }

    public AgentDesiredState desiredState(String credential) {
        return sendJson("GET", "/api/v1/agent/desired-state", null, credential, AgentDesiredState.class);
    }

    public void reportRuntimeServices(String credential, AgentServiceRuntimeReport runtime) {
        sendJson("POST", "/api/v1/agent/runtime/services", runtime, credential, JsonNode.class);
    }

    public void reportRuntimePeers(String credential, AgentPeerRuntimeReport runtime) {
        sendJson("POST", "/api/v1/agent/runtime/peers", runtime, credential, JsonNode.class);
    }

    public void reportRuntimeForwards(String credential, AgentForwardRuntimeReport runtime) {
        sendJson("POST", "/api/v1/agent/runtime/forwards", runtime, credential, JsonNode.class);
    }

    public void reportRuntimeVirtualNetworks(String credential, AgentVirtualNetworkRuntimeReport runtime) {
        sendJson("POST", "/api/v1/agent/runtime/virtual-networks", runtime, credential, JsonNode.class);
    }

    public CompletableFuture<WebSocket> openWebSocket(String credential, WebSocket.Listener listener) {
        Objects.requireNonNull(listener, "listener");
        try {
            return httpClient.newWebSocketBuilder()
                    .connectTimeout(requestTimeout)
                    .header("Authorization", "Bearer " + requireCredential(credential))
                    .buildAsync(config.websocketEndpoint(), listener)
                    .exceptionally(exception -> {
                        Throwable cause = exception instanceof CompletionException && exception.getCause() != null
                                ? exception.getCause() : exception;
                        throw new CompletionException(new AgentControlException(
                                "TM-CTRL-001", 0, "control WebSocket connection failed", cause));
                    });
        } catch (IllegalArgumentException exception) {
            throw new AgentControlException("TM-AGENT-010", 0, "control WebSocket URL is invalid", exception);
        }
    }

    private <T> T sendJson(String method, String path, Object body, String credential, Class<T> responseType) {
        HttpRequest request;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(config.endpoint(path))
                    .timeout(requestTimeout)
                    .header("Accept", "application/json");
            if (credential != null && !credential.isBlank()) {
                builder.header("Authorization", "Bearer " + credential);
            }
            if ("GET".equals(method)) {
                request = builder.GET().build();
            } else {
                String json = body == null ? "{}" : objectMapper.writeValueAsString(body);
                request = builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(json))
                        .build();
            }
        } catch (IOException | IllegalArgumentException exception) {
            throw new AgentControlException("TM-CTRL-001", 0, "unable to build control-plane request", exception);
        }

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            throw new AgentControlException("TM-CTRL-001", 0, "control-plane request failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AgentControlException("TM-CTRL-001", 0,
                    "interrupted during control-plane request", exception);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw serverError(response);
        }
        if (responseType == JsonNode.class && response.body().isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(response.body(), responseType);
        } catch (IOException exception) {
            throw new AgentControlException("TM-CTRL-004", response.statusCode(),
                    "control-plane response is invalid", exception);
        }
    }

    private AgentControlException serverError(HttpResponse<String> response) {
        String code = response.statusCode() == 401 ? "TM-CTRL-001" : "TM-CTRL-004";
        String message = "control-plane request failed with HTTP " + response.statusCode();
        try {
            JsonNode error = objectMapper.readTree(response.body());
            if (error != null && error.path("code").isTextual()) {
                code = error.path("code").asText(code);
            }
            if (error != null && error.path("message").isTextual()
                    && !error.path("message").asText().isBlank()) {
                message = error.path("message").asText();
            }
        } catch (IOException ignored) {
            // Keep the generic status-only message.
        }
        return new AgentControlException(code, response.statusCode(), message);
    }

    private static String requireCredential(String credential) {
        if (credential == null || credential.isBlank()) {
            throw new AgentControlException("TM-CTRL-001", 401, "Agent credential is missing");
        }
        return credential;
    }

    private static Duration positive(Duration value) {
        Objects.requireNonNull(value, "requestTimeout");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        return value;
    }

}
