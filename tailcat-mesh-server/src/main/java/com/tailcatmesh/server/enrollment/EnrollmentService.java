package com.tailcatmesh.server.enrollment;

import com.tailcatmesh.server.common.ControlPlaneException;
import com.tailcatmesh.server.common.CredentialHasher;
import com.tailcatmesh.server.device.DeviceRecord;
import com.tailcatmesh.server.device.DeviceStatus;
import com.tailcatmesh.server.device.DeviceRepository;
import com.tailcatmesh.server.mesh.MeshNetworkRepository;
import com.tailcatmesh.protocol.agent.AgentEnrollmentRequest;
import com.tailcatmesh.protocol.agent.AgentEnrollmentResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/** Implements one-time enrollment-token consumption and Agent registration. */
@Service
public class EnrollmentService {

    private static final Pattern PUBLIC_KEY = Pattern.compile("nodekey:[0-9a-fA-F]{64}");
    private static final int MAX_USES_LIMIT = 100_000;
    private static final int MAX_TOKEN_HOURS = 24 * 365;

    private final EnrollmentTokenRepository tokenRepository;
    private final MeshNetworkRepository networkRepository;
    private final DeviceRepository deviceRepository;
    private final AgentCredentialRepository credentialRepository;
    private final CredentialHasher credentialHasher;
    private final int defaultExpireHours;

    public EnrollmentService(
            EnrollmentTokenRepository tokenRepository,
            MeshNetworkRepository networkRepository,
            DeviceRepository deviceRepository,
            AgentCredentialRepository credentialRepository,
            CredentialHasher credentialHasher,
            @Value("${tailcat-mesh.enrollment.default-expire-hours:24}") int defaultExpireHours) {
        this.tokenRepository = tokenRepository;
        this.networkRepository = networkRepository;
        this.deviceRepository = deviceRepository;
        this.credentialRepository = credentialRepository;
        this.credentialHasher = credentialHasher;
        this.defaultExpireHours = validExpireHours(defaultExpireHours);
    }

    public EnrollmentTokenCreated createToken(CreateEnrollmentTokenRequest request) {
        UUID networkId = request == null || request.networkId() == null
                ? networkRepository.findBySlug("default").map(network -> network.id()).orElseThrow(
                () -> new ControlPlaneException("TM-CTRL-500", HttpStatus.INTERNAL_SERVER_ERROR,
                        "default mesh network is not initialized"))
                : request.networkId();
        if (networkRepository.findById(networkId).isEmpty()) {
            throw new ControlPlaneException("TM-CTRL-002", HttpStatus.BAD_REQUEST, "mesh network not found");
        }
        int maxUses = request == null || request.maxUses() == null ? 1 : request.maxUses();
        if (maxUses < 1 || maxUses > MAX_USES_LIMIT) {
            throw new ControlPlaneException("TM-CTRL-002", HttpStatus.BAD_REQUEST,
                    "maxUses must be between 1 and " + MAX_USES_LIMIT);
        }
        int expireHours;
        try {
            expireHours = request == null || request.expiresInHours() == null
                    ? defaultExpireHours
                    : validExpireHours(request.expiresInHours());
        } catch (IllegalArgumentException exception) {
            throw new ControlPlaneException("TM-CTRL-002", HttpStatus.BAD_REQUEST,
                    "expiresInHours must be between 1 and " + MAX_TOKEN_HOURS);
        }
        Instant now = Instant.now();
        String rawToken = credentialHasher.newSecret("tm_enroll_");
        EnrollmentTokenRecord record = new EnrollmentTokenRecord(
                UUID.randomUUID(), networkId, credentialHasher.hash(rawToken),
                now.plusSeconds(expireHours * 3_600L), maxUses, 0, true, now
        );
        tokenRepository.insert(record);
        return new EnrollmentTokenCreated(record.id(), rawToken, record.expiresAt(), record.maxUses());
    }

    public List<EnrollmentTokenView> listTokens() {
        return tokenRepository.findAll().stream()
                .map(token -> new EnrollmentTokenView(
                        token.id(), token.networkId(), token.expiresAt(), token.maxUses(),
                        token.usedCount(), token.enabled(), token.createdAt()))
                .toList();
    }

    public void disableToken(UUID tokenId) {
        if (tokenId == null) {
            throw new ControlPlaneException("TM-CTRL-002", HttpStatus.BAD_REQUEST, "token id is required");
        }
        try {
            tokenRepository.disable(tokenId);
        } catch (IllegalArgumentException exception) {
            throw new ControlPlaneException("TM-CTRL-002", HttpStatus.NOT_FOUND,
                    "enrollment token not found");
        }
    }

    @Transactional
    public AgentEnrollmentResponse enroll(AgentEnrollmentRequest request) {
        validateEnrollmentRequest(request);
        Instant now = Instant.now();
        EnrollmentTokenRecord token = findMatchingToken(request.enrollmentToken());
        if (token == null || !token.enabled() || token.usedCount() >= token.maxUses()
                || !token.expiresAt().isAfter(now)
                || !credentialHasher.matches(request.enrollmentToken(), token.tokenHash())) {
            throw new ControlPlaneException("TM-CTRL-002", HttpStatus.BAD_REQUEST,
                    "enrollment token is invalid or expired");
        }
        UUID deviceId = UUID.randomUUID();
        String deviceName = request.deviceName() == null || request.deviceName().isBlank()
                ? request.hostname() : request.deviceName().trim();
        DeviceRecord device = new DeviceRecord(
                deviceId,
                token.networkId(),
                deviceName,
                request.hostname(),
                request.os(),
                request.arch(),
                DeviceStatus.PENDING,
                request.agentVersion(),
                request.tailcatVersion(),
                request.clientPublicKey(),
                null,
                null,
                null,
                0,
                now,
                now
        );
        deviceRepository.insert(device);
        String rawCredential = credentialHasher.newSecret("tm_agent_");
        credentialRepository.insert(new AgentCredentialRecord(
                UUID.randomUUID(), deviceId, credentialHasher.hash(rawCredential), now, null, null));
        tokenRepository.incrementUsed(token.id());
        return new AgentEnrollmentResponse(deviceId, rawCredential, DeviceStatus.PENDING.name());
    }

    private EnrollmentTokenRecord findMatchingToken(String rawToken) {
        // v0.1 tokens intentionally have no lookup identifier; the hash is the only persisted secret.
        return tokenRepository.findAllForUpdate().stream()
                .filter(token -> credentialHasher.matches(rawToken, token.tokenHash()))
                .findFirst()
                .orElse(null);
    }

    private static void validateEnrollmentRequest(AgentEnrollmentRequest request) {
        if (request == null || request.enrollmentToken() == null || request.enrollmentToken().isBlank()) {
            throw new ControlPlaneException("TM-CTRL-002", HttpStatus.BAD_REQUEST,
                    "enrollmentToken is required");
        }
        requireText(request.hostname(), "hostname", 255);
        requireText(request.os(), "os", 64);
        requireText(request.arch(), "arch", 64);
        requireText(request.agentVersion(), "agentVersion", 64);
        requireText(request.tailcatVersion(), "tailcatVersion", 64);
        if (request.deviceName() != null && !request.deviceName().isBlank()) {
            requireText(request.deviceName(), "deviceName", 255);
        }
        if (request.clientPublicKey() == null || !PUBLIC_KEY.matcher(request.clientPublicKey()).matches()) {
            throw new ControlPlaneException("TM-CTRL-002", HttpStatus.BAD_REQUEST,
                    "clientPublicKey is invalid");
        }
    }

    private static void requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new ControlPlaneException("TM-CTRL-002", HttpStatus.BAD_REQUEST,
                    field + " is required and must be at most " + maxLength + " characters");
        }
    }

    private static int validExpireHours(int value) {
        if (value < 1 || value > MAX_TOKEN_HOURS) {
            throw new IllegalArgumentException("enrollment expiry must be between 1 and " + MAX_TOKEN_HOURS);
        }
        return value;
    }

    public record CreateEnrollmentTokenRequest(UUID networkId, Integer maxUses, Integer expiresInHours) {
    }

    public record EnrollmentTokenCreated(UUID id, String token, Instant expiresAt, int maxUses) {
    }

    public record EnrollmentTokenView(UUID id, UUID networkId, Instant expiresAt, int maxUses,
                                      int usedCount, boolean enabled, Instant createdAt) {
    }

}
