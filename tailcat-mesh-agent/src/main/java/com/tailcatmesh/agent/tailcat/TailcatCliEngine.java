package com.tailcatmesh.agent.tailcat;

import com.tailcatmesh.agent.tailcat.TailcatProcessSupervisor.CommandResult;
import com.tailcatmesh.agent.tailcat.model.ManagedProcess;
import com.tailcatmesh.agent.tailcat.model.ProcessState;
import com.tailcatmesh.agent.tailcat.model.TailcatCompatibility;
import com.tailcatmesh.agent.tailcat.model.TailcatIdentity;
import com.tailcatmesh.agent.tailcat.model.TailcatIdentityConfig;
import com.tailcatmesh.agent.tailcat.model.TailcatPeerProxyConfig;
import com.tailcatmesh.agent.tailcat.model.TailcatPeerProxyHandle;
import com.tailcatmesh.agent.tailcat.model.TailcatPingResult;
import com.tailcatmesh.agent.tailcat.model.TailcatRuntimeStatus;
import com.tailcatmesh.agent.tailcat.model.TailcatServerConfig;
import com.tailcatmesh.agent.tailcat.model.TailcatServerHandle;
import com.tailcatmesh.agent.tailcat.model.TailcatTokenInfo;
import com.tailcatmesh.agent.tailcat.model.TailcatVersion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * {@link TailcatEngine} implementation backed by the official Tailcat CLI.
 *
 * <p>This class owns command construction, process execution, and parsing. No
 * Agent business service should invoke {@link ProcessBuilder} or the Tailcat
 * executable directly.</p>
 */
public final class TailcatCliEngine implements TailcatEngine, AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(TailcatCliEngine.class);
    private static final Pattern PUBLIC_KEY = Pattern.compile("nodekey:[0-9a-fA-F]{64}");
    private static final Duration PROCESS_STOP_TIMEOUT = Duration.ofSeconds(5);

    private final TailcatCliEngineConfig config;
    private final TailcatCommandFactory commandFactory;
    private final TailcatCliParser parser;
    private final TailcatProcessSupervisor supervisor;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TailcatVersion version;
    private final TailcatCompatibility compatibility;
    private final Object serverLock = new Object();
    private final Object peerProxyLock = new Object();
    private final Map<UUID, TailcatPeerProxyHandle> peerProxies = new HashMap<>();

    private volatile TailcatIdentity identity;
    private volatile TailcatServerHandle serverHandle;
    private volatile Thread serverOutputMonitor;
    private volatile boolean closed;

    public TailcatCliEngine(Path binary) {
        this(TailcatCliEngineConfig.defaults(binary));
    }

    public TailcatCliEngine(TailcatCliEngineConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        if (!Files.isRegularFile(config.binary())) {
            throw new TailcatEngineException("TM-AGENT-001", "official tailcat binary not found");
        }
        this.commandFactory = new TailcatCommandFactory(config.binary());
        this.parser = new TailcatCliParser();
        TailcatProcessSupervisor processSupervisor = new TailcatProcessSupervisor();
        TailcatVersion detectedVersion;
        TailcatCompatibility detectedCompatibility;
        try {
            CommandResult result = processSupervisor.execute(
                    commandFactory.versionCommand(),
                    config.workingDirectory(),
                    config.environment(),
                    config.commandTimeout()
            );
            if (result.exitCode() != 0) {
                throw new TailcatEngineException("TM-AGENT-002", "tailcat --version failed");
            }
            detectedVersion = parser.parseVersion(result.stdout());
            detectedCompatibility = parser.classify(detectedVersion);
            if (detectedCompatibility != TailcatCompatibility.SUPPORTED
                    && !config.allowUnsupportedTailcat()) {
                throw new TailcatEngineException(
                        "TM-AGENT-002",
                        "unsupported tailcat version " + detectedVersion
                );
            }
        } catch (RuntimeException exception) {
            processSupervisor.close();
            throw exception;
        }
        this.supervisor = processSupervisor;
        this.version = detectedVersion;
        this.compatibility = detectedCompatibility;
    }

    /** Creates an Engine using the standard discovery rules. */
    public static TailcatCliEngine discover() {
        return new TailcatCliEngine(TailcatBinaryLocator.require());
    }

    public Path binary() {
        return config.binary();
    }

    public TailcatCompatibility compatibility() {
        return compatibility;
    }

    @Override
    public TailcatVersion getVersion() {
        return version;
    }

    @Override
    public TailcatIdentity ensureIdentity(TailcatIdentityConfig identityConfig) {
        ensureOpen();
        Objects.requireNonNull(identityConfig, "identityConfig");
        if (identityConfig.serverKeyPath().equals(identityConfig.clientKeyPath())) {
            throw new TailcatEngineException("TM-AGENT-003", "server and client key paths must be different");
        }
        ensureKey(identityConfig.serverKeyPath(), false);
        ensureKey(identityConfig.clientKeyPath(), true);
        String publicKey = printPublicKey(identityConfig.clientKeyPath());
        TailcatIdentity result = new TailcatIdentity(
                identityConfig.serverKeyPath(),
                identityConfig.clientKeyPath(),
                publicKey
        );
        identity = result;
        return result;
    }

    /** Executes the official {@code printpub} wrapper for a persisted client key. */
    public String printPublicKey(Path clientKeyPath) {
        ensureOpen();
        CommandResult result = supervisor.execute(
                commandFactory.printPublicKeyCommand(clientKeyPath),
                config.workingDirectory(),
                config.environment(),
                config.commandTimeout()
        );
        if (result.exitCode() != 0) {
            throw new TailcatEngineException("TM-AGENT-003", "tailcat printpub failed");
        }
        String publicKey = result.stdout().trim();
        if (!PUBLIC_KEY.matcher(publicKey).matches()) {
            throw new TailcatEngineException("TM-AGENT-003", "tailcat printpub returned an invalid public key");
        }
        return publicKey;
    }

    @Override
    public TailcatServerHandle startServer(TailcatServerConfig serverConfig) {
        ensureOpen();
        Objects.requireNonNull(serverConfig, "serverConfig");
        if (!Files.isRegularFile(serverConfig.serverKeyPath())) {
            throw new TailcatEngineException("TM-AGENT-003", "Tailcat server key does not exist");
        }

        synchronized (serverLock) {
            if (serverHandle != null && serverHandle.process().state() != ProcessState.STOPPED) {
                throw new TailcatEngineException("TM-AGENT-003", "Tailcat server is already running");
            }
            TailcatProcessSupervisor.ManagedProcessHandle process = supervisor.start(
                    commandFactory.serverCommand(serverConfig),
                    config.workingDirectory(),
                    config.environment(),
                    true
            );
            try {
                String jsonLine = process.awaitStdoutLine(config.startupTimeout());
                String listenAddress = parser.parseServerListenAddress(jsonLine);
                TailcatServerHandle handle = new TailcatServerHandle(
                        process,
                        listenAddress,
                        process.startedAt() == null ? Instant.now() : process.startedAt()
                );
                serverHandle = handle;
                monitorServerOutput(process);
                return handle;
            } catch (TimeoutException exception) {
                process.stop(PROCESS_STOP_TIMEOUT);
                throw new TailcatEngineException("TM-AGENT-003", "Tailcat server did not become ready", exception);
            } catch (RuntimeException exception) {
                process.stop(PROCESS_STOP_TIMEOUT);
                throw exception;
            }
        }
    }

    @Override
    public void stopServer() {
        synchronized (serverLock) {
            if (serverHandle != null) {
                Thread monitor = serverOutputMonitor;
                if (monitor != null) {
                    monitor.interrupt();
                }
                serverHandle.process().stop(PROCESS_STOP_TIMEOUT);
            }
        }
    }

    @Override
    public void restartServer(TailcatServerConfig serverConfig) {
        stopServer();
        startServer(serverConfig);
    }

    @Override
    public TailcatPeerProxyHandle startPeerProxy(UUID peerDeviceId, String connBlob,
                                                  TailcatPeerProxyConfig peerConfig) {
        ensureOpen();
        Objects.requireNonNull(peerDeviceId, "peerDeviceId");
        Objects.requireNonNull(peerConfig, "peerConfig");
        if (connBlob == null || connBlob.isBlank()) {
            throw new TailcatEngineException("TM-AGENT-005", "peer ConnBlob is missing");
        }
        if (!Files.isRegularFile(peerConfig.clientKeyPath())) {
            throw new TailcatEngineException("TM-AGENT-005", "Tailcat client key does not exist");
        }

        synchronized (peerProxyLock) {
            TailcatPeerProxyHandle existing = peerProxies.get(peerDeviceId);
            if (existing != null
                    && existing.connBlob().equals(connBlob)
                    && existing.localSocksHost().equals(peerConfig.listenHost())
                    && (peerConfig.listenPort() == 0 || existing.localSocksPort() == peerConfig.listenPort())
                    && existing.process().state() != ProcessState.STOPPED) {
                return existing;
            }
            if (existing != null) {
                stopPeerProxyLocked(peerDeviceId, existing);
            }

            int requestedPort = peerConfig.listenPort();
            String listenAddress = peerConfig.listenHost() + ":" + requestedPort;
            TailcatProcessSupervisor.ManagedProcessHandle process = null;
            try {
                process = supervisor.start(
                        commandFactory.peerSocksCommand(peerConfig.clientKeyPath(), connBlob, listenAddress),
                        config.workingDirectory(),
                        config.environment(),
                        true
                );
                TailcatCliParser.SocksListenAddress reported = awaitSocksReady(process);
                if (!peerConfig.listenHost().equals(reported.host())
                        || (requestedPort != 0 && requestedPort != reported.port())) {
                    process.stop(PROCESS_STOP_TIMEOUT);
                    throw new TailcatEngineException("TM-AGENT-005",
                            "Tailcat SOCKS listen address did not match the requested loopback address");
                }
                TailcatPeerProxyHandle handle = new TailcatPeerProxyHandle(
                        peerDeviceId,
                        process,
                        reported.host(),
                        reported.port(),
                        connBlob,
                        process.startedAt() == null ? Instant.now() : process.startedAt());
                peerProxies.put(peerDeviceId, handle);
                return handle;
            } catch (TimeoutException exception) {
                if (process != null) {
                    process.stop(PROCESS_STOP_TIMEOUT);
                }
                throw new TailcatEngineException("TM-AGENT-005",
                        "Tailcat SOCKS did not become ready", exception);
            } catch (TailcatEngineException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                throw new TailcatEngineException("TM-AGENT-005",
                        "Tailcat SOCKS failed to start", exception);
            }
        }
    }

    @Override
    public void stopPeerProxy(UUID peerDeviceId) {
        if (peerDeviceId == null) {
            return;
        }
        synchronized (peerProxyLock) {
            TailcatPeerProxyHandle handle = peerProxies.remove(peerDeviceId);
            if (handle != null) {
                handle.process().stop(PROCESS_STOP_TIMEOUT);
            }
        }
    }

    @Override
    public TailcatPingResult ping(String connBlob, Duration timeout) {
        ensureOpen();
        TailcatIdentity localIdentity = identity;
        if (localIdentity == null) {
            throw new TailcatEngineException("TM-AGENT-003", "Tailcat identity has not been initialized");
        }
        CommandResult result = supervisor.execute(
                commandFactory.pingCommand(localIdentity.clientKeyPath(), connBlob, timeout, false),
                config.workingDirectory(),
                config.environment(),
                timeout.plus(config.commandTimeout())
        );
        if (result.exitCode() != 0) {
            return TailcatPingResult.offline("");
        }
        return parser.parsePingOutput(result.stdout());
    }

    @Override
    public TailcatTokenInfo parseToken(String connBlob) {
        ensureOpen();
        CommandResult result = supervisor.execute(
                commandFactory.parseTokenCommand(connBlob),
                config.workingDirectory(),
                config.environment(),
                config.commandTimeout()
        );
        if (result.exitCode() != 0) {
            throw new TailcatEngineException("TM-AGENT-004", "tailcat parse failed");
        }
        return parser.parseTokenJson(result.stdout());
    }

    @Override
    public TailcatRuntimeStatus getRuntimeStatus() {
        TailcatServerHandle handle = serverHandle;
        if (handle == null) {
            return new TailcatRuntimeStatus(ProcessState.STOPPED, null, null, "", 0);
        }
        ManagedProcess managedProcess = handle.process();
        if (managedProcess instanceof TailcatProcessSupervisor.ManagedProcessHandle process) {
            return new TailcatRuntimeStatus(
                    process.state(),
                    handle.listenAddress(),
                    process.exitCode(),
                    process.stderrTail(),
                    process.restartCount()
            );
        }
        return new TailcatRuntimeStatus(managedProcess.state(), handle.listenAddress(), null, "", 0);
    }

    @Override
    public void shutdown() {
        if (closed) {
            return;
        }
        closed = true;
        synchronized (peerProxyLock) {
            for (Map.Entry<UUID, TailcatPeerProxyHandle> entry : peerProxies.entrySet()) {
                entry.getValue().process().stop(PROCESS_STOP_TIMEOUT);
            }
            peerProxies.clear();
        }
        stopServer();
        supervisor.close();
    }

    @Override
    public void close() {
        shutdown();
    }

    private void ensureKey(Path keyPath, boolean client) {
        if (Files.isRegularFile(keyPath)) {
            if (!client && hasAutoSelectedRegion(keyPath)) {
                regenerateFixedRegionServerKey(keyPath);
            }
            return;
        }
        try {
            Path parent = keyPath.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException exception) {
            throw new TailcatEngineException("TM-AGENT-003", "unable to create Tailcat key directory", exception);
        }
        CommandResult result = supervisor.execute(
                commandFactory.genKeyCommand(keyPath, client, !client),
                config.workingDirectory(),
                config.environment(),
                config.commandTimeout()
        );
        if (result.exitCode() != 0 || !Files.isRegularFile(keyPath)) {
            throw new TailcatEngineException("TM-AGENT-003", "tailcat key initialization failed");
        }
    }

    /**
     * M2 generated server keys with Tailcat's default auto region. Rotate that
     * Agent-owned key once so subsequent server restarts keep the same DERP
     * region and therefore the same ConnBlob.
     */
    private boolean hasAutoSelectedRegion(Path keyPath) {
        try {
            JsonNode root = objectMapper.readTree(Files.readString(keyPath));
            JsonNode regionId = root == null ? null : root.path("Public").path("RegionID");
            return regionId == null || regionId.isMissingNode() || regionId.isNull() || regionId.asInt(-1) < 0;
        } catch (IOException | RuntimeException exception) {
            // Let Tailcat validate an otherwise custom/legacy key format; do not
            // overwrite a key that the Engine cannot confidently classify.
            return false;
        }
    }

    private void regenerateFixedRegionServerKey(Path keyPath) {
        CommandResult result = supervisor.execute(
                commandFactory.genKeyCommand(keyPath, false, true, true),
                config.workingDirectory(),
                config.environment(),
                config.commandTimeout()
        );
        if (result.exitCode() != 0 || hasAutoSelectedRegion(keyPath)) {
            throw new TailcatEngineException("TM-AGENT-003",
                    "Tailcat server key could not be pinned to a fixed DERP region");
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Tailcat CLI Engine is shut down");
        }
    }

    private TailcatCliParser.SocksListenAddress awaitSocksReady(
            TailcatProcessSupervisor.ManagedProcessHandle process) throws TimeoutException {
        long deadline = System.nanoTime() + config.startupTimeout().toNanos();
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new TimeoutException("timed out waiting for Tailcat SOCKS readiness");
            }
            try {
                String line = process.awaitStderrLine(Duration.ofNanos(remaining));
                try {
                    return parser.parseSocksListenAddress(line);
                } catch (TailcatEngineException ignored) {
                    // Tailcat may emit timestamped diagnostics before its
                    // readiness line. Only the documented line is relevant.
                }
            } catch (TimeoutException exception) {
                throw exception;
            }
        }
    }

    private void stopPeerProxyLocked(UUID peerDeviceId, TailcatPeerProxyHandle handle) {
        peerProxies.remove(peerDeviceId);
        handle.process().stop(PROCESS_STOP_TIMEOUT);
    }

    /**
     * Consumes JSON emitted after a supervised process restart. Tailcat keeps
     * the same address when a stable server key is reused, but the monitor
     * still treats every emitted address as authoritative and lets the Agent
     * re-upload it if a future Tailcat version changes that behavior.
     */
    private void monitorServerOutput(TailcatProcessSupervisor.ManagedProcessHandle process) {
        Thread previous = serverOutputMonitor;
        if (previous != null) {
            previous.interrupt();
        }
        serverOutputMonitor = Thread.ofVirtual()
                .name("tailcat-server-json-monitor")
                .start(() -> {
                    try {
                        while (!closed) {
                            synchronized (serverLock) {
                                if (serverHandle == null || serverHandle.process() != process) {
                                    return;
                                }
                            }
                            try {
                                String line = process.awaitStdoutLine(Duration.ofMillis(500));
                                String listenAddress = parser.parseServerListenAddress(line);
                                synchronized (serverLock) {
                                    if (serverHandle != null && serverHandle.process() == process
                                            && !serverHandle.listenAddress().equals(listenAddress)) {
                                        serverHandle = new TailcatServerHandle(
                                                process, listenAddress,
                                                serverHandle.startedAt() == null
                                                        ? Instant.now() : serverHandle.startedAt());
                                    }
                                }
                            } catch (TimeoutException exception) {
                                if (process.state() == ProcessState.STOPPED
                                        || (process.state() == ProcessState.STOPPING && !process.isAlive())) {
                                    return;
                                }
                                sleepBriefly();
                            } catch (TailcatEngineException exception) {
                                LOGGER.debug("ignored non-JSON Tailcat server output");
                            }
                        }
                    } finally {
                        if (Thread.currentThread() == serverOutputMonitor) {
                            serverOutputMonitor = null;
                        }
                    }
                });
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
