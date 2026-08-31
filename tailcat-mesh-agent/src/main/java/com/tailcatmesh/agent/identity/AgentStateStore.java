package com.tailcatmesh.agent.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tailcatmesh.agent.config.AgentConfigException;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Optional;

/** Atomic local persistence for the one-time Agent credential. */
public final class AgentStateStore {

    private final Path statePath;
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public AgentStateStore(Path dataDir) {
        this.statePath = dataDir.toAbsolutePath().normalize().resolve("identity").resolve("agent-state.json");
    }

    public Optional<AgentState> load() {
        if (!Files.isRegularFile(statePath)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(Files.readString(statePath), AgentState.class));
        } catch (IOException | RuntimeException exception) {
            throw new AgentConfigException("TM-AGENT-010", "local Agent identity state is invalid", exception);
        }
    }

    public void save(AgentState state) {
        try {
            Files.createDirectories(statePath.getParent());
            Path temporary = Files.createTempFile(statePath.getParent(), "agent-state-", ".tmp");
            try {
                Files.writeString(temporary, objectMapper.writeValueAsString(state));
                restrict(temporary);
                try {
                    Files.move(temporary, statePath, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException exception) {
                    Files.move(temporary, statePath, StandardCopyOption.REPLACE_EXISTING);
                }
                restrict(statePath);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException exception) {
            throw new AgentConfigException("TM-AGENT-010", "unable to persist local Agent identity", exception);
        }
    }

    public Path path() {
        return statePath;
    }

    private static void restrict(Path path) {
        try {
            FileStore store = Files.getFileStore(path);
            if (store.supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(path, EnumSet.of(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            }
        } catch (IOException | UnsupportedOperationException ignored) {
            // Windows ACLs are managed by the installation directory; POSIX permissions are best effort.
        }
    }
}
