package com.tailcatmesh.agent.virtual;

import com.tailcatmesh.agent.config.AgentConfig;
import com.tailcatmesh.agent.config.VirtualLanAgentConfig;

import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Creates the platform-specific M7 data plane only when it is explicitly enabled. */
public final class VirtualLanDataPlaneFactory {

    private VirtualLanDataPlaneFactory() {
    }

    public static VirtualLanDataPlaneSupervisor create(AgentConfig agentConfig) {
        Objects.requireNonNull(agentConfig, "agentConfig");
        VirtualLanAgentConfig local = agentConfig.virtualLan();
        if (!local.enabled()) {
            return null;
        }

        HostPlatform platform = HostPlatform.detect();
        ProcessOsCommandExecutor executor = new ProcessOsCommandExecutor();
        try {
            TunRuntime tunRuntime = switch (platform) {
                case WINDOWS -> new WindowsWintunRuntime(
                        new JnaWintunAdapterProvider(local.wintunDll()), executor,
                        platform, local.commandTimeout(), false);
                case LINUX -> new LinuxTunRuntime(
                        executor, platform, LinuxTunRuntime.DEFAULT_TUN_DEVICE,
                        local.commandTimeout());
                case UNSUPPORTED -> throw new TunRuntimeException(
                        "M7 Virtual LAN is supported only on Windows and Linux");
            };
            SystemOsRouteManager routeManager = new SystemOsRouteManager(
                    platform, executor, local.commandTimeout());
            Tun2SocksSupervisor tun2SocksSupervisor = new Tun2SocksSupervisor();
            VirtualLanDataPlaneConfig dataPlaneConfig = new VirtualLanDataPlaneConfig(
                    local.interfaceName(), local.adapterGuid(), local.wintunDll(),
                    local.tun2socksBinary(), local.tun2socksArgumentTemplate(),
                    local.workingDirectory(), sidecarEnvironment(local, platform), local.commandTimeout(),
                    local.tun2socksStartupTimeout(),
                    agentConfig.dataDir().resolve("virtual-lan/routes.tsv"));
            return new VirtualLanDataPlaneSupervisor(
                    dataPlaneConfig, tunRuntime, routeManager, tun2SocksSupervisor,
                    List.of(executor));
        } catch (RuntimeException exception) {
            executor.close();
            throw exception;
        }
    }

    /** Makes a configured Wintun DLL discoverable by the external tun2socks process. */
    private static Map<String, String> sidecarEnvironment(VirtualLanAgentConfig local,
                                                            HostPlatform platform) {
        Map<String, String> environment = new HashMap<>(local.environment());
        if (platform == HostPlatform.WINDOWS && local.wintunDll() != null) {
            Path parent = local.wintunDll().getParent();
            if (parent != null) {
                String inheritedPath = environment.getOrDefault("PATH", System.getenv("PATH"));
                String prefix = parent.toString();
                environment.put("PATH", inheritedPath == null || inheritedPath.isBlank()
                        ? prefix : prefix + File.pathSeparator + inheritedPath);
            }
        }
        return Map.copyOf(environment);
    }
}
