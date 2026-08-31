package com.tailcatmesh.server.forward;

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

/** Admin CRUD boundary for Local Forwards. */
@RestController
@RequestMapping("/api/v1/forwards")
public final class ForwardController {

    private final ForwardService forwardService;

    public ForwardController(ForwardService forwardService) {
        this.forwardService = forwardService;
    }

    @GetMapping
    public List<ForwardView> list() {
        return forwardService.list();
    }

    @GetMapping("/{id}")
    public ForwardView get(@PathVariable UUID id) {
        return forwardService.get(id);
    }

    @PostMapping
    public ForwardView create(@RequestBody ForwardService.ForwardRequest request) {
        return forwardService.create(request);
    }

    @PutMapping("/{id}")
    public ForwardView update(@PathVariable UUID id,
                              @RequestBody ForwardService.ForwardRequest request) {
        return forwardService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        forwardService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
