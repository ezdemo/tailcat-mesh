package com.tailcatmesh.server.agentws;

import com.tailcatmesh.protocol.agent.AgentDesiredState;
import com.tailcatmesh.protocol.agent.AgentPeer;
import com.tailcatmesh.protocol.agent.AgentService;
import com.tailcatmesh.protocol.agent.AgentVirtualNetwork;
import com.tailcatmesh.protocol.agent.AgentVirtualNetworkPeer;
import com.tailcatmesh.server.common.ControlPlaneException;
import com.tailcatmesh.server.device.DeviceRecord;
import com.tailcatmesh.server.device.DeviceRepository;
import com.tailcatmesh.server.device.DeviceStatus;
import com.tailcatmesh.server.forward.ForwardService;
import com.tailcatmesh.server.mesh.MeshAllowlistCalculator;
import com.tailcatmesh.server.mesh.MeshNetworkMemberRecord;
import com.tailcatmesh.server.mesh.MeshNetworkMemberRepository;
import com.tailcatmesh.server.mesh.MeshNetworkRecord;
import com.tailcatmesh.server.mesh.MeshNetworkRepository;
import com.tailcatmesh.server.mesh.VirtualNetworkRuntimeRecord;
import com.tailcatmesh.server.mesh.VirtualNetworkRuntimeRepository;
import com.tailcatmesh.server.service.ServiceRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Builds the complete M3 desired-state projection for one Agent. */
@Service
public final class AgentDesiredStateService {

    private final DeviceRepository deviceRepository;
    private final MeshAllowlistCalculator allowlistCalculator;
    private final ServiceRepository serviceRepository;
    private final ForwardService forwardService;
    private final MeshNetworkRepository meshNetworkRepository;
    private final MeshNetworkMemberRepository meshNetworkMemberRepository;
    private final VirtualNetworkRuntimeRepository virtualNetworkRuntimeRepository;

    public AgentDesiredStateService(DeviceRepository deviceRepository,
                                    MeshAllowlistCalculator allowlistCalculator,
                                    ServiceRepository serviceRepository,
                                    ForwardService forwardService,
                                    MeshNetworkRepository meshNetworkRepository,
                                    MeshNetworkMemberRepository meshNetworkMemberRepository,
                                    VirtualNetworkRuntimeRepository virtualNetworkRuntimeRepository) {
        this.deviceRepository = deviceRepository;
        this.allowlistCalculator = allowlistCalculator;
        this.serviceRepository = serviceRepository;
        this.forwardService = forwardService;
        this.meshNetworkRepository = meshNetworkRepository;
        this.meshNetworkMemberRepository = meshNetworkMemberRepository;
        this.virtualNetworkRuntimeRepository = virtualNetworkRuntimeRepository;
    }

    public AgentDesiredState get(UUID deviceId) {
        DeviceRecord device = deviceRepository.findById(deviceId).orElseThrow(() ->
                new ControlPlaneException("TM-CTRL-003", HttpStatus.NOT_FOUND, "device not found"));
        List<DeviceRecord> networkDevices = deviceRepository.findByNetworkId(device.networkId());
        return new AgentDesiredState(
                device.id(),
                device.desiredRevision(),
                allowlistCalculator.allowedClientPublicKeys(device, networkDevices),
                serviceRepository.findByDeviceId(device.id()).stream()
                        .map(service -> new AgentService(
                                service.id(), service.name(), service.protocol(), service.targetHost(),
                                service.targetPort(), service.enabled()))
                        .toList(),
                networkDevices.stream()
                        .filter(peer -> !device.id().equals(peer.id()))
                        .filter(peer -> peer.status() == DeviceStatus.ONLINE || peer.status() == DeviceStatus.OFFLINE)
                        .map(peer -> new AgentPeer(peer.id(), peer.name(), peer.serverConnBlob()))
                        .toList(),
                forwardService.desiredState(device.id()),
                Map.of(),
                Map.of(),
                virtualNetworksFor(device)
        );
    }

    /** Builds only memberships owned by this Agent; each peer list is network-scoped. */
    private List<AgentVirtualNetwork> virtualNetworksFor(DeviceRecord device) {
        return meshNetworkMemberRepository.findByDeviceId(device.id()).stream()
                .filter(MeshNetworkMemberRecord::enabled)
                .map(member -> meshNetworkRepository.findById(member.networkId())
                        .map(network -> virtualNetworkFor(device, member, network))
                        .orElse(null))
                .filter(java.util.Objects::nonNull)
                .filter(AgentVirtualNetwork::enabled)
                .toList();
    }

    private AgentVirtualNetwork virtualNetworkFor(DeviceRecord device,
                                                  MeshNetworkMemberRecord localMember,
                                                  MeshNetworkRecord network) {
        Map<UUID, VirtualNetworkRuntimeRecord> runtimes =
                virtualNetworkRuntimeRepository.findByNetworkId(network.id()).stream()
                        .collect(java.util.stream.Collectors.toMap(
                                VirtualNetworkRuntimeRecord::deviceId,
                                runtime -> runtime,
                                (left, right) -> right));
        List<AgentVirtualNetworkPeer> peers = meshNetworkMemberRepository.findByNetworkId(network.id()).stream()
                .filter(MeshNetworkMemberRecord::enabled)
                .filter(member -> !member.deviceId().equals(device.id()))
                .map(member -> peerFor(member, runtimes.get(member.deviceId())))
                .filter(java.util.Objects::nonNull)
                .toList();
        return new AgentVirtualNetwork(
                network.id(), network.name(), network.cidr(), localMember.virtualIpv4(),
                network.enabled(), peers);
    }

    private AgentVirtualNetworkPeer peerFor(MeshNetworkMemberRecord member,
                                            VirtualNetworkRuntimeRecord runtime) {
        DeviceRecord peer = deviceRepository.findById(member.deviceId()).orElse(null);
        if (peer == null || (peer.status() != DeviceStatus.ONLINE && peer.status() != DeviceStatus.OFFLINE)) {
            return null;
        }
        String connBlob = runtime != null && "READY".equals(runtime.status())
                ? runtime.connBlob() : null;
        return new AgentVirtualNetworkPeer(
                peer.id(), peer.name(), member.virtualIpv4(), connBlob, peer.clientPublicKey());
    }

    /** Returns all registered devices so existing WebSocket sessions can refresh. */
    public List<UUID> deviceIdsInNetwork(UUID networkId) {
        return deviceRepository.findByNetworkId(networkId).stream()
                .map(DeviceRecord::id)
                .toList();
    }
}
