package com.tailcatmesh.server.agentws;

import com.tailcatmesh.server.common.ControlPlaneException;
import com.tailcatmesh.server.common.CredentialHasher;
import com.tailcatmesh.server.device.DeviceRecord;
import com.tailcatmesh.server.device.DeviceRepository;
import com.tailcatmesh.server.device.DeviceStatus;
import com.tailcatmesh.server.enrollment.AgentCredentialRecord;
import com.tailcatmesh.server.enrollment.AgentCredentialRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;

/** Resolves Agent bearer credentials without persisting or logging raw secrets. */
@Service
public final class AgentAuthenticationService {

    private final AgentCredentialRepository credentialRepository;
    private final DeviceRepository deviceRepository;
    private final CredentialHasher credentialHasher;

    public AgentAuthenticationService(
            AgentCredentialRepository credentialRepository,
            DeviceRepository deviceRepository,
            CredentialHasher credentialHasher) {
        this.credentialRepository = credentialRepository;
        this.deviceRepository = deviceRepository;
        this.credentialHasher = credentialHasher;
    }

    public AgentPrincipal authenticate(String rawCredential) {
        if (rawCredential == null || rawCredential.isBlank()) {
            throw unauthorized();
        }
        for (AgentCredentialRecord credential : credentialRepository.findActive()) {
            if (!credentialHasher.matches(rawCredential, credential.secretHash())) {
                continue;
            }
            DeviceRecord device = deviceRepository.findById(credential.deviceId()).orElseThrow(
                    AgentAuthenticationService::unauthorized);
            if (device.status() == DeviceStatus.DISABLED) {
                throw new ControlPlaneException("TM-CTRL-003", HttpStatus.FORBIDDEN, "device is disabled");
            }
            credentialRepository.touch(credential.id(), Instant.now());
            return new AgentPrincipal(credential.id(), device.id(), device.status());
        }
        throw unauthorized();
    }

    private static ControlPlaneException unauthorized() {
        return new ControlPlaneException("TM-CTRL-001", HttpStatus.UNAUTHORIZED, "agent authentication failed");
    }
}
