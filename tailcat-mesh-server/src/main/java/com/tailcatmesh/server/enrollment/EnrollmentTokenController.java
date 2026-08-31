package com.tailcatmesh.server.enrollment;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Admin endpoints for creating and revoking enrollment tokens. */
@RestController
@RequestMapping("/api/v1/enrollment-tokens")
public final class EnrollmentTokenController {

    private final EnrollmentService enrollmentService;

    public EnrollmentTokenController(EnrollmentService enrollmentService) {
        this.enrollmentService = enrollmentService;
    }

    @PostMapping
    public EnrollmentService.EnrollmentTokenCreated create(
            @RequestBody(required = false) EnrollmentService.CreateEnrollmentTokenRequest request) {
        return enrollmentService.createToken(request);
    }

    @GetMapping
    public List<EnrollmentService.EnrollmentTokenView> list() {
        return enrollmentService.listTokens();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> disable(@PathVariable UUID id) {
        enrollmentService.disableToken(id);
        return ResponseEntity.noContent().build();
    }
}
