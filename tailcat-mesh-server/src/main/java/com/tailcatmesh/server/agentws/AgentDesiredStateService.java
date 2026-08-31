package com.tailcatmesh.server.agentws;

import com.tailcatmesh.protocol.agent.AgentDesiredState;
import com.tailcatmesh.protocol.agent.AgentPeer;
import com.tailcatmesh.protocol.agent.AgentService;
import com.tailcatmesh.server.common.ControlPlaneException;
import com.tailcatmesh.server.device.DeviceRecord;
import com.tailcatmesh.server.device.DeviceRepository;
import com.tailcatmesh.server.device.DeviceStatus;
import com.tailcatmesh.server.forward.ForwardService;
import com.tailcatmesh.server.mesh.MeshAllowlistCalculator;
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

    public AgentDesiredStateService(DeviceRepository deviceRepository,
                                    MeshAllowlistCalculator allowlistCalculator,
                                    ServiceRepository serviceRepository,
                                    ForwardService forwardService) {
        this.deviceRepository = deviceRepository;
        this.allowlistCalculator = allowlistCalculator;
        this.serviceRepository = serviceRepository;
        this.forwardService = forwardService;
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
                Map.of()
        );
    }

    /** Returns all registered devices so existing WebSocket sessions can refresh. */
    public List<UUID> deviceIdsInNetwork(UUID networkId) {
        return deviceRepository.findByNetworkId(networkId).stream()
                .map(DeviceRecord::id)
                .toList();
    }
}
