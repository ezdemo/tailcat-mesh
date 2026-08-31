package com.tailcatmesh.server.device;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Admin device list and approval endpoints. */
@RestController
@RequestMapping("/api/v1/devices")
public final class DeviceController {

    private final DeviceService deviceService;

    public DeviceController(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    @GetMapping
    public List<DeviceView> list() {
        return deviceService.list();
    }

    @GetMapping("/{id}")
    public DeviceView get(@PathVariable UUID id) {
        return deviceService.get(id);
    }

    @PostMapping("/{id}/approve")
    public DeviceView approve(@PathVariable UUID id) {
        return deviceService.approve(id);
    }

    @PostMapping("/{id}/disable")
    public DeviceView disable(@PathVariable UUID id) {
        return deviceService.disable(id);
    }
}
