package com.tailcatmesh.agent.config;

import java.net.URI;
import java.nio.file.Path;

/** CLI values that intentionally override the optional YAML configuration. */
public record AgentConfigOverrides(URI serverUrl, Path tailcatBinary, Path dataDir) {
}
