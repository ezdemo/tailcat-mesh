package com.tailcatmesh.server.peer;

import com.tailcatmesh.protocol.agent.AgentPeerRuntime;
import com.tailcatmesh.protocol.agent.AgentPeerRuntimeReport;
import com.tailcatmesh.server.common.ControlPlaneException;
import com.tailcatmesh.server.device.DeviceRecord;
import com.tailcatmesh.server.device.DeviceRepository;
import com.tailcatmesh.server.device.DeviceStatus;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Coordinates Agent peer-path reports and the admin Connections projection. */
@Service
public final class PeerService {

    private static final int MAX_ERROR_LENGTH = 2_000;
    private static final int MAX_ENDPOINT_LENGTH = 255;
    private static final int MAX_REGION_LENGTH = 128;

    private final PeerStatusRepository statusRepository;
    private final DeviceRepository deviceRepository;

    public PeerService(PeerStatusRepository statusRepository, DeviceRepository deviceRepository) {
        this.statusRepository = statusRepository;
        this.deviceRepository = deviceRepository;
    }

    public List<PeerStatusView> list() {
        Map<UUID, DeviceRecord> devices = deviceRepository.findAll().stream()
                .collect(Collectors.toMap(DeviceRecord::id, Function.identity()));
        return statusRepository.findAll().stream()
                .map(status -> new PeerStatusView(
                        status.sourceDeviceId(),
                        deviceName(devices.get(status.sourceDeviceId())),
                        status.peerDeviceId(),
                        deviceName(devices.get(status.peerDeviceId())),
                        status.status(), status.pathType(), status.latencyMs(), status.derpRegion(),
                        status.directEndpoint(), status.lastCheckAt(), status.lastError()))
                .toList();
    }

    /** Stores a complete path snapshot from one authenticated Agent. */
    public void recordRuntime(UUID sourceDeviceId, AgentPeerRuntimeReport report) {
        if (report == null) {
            throw badRequest("peer runtime report is required");
        }
        DeviceRecord source = findDevice(sourceDeviceId);
        if (source.status() == DeviceStatus.DISABLED) {
            throw new ControlPlaneException("TM-CTRL-003", HttpStatus.FORBIDDEN, "device is disabled");
        }
        Instant timestamp = report.timestamp();
        if (timestamp == null) {
            timestamp = Instant.now();
        }
        List<PeerStatusRecord> statuses = new ArrayList<>();
        for (AgentPeerRuntime runtime : report.peers()) {
            if (runtime == null) {
                throw badRequest("peer runtime entry is required");
            }
            DeviceRecord peer = deviceRepository.findById(runtime.peerDeviceId()).orElse(null);
            if (peer == null) {
                // Desired State is authoritative; a report can race with a
                // device removal or network move.
                continue;
            }
            if (!source.networkId().equals(peer.networkId())) {
                throw new ControlPlaneException("TM-CTRL-003", HttpStatus.FORBIDDEN,
                        "peer does not belong to the source device network");
            }
            if (source.id().equals(peer.id())) {
                throw badRequest("source device cannot report itself as a peer");
            }
            PeerStatus status = parseStatus(runtime.status());
            String pathType = parsePathType(runtime.pathType());
            Double latency = runtime.latencyMs() < 0 ? null : runtime.latencyMs();
            String region = bounded(runtime.derpRegion(), MAX_REGION_LENGTH, "derpRegion");
            String endpoint = bounded(runtime.directEndpoint(), MAX_ENDPOINT_LENGTH, "directEndpoint");
            String error = bounded(runtime.lastError(), MAX_ERROR_LENGTH, "lastError");
            statuses.add(new PeerStatusRecord(
                    source.id(), peer.id(), status, pathType, latency, region, endpoint, timestamp, error));
        }
        statusRepository.deleteBySourceDeviceId(source.id());
        statuses.forEach(statusRepository::upsert);
    }

    private DeviceRecord findDevice(UUID id) {
        return deviceRepository.findById(id).orElseThrow(() ->
                new ControlPlaneException("TM-CTRL-003", HttpStatus.NOT_FOUND, "device not found"));
    }

    private static String deviceName(DeviceRecord device) {
        return device == null ? "unknown" : device.name();
    }

    private static PeerStatus parseStatus(String value) {
        try {
            return PeerStatus.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw badRequest("unsupported peer runtime status");
        }
    }

    private static String parsePathType(String value) {
        if (value == null) {
            throw badRequest("peer path type is required");
        }
        String normalized = value.toUpperCase(Locale.ROOT);
        if (!switch (normalized) {
            case "DIRECT", "DERP", "OFFLINE", "UNKNOWN" -> true;
            default -> false;
        }) {
            throw badRequest("unsupported peer path type");
        }
        return normalized;
    }

    private static String bounded(String value, int maxLength, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength || normalized.indexOf('\r') >= 0
                || normalized.indexOf('\n') >= 0) {
            throw badRequest(field + " is too long or invalid");
        }
        return normalized;
    }

    private static ControlPlaneException badRequest(String message) {
        return new ControlPlaneException("TM-CTRL-002", HttpStatus.BAD_REQUEST, message);
    }
}
