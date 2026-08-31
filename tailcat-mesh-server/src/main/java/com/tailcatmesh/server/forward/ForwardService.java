package com.tailcatmesh.server.forward;

import com.tailcatmesh.protocol.agent.AgentForward;
import com.tailcatmesh.protocol.agent.AgentForwardRuntime;
import com.tailcatmesh.protocol.agent.AgentForwardRuntimeReport;
import com.tailcatmesh.server.agentws.DesiredStateChangedEvent;
import com.tailcatmesh.server.common.ControlPlaneException;
import com.tailcatmesh.server.device.DeviceRecord;
import com.tailcatmesh.server.device.DeviceRepository;
import com.tailcatmesh.server.device.DeviceStatus;
import com.tailcatmesh.server.service.ServiceRecord;
import com.tailcatmesh.server.service.ServiceRepository;
import com.tailcatmesh.server.service.ServiceRuntimeRecord;
import com.tailcatmesh.server.service.ServiceRuntimeRepository;
import com.tailcatmesh.server.service.ServiceStatus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Coordinates Local Forward CRUD, desired-state projection and runtime state. */
@Service
public final class ForwardService {

    private static final String DEFAULT_BIND_HOST = "127.0.0.1";
    private static final int MAX_NAME_LENGTH = 255;
    private static final int MAX_HOST_LENGTH = 255;
    private static final int MAX_ERROR_LENGTH = 2_000;
    private static final int MAX_ERROR_CODE_LENGTH = 64;

    private final ForwardRepository forwardRepository;
    private final ForwardRuntimeRepository runtimeRepository;
    private final DeviceRepository deviceRepository;
    private final ServiceRepository serviceRepository;
    private final ServiceRuntimeRepository serviceRuntimeRepository;
    private final ApplicationEventPublisher eventPublisher;

    public ForwardService(ForwardRepository forwardRepository,
                          ForwardRuntimeRepository runtimeRepository,
                          DeviceRepository deviceRepository,
                          ServiceRepository serviceRepository,
                          ServiceRuntimeRepository serviceRuntimeRepository,
                          ApplicationEventPublisher eventPublisher) {
        this.forwardRepository = forwardRepository;
        this.runtimeRepository = runtimeRepository;
        this.deviceRepository = deviceRepository;
        this.serviceRepository = serviceRepository;
        this.serviceRuntimeRepository = serviceRuntimeRepository;
        this.eventPublisher = eventPublisher;
    }

    public List<ForwardView> list() {
        Map<UUID, DeviceRecord> devices = deviceRepository.findAll().stream()
                .collect(Collectors.toMap(DeviceRecord::id, Function.identity()));
        Map<UUID, ServiceRecord> services = serviceRepository.findAll().stream()
                .collect(Collectors.toMap(ServiceRecord::id, Function.identity()));
        Map<UUID, ForwardRuntimeRecord> runtimes = runtimeRepository.findAll().stream()
                .collect(Collectors.toMap(ForwardRuntimeRecord::forwardId, Function.identity()));
        return forwardRepository.findAll().stream()
                .map(forward -> view(forward, devices, services, runtimes.get(forward.id())))
                .toList();
    }

    public ForwardView get(UUID id) {
        return view(find(id));
    }

    public ForwardView create(ForwardRequest request) {
        ForwardInput input = normalize(request, null);
        DeviceRecord source = findUsableDevice(input.sourceDeviceId());
        ServiceRecord remoteService = validateRemote(source, input.remoteServiceId());
        Instant now = Instant.now();
        ForwardRecord forward = new ForwardRecord(
                UUID.randomUUID(), source.id(), remoteService.id(), input.name(), input.localBindHost(),
                input.localBindPort(), input.enabled(), now, now);
        forwardRepository.insert(forward);
        bumpDesiredState(source, now);
        return view(forward);
    }

    public ForwardView update(UUID id, ForwardRequest request) {
        ForwardRecord existing = find(id);
        ForwardInput input = normalize(request, existing);
        DeviceRecord oldSource = findDevice(existing.sourceDeviceId());
        DeviceRecord source = findUsableDevice(input.sourceDeviceId());
        ServiceRecord remoteService = validateRemote(source, input.remoteServiceId());
        if (!sameConfiguration(existing, input)) {
            Instant now = Instant.now();
            ForwardRecord updated = new ForwardRecord(
                    existing.id(), source.id(), remoteService.id(), input.name(), input.localBindHost(),
                    input.localBindPort(), input.enabled(), existing.createdAt(), now);
            if (!forwardRepository.update(updated)) {
                throw new ControlPlaneException("TM-CTRL-003", HttpStatus.NOT_FOUND, "forward not found");
            }
            runtimeRepository.deleteByForwardId(existing.id());
            bumpDesiredState(oldSource, now);
            if (!oldSource.id().equals(source.id())) {
                bumpDesiredState(source, now);
            }
            return view(updated);
        }
        return view(existing);
    }

    public void delete(UUID id) {
        ForwardRecord forward = find(id);
        if (!forwardRepository.delete(id)) {
            throw new ControlPlaneException("TM-CTRL-003", HttpStatus.NOT_FOUND, "forward not found");
        }
        runtimeRepository.deleteByForwardId(id);
        bumpDesiredState(findDevice(forward.sourceDeviceId()), Instant.now());
    }

    /** Builds the source Agent's Forward projection with the current remote bridge port. */
    public List<AgentForward> desiredState(UUID sourceDeviceId) {
        DeviceRecord source = findDevice(sourceDeviceId);
        List<AgentForward> result = new ArrayList<>();
        for (ForwardRecord forward : forwardRepository.findBySourceDeviceId(sourceDeviceId)) {
            ServiceRecord remoteService = serviceRepository.findById(forward.remoteServiceId()).orElse(null);
            if (remoteService == null) {
                continue;
            }
            DeviceRecord remoteDevice = deviceRepository.findById(remoteService.deviceId()).orElse(null);
            if (remoteDevice == null || !source.networkId().equals(remoteDevice.networkId())
                    || source.id().equals(remoteDevice.id())) {
                continue;
            }
            ServiceRuntimeRecord remoteRuntime = serviceRuntimeRepository
                    .findByServiceId(remoteService.id()).orElse(null);
            Integer remoteBridgePort = remoteRuntime != null
                    && ServiceStatus.READY.name().equals(remoteRuntime.status())
                    ? remoteRuntime.bridgePort() : null;
            result.add(new AgentForward(
                    forward.id(), forward.name(), remoteService.deviceId(), remoteService.id(),
                    forward.localBindHost(), forward.localBindPort(), remoteBridgePort, forward.enabled()));
        }
        return List.copyOf(result);
    }

    /** Stores a complete runtime snapshot reported by one source Agent. */
    public void recordRuntime(UUID sourceDeviceId, AgentForwardRuntimeReport report) {
        if (report == null) {
            throw badRequest("forward runtime report is required");
        }
        DeviceRecord source = findDevice(sourceDeviceId);
        if (source.status() == DeviceStatus.DISABLED) {
            throw new ControlPlaneException("TM-CTRL-003", HttpStatus.FORBIDDEN,
                    "disabled device cannot report forward runtime");
        }
        Set<UUID> reportedIds = new HashSet<>();
        List<ForwardRuntimeRecord> runtimes = new ArrayList<>();
        for (AgentForwardRuntime runtime : report.forwards()) {
            if (runtime == null) {
                throw badRequest("forward runtime entry is required");
            }
            if (!reportedIds.add(runtime.forwardId())) {
                throw badRequest("forward runtime snapshot contains duplicate entries");
            }
            ForwardRecord forward = forwardRepository.findById(runtime.forwardId()).orElse(null);
            if (forward == null) {
                continue;
            }
            if (!sourceDeviceId.equals(forward.sourceDeviceId())) {
                throw new ControlPlaneException("TM-CTRL-003", HttpStatus.FORBIDDEN,
                        "forward does not belong to this device");
            }
            String status = parseStatus(runtime.status());
            String errorCode = bounded(runtime.errorCode(), MAX_ERROR_CODE_LENGTH, "errorCode");
            String lastError = bounded(runtime.lastError(), MAX_ERROR_LENGTH, "lastError");
            runtimes.add(new ForwardRuntimeRecord(
                    forward.id(), status, errorCode, lastError, report.timestamp()));
        }
        runtimeRepository.deleteBySourceDeviceId(sourceDeviceId);
        runtimes.forEach(runtimeRepository::upsert);
    }

    private ForwardView view(ForwardRecord forward) {
        Map<UUID, DeviceRecord> devices = deviceRepository.findAll().stream()
                .collect(Collectors.toMap(DeviceRecord::id, Function.identity()));
        Map<UUID, ServiceRecord> services = serviceRepository.findAll().stream()
                .collect(Collectors.toMap(ServiceRecord::id, Function.identity()));
        return view(forward, devices, services, runtimeRepository.findByForwardId(forward.id()).orElse(null));
    }

    private ForwardView view(ForwardRecord forward, Map<UUID, DeviceRecord> devices,
                             Map<UUID, ServiceRecord> services, ForwardRuntimeRecord runtime) {
        ServiceRecord remoteService = services.get(forward.remoteServiceId());
        DeviceRecord source = devices.get(forward.sourceDeviceId());
        DeviceRecord remoteDevice = remoteService == null ? null : devices.get(remoteService.deviceId());
        String status = runtime == null ? ForwardStatus.STOPPED.name() : runtime.status();
        return new ForwardView(
                forward.id(), forward.sourceDeviceId(), deviceName(source), forward.remoteServiceId(),
                remoteService == null ? "unknown" : remoteService.name(),
                remoteService == null ? null : remoteService.deviceId(), deviceName(remoteDevice),
                forward.name(), forward.localBindHost(), forward.localBindPort(), forward.enabled(), status,
                runtime == null ? null : runtime.errorCode(), runtime == null ? null : runtime.lastError(),
                forward.createdAt(), forward.updatedAt());
    }

    private DeviceRecord findUsableDevice(UUID id) {
        DeviceRecord device = findDevice(id);
        if (device.status() == DeviceStatus.DISABLED) {
            throw new ControlPlaneException("TM-CTRL-003", HttpStatus.CONFLICT,
                    "disabled device cannot own a forward");
        }
        return device;
    }

    private DeviceRecord findDevice(UUID id) {
        if (id == null) {
            throw badRequest("sourceDeviceId is required");
        }
        return deviceRepository.findById(id).orElseThrow(() ->
                new ControlPlaneException("TM-CTRL-003", HttpStatus.NOT_FOUND, "device not found"));
    }

    private ServiceRecord validateRemote(DeviceRecord source, UUID remoteServiceId) {
        if (remoteServiceId == null) {
            throw badRequest("remoteServiceId is required");
        }
        ServiceRecord remoteService = serviceRepository.findById(remoteServiceId).orElseThrow(() ->
                new ControlPlaneException("TM-CTRL-003", HttpStatus.NOT_FOUND, "remote service not found"));
        DeviceRecord remoteDevice = findDevice(remoteService.deviceId());
        if (source.id().equals(remoteDevice.id())) {
            throw badRequest("source device and remote service device must differ");
        }
        if (!source.networkId().equals(remoteDevice.networkId())) {
            throw badRequest("source and remote service must be in the same mesh network");
        }
        if (remoteDevice.status() == DeviceStatus.DISABLED) {
            throw new ControlPlaneException("TM-CTRL-003", HttpStatus.CONFLICT,
                    "disabled device cannot be a forward target");
        }
        return remoteService;
    }

    private void bumpDesiredState(DeviceRecord source, Instant now) {
        deviceRepository.incrementDesiredRevision(source.id(), now);
        eventPublisher.publishEvent(new DesiredStateChangedEvent(source.networkId(), source.id()));
    }

    private static boolean sameConfiguration(ForwardRecord existing, ForwardInput input) {
        return existing.sourceDeviceId().equals(input.sourceDeviceId())
                && existing.remoteServiceId().equals(input.remoteServiceId())
                && existing.name().equals(input.name())
                && existing.localBindHost().equals(input.localBindHost())
                && existing.localBindPort() == input.localBindPort()
                && existing.enabled() == input.enabled();
    }

    private static ForwardInput normalize(ForwardRequest request, ForwardRecord existing) {
        if (request == null) {
            throw badRequest("forward request is required");
        }
        UUID sourceDeviceId = request.sourceDeviceId() == null && existing != null
                ? existing.sourceDeviceId() : request.sourceDeviceId();
        UUID remoteServiceId = request.remoteServiceId() == null && existing != null
                ? existing.remoteServiceId() : request.remoteServiceId();
        String name = request.name() == null && existing != null ? existing.name()
                : requiredText(request.name(), "name", MAX_NAME_LENGTH);
        String host = request.localBindHost() == null && existing != null
                ? existing.localBindHost()
                : request.localBindHost() == null ? DEFAULT_BIND_HOST
                : requiredText(request.localBindHost(), "localBindHost", MAX_HOST_LENGTH);
        if (!"127.0.0.1".equals(host) && !"::1".equals(host)) {
            throw badRequest("localBindHost must be 127.0.0.1 or ::1");
        }
        Integer port = request.localBindPort() == null && existing != null
                ? existing.localBindPort() : request.localBindPort();
        if (port == null || port < 1 || port > 65_535) {
            throw badRequest("localBindPort must be between 1 and 65535");
        }
        boolean enabled = request.enabled() == null && existing != null
                ? existing.enabled() : request.enabled() == null || request.enabled();
        return new ForwardInput(sourceDeviceId, remoteServiceId, name, host, port, enabled);
    }

    private ForwardRecord find(UUID id) {
        if (id == null) {
            throw badRequest("forward id is required");
        }
        return forwardRepository.findById(id).orElseThrow(() ->
                new ControlPlaneException("TM-CTRL-003", HttpStatus.NOT_FOUND, "forward not found"));
    }

    private static String parseStatus(String value) {
        try {
            return ForwardStatus.valueOf(value.toUpperCase(java.util.Locale.ROOT)).name();
        } catch (RuntimeException exception) {
            throw badRequest("unsupported forward runtime status");
        }
    }

    private static String bounded(String value, int maxLength, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.length() > maxLength || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw badRequest(field + " is too long or invalid");
        }
        return value.trim();
    }

    private static String requiredText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw badRequest(field + " is required and must be a single short value");
        }
        return value.trim();
    }

    private static String deviceName(DeviceRecord device) {
        return device == null ? "unknown" : device.name();
    }

    private static ControlPlaneException badRequest(String message) {
        return new ControlPlaneException("TM-CTRL-002", HttpStatus.BAD_REQUEST, message);
    }

    public record ForwardRequest(
            UUID sourceDeviceId,
            UUID remoteServiceId,
            String name,
            String localBindHost,
            Integer localBindPort,
            Boolean enabled
    ) {
    }

    private record ForwardInput(UUID sourceDeviceId, UUID remoteServiceId, String name,
                                String localBindHost, int localBindPort, boolean enabled) {
    }
}
