package com.tailcatmesh.agent.virtual;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Explicit local configuration for the Agent-owned M7 TUN data plane. */
public record VirtualLanDataPlaneConfig(
        String interfaceName,
        UUID adapterGuid,
        Path wintunDll,
        Path tun2socksBinary,
        List<String> tun2socksArgumentTemplate,
        Path workingDirectory,
        Map<String, String> environment,
        Duration commandTimeout,
        Duration tun2socksStartupTimeout,
        Path routeStateFile
) {

    public VirtualLanDataPlaneConfig(String interfaceName, UUID adapterGuid, Path wintunDll,
                                     Path tun2socksBinary, List<String> tun2socksArgumentTemplate,
                                     Path workingDirectory, Map<String, String> environment,
                                     Duration commandTimeout, Duration tun2socksStartupTimeout) {
        this(interfaceName, adapterGuid, wintunDll, tun2socksBinary, tun2socksArgumentTemplate,
                workingDirectory, environment, commandTimeout, tun2socksStartupTimeout, null);
    }

    public VirtualLanDataPlaneConfig {
        interfaceName = Objects.requireNonNull(interfaceName, "interfaceName").trim();
        if (interfaceName.isBlank() || interfaceName.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("interfaceName must not be blank");
        }
        if (wintunDll != null) {
            wintunDll = wintunDll.toAbsolutePath().normalize();
        }
        tun2socksBinary = Objects.requireNonNull(tun2socksBinary, "tun2socksBinary")
                .toAbsolutePath().normalize();
        tun2socksArgumentTemplate = tun2socksArgumentTemplate == null
                ? List.of() : List.copyOf(tun2socksArgumentTemplate);
        if (tun2socksArgumentTemplate.isEmpty()) {
            throw new IllegalArgumentException("tun2socksArgumentTemplate must not be empty");
        }
        if (workingDirectory != null) {
            workingDirectory = workingDirectory.toAbsolutePath().normalize();
        }
        environment = environment == null ? Map.of() : Map.copyOf(environment);
        commandTimeout = positive(commandTimeout, "commandTimeout");
        tun2socksStartupTimeout = positive(tun2socksStartupTimeout, "tun2socksStartupTimeout");
        if (routeStateFile != null) {
            routeStateFile = routeStateFile.toAbsolutePath().normalize();
        }
    }

    public TunConfig tunConfig(List<Ipv4Cidr> localAddresses) {
        return new TunConfig(interfaceName, adapterGuid, localAddresses, wintunDll);
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
