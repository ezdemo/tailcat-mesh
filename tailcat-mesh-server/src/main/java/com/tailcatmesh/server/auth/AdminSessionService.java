package com.tailcatmesh.server.auth;

import com.tailcatmesh.server.common.ControlPlaneException;
import com.tailcatmesh.server.common.CredentialHasher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Short-lived in-memory admin sessions returned by the login endpoint. */
@Service
public final class AdminSessionService {

    private static final Duration SESSION_LIFETIME = Duration.ofHours(8);

    private final UserRepository userRepository;
    private final CredentialHasher credentialHasher;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public AdminSessionService(UserRepository userRepository, CredentialHasher credentialHasher) {
        this.userRepository = userRepository;
        this.credentialHasher = credentialHasher;
    }

    public LoginResult login(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isEmpty()) {
            throw authenticationFailed();
        }
        UserRecord user = userRepository.findByUsername(username.trim())
                .filter(candidate -> credentialHasher.matches(password, candidate.passwordHash()))
                .orElseThrow(AdminSessionService::authenticationFailed);
        String token = credentialHasher.newSecret("tm_admin_");
        Instant expiresAt = Instant.now().plus(SESSION_LIFETIME);
        sessions.put(token, new Session(new AdminPrincipal(user.id(), user.username(), user.role()), expiresAt));
        return new LoginResult(token, expiresAt);
    }

    public Optional<AdminPrincipal> authenticate(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Session session = sessions.get(token);
        if (session == null) {
            return Optional.empty();
        }
        if (session.expiresAt().isBefore(Instant.now())) {
            sessions.remove(token, session);
            return Optional.empty();
        }
        return Optional.of(session.principal());
    }

    public void logout(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    private static ControlPlaneException authenticationFailed() {
        return new ControlPlaneException("TM-CTRL-001", HttpStatus.UNAUTHORIZED, "authentication failed");
    }

    public record LoginResult(String accessToken, Instant expiresAt) {
    }

    private record Session(AdminPrincipal principal, Instant expiresAt) {
    }
}
