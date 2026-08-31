package com.tailcatmesh.server.device;

import com.tailcatmesh.server.common.ControlPlaneException;
import com.tailcatmesh.server.agentws.DesiredStateChangedEvent;
import com.tailcatmesh.protocol.agent.AgentHeartbeatRequest;
import com.tailcatmesh.protocol.agent.AgentHeartbeatResponse;
import com.tailcatmesh.protocol.agent.AgentRuntimeServerRequest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/** Coordinates device approval, heartbeat state, and runtime reporting. */
@Service
public final class DeviceService {

    private static final Pattern CONN_BLOB = Pattern.compile("tc[A-Za-z0-9_-]+");

    private final DeviceRepository deviceRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final long heartbeatTimeoutSeconds;

    public DeviceService(
            DeviceRepository deviceRepository,
            ApplicationEventPublisher eventPublisher,
            @Value("${tailcat-mesh.agent.heartbeat-timeout-seconds:45}") long heartbeatTimeoutSeconds) {
        this.deviceRepository = deviceRepository;
        this.eventPublisher = eventPublisher;
        if (heartbeatTimeoutSeconds < 1) {
            throw new IllegalArgumentException("heartbeat timeout must be positive");
        }
        this.heartbeatTimeoutSeconds = heartbeatTimeoutSeconds;
    }

    public List<DeviceView> list() {
        markTimedOut();
        return deviceRepository.findAll().stream().map(DeviceView::from).toList();
    }

    public DeviceView get(UUID id) {
        markTimedOut();
        return DeviceView.from(find(id));
    }

    public DeviceView approve(UUID id) {
        DeviceRecord device = find(id);
        if (device.status() == DeviceStatus.DISABLED) {
            throw new ControlPlaneException("TM-CTRL-003", HttpStatus.CONFLICT, "device is disabled");
        }
        Instant now = Instant.now();
        deviceRepository.approve(id, now);
        deviceRepository.incrementDesiredRevisionForNetwork(device.networkId(), now);
        eventPublisher.publishEvent(new DesiredStateChangedEvent(device.networkId()));
        return get(id);
    }

    public DeviceView disable(UUID id) {
        DeviceRecord device = find(id);
        Instant now = Instant.now();
        deviceRepository.disable(id, now);
        deviceRepository.incrementDesiredRevisionForNetwork(device.networkId(), now);
        eventPublisher.publishEvent(new DesiredStateChangedEvent(device.networkId()));
        return get(id);
    }

    public AgentHeartbeatResponse heartbeat(UUID deviceId, AgentHeartbeatRequest request) {
        DeviceRecord device = find(deviceId);
        if (device.status() == DeviceStatus.DISABLED) {
            throw new ControlPlaneException("TM-CTRL-003", HttpStatus.FORBIDDEN, "device is disabled");
        }
        if (request == null || request.desiredRevision() < 0) {
            throw new ControlPlaneException("TM-CTRL-004", HttpStatus.BAD_REQUEST,
                    "heartbeat desiredRevision is invalid");
        }
        Instant now = Instant.now();
        DeviceStatus nextStatus = device.status() == DeviceStatus.PENDING
                ? DeviceStatus.PENDING
                : DeviceStatus.ONLINE;
        deviceRepository.recordHeartbeat(deviceId, nextStatus, now);
        return new AgentHeartbeatResponse(deviceId, nextStatus.name(), device.desiredRevision(), true);
    }

    public void runtimeServer(UUID deviceId, AgentRuntimeServerRequest request) {
        DeviceRecord device = find(deviceId);
        if (device.status() == DeviceStatus.DISABLED) {
            throw new ControlPlaneException("TM-CTRL-003", HttpStatus.FORBIDDEN, "device is disabled");
        }
        if (request == null) {
            throw new ControlPlaneException("TM-CTRL-004", HttpStatus.BAD_REQUEST,
                    "runtime server payload is required");
        }
        if (request.running() && (request.connBlob() == null || !CONN_BLOB.matcher(request.connBlob()).matches())) {
            throw new ControlPlaneException("TM-CTRL-004", HttpStatus.BAD_REQUEST,
                    "runtime server ConnBlob is invalid");
        }
        String hash = request.connBlob() == null ? null : sha256Hex(request.connBlob());
        deviceRepository.recordRuntime(deviceId, request.running(), request.connBlob(), hash, Instant.now());
    }

    public DeviceRecord find(UUID id) {
        return deviceRepository.findById(id).orElseThrow(() ->
                new ControlPlaneException("TM-CTRL-003", HttpStatus.NOT_FOUND, "device not found"));
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void markTimedOut() {
        Instant now = Instant.now();
        deviceRepository.markTimedOut(now.minusSeconds(heartbeatTimeoutSeconds), now);
    }
}
