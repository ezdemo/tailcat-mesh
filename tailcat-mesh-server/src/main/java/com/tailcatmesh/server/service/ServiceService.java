package com.tailcatmesh.server.service;

import com.tailcatmesh.protocol.agent.AgentService;
import com.tailcatmesh.protocol.agent.AgentServiceRuntime;
import com.tailcatmesh.protocol.agent.AgentServiceRuntimeReport;
import com.tailcatmesh.server.agentws.DesiredStateChangedEvent;
import com.tailcatmesh.server.common.ControlPlaneException;
import com.tailcatmesh.server.device.DeviceRecord;
import com.tailcatmesh.server.device.DeviceRepository;
import com.tailcatmesh.server.device.DeviceStatus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Coordinates Service CRUD, desired-state revisions, and runtime reports. */
@Service
public final class ServiceService {

    private static final int MAX_NAME_LENGTH = 255;
    private static final int MAX_HOST_LENGTH = 255;
    private static final int MAX_ERROR_LENGTH = 2_000;

    private final ServiceRepository serviceRepository;
    private final ServiceRuntimeRepository runtimeRepository;
    private final DeviceRepository deviceRepository;
    private final ApplicationEventPublisher eventPublisher;

    public ServiceService(ServiceRepository serviceRepository,
                          ServiceRuntimeRepository runtimeRepository,
                          DeviceRepository deviceRepository,
                          ApplicationEventPublisher eventPublisher) {
        this.serviceRepository = serviceRepository;
        this.runtimeRepository = runtimeRepository;
        this.deviceRepository = deviceRepository;
        this.eventPublisher = eventPublisher;
    }

    public List<ServiceView> list() {
        return serviceRepository.findAll().stream().map(this::view).toList();
    }

    public ServiceView get(UUID id) {
        return view(find(id));
    }

    public ServiceView create(ServiceRequest request) {
        ServiceInput input = normalize(request, null);
        DeviceRecord device = findUsableDevice(input.deviceId());
        Instant now = Instant.now();
        ServiceRecord service = new ServiceRecord(
                UUID.randomUUID(), device.id(), input.name(), input.protocol(), input.targetHost(),
                input.targetPort(), input.enabled(), now, now);
        serviceRepository.insert(service);
        bumpDesiredState(device, now);
        return view(service);
    }

    public ServiceView update(UUID id, ServiceRequest request) {
        ServiceRecord existing = find(id);
        ServiceInput input = normalize(request, existing);
        DeviceRecord oldDevice = findDevice(existing.deviceId());
        DeviceRecord nextDevice = findUsableDevice(input.deviceId());
        if (!sameConfiguration(existing, input)) {
            Instant now = Instant.now();
            ServiceRecord updated = new ServiceRecord(
                    existing.id(), nextDevice.id(), input.name(), input.protocol(), input.targetHost(),
                    input.targetPort(), input.enabled(), existing.createdAt(), now);
            if (!serviceRepository.update(updated)) {
                throw new ControlPlaneException("TM-CTRL-003", HttpStatus.NOT_FOUND, "service not found");
            }
            // The previous bridge no longer represents the updated target or
            // owner. Let the next Agent report establish a fresh runtime view.
            runtimeRepository.deleteByServiceId(existing.id());
            bumpDesiredState(oldDevice, nextDevice, now);
            return view(updated);
        }
        return view(existing);
    }

    public void delete(UUID id) {
        ServiceRecord service = find(id);
        if (!serviceRepository.delete(id)) {
            throw new ControlPlaneException("TM-CTRL-003", HttpStatus.NOT_FOUND, "service not found");
        }
        runtimeRepository.deleteByServiceId(id);
        DeviceRecord device = findDevice(service.deviceId());
        Instant now = Instant.now();
        bumpDesiredState(device, now);
    }

    /** Builds only the service portion of one Agent's complete Desired State. */
    public List<AgentService> desiredState(UUID deviceId) {
        return serviceRepository.findByDeviceId(deviceId).stream()
                .map(service -> new AgentService(
                        service.id(), service.name(), service.protocol(), service.targetHost(),
                        service.targetPort(), service.enabled()))
                .toList();
    }

    /** Stores a complete runtime snapshot reported by the owning Agent. */
    public void recordRuntime(UUID deviceId, AgentServiceRuntimeReport report) {
        if (report == null) {
            throw badRequest("service runtime report is required");
        }
        boolean acceptedRuntime = false;
        for (AgentServiceRuntime runtime : report.services()) {
            if (runtime == null) {
                throw badRequest("service runtime entry is required");
            }
            ServiceRecord service = serviceRepository.findById(runtime.serviceId()).orElse(null);
            if (service == null) {
                // A report can race with a Service deletion. Desired State is
                // already authoritative, so stale runtime entries are ignored.
                continue;
            }
            if (!deviceId.equals(service.deviceId())) {
                throw new ControlPlaneException("TM-CTRL-003", HttpStatus.FORBIDDEN,
                        "service does not belong to this device");
            }
            ServiceStatus status = parseStatus(runtime.status());
            Integer bridgePort = runtime.bridgePort();
            if ((status == ServiceStatus.STARTING || status == ServiceStatus.READY)
                    && bridgePort == null) {
                throw badRequest("bridgePort is required for an active service runtime");
            }
            if (status == ServiceStatus.STOPPED) {
                bridgePort = null;
            }
            String lastError = normalizeError(runtime.lastError());
            runtimeRepository.upsert(new ServiceRuntimeRecord(
                    service.id(), bridgePort, status.name(), lastError,
                    report.timestamp()));
            acceptedRuntime = true;
        }
        if (acceptedRuntime) {
            DeviceRecord owner = deviceRepository.findById(deviceId).orElse(null);
            if (owner != null) {
                notifyNetwork(owner);
            }
        }
    }

    private ServiceView view(ServiceRecord service) {
        ServiceRuntimeRecord runtime = runtimeRepository.findByServiceId(service.id()).orElse(
                new ServiceRuntimeRecord(service.id(), null, ServiceStatus.STOPPED.name(), null,
                        service.updatedAt()));
        return new ServiceView(
                service.id(), service.deviceId(), service.name(), service.protocol(), service.targetHost(),
                service.targetPort(), service.enabled(), runtime.bridgePort(), runtime.status(),
                runtime.lastError(), service.createdAt(), service.updatedAt());
    }

    private ServiceRecord find(UUID id) {
        if (id == null) {
            throw badRequest("service id is required");
        }
        return serviceRepository.findById(id).orElseThrow(() ->
                new ControlPlaneException("TM-CTRL-003", HttpStatus.NOT_FOUND, "service not found"));
    }

    private DeviceRecord findDevice(UUID id) {
        return deviceRepository.findById(id).orElseThrow(() ->
                new ControlPlaneException("TM-CTRL-003", HttpStatus.NOT_FOUND, "device not found"));
    }

    private DeviceRecord findUsableDevice(UUID id) {
        DeviceRecord device = findDevice(id);
        if (device.status() == DeviceStatus.DISABLED) {
            throw new ControlPlaneException("TM-CTRL-003", HttpStatus.CONFLICT,
                    "disabled device cannot publish a service");
        }
        return device;
    }

    private void bumpDesiredState(DeviceRecord device, Instant now) {
        deviceRepository.incrementDesiredRevisionForNetwork(device.networkId(), now);
        eventPublisher.publishEvent(new DesiredStateChangedEvent(device.networkId()));
    }

    private void bumpDesiredState(DeviceRecord oldDevice, DeviceRecord newDevice, Instant now) {
        if (oldDevice.networkId().equals(newDevice.networkId())) {
            bumpDesiredState(newDevice, now);
            return;
        }
        bumpDesiredState(newDevice, now);
        bumpDesiredState(oldDevice, now);
    }

    /** Remote service runtime/config changes can alter another device's Forward target. */
    private void notifyNetwork(DeviceRecord device) {
        deviceRepository.incrementDesiredRevisionForNetworkExcept(
                device.networkId(), device.id(), Instant.now());
        eventPublisher.publishEvent(new DesiredStateChangedEvent(device.networkId()));
    }

    private static boolean sameConfiguration(ServiceRecord existing, ServiceInput input) {
        return existing.deviceId().equals(input.deviceId())
                && existing.name().equals(input.name())
                && existing.protocol().equals(input.protocol())
                && existing.targetHost().equals(input.targetHost())
                && existing.targetPort() == input.targetPort()
                && existing.enabled() == input.enabled();
    }

    private static ServiceInput normalize(ServiceRequest request, ServiceRecord existing) {
        if (request == null) {
            throw badRequest("service request is required");
        }
        UUID deviceId = request.deviceId() == null && existing != null
                ? existing.deviceId() : request.deviceId();
        if (deviceId == null) {
            throw badRequest("deviceId is required");
        }
        String name = request.name() == null && existing != null ? existing.name()
                : requiredText(request.name(), "name", MAX_NAME_LENGTH);
        String protocol = request.protocol() == null && existing != null ? existing.protocol()
                : requiredText(request.protocol() == null ? "TCP" : request.protocol(), "protocol", 16)
                .toUpperCase(Locale.ROOT);
        if (!"TCP".equals(protocol)) {
            throw badRequest("only TCP services are supported");
        }
        String targetHost = request.targetHost() == null && existing != null ? existing.targetHost()
                : requiredHost(request.targetHost());
        Integer targetPort = request.targetPort() == null && existing != null
                ? existing.targetPort() : request.targetPort();
        if (targetPort == null || targetPort < 1 || targetPort > 65_535) {
            throw badRequest("targetPort must be between 1 and 65535");
        }
        boolean enabled = request.enabled() == null && existing != null
                ? existing.enabled() : request.enabled() == null || request.enabled();
        return new ServiceInput(deviceId, name, protocol, targetHost, targetPort, enabled);
    }

    private static String requiredText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw badRequest(field + " is required and must be a single short value");
        }
        return value.trim();
    }

    private static String requiredHost(String value) {
        String host = requiredText(value, "targetHost", MAX_HOST_LENGTH);
        if (host.contains(" ")) {
            throw badRequest("targetHost must not contain spaces");
        }
        return host;
    }

    private static String normalizeError(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.length() > MAX_ERROR_LENGTH) {
            throw badRequest("lastError is too long");
        }
        return value.trim();
    }

    private static ServiceStatus parseStatus(String value) {
        try {
            return ServiceStatus.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw badRequest("unsupported service runtime status");
        }
    }

    private static ControlPlaneException badRequest(String message) {
        return new ControlPlaneException("TM-CTRL-002", HttpStatus.BAD_REQUEST, message);
    }

    public record ServiceRequest(
            UUID deviceId,
            String name,
            String protocol,
            String targetHost,
            Integer targetPort,
            Boolean enabled
    ) {
    }

    private record ServiceInput(UUID deviceId, String name, String protocol,
                                String targetHost, int targetPort, boolean enabled) {
    }
}
