package com.tailcatmesh.server.auth;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Minimal stateless-looking login API for the admin control plane. */
@RestController
@RequestMapping("/api/v1/auth")
public final class AuthController {

    private final AdminSessionService sessionService;

    public AuthController(AdminSessionService sessionService) {
        this.sessionService = sessionService;
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest request) {
        AdminSessionService.LoginResult result = sessionService.login(request.username(), request.password());
        return new LoginResponse(result.accessToken(), result.expiresAt());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        sessionService.logout(AdminAuthenticationFilter.bearerToken(authorization));
        return ResponseEntity.noContent().build();
    }

    public record LoginRequest(String username, String password) {
    }

    public record LoginResponse(String accessToken, java.time.Instant expiresAt) {
    }
}
