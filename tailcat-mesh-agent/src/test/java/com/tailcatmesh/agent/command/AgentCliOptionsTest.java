package com.tailcatmesh.agent.command;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCliOptionsTest {

    @Test
    void parsesFirstTimeConnectCommand() {
        AgentCliOptions options = AgentCliOptions.parse(new String[]{
                "connect",
                "--server", "https://mesh.example.test/",
                "--token=tm_enroll_test",
                "--tailcat-binary", "tailcat.exe",
                "--data-dir", "agent-data",
                "--config", "custom-agent.yml",
                "--once"
        });

        assertEquals(AgentCliOptions.Command.CONNECT, options.command());
        assertEquals(URI.create("https://mesh.example.test/"), options.serverUrl());
        assertEquals("tm_enroll_test", options.enrollmentToken());
        assertEquals(Path.of("tailcat.exe"), options.tailcatBinary());
        assertEquals(Path.of("agent-data"), options.dataDir());
        assertEquals(Path.of("custom-agent.yml"), options.configPath());
        assertTrue(options.once());
        assertFalse(options.help());
    }

    @Test
    void optionsCanOmitTheConnectCommand() {
        AgentCliOptions options = AgentCliOptions.parse(new String[]{
                "--server=http://127.0.0.1:8080",
                "--token", "tm_enroll_test"
        });

        assertEquals(AgentCliOptions.Command.CONNECT, options.command());
        assertEquals(URI.create("http://127.0.0.1:8080"), options.serverUrl());
    }

    @Test
    void versionAndInvalidOptionsAreHandledBeforeRuntimeStartup() {
        assertEquals(AgentCliOptions.Command.VERSION,
                AgentCliOptions.parse(new String[]{"--version"}).command());
        assertEquals(AgentCliOptions.Command.HELP,
                AgentCliOptions.parse(new String[]{"--help"}).command());
        assertThrows(IllegalArgumentException.class,
                () -> AgentCliOptions.parse(new String[]{"connect", "--unknown"}));
        assertThrows(IllegalArgumentException.class,
                () -> AgentCliOptions.parse(new String[]{"connect", "--token"}));
    }
}
