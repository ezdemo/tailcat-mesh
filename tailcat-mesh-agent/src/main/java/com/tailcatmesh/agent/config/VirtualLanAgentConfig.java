package com.tailcatmesh.agent.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Optional local configuration that enables the M7 system-level data plane. */
public record VirtualLanAgentConfig(
        boolean enabled,
        String interfaceName,
        UUID adapterGuid,
        Path wintunDll,
        Path tun2socksBinary,
        List<String> tun2socksArgumentTemplate,
        Path workingDirectory,
        Map<String, String> environment,
        Duration commandTimeout,
        Duration tun2socksStartupTimeout
) {

    /** No whitespace: official tun2socks places this name in a tun:// URL host. */
    public static final String DEFAULT_INTERFACE_NAME = "TailcatMesh";
    public static final UUID DEFAULT_ADAPTER_GUID =
            UUID.fromString("7d2a8db0-6f69-4d26-9d6e-9e0e4d2c6b71");

    public VirtualLanAgentConfig {
        interfaceName = Objects.requireNonNull(interfaceName, "interfaceName").trim();
        if (interfaceName.isBlank() || interfaceName.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("virtualLan.interfaceName must not be blank");
        }
        adapterGuid = adapterGuid == null ? DEFAULT_ADAPTER_GUID : adapterGuid;
        if (wintunDll != null) {
            wintunDll = wintunDll.toAbsolutePath().normalize();
        }
        if (tun2socksBinary != null) {
            tun2socksBinary = tun2socksBinary.toAbsolutePath().normalize();
        }
        tun2socksArgumentTemplate = tun2socksArgumentTemplate == null
                ? List.of() : List.copyOf(tun2socksArgumentTemplate);
        if (workingDirectory != null) {
            workingDirectory = workingDirectory.toAbsolutePath().normalize();
        }
        environment = environment == null ? Map.of() : Map.copyOf(environment);
        commandTimeout = positive(commandTimeout, "virtualLan.commandTimeout");
        tun2socksStartupTimeout = positive(tun2socksStartupTimeout,
                "virtualLan.tun2socksStartupTimeout");
        if (enabled && (tun2socksBinary == null || tun2socksArgumentTemplate.isEmpty())) {
            throw new IllegalArgumentException(
                    "enabled virtualLan requires tun2socksBinary and tun2socksArgumentTemplate");
        }
    }

    public static VirtualLanAgentConfig disabled() {
        return new VirtualLanAgentConfig(false, DEFAULT_INTERFACE_NAME, DEFAULT_ADAPTER_GUID,
                null, null, List.of(), null, Map.of(), Duration.ofSeconds(15), Duration.ofSeconds(15));
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
