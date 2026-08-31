package com.tailcatmesh.agent.identity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentStateStoreTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void savesAndLoadsCredentialStateAtomicallyWithoutEnrollmentToken() throws Exception {
        AgentStateStore store = new AgentStateStore(temporaryDirectory);
        AgentState expected = new AgentState(
                UUID.randomUUID(), "tm_agent_secret", Instant.parse("2026-08-31T03:00:00Z"));

        assertTrue(store.load().isEmpty());
        store.save(expected);

        assertEquals(expected, store.load().orElseThrow());
        assertTrue(Files.isRegularFile(store.path()));
        String persisted = Files.readString(store.path());
        assertTrue(persisted.contains("tm_agent_secret"));
        assertFalse(persisted.contains("tm_enroll_"));
        assertFalse(Files.exists(store.path().resolveSibling("agent-state.json.tmp")));
    }
}
