package com.tailcatmesh.agent.virtual;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Linux system-TUN adapter backed by {@code /dev/net/tun} and {@code ip}. */
public final class LinuxTunRuntime implements TunRuntime {

    public static final Path DEFAULT_TUN_DEVICE = Path.of("/dev/net/tun");
    private static final String IP_COMMAND = "ip";

    private final OsCommandExecutor executor;
    private final HostPlatform platform;
    private final Path tunDevice;
    private final Duration commandTimeout;
    private final Object lock = new Object();
    private TunHandle active;

    public LinuxTunRuntime(OsCommandExecutor executor, Duration commandTimeout) {
        this(executor, HostPlatform.detect(), DEFAULT_TUN_DEVICE, commandTimeout);
    }

    LinuxTunRuntime(OsCommandExecutor executor, HostPlatform platform,
                    Path tunDevice, Duration commandTimeout) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.platform = Objects.requireNonNull(platform, "platform");
        this.tunDevice = Objects.requireNonNull(tunDevice, "tunDevice");
        this.commandTimeout = requirePositive(commandTimeout);
    }

    @Override
    public TunHandle open(TunConfig config) {
        Objects.requireNonNull(config, "config");
        if (platform != HostPlatform.LINUX) {
            throw new TunRuntimeException("Linux TUN runtime requires a Linux host");
        }
        if (!Files.isReadable(tunDevice)) {
            throw new TunRuntimeException("Linux TUN device is not readable: " + tunDevice);
        }
        synchronized (lock) {
            if (active != null && !active.isClosed()) {
                if (active.interfaceName().equals(config.interfaceName())) {
                    return active;
                }
                active.close();
                active = null;
            }

            boolean existed = command(
                    List.of(IP_COMMAND, "link", "show", "dev", config.interfaceName())).exitCode() == 0;
            if (!existed) {
                requireSuccess(List.of(IP_COMMAND, "tuntap", "add", "dev",
                        config.interfaceName(), "mode", "tun"), "create Linux TUN");
            }
            try {
                requireSuccess(List.of(IP_COMMAND, "link", "set", "dev",
                        config.interfaceName(), "up"), "enable Linux TUN");
                for (Ipv4Cidr address : config.localAddresses()) {
                    requireSuccess(List.of(IP_COMMAND, "address", "replace", address.value(),
                            "dev", config.interfaceName()), "configure Linux TUN address");
                }
            } catch (RuntimeException exception) {
                cleanup(config, existed, config.localAddresses());
                throw exception;
            }
            TunHandle handle = new TunHandle(config.interfaceName(), null, !existed,
                    () -> cleanup(config, existed, config.localAddresses()));
            active = handle;
            return handle;
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (active != null) {
                active.close();
                active = null;
            }
        }
    }

    private void cleanup(TunConfig config, boolean existed, List<Ipv4Cidr> addresses) {
        for (Ipv4Cidr address : addresses) {
            bestEffort(List.of(IP_COMMAND, "address", "del", address.value(),
                    "dev", config.interfaceName()));
        }
        if (!existed) {
            bestEffort(List.of(IP_COMMAND, "link", "delete", "dev", config.interfaceName()));
        }
    }

    private OsCommandExecutor.CommandResult command(List<String> command) {
        return executor.execute(command, null, Map.of(), commandTimeout);
    }

    private void requireSuccess(List<String> command, String action) {
        OsCommandExecutor.CommandResult result = command(command);
        if (result.exitCode() != 0) {
            throw new TunRuntimeException(action + " failed"
                    + (result.stderr().isBlank() ? "" : ": " + result.stderr().trim()));
        }
    }

    private void bestEffort(List<String> command) {
        try {
            command(command);
        } catch (RuntimeException ignored) {
            // Interface cleanup is best effort after an Agent crash or failure.
        }
    }

    private static Duration requirePositive(Duration timeout) {
        Objects.requireNonNull(timeout, "commandTimeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("commandTimeout must be positive");
        }
        return timeout;
    }
}
