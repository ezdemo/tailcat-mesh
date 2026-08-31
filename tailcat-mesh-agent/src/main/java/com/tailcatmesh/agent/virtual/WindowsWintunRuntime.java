package com.tailcatmesh.agent.virtual;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Windows Wintun adapter with stable name/GUID and IPv4 address lifecycle. */
public final class WindowsWintunRuntime implements TunRuntime {

    /** No whitespace: official tun2socks places this name in a tun:// URL host. */
    public static final String DEFAULT_INTERFACE_NAME = "TailcatMesh";
    public static final java.util.UUID DEFAULT_ADAPTER_GUID =
            java.util.UUID.fromString("7d2a8db0-6f69-4d26-9d6e-9e0e4d2c6b71");
    /** Must match the official tun2socks Wintun pool name for adapter reuse. */
    public static final String DEFAULT_TUNNEL_TYPE = "tun2socks";

    private final WintunAdapterProvider adapterProvider;
    private final OsCommandExecutor executor;
    private final HostPlatform platform;
    private final Duration commandTimeout;
    private final boolean createAdapterIfMissing;
    private final Object lock = new Object();
    private TunHandle active;

    public WindowsWintunRuntime(WintunAdapterProvider adapterProvider,
                                OsCommandExecutor executor, Duration commandTimeout) {
        this(adapterProvider, executor, HostPlatform.detect(), commandTimeout, true);
    }

    WindowsWintunRuntime(WintunAdapterProvider adapterProvider, OsCommandExecutor executor,
                         HostPlatform platform, Duration commandTimeout) {
        this(adapterProvider, executor, platform, commandTimeout, true);
    }

    WindowsWintunRuntime(WintunAdapterProvider adapterProvider, OsCommandExecutor executor,
                         HostPlatform platform, Duration commandTimeout,
                         boolean createAdapterIfMissing) {
        this.adapterProvider = Objects.requireNonNull(adapterProvider, "adapterProvider");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.platform = Objects.requireNonNull(platform, "platform");
        this.commandTimeout = requirePositive(commandTimeout);
        this.createAdapterIfMissing = createAdapterIfMissing;
    }

    @Override
    public void prepare(TunConfig config) {
        Objects.requireNonNull(config, "config");
        if (platform != HostPlatform.WINDOWS) {
            throw new TunRuntimeException("Wintun runtime requires a Windows host");
        }
        if (config.adapterGuid() == null) {
            throw new TunRuntimeException("Wintun configuration requires a stable adapter GUID");
        }
        removeAdapter(config.adapterGuid(), true);
    }

    @Override
    public TunHandle open(TunConfig config) {
        Objects.requireNonNull(config, "config");
        if (platform != HostPlatform.WINDOWS) {
            throw new TunRuntimeException("Wintun runtime requires a Windows host");
        }
        if (config.adapterGuid() == null) {
            throw new TunRuntimeException("Wintun configuration requires a stable adapter GUID");
        }
        synchronized (lock) {
            if (active != null && !active.isClosed()) {
                if (active.interfaceName().equals(config.interfaceName())
                        && config.adapterGuid().equals(active.adapterGuid())) {
                    return active;
                }
                active.close();
                active = null;
            }

            WintunAdapterProvider.Adapter adapter = openAdapter(config);
            try {
                configureAddresses(config);
            } catch (RuntimeException exception) {
                closeAdapter(adapter);
                throw exception;
            }
            TunHandle handle = new TunHandle(config.interfaceName(), config.adapterGuid(),
                    adapter.createdByAgent(), () -> cleanup(config, adapter));
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

    private void configureAddresses(TunConfig config) {
        List<Ipv4Cidr> addresses = config.localAddresses();
        if (addresses.isEmpty()) {
            return;
        }
        requireSuccess(TunAddressCommandFactory.windowsSetAddress(
                config.interfaceName(), addresses.get(0)), "configure Wintun IPv4 address");
        for (int index = 1; index < addresses.size(); index++) {
            requireSuccess(TunAddressCommandFactory.windowsAddAddress(
                    config.interfaceName(), addresses.get(index)), "configure Wintun IPv4 address");
        }
    }

    private WintunAdapterProvider.Adapter openAdapter(TunConfig config) {
        if (createAdapterIfMissing) {
            return adapterProvider.openOrCreate(config.interfaceName(), config.adapterGuid());
        }
        long deadline = System.nanoTime() + commandTimeout.toNanos();
        RuntimeException lastFailure = null;
        do {
            try {
                return adapterProvider.openExisting(config.interfaceName(), config.adapterGuid());
            } catch (RuntimeException exception) {
                lastFailure = exception;
                try {
                    Thread.sleep(100);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new TunRuntimeException("interrupted while waiting for Wintun adapter", interrupted);
                }
            }
        } while (System.nanoTime() < deadline);
        throw new TunRuntimeException("Wintun adapter did not become ready: "
                + config.interfaceName(), lastFailure);
    }

    private void cleanup(TunConfig config, WintunAdapterProvider.Adapter adapter) {
        for (Ipv4Cidr address : config.localAddresses()) {
            bestEffort(TunAddressCommandFactory.windowsDeleteAddress(
                    config.interfaceName(), address));
        }
        closeAdapter(adapter);
        removeAdapter(config.adapterGuid(), false);
    }

    private void closeAdapter(WintunAdapterProvider.Adapter adapter) {
        try {
            adapterProvider.close(adapter);
        } catch (RuntimeException ignored) {
            // Wintun handles are closed on a best-effort shutdown path.
        }
    }

    private void requireSuccess(List<String> command, String action) {
        OsCommandExecutor.CommandResult result = executor.execute(command, null, Map.of(), commandTimeout);
        if (result.exitCode() != 0) {
            throw new TunRuntimeException(action + " failed"
                    + (result.stderr().isBlank() ? "" : ": " + result.stderr().trim()));
        }
    }

    private void bestEffort(List<String> command) {
        try {
            executor.execute(command, null, Map.of(), commandTimeout);
        } catch (RuntimeException ignored) {
            // Address cleanup is best effort after an Agent crash or failure.
        }
    }

    private void removeAdapter(java.util.UUID adapterGuid, boolean required) {
        List<String> command = List.of(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                "$guid = [Environment]::GetEnvironmentVariable('TAILCAT_MESH_ADAPTER_GUID'); "
                        + "$instanceId = 'SWD\\Wintun\\{' + $guid + '}'; "
                        + "$device = Get-PnpDevice -Class Net "
                        + "-ErrorAction SilentlyContinue | "
                        + "Where-Object { $_.InstanceId -ieq $instanceId }; "
                        + "if ($null -ne $device) { & pnputil.exe /remove-device "
                        + "$device.InstanceId; exit $LASTEXITCODE }");
        try {
            OsCommandExecutor.CommandResult result = executor.execute(
                    command, null, Map.of("TAILCAT_MESH_ADAPTER_GUID", adapterGuid.toString()), commandTimeout);
            if (required && result.exitCode() != 0) {
                throw new TunRuntimeException("remove stale Wintun adapter failed"
                        + (result.stderr().isBlank() ? "" : ": " + result.stderr().trim()));
            }
        } catch (RuntimeException exception) {
            if (required) {
                throw exception;
            }
            // Adapter removal is best effort during shutdown. The launcher
            // performs the same targeted cleanup before the next start.
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
