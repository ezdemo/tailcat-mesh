package com.tailcatmesh.server.mesh;

import com.tailcatmesh.protocol.agent.AgentVirtualNetworkRuntime;
import com.tailcatmesh.protocol.agent.AgentVirtualNetworkRuntimeReport;
import com.tailcatmesh.server.agentws.DesiredStateChangedEvent;
import com.tailcatmesh.server.common.ControlPlaneException;
import com.tailcatmesh.server.device.DeviceRecord;
import com.tailcatmesh.server.device.DeviceRepository;
import com.tailcatmesh.server.device.DeviceStatus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Validates and stores one Agent's per-network Tailcat runtime snapshot. */
@Service
public final class VirtualNetworkRuntimeService {

    private static final Pattern CONN_BLOB = Pattern.compile("tc[A-Za-z0-9_-]{1,4095}");
    private static final int MAX_ERROR_LENGTH = 2_000;
    private static final int MAX_CODE_LENGTH = 64;

    private final VirtualNetworkRuntimeRepository runtimeRepository;
    private final MeshNetworkMemberRepository memberRepository;
    private final MeshNetworkRepository networkRepository;
    private final DeviceRepository deviceRepository;
    private final ApplicationEventPublisher eventPublisher;

    public VirtualNetworkRuntimeService(VirtualNetworkRuntimeRepository runtimeRepository,
                                        MeshNetworkMemberRepository memberRepository,
                                        MeshNetworkRepository networkRepository,
                                        DeviceRepository deviceRepository,
                                        ApplicationEventPublisher eventPublisher) {
        this.runtimeRepository = runtimeRepository;
        this.memberRepository = memberRepository;
        this.networkRepository = networkRepository;
        this.deviceRepository = deviceRepository;
        this.eventPublisher = eventPublisher;
    }

    public void recordRuntime(UUID deviceId, AgentVirtualNetworkRuntimeReport report) {
        if (report == null) {
            throw badRequest("virtual network runtime report is required");
        }
        DeviceRecord device = deviceRepository.findById(deviceId).orElseThrow(() ->
                new ControlPlaneException("TM-CTRL-003", HttpStatus.NOT_FOUND, "device not found"));
        if (device.status() == DeviceStatus.DISABLED) {
            throw new ControlPlaneException("TM-CTRL-003", HttpStatus.FORBIDDEN, "device is disabled");
        }

        Set<UUID> seenNetworks = new HashSet<>();
        Set<UUID> changedNetworks = new HashSet<>();
        for (AgentVirtualNetworkRuntime runtime : report.virtualNetworks()) {
            if (runtime == null || runtime.networkId() == null
                    || !seenNetworks.add(runtime.networkId())) {
                throw badRequest("virtual network runtime entries must be non-null and unique");
            }
            if (networkRepository.findById(runtime.networkId()).isEmpty()) {
                // A report can race with network deletion; desired state is authoritative.
                continue;
            }
            MeshNetworkMemberRecord member = memberRepository
                    .findByNetworkAndDevice(runtime.networkId(), deviceId).orElse(null);
            if (member == null || !member.enabled()) {
                // A report can race with membership removal. The runtime row is
                // removed with the membership and must not be recreated here.
                continue;
            }
            RuntimeValues values = normalize(runtime);
            VirtualNetworkRuntimeRecord previous = runtimeRepository
                    .findByNetworkAndDevice(runtime.networkId(), deviceId).orElse(null);
            VirtualNetworkRuntimeRecord next = new VirtualNetworkRuntimeRecord(
                    runtime.networkId(), deviceId, values.connBlob(),
                    values.connBlob() == null ? null : hashConnBlob(values.connBlob()),
                    values.status(), values.errorCode(), values.lastError(), report.timestamp());
            runtimeRepository.upsert(next);
            if (!sameState(previous, next)) {
                changedNetworks.add(runtime.networkId());
            }
        }

        for (UUID networkId : changedNetworks) {
            notifyMembers(networkId);
        }
    }

    private void notifyMembers(UUID networkId) {
        for (MeshNetworkMemberRecord member : memberRepository.findByNetworkId(networkId)) {
            if (!member.enabled()) {
                continue;
            }
            deviceRepository.incrementDesiredRevision(member.deviceId(), Instant.now());
            eventPublisher.publishEvent(new DesiredStateChangedEvent(networkId, member.deviceId()));
        }
    }

    private static RuntimeValues normalize(AgentVirtualNetworkRuntime runtime) {
        String status = runtime.status() == null ? "" : runtime.status().trim().toUpperCase();
        if (!Set.of("STARTING", "READY", "ERROR", "STOPPED").contains(status)) {
            throw badRequest("virtual network runtime status is invalid");
        }
        String connBlob = blankToNull(runtime.connBlob());
        if (("STARTING".equals(status) || "READY".equals(status))
                && (connBlob == null || !CONN_BLOB.matcher(connBlob).matches())) {
            throw badRequest("connBlob is required for an active virtual network runtime");
        }
        if (connBlob != null && !CONN_BLOB.matcher(connBlob).matches()) {
            throw badRequest("connBlob is invalid");
        }
        if ("STOPPED".equals(status)) {
            connBlob = null;
        }
        String errorCode = bounded(runtime.errorCode(), MAX_CODE_LENGTH, "errorCode");
        String lastError = bounded(runtime.lastError(), MAX_ERROR_LENGTH, "lastError");
        return new RuntimeValues(status, connBlob, errorCode, lastError);
    }

    private static boolean sameState(VirtualNetworkRuntimeRecord previous,
                                     VirtualNetworkRuntimeRecord next) {
        return previous != null
                && java.util.Objects.equals(previous.connBlob(), next.connBlob())
                && java.util.Objects.equals(previous.connBlobHash(), next.connBlobHash())
                && java.util.Objects.equals(previous.status(), next.status())
                && java.util.Objects.equals(previous.errorCode(), next.errorCode())
                && java.util.Objects.equals(previous.lastError(), next.lastError());
    }

    private static String bounded(String value, int maxLength, String field) {
        String normalized = blankToNull(value);
        if (normalized != null && normalized.length() > maxLength) {
            throw badRequest(field + " is too long");
        }
        return normalized;
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw badRequest("runtime text must not contain newlines");
        }
        return value.trim();
    }

    private static String hashConnBlob(String connBlob) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(connBlob.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder("sha256:");
            for (byte value : digest) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static ControlPlaneException badRequest(String message) {
        return new ControlPlaneException("TM-CTRL-004", HttpStatus.BAD_REQUEST, message);
    }

    private record RuntimeValues(String status, String connBlob, String errorCode, String lastError) {
    }
}
