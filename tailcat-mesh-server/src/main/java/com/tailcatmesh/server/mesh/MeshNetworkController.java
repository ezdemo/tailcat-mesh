package com.tailcatmesh.server.mesh;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Admin REST API for M7.1 Network and membership desired state. */
@RestController
@RequestMapping("/api/v1/networks")
public final class MeshNetworkController {

    private final MeshNetworkService meshNetworkService;

    public MeshNetworkController(MeshNetworkService meshNetworkService) {
        this.meshNetworkService = meshNetworkService;
    }

    @GetMapping
    public List<MeshNetworkView> list() {
        return meshNetworkService.list();
    }

    @GetMapping("/{id}")
    public MeshNetworkView get(@PathVariable UUID id) {
        return meshNetworkService.get(id);
    }

    @PostMapping
    public MeshNetworkView create(@RequestBody(required = false) MeshNetworkService.NetworkRequest request) {
        return meshNetworkService.create(request);
    }

    @PutMapping("/{id}")
    public MeshNetworkView update(@PathVariable UUID id,
                                  @RequestBody MeshNetworkService.NetworkUpdateRequest request) {
        return meshNetworkService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        meshNetworkService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/members")
    public MeshNetworkMemberView addMember(
            @PathVariable UUID id,
            @RequestBody(required = false) MeshNetworkService.MemberRequest request) {
        return meshNetworkService.addMember(id, request);
    }

    @DeleteMapping("/{id}/members/{deviceId}")
    public ResponseEntity<Void> removeMember(@PathVariable UUID id, @PathVariable UUID deviceId) {
        meshNetworkService.removeMember(id, deviceId);
        return ResponseEntity.noContent().build();
    }
}
