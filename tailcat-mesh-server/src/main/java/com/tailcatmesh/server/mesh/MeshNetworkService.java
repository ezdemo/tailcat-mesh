package com.tailcatmesh.server.mesh;

import com.tailcatmesh.server.agentws.DesiredStateChangedEvent;
import com.tailcatmesh.server.common.ControlPlaneException;
import com.tailcatmesh.server.device.DeviceRecord;
import com.tailcatmesh.server.device.DeviceRepository;
import com.tailcatmesh.server.device.DeviceStatus;
import com.tailcatmesh.server.peer.PeerStatus;
import com.tailcatmesh.server.peer.PeerStatusRecord;
import com.tailcatmesh.server.peer.PeerStatusRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * M7.1 control-plane lifecycle for virtual networks and stable membership IPs.
 *
 * <p>This service intentionally stops at persisted desired state. The
 * network-specific Tailcat runtimes and TUN path are added by M7.2-M7.5.</p>
 */
@Service
public class MeshNetworkService {

    private static final int MAX_NAME_LENGTH = 128;
    private final MeshNetworkRepository networkRepository;
    private final MeshNetworkMemberRepository memberRepository;
    private final DeviceRepository deviceRepository;
    private final VirtualIpam virtualIpam;
    private final ApplicationEventPublisher eventPublisher;
    private final VirtualNetworkRuntimeRepository virtualNetworkRuntimeRepository;
    private final PeerStatusRepository peerStatusRepository;

    public MeshNetworkService(
            MeshNetworkRepository networkRepository,
            MeshNetworkMemberRepository memberRepository,
            DeviceRepository deviceRepository,
            VirtualIpam virtualIpam,
            ApplicationEventPublisher eventPublisher,
            VirtualNetworkRuntimeRepository virtualNetworkRuntimeRepository,
            PeerStatusRepository peerStatusRepository) {
        this.networkRepository = networkRepository;
        this.memberRepository = memberRepository;
        this.deviceRepository = deviceRepository;
        this.virtualIpam = virtualIpam;
        this.eventPublisher = eventPublisher;
        this.virtualNetworkRuntimeRepository = virtualNetworkRuntimeRepository;
        this.peerStatusRepository = peerStatusRepository;
    }

    @Transactional(readOnly = true)
    public List<MeshNetworkView> list() {
        return networkRepository.findAll().stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public MeshNetworkView get(UUID id) {
        return view(findNetwork(id));
    }

    @Transactional
    public MeshNetworkView create(NetworkRequest request) {
        String name = requiredName(request == null ? null : request.name());
        List<String> existingCidrs = networkRepository.findAllCidrs();
        String cidr;
        try {
            if (request == null || request.cidr() == null || request.cidr().isBlank()) {
                cidr = virtualIpam.allocateDefaultCidr(existingCidrs);
            } else {
                cidr = virtualIpam.canonicalizeNetworkCidr(request.cidr());
                virtualIpam.ensureNoOverlap(cidr, existingCidrs);
            }
        } catch (IllegalArgumentException exception) {
            throw badRequest(exception.getMessage());
        }

        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        MeshNetworkRecord network = new MeshNetworkRecord(
                id, name, uniqueSlug(name, id), cidr, true, now, now);
        try {
            networkRepository.insert(network);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("mesh network name or slug is already in use");
        }
        return view(networkRepository.findById(id).orElse(network));
    }

    @Transactional
    public MeshNetworkView update(UUID id, NetworkUpdateRequest request) {
        MeshNetworkRecord existing = findNetwork(id);
        if (request == null) {
            throw badRequest("network update request is required");
        }

        String name = request.name() == null ? existing.name() : requiredName(request.name());
        String cidr = existing.cidr();
        boolean cidrChanged = false;
        if (request.cidr() != null) {
            try {
                cidr = virtualIpam.canonicalizeNetworkCidr(request.cidr());
            } catch (IllegalArgumentException exception) {
                throw badRequest(exception.getMessage());
            }
            cidrChanged = !cidr.equals(existing.cidr());
            if (cidrChanged) {
                List<MeshNetworkMemberRecord> members = memberRepository.findByNetworkId(id);
                if (!members.isEmpty()) {
                    throw conflict("network CIDR cannot change while members exist");
                }
                try {
                    virtualIpam.ensureNoOverlap(cidr, networkRepository.findAll().stream()
                            .filter(network -> !network.id().equals(id))
                            .map(MeshNetworkRecord::cidr)
                            .toList());
                } catch (IllegalArgumentException exception) {
                    throw badRequest(exception.getMessage());
                }
            }
        }
        boolean enabled = request.enabled() == null ? existing.enabled() : request.enabled();
        boolean enabledChanged = enabled != existing.enabled();
        if (name.equals(existing.name()) && !cidrChanged && !enabledChanged) {
            return view(existing);
        }

        Instant now = Instant.now();
        MeshNetworkRecord updated = new MeshNetworkRecord(
                existing.id(), name, existing.slug(), cidr, enabled, existing.createdAt(), now);
        networkRepository.update(updated);
        if (enabledChanged) {
            if (!enabled) {
                virtualNetworkRuntimeRepository.deleteByNetworkId(id);
            }
            touchMembers(id, List.of());
        }
        return view(updated);
    }

    @Transactional
    public void delete(UUID id) {
        MeshNetworkRecord network = findNetwork(id);
        List<MeshNetworkMemberRecord> members = memberRepository.findByNetworkId(id);
        List<UUID> affectedDevices = members.stream()
                .filter(MeshNetworkMemberRecord::enabled)
                .map(MeshNetworkMemberRecord::deviceId)
                .toList();
        // Revoke all per-device Virtual Network runtimes before removing the
        // membership rows. The FK cascade remains a database safety net, but
        // revocation is explicit at the control-plane boundary.
        virtualNetworkRuntimeRepository.deleteByNetworkId(id);
        memberRepository.deleteByNetworkId(id);
        try {
            if (!networkRepository.delete(id)) {
                throw notFound("mesh network not found");
            }
        } catch (DataIntegrityViolationException exception) {
            throw conflict("network is still used by control-plane devices or enrollment tokens");
        }
        touchDevices(id, affectedDevices);
    }

    @Transactional
    public MeshNetworkMemberView addMember(UUID networkId, MemberRequest request) {
        MeshNetworkRecord network = findNetwork(networkId);
        if (request == null || request.deviceId() == null) {
            throw badRequest("deviceId is required");
        }
        DeviceRecord device = deviceRepository.findById(request.deviceId()).orElseThrow(() ->
                notFound("device not found"));
        if (!isApproved(device.status())) {
            throw conflict("only approved, non-disabled devices can join a network");
        }

        MeshNetworkMemberRecord existing = memberRepository
                .findByNetworkAndDevice(networkId, request.deviceId()).orElse(null);
        if (existing != null) {
            if (request.virtualIpv4() != null && !request.virtualIpv4().isBlank()
                    && !existing.virtualIpv4().equals(request.virtualIpv4().trim())) {
                throw conflict("existing member virtual IPv4 is stable and cannot be changed");
            }
            if (!existing.enabled()) {
                memberRepository.setEnabled(networkId, request.deviceId(), true);
                touchMembers(networkId, List.of(request.deviceId()));
                existing = new MeshNetworkMemberRecord(existing.id(), existing.networkId(),
                        existing.deviceId(), existing.virtualIpv4(), existing.joinedAt(), true);
            }
            return viewMember(existing);
        }

        String virtualIpv4;
        try {
            virtualIpv4 = virtualIpam.allocateMemberIp(network.cidr(),
                    memberRepository.findAllVirtualIps(networkId), request.virtualIpv4());
        } catch (IllegalArgumentException exception) {
            throw badRequest(exception.getMessage());
        }
        MeshNetworkMemberRecord member = new MeshNetworkMemberRecord(
                UUID.randomUUID(), networkId, request.deviceId(), virtualIpv4, Instant.now(), true);
        try {
            memberRepository.insert(member);
        } catch (DataIntegrityViolationException exception) {
            // A concurrent idempotent add may have won the unique race.
            MeshNetworkMemberRecord concurrent = memberRepository
                    .findByNetworkAndDevice(networkId, request.deviceId()).orElse(null);
            if (concurrent != null && concurrent.enabled()
                    && (request.virtualIpv4() == null || request.virtualIpv4().isBlank()
                    || concurrent.virtualIpv4().equals(request.virtualIpv4().trim()))) {
                return viewMember(concurrent);
            }
            throw conflict("network member or virtual IPv4 is already in use");
        }
        touchMembers(networkId, List.of(request.deviceId()));
        return viewMember(member);
    }

    @Transactional
    public void removeMember(UUID networkId, UUID deviceId) {
        findNetwork(networkId);
        MeshNetworkMemberRecord member = memberRepository
                .findByNetworkAndDevice(networkId, deviceId).orElse(null);
        if (member == null || !member.enabled()) {
            return;
        }
        memberRepository.setEnabled(networkId, deviceId, false);
        virtualNetworkRuntimeRepository.deleteByNetworkAndDevice(networkId, deviceId);
        touchMembers(networkId, List.of(deviceId));
    }

    private MeshNetworkRecord findNetwork(UUID id) {
        if (id == null) {
            throw badRequest("network id is required");
        }
        return networkRepository.findById(id).orElseThrow(() ->
                notFound("mesh network not found"));
    }

    private MeshNetworkView view(MeshNetworkRecord network) {
        List<MeshNetworkMemberView> members = memberRepository.findByNetworkId(network.id()).stream()
                .map(this::viewMember).toList();
        return new MeshNetworkView(
                network.id(), network.name(), network.slug(), network.cidr(), network.enabled(),
                network.createdAt(), network.updatedAt(),
                members,
                peerPaths(members)
        );
    }

    private List<MeshNetworkPeerView> peerPaths(List<MeshNetworkMemberView> members) {
        Set<UUID> memberIds = members.stream()
                .filter(MeshNetworkMemberView::enabled)
                .map(MeshNetworkMemberView::deviceId)
                .collect(java.util.stream.Collectors.toSet());
        if (memberIds.size() < 2) {
            return List.of();
        }
        java.util.Map<java.util.UUID, String> names = members.stream()
                .collect(java.util.stream.Collectors.toMap(
                        MeshNetworkMemberView::deviceId, MeshNetworkMemberView::deviceName,
                        (left, right) -> left));
        java.util.Map<java.util.UUID, java.util.Map<java.util.UUID, PeerStatusRecord>> statuses =
                peerStatusRepository.findAll().stream()
                        .filter(status -> memberIds.contains(status.sourceDeviceId())
                                && memberIds.contains(status.peerDeviceId()))
                        .collect(java.util.stream.Collectors.groupingBy(
                                PeerStatusRecord::sourceDeviceId,
                                java.util.stream.Collectors.toMap(
                                        PeerStatusRecord::peerDeviceId, status -> status,
                                        (left, right) -> left)));
        List<MeshNetworkPeerView> result = new ArrayList<>();
        for (UUID sourceId : memberIds.stream().sorted().toList()) {
            for (UUID peerId : memberIds.stream().filter(id -> !id.equals(sourceId)).sorted().toList()) {
                PeerStatusRecord status = statuses.getOrDefault(sourceId, java.util.Map.of()).get(peerId);
                result.add(new MeshNetworkPeerView(
                        sourceId, names.getOrDefault(sourceId, "unknown"), peerId,
                        names.getOrDefault(peerId, "unknown"),
                        status == null ? PeerStatus.UNKNOWN : status.status(),
                        status == null ? "UNKNOWN" : status.pathType(),
                        status == null ? null : status.latencyMs(),
                        status == null ? null : status.derpRegion(),
                        status == null ? null : status.directEndpoint(),
                        status == null ? null : status.lastCheckAt(),
                        status == null ? null : status.lastError()));
            }
        }
        return List.copyOf(result);
    }

    private MeshNetworkMemberView viewMember(MeshNetworkMemberRecord member) {
        DeviceRecord device = deviceRepository.findById(member.deviceId()).orElse(null);
        return new MeshNetworkMemberView(
                member.id(), member.networkId(), member.deviceId(),
                device == null ? "Unknown device" : device.name(),
                device == null ? "" : device.hostname(),
                device == null ? DeviceStatus.DISABLED : device.status(),
                member.virtualIpv4(), member.joinedAt(), member.enabled()
        );
    }

    private void touchMembers(UUID networkId, Collection<UUID> extraDeviceIds) {
        Set<UUID> affected = new HashSet<>(extraDeviceIds);
        memberRepository.findByNetworkId(networkId).stream()
                .filter(MeshNetworkMemberRecord::enabled)
                .map(MeshNetworkMemberRecord::deviceId)
                .forEach(affected::add);
        touchDevices(networkId, affected);
    }

    private void touchDevices(UUID networkId, Collection<UUID> deviceIds) {
        Instant now = Instant.now();
        for (UUID deviceId : new HashSet<>(deviceIds)) {
            deviceRepository.incrementDesiredRevision(deviceId, now);
            // Use a per-device event: M7 network IDs are not the legacy
            // enrollment network IDs consumed by the M3 WebSocket fan-out.
            eventPublisher.publishEvent(new DesiredStateChangedEvent(networkId, deviceId));
        }
    }

    private String uniqueSlug(String name, UUID id) {
        String base = name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (base.isBlank()) {
            base = "network-" + id.toString().substring(0, 8);
        }
        base = base.substring(0, Math.min(base.length(), 100));
        String candidate = base;
        int suffix = 2;
        while (networkRepository.findBySlug(candidate).isPresent()) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }

    private static String requiredName(String value) {
        if (value == null || value.isBlank() || value.trim().length() > MAX_NAME_LENGTH) {
            throw badRequest("name is required and must be at most " + MAX_NAME_LENGTH + " characters");
        }
        return value.trim();
    }

    private static boolean isApproved(DeviceStatus status) {
        return status == DeviceStatus.ONLINE || status == DeviceStatus.OFFLINE;
    }

    private static ControlPlaneException badRequest(String message) {
        return new ControlPlaneException("TM-CTRL-004", HttpStatus.BAD_REQUEST,
                message == null || message.isBlank() ? "network request is invalid" : message);
    }

    private static ControlPlaneException conflict(String message) {
        return new ControlPlaneException("TM-CTRL-409", HttpStatus.CONFLICT, message);
    }

    private static ControlPlaneException notFound(String message) {
        return new ControlPlaneException("TM-CTRL-003", HttpStatus.NOT_FOUND, message);
    }

    public record NetworkRequest(String name, String cidr) {
    }

    public record NetworkUpdateRequest(String name, String cidr, Boolean enabled) {
    }

    public record MemberRequest(UUID deviceId, String virtualIpv4) {
    }
}
