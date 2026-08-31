package com.tailcatmesh.server.common;

import com.tailcatmesh.server.auth.UserRecord;
import com.tailcatmesh.server.auth.UserRepository;
import com.tailcatmesh.server.mesh.MeshNetworkRecord;
import com.tailcatmesh.server.mesh.MeshNetworkRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/** Creates the development bootstrap admin and the default mesh if absent. */
@Component
@Order(0)
public final class ServerBootstrapInitializer implements ApplicationRunner {

    private final UserRepository userRepository;
    private final MeshNetworkRepository meshNetworkRepository;
    private final CredentialHasher credentialHasher;
    private final String adminUsername;
    private final String adminPassword;

    public ServerBootstrapInitializer(
            UserRepository userRepository,
            MeshNetworkRepository meshNetworkRepository,
            CredentialHasher credentialHasher,
            @Value("${tailcat-mesh.admin.username:admin}") String adminUsername,
            @Value("${tailcat-mesh.admin.password:change-me}") String adminPassword) {
        this.userRepository = userRepository;
        this.meshNetworkRepository = meshNetworkRepository;
        this.credentialHasher = credentialHasher;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        Instant now = Instant.now();
        if (meshNetworkRepository.findBySlug("default").isEmpty()) {
            meshNetworkRepository.insert(new MeshNetworkRecord(
                    UUID.randomUUID(), "Default Mesh", "default", now, now));
        }
        if (userRepository.findByUsername(adminUsername).isEmpty()) {
            userRepository.insert(new UserRecord(
                    UUID.randomUUID(),
                    adminUsername,
                    credentialHasher.hash(adminPassword),
                    "ADMIN",
                    now,
                    now
            ));
        }
    }
}
