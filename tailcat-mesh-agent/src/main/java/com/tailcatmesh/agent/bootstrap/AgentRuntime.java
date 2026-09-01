package com.tailcatmesh.agent.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tailcatmesh.agent.config.AgentConfig;
import com.tailcatmesh.agent.control.AgentControlClient;
import com.tailcatmesh.agent.control.AgentControlException;
import com.tailcatmesh.agent.forward.LocalForwardException;
import com.tailcatmesh.agent.forward.LocalForwardHandle;
import com.tailcatmesh.agent.forward.LocalForwardManager;
import com.tailcatmesh.agent.forward.PeerSocksEndpoint;
import com.tailcatmesh.agent.identity.AgentState;
import com.tailcatmesh.agent.identity.AgentStateStore;
import com.tailcatmesh.agent.service.ServiceBridge;
import com.tailcatmesh.agent.service.ServiceBridgeHandle;
import com.tailcatmesh.agent.service.ServiceRuntimeConfig;
import com.tailcatmesh.agent.service.TcpServiceBridge;
import com.tailcatmesh.agent.status.AgentLocalStatusServer;
import com.tailcatmesh.agent.status.LocalAgentStatus;
import com.tailcatmesh.agent.status.LocalNetworkStatus;
import com.tailcatmesh.agent.tailcat.TailcatCliEngine;
import com.tailcatmesh.agent.tailcat.TailcatEngine;
import com.tailcatmesh.agent.tailcat.model.ProcessState;
import com.tailcatmesh.agent.tailcat.model.TailcatIdentity;
import com.tailcatmesh.agent.tailcat.model.TailcatIdentityConfig;
import com.tailcatmesh.agent.tailcat.model.TailcatPeerProxyConfig;
import com.tailcatmesh.agent.tailcat.model.TailcatPeerProxyHandle;
import com.tailcatmesh.agent.tailcat.model.TailcatPathType;
import com.tailcatmesh.agent.tailcat.model.TailcatPingResult;
import com.tailcatmesh.agent.tailcat.model.TailcatRuntimeStatus;
import com.tailcatmesh.agent.tailcat.model.TailcatServerConfig;
import com.tailcatmesh.agent.tailcat.model.TailcatServerHandle;
import com.tailcatmesh.agent.virtual.VirtualNetworkManager;
import com.tailcatmesh.protocol.ProtocolEnvelope;
import com.tailcatmesh.protocol.agent.AgentDesiredState;
import com.tailcatmesh.protocol.agent.AgentEnrollmentResponse;
import com.tailcatmesh.protocol.agent.AgentForward;
import com.tailcatmesh.protocol.agent.AgentForwardRuntime;
import com.tailcatmesh.protocol.agent.AgentForwardRuntimeReport;
import com.tailcatmesh.protocol.agent.AgentHeartbeatRequest;
import com.tailcatmesh.protocol.agent.AgentHeartbeatResponse;
import com.tailcatmesh.protocol.agent.AgentPeer;
import com.tailcatmesh.protocol.agent.AgentPeerRuntime;
import com.tailcatmesh.protocol.agent.AgentPeerRuntimeReport;
import com.tailcatmesh.protocol.agent.AgentRuntimeServerRequest;
import com.tailcatmesh.protocol.agent.AgentService;
import com.tailcatmesh.protocol.agent.AgentServiceRuntime;
import com.tailcatmesh.protocol.agent.AgentServiceRuntimeReport;
import com.tailcatmesh.protocol.agent.AgentVirtualNetworkRuntime;
import com.tailcatmesh.protocol.agent.AgentVirtualNetworkRuntimeReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.net.http.WebSocket;

/**
 * Minimal M2 Agent lifecycle: local identity, enrollment, Tailcat runtime,
 * authenticated heartbeat and WebSocket control connection.
 */
public final class AgentRuntime implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentRuntime.class);
    private static final String CONN_BLOB_HASH_PREFIX = "sha256:";
    private static final long DESIRED_STATE_DEBOUNCE_SECONDS = 2;

    private final AgentConfig config;
    private final TailcatEngine tailcatEngine;
    private final AgentControlClient controlClient;
    private final AgentStateStore stateStore;
    private final ServiceBridge serviceBridge;
    private final LocalForwardManager forwardManager;
    private final VirtualNetworkManager virtualNetworkManager;
    private final String agentVersion;
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final ScheduledExecutorService heartbeatExecutor =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "tailcat-mesh-heartbeat");
                thread.setDaemon(true);
                return thread;
            });
    private final ScheduledExecutorService peerPingExecutor =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "tailcat-mesh-peer-ping");
                thread.setDaemon(true);
                return thread;
            });
    private final CountDownLatch termination = new CountDownLatch(1);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CompletableFuture<Void> startupCompletion = new CompletableFuture<>();

    private volatile AgentState state;
    private volatile TailcatIdentity identity;
    private volatile TailcatServerHandle serverHandle;
    private volatile WebSocket webSocket;
    private volatile ScheduledFuture<?> heartbeatTask;
    private volatile ScheduledFuture<?> peerPingTask;
    private volatile String serverConnBlobHash;
    private volatile String controlPlaneStatus = "PENDING";
    private volatile String agentState = "STARTING";
    private volatile boolean initialSyncFinished;
    private volatile Thread startupThread;
    private volatile long desiredRevision;
    private volatile AgentDesiredState desiredState;
    private volatile AgentDesiredState pendingDesiredStateFallback;
    private volatile ScheduledFuture<?> desiredStateTask;
    private volatile AgentLocalStatusServer localStatusServer;
    private volatile TailcatServerConfig serverConfig;
    private volatile boolean runtimeServerReported;
    private volatile boolean serviceRuntimeReported;
    private volatile boolean peerRuntimeReported;
    private volatile boolean forwardRuntimeReported;
    private volatile boolean virtualNetworkRuntimeReported;
    private volatile List<AgentForwardRuntime> lastReportedForwardRuntimes = List.of();
    private volatile List<AgentVirtualNetworkRuntime> lastReportedVirtualNetworkRuntimes = List.of();
    private volatile PrintWriter output;
    private final Object serverReconcileLock = new Object();
    private final Object desiredStateDebounceLock = new Object();
    private final Map<UUID, AgentService> appliedServices = new HashMap<>();
    private final Map<UUID, ServiceBridgeHandle> serviceHandles = new HashMap<>();
    private final Map<UUID, AgentPeer> appliedPeers = new HashMap<>();
    private final Map<UUID, TailcatPeerProxyHandle> peerHandles = new HashMap<>();
    private final Map<UUID, AgentPeerRuntime> peerRuntimes = new HashMap<>();
    private final Map<UUID, AgentForward> appliedForwards = new HashMap<>();
    private final Map<UUID, AgentForwardRuntime> forwardRuntimes = new HashMap<>();

    public AgentRuntime(AgentConfig config, TailcatCliEngine engine, AgentControlClient controlClient,
                        AgentStateStore stateStore, String agentVersion) {
        this(config, (TailcatEngine) engine, controlClient, stateStore, new TcpServiceBridge(), agentVersion);
    }

    public AgentRuntime(AgentConfig config, TailcatEngine tailcatEngine, AgentControlClient controlClient,
                        AgentStateStore stateStore, String agentVersion) {
        this(config, tailcatEngine, controlClient, stateStore, new TcpServiceBridge(), agentVersion);
    }

    public AgentRuntime(AgentConfig config, TailcatEngine tailcatEngine, AgentControlClient controlClient,
                        AgentStateStore stateStore, ServiceBridge serviceBridge, String agentVersion) {
        this.config = Objects.requireNonNull(config, "config");
        this.tailcatEngine = Objects.requireNonNull(tailcatEngine, "tailcatEngine");
        this.controlClient = Objects.requireNonNull(controlClient, "controlClient");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.serviceBridge = Objects.requireNonNull(serviceBridge, "serviceBridge");
        this.forwardManager = new LocalForwardManager(this::resolvePeerSocks);
        this.virtualNetworkManager = new VirtualNetworkManager(config, tailcatEngine);
        if (agentVersion == null || agentVersion.isBlank()) {
            throw new IllegalArgumentException("agentVersion must not be blank");
        }
        this.agentVersion = agentVersion;
    }

    /** Starts the lifecycle and returns the non-sensitive startup projection. */
    public StartupResult start(String enrollmentToken, PrintWriter output) {
        if (closed.get()) {
            throw new IllegalStateException("Agent runtime is closed");
        }
        this.output = Objects.requireNonNull(output, "output");
        try {
            try {
                java.nio.file.Files.createDirectories(config.dataDir());
            } catch (java.io.IOException exception) {
                throw new com.tailcatmesh.agent.config.AgentConfigException(
                        "TM-AGENT-010", "unable to create Agent data directory", exception);
            }
            identity = tailcatEngine.ensureIdentity(new TailcatIdentityConfig(
                    config.serverKeyPath(), config.clientKeyPath()));
            state = loadOrEnroll(enrollmentToken, identity);

            // The Desktop only needs a trustworthy loopback status channel to
            // become responsive. Network reconciliation can involve several
            // long-lived child processes and must not block the Agent bootstrap
            // thread or make one optional component failure fatal to the whole
            // runtime.
            localStatusServer = new AgentLocalStatusServer(
                    config.dataDir(), this::localStatus, this::reconnect, this::close);
            localStatusServer.start();
            agentState = "RUNNING";
            // From this point on the Agent process is ready to serve status and
            // control requests. Tailcat/Peer/TUN work is component startup,
            // not a reason to keep the whole Desktop in a blocked state.
            initialSyncFinished = true;
            heartbeatTask = heartbeatExecutor.scheduleAtFixedRate(this::heartbeatSafely,
                    config.heartbeatInterval().toSeconds(), config.heartbeatInterval().toSeconds(), TimeUnit.SECONDS);
            peerPingTask = peerPingExecutor.scheduleAtFixedRate(this::peerPingSafely,
                    0, config.peerPingInterval().toSeconds(), TimeUnit.SECONDS);

            startupThread = Thread.ofVirtual()
                    .name("tailcat-mesh-agent-startup")
                    .start(this::initializeRuntimeInBackground);
            output.println("Tailcat Mesh Agent starting; device=" + state.deviceId());
            output.println("Local status channel is ready; network components are starting in the background.");
            output.flush();
            return new StartupResult(state.deviceId(), "STARTING", null);
        } catch (RuntimeException exception) {
            close();
            throw exception;
        }
    }

    /** Runs the foreground client until Ctrl+C or an explicit close. */
    public int run(String enrollmentToken, boolean once, PrintWriter output) {
        start(enrollmentToken, output);
        if (once) {
            awaitInitialStartup();
            close();
            return 0;
        }
        Thread shutdownHook = new Thread(this::close, "tailcat-mesh-agent-shutdown");
        try {
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        } catch (IllegalStateException ignored) {
            // JVM shutdown has already started; the normal close path remains safe.
        }
        try {
            termination.await();
            return 0;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return 130;
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // JVM shutdown is already in progress.
            }
        }
    }

    public Optional<AgentState> state() {
        return Optional.ofNullable(state);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            termination.countDown();
            return;
        }
        Thread startup = startupThread;
        if (startup != null) {
            startup.interrupt();
        }
        startupCompletion.complete(null);
        ScheduledFuture<?> task = heartbeatTask;
        if (task != null) {
            task.cancel(false);
        }
        ScheduledFuture<?> peerTask = peerPingTask;
        if (peerTask != null) {
            peerTask.cancel(false);
        }
        synchronized (desiredStateDebounceLock) {
            pendingDesiredStateFallback = null;
            ScheduledFuture<?> desiredTask = desiredStateTask;
            desiredStateTask = null;
            if (desiredTask != null) {
                desiredTask.cancel(false);
            }
        }
        heartbeatExecutor.shutdownNow();
        peerPingExecutor.shutdownNow();
        AgentLocalStatusServer local = localStatusServer;
        localStatusServer = null;
        if (local != null) {
            local.close();
        }
        WebSocket socket = webSocket;
        if (socket != null) {
            try {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "agent stopping")
                        .orTimeout(2, TimeUnit.SECONDS).join();
            } catch (RuntimeException ignored) {
                // The control plane may already be gone during shutdown.
            }
        }
        AgentState currentState = state;
        if (currentState != null && serverHandle != null) {
            try {
                reportRuntimeServer(false);
            } catch (RuntimeException exception) {
                LOGGER.debug("could not report Agent shutdown to control plane");
            }
        }
        List<AgentVirtualNetworkRuntime> stoppedVirtualNetworks =
                virtualNetworkManager.stopAll();
        try {
            if (!stoppedVirtualNetworks.isEmpty()) {
                try {
                    reportVirtualNetworkRuntimes(stoppedVirtualNetworks);
                } catch (RuntimeException exception) {
                    LOGGER.debug("could not report virtual-network shutdown to control plane");
                }
            }
        } finally {
            virtualNetworkManager.close();
        }
        try {
            reportServiceRuntimes(stoppedServiceRuntimes());
        } catch (RuntimeException exception) {
            LOGGER.debug("could not report ServiceBridge shutdown to control plane");
        }
        List<AgentPeerRuntime> stoppedPeers = stoppedPeerRuntimes();
        if (!stoppedPeers.isEmpty()) {
            try {
                reportPeerRuntimes(stoppedPeers);
            } catch (RuntimeException exception) {
                LOGGER.debug("could not report Peer SOCKS shutdown to control plane");
            }
        }
        List<AgentForwardRuntime> stoppedForwards = stoppedForwardRuntimes();
        if (!stoppedForwards.isEmpty()) {
            try {
                reportForwardRuntimes(stoppedForwards);
            } catch (RuntimeException exception) {
                LOGGER.debug("could not report Local Forward shutdown to control plane");
            }
        }
        forwardManager.close();
        synchronized (serverReconcileLock) {
            for (UUID peerDeviceId : new ArrayList<>(peerHandles.keySet())) {
                try {
                    tailcatEngine.stopPeerProxy(peerDeviceId);
                } catch (RuntimeException exception) {
                    LOGGER.debug("could not stop Peer SOCKS cleanly");
                }
            }
            peerHandles.clear();
        }
        try {
            tailcatEngine.stopServer();
        } catch (RuntimeException exception) {
            LOGGER.debug("could not stop Tailcat server cleanly", exception);
        } finally {
            serviceBridge.close();
            tailcatEngine.shutdown();
            termination.countDown();
        }
    }

    private AgentState loadOrEnroll(String enrollmentToken, TailcatIdentity localIdentity) {
        Optional<AgentState> saved = stateStore.load();
        if (saved.isPresent()) {
            controlPlaneStatus = "CONNECTING";
            return saved.get();
        }
        if (enrollmentToken == null || enrollmentToken.isBlank()) {
            throw new AgentControlException("TM-CTRL-002", 400,
                    "this Agent is not enrolled; provide --token once");
        }
        AgentEnrollmentResponse enrolled = controlClient.enroll(
                enrollmentToken,
                localHostname(),
                normalizedOs(),
                System.getProperty("os.arch", "unknown"),
                agentVersion,
                tailcatEngine.getVersion().toString(),
                localIdentity.clientPublicKey(),
                config.deviceName()
        );
        if (enrolled == null || enrolled.deviceId() == null
                || enrolled.agentCredential() == null || enrolled.agentCredential().isBlank()) {
            throw new AgentControlException("TM-CTRL-004", 0, "control-plane enrollment response is incomplete");
        }
        AgentState result = new AgentState(enrolled.deviceId(), enrolled.agentCredential(), Instant.now());
        controlPlaneStatus = enrolled.status() == null || enrolled.status().isBlank()
                ? "PENDING" : enrolled.status();
        stateStore.save(result);
        return result;
    }

    /** Performs slow control-plane and component reconciliation outside bootstrap. */
    private void initializeRuntimeInBackground() {
        try {
            if (closed.get()) {
                return;
            }
            try {
                AgentDesiredState initialDesiredState = controlClient.desiredState(state.agentCredential());
                if (!closed.get()) {
                    // A successful desired-state response proves that the
                    // Control Server is reachable. The subsequent component
                    // reconciliation may still take longer, especially when a
                    // peer or Wintun is unavailable.
                    controlPlaneStatus = "ONLINE";
                    // A failed Peer SOCKS or virtual LAN must be represented in
                    // its component runtime, not terminate the Agent process.
                    reconcileDesiredState(initialDesiredState, false);
                }
            } catch (RuntimeException exception) {
                markControlPlaneFailure("initial desired-state sync failed", exception);
            }

            if (!closed.get()) {
                try {
                    sendHeartbeat();
                } catch (RuntimeException exception) {
                    markControlPlaneFailure("initial heartbeat failed", exception);
                }
            }
            // The local API was already available and the Agent process is
            // healthy even when an optional data-plane component is degraded.
            announceStartupResult();
            if (!closed.get()) {
                openWebSocket();
            }
        } finally {
            startupCompletion.complete(null);
            startupThread = null;
        }
    }

    private void awaitInitialStartup() {
        try {
            startupCompletion.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException ignored) {
            // Startup failures are exposed through the local status channel;
            // --once remains a best-effort diagnostic command.
        }
    }

    private void announceStartupResult() {
        PrintWriter currentOutput = output;
        AgentState currentState = state;
        if (currentOutput == null || currentState == null || closed.get()) {
            return;
        }
        String status = currentServerStatus();
        currentOutput.println("Tailcat Mesh Agent startup finished; device="
                + currentState.deviceId() + ", status=" + status);
        if ("PENDING".equals(status)) {
            currentOutput.println("Waiting for administrator approval; keep this process running.");
        }
        if (serverHandle != null) {
            currentOutput.println("Tailcat server is running; ConnBlob is stored by the control plane (hash="
                    + serverConnBlobHash + ")");
        } else {
            currentOutput.println("Tailcat server is not ready; the next heartbeat will retry it.");
        }
        currentOutput.flush();
    }

    /** Refreshes desired state through the authenticated control channel. */
    public void reconnect() {
        if (closed.get() || state == null) {
            throw new IllegalStateException("Agent is not running");
        }
        try {
            reconcileDesiredState(controlClient.desiredState(state.agentCredential()), false);
            sendHeartbeat();
        } catch (RuntimeException exception) {
            markControlPlaneFailure("manual reconnect failed", exception);
            throw exception;
        }
    }

    /** Returns the non-secret projection used by the loopback Desktop API. */
    public LocalAgentStatus localStatus() {
        TailcatRuntimeStatus runtimeStatus;
        try {
            runtimeStatus = tailcatEngine.getRuntimeStatus();
        } catch (RuntimeException exception) {
            runtimeStatus = new TailcatRuntimeStatus(ProcessState.FAILED, null, null,
                    safeError(exception), 0);
        }
        boolean tailcatRunning = runtimeStatus.state() == ProcessState.RUNNING;
        String controlStatus = controlPlaneStatus == null ? "UNKNOWN" : controlPlaneStatus;
        String status;
        if (!initialSyncFinished || "CONNECTING".equalsIgnoreCase(controlStatus)) {
            status = "CONNECTING";
        } else if (isControlPlaneOffline(controlStatus)) {
            status = "RECONNECTING";
        } else if (tailcatRunning) {
            status = "ONLINE".equalsIgnoreCase(controlStatus) ? "CONNECTED" : "PENDING";
        } else {
            // The Agent and Control Server can be ready while Tailcat is still
            // starting or one optional data-plane component is retrying.
            status = "RECONNECTING";
        }

        Map<UUID, AgentVirtualNetworkRuntime> runtimeByNetwork = new HashMap<>();
        for (AgentVirtualNetworkRuntime runtime : virtualNetworkManager.snapshot()) {
            runtimeByNetwork.put(runtime.networkId(), runtime);
        }
        List<LocalNetworkStatus> networks = desiredState == null ? List.of()
                : desiredState.virtualNetworks().stream()
                .map(network -> {
                    AgentVirtualNetworkRuntime runtime = runtimeByNetwork.get(network.networkId());
                    String networkStatus = runtime == null ? "PENDING" : runtime.status();
                    String error = runtime == null ? null : runtime.lastError();
                    return new LocalNetworkStatus(network.networkId(), network.name(), network.cidr(),
                            network.virtualIpv4(), networkStatus, null, error);
                })
                .toList();
        String lastError = tailcatRunning ? null : safeError(runtimeStatus.stderrTail());
        return new LocalAgentStatus(
                status,
                controlStatus,
                state == null ? null : state.deviceId(),
                configuredDeviceName(),
                config.serverUrl().toString(),
                agentState,
                ProcessHandle.current().pid(),
                safeTailcatVersion(),
                runtimeStatus.state().name(),
                networks,
                lastError,
                Instant.now());
    }

    private void sendHeartbeat() {
        if (state == null) {
            return;
        }
        runMaintenanceSafely("Tailcat runtime refresh", this::refreshRuntimeState);
        runMaintenanceSafely("ServiceBridge runtime refresh", this::refreshServiceRuntime);
        runMaintenanceSafely("Local Forward runtime refresh", this::refreshForwardRuntime);
        runMaintenanceSafely("virtual-network runtime refresh", this::refreshVirtualNetworkRuntime);
        TailcatRuntimeStatus runtimeStatus = safeRuntimeStatus();
        AgentHeartbeatResponse response = controlClient.heartbeat(
                state.agentCredential(), new AgentHeartbeatRequest(
                        agentVersion,
                        tailcatEngine.getVersion().toString(),
                        desiredRevision,
                        serverHandle != null && runtimeStatus.state() == ProcessState.RUNNING,
                serverConnBlobHash,
                        readyServiceCount(),
                        readyForwardCount(),
                        Instant.now()
                ));
        if (response != null && output != null && !response.status().equals(currentServerStatus())) {
            controlPlaneStatus = response.status();
            output.println("Control-plane device status: " + response.status());
            output.flush();
        } else if (response != null) {
            controlPlaneStatus = response.status();
        }
        if (response != null && response.desiredRevision() > desiredRevision) {
            scheduleDesiredStateRefresh(null);
        }
    }

    private void heartbeatSafely() {
        if (closed.get()) {
            return;
        }
        try {
            sendHeartbeat();
        } catch (RuntimeException exception) {
            markControlPlaneFailure("control-plane heartbeat failed", exception);
        }
    }

    private void openWebSocket() {
        WebSocket.Listener listener = new WebSocket.Listener() {
            private final StringBuilder message = new StringBuilder();

            @Override
            public void onOpen(WebSocket webSocket) {
                webSocket.request(1);
                sendHello(webSocket);
            }

            @Override
            public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                message.append(data);
                if (last) {
                    acceptControlMessage(message.toString());
                    message.setLength(0);
                }
                webSocket.request(1);
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                LOGGER.debug("control WebSocket closed: {}", statusCode);
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public void onError(WebSocket webSocket, Throwable error) {
                LOGGER.warn("control WebSocket failed; heartbeat remains active");
            }
        };
        try {
            webSocket = controlClient.openWebSocket(state.agentCredential(), listener)
                    .get(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            LOGGER.warn("interrupted while opening control WebSocket");
        } catch (TimeoutException | CompletionException exception) {
            LOGGER.warn("control WebSocket unavailable; heartbeat remains active");
        } catch (Exception exception) {
            LOGGER.warn("control WebSocket unavailable; heartbeat remains active");
        }
    }

    private void sendHello(WebSocket socket) {
        try {
            ObjectNode payload = objectMapper.createObjectNode()
                    .put("deviceId", state.deviceId().toString())
                    .put("agentVersion", agentVersion)
                    .put("tailcatVersion", tailcatEngine.getVersion().toString());
            String envelope = objectMapper.writeValueAsString(ProtocolEnvelope.of("HELLO", payload));
            socket.sendText(envelope, true);
        } catch (Exception exception) {
            LOGGER.debug("could not send Agent HELLO envelope");
        }
    }

    private void acceptControlMessage(String message) {
        try {
            JsonNode envelope = objectMapper.readTree(message);
            ProtocolEnvelope protocolEnvelope = objectMapper.treeToValue(envelope, ProtocolEnvelope.class);
            if ("SYNC_DESIRED_STATE".equals(protocolEnvelope.type())) {
                long notifiedRevision = protocolEnvelope.payload().path("revision").asLong(desiredRevision);
                if (notifiedRevision >= desiredRevision) {
                    AgentDesiredState notifiedState = objectMapper.treeToValue(
                            protocolEnvelope.payload(), AgentDesiredState.class);
                    scheduleDesiredStateRefresh(notifiedState);
                }
            }
        } catch (Exception exception) {
            LOGGER.warn("ignored invalid desired state from control plane");
        }
    }

    /**
     * Coalesces rapid control-plane changes and refreshes the complete state
     * from REST after the M3 debounce window. The WS payload is only a
     * fail-closed fallback for an Agent that has just been disabled and can no
     * longer authenticate the REST request.
     */
    private void scheduleDesiredStateRefresh(AgentDesiredState fallback) {
        if (closed.get()) {
            return;
        }
        synchronized (desiredStateDebounceLock) {
            if (closed.get()) {
                return;
            }
            if (fallback != null && pendingDesiredStateFallback != null
                    && fallback.revision() < pendingDesiredStateFallback.revision()) {
                return;
            }
            if (fallback != null) {
                pendingDesiredStateFallback = fallback;
            }
            ScheduledFuture<?> previous = desiredStateTask;
            if (previous != null) {
                previous.cancel(false);
            }
            try {
                desiredStateTask = heartbeatExecutor.schedule(
                        this::applyPendingDesiredState,
                        DESIRED_STATE_DEBOUNCE_SECONDS,
                        TimeUnit.SECONDS);
            } catch (RejectedExecutionException ignored) {
                // Close raced with a control-plane notification.
                desiredStateTask = null;
            }
        }
    }

    private void applyPendingDesiredState() {
        AgentDesiredState fallback;
        synchronized (desiredStateDebounceLock) {
            fallback = pendingDesiredStateFallback;
            pendingDesiredStateFallback = null;
            desiredStateTask = null;
        }
        if (closed.get() || state == null) {
            return;
        }
        try {
            reconcileDesiredState(controlClient.desiredState(state.agentCredential()), false);
        } catch (RuntimeException exception) {
            if (fallback != null) {
                // A disabled Agent can no longer authenticate REST calls; the
                // authenticated WS projection still tells it to fail closed.
                reconcileDesiredState(fallback, false);
            } else {
                LOGGER.warn("could not refresh newer desired state; will retry on the next heartbeat");
            }
        }
    }

    /** Applies the complete desired state; the REST response remains the source of truth. */
    private void reconcileDesiredState(AgentDesiredState candidate, boolean failOnError) {
        AgentDesiredState next = normalizeDesiredState(candidate);
        try {
            boolean shouldReportRuntime = false;
            boolean shouldReportServices = false;
            boolean shouldReportPeers = false;
            boolean shouldReportForwards = false;
            boolean shouldReportVirtualNetworks = false;
            synchronized (serverReconcileLock) {
                if (!failOnError && desiredState != null && next.revision() < desiredRevision) {
                    return;
                }
                boolean hadServices = !appliedServices.isEmpty();
                boolean hadPeers = !appliedPeers.isEmpty();
                boolean hadForwards = !appliedForwards.isEmpty();
                List<AgentVirtualNetworkRuntime> previousVirtualNetworkRuntimes =
                        virtualNetworkManager.snapshot();
                boolean servicesChanged = reconcileServiceBridges(next.services());
                TailcatServerConfig nextConfig = new TailcatServerConfig(
                        identity.serverKeyPath(),
                        activeServicePorts(),
                        next.allowedClientPublicKeys(),
                        config.fullAddress(),
                        config.derpMapUrl());
                desiredState = next;
                desiredRevision = next.revision();
                boolean alreadyApplied = serverHandle != null
                        && serverConfig != null
                        && serverConfig.equals(nextConfig)
                        && serverHandle.process().state() == ProcessState.RUNNING;
                if (!alreadyApplied) {
                    if (serverHandle != null && serverHandle.process().state() != ProcessState.STOPPED) {
                        tailcatEngine.stopServer();
                    }
                    serverHandle = tailcatEngine.startServer(nextConfig);
                    serverConfig = nextConfig;
                    shouldReportRuntime = true;
                } else if (!runtimeServerReported) {
                    shouldReportRuntime = true;
                }
                boolean peersChanged = reconcilePeerProxies(next.peers());
                boolean forwardsChanged = reconcileLocalForwards(next.forwards());
                List<AgentVirtualNetworkRuntime> currentVirtualNetworkRuntimes =
                        virtualNetworkManager.reconcile(next.virtualNetworks());
                shouldReportServices = servicesChanged || !next.services().isEmpty() && !serviceRuntimeReported
                        || next.services().isEmpty() && hadServices;
                shouldReportPeers = peersChanged || !next.peers().isEmpty() && !peerRuntimeReported
                        || next.peers().isEmpty() && hadPeers;
                shouldReportForwards = forwardsChanged
                        || !next.forwards().isEmpty() && !forwardRuntimeReported
                        || next.forwards().isEmpty() && hadForwards;
                shouldReportVirtualNetworks = !previousVirtualNetworkRuntimes.equals(currentVirtualNetworkRuntimes)
                        || !next.virtualNetworks().isEmpty() && !virtualNetworkRuntimeReported
                        || next.virtualNetworks().isEmpty() && !previousVirtualNetworkRuntimes.isEmpty();
            }
            if (shouldReportRuntime) {
                reportRuntimeServer(true);
            }
            if (shouldReportServices) {
                reportServiceRuntimes(serviceRuntimes());
            }
            if (shouldReportPeers) {
                reportPeerRuntimes(peerRuntimeSnapshot());
            }
            if (shouldReportForwards) {
                reportForwardRuntimes(forwardRuntimeSnapshot());
            }
            if (shouldReportVirtualNetworks) {
                reportVirtualNetworkRuntimes(virtualNetworkManager.snapshot());
            }
        } catch (RuntimeException exception) {
            if (failOnError) {
                throw exception;
            }
            LOGGER.warn("Tailcat Server desired-state reconcile failed; will retry on the next sync: {}",
                    exception.getMessage(), exception);
        }
    }

    /** Reconciles Java ServiceBridges before the Tailcat Server configuration. */
    private boolean reconcileServiceBridges(List<AgentService> desired) {
        Map<UUID, AgentService> desiredById = new HashMap<>();
        for (AgentService service : desired) {
            if (service == null || desiredById.put(service.serviceId(), service) != null) {
                throw new AgentControlException("TM-CTRL-004", 400,
                        "desired-state contains duplicate or null service entries");
            }
        }

        boolean changed = false;
        for (UUID serviceId : new ArrayList<>(appliedServices.keySet())) {
            AgentService old = appliedServices.get(serviceId);
            AgentService next = desiredById.get(serviceId);
            if (next == null || !next.enabled() || !next.equals(old)) {
                if (serviceHandles.remove(serviceId) != null) {
                    serviceBridge.stop(serviceId);
                }
                changed = true;
            }
        }

        for (AgentService service : desiredById.values()) {
            if (!service.enabled()) {
                continue;
            }
            ServiceBridgeHandle currentHandle = serviceHandles.get(service.serviceId());
            AgentService old = appliedServices.get(service.serviceId());
            if (currentHandle != null && service.equals(old) && currentHandle.isRunning()) {
                continue;
            }
            if (currentHandle != null) {
                serviceHandles.remove(service.serviceId());
                serviceBridge.stop(service.serviceId());
            }
            ServiceBridgeHandle started = serviceBridge.start(new ServiceRuntimeConfig(
                    service.serviceId(),
                    "127.0.0.1",
                    0,
                    service.targetHost(),
                    service.targetPort(),
                    Duration.ofSeconds(5),
                    Duration.ofMinutes(30)));
            serviceHandles.put(service.serviceId(), started);
            changed = true;
        }

        appliedServices.clear();
        appliedServices.putAll(desiredById);
        serviceHandles.keySet().removeIf(serviceId -> {
            AgentService service = desiredById.get(serviceId);
            return service == null || !service.enabled();
        });
        if (desiredById.isEmpty()) {
            serviceRuntimeReported = false;
        }
        return changed;
    }

    /** Reconciles one long-lived official Tailcat SOCKS process per Peer. */
    private boolean reconcilePeerProxies(List<AgentPeer> desired) {
        Map<UUID, AgentPeer> desiredById = new HashMap<>();
        for (AgentPeer peer : desired) {
            if (peer == null || peerDeviceIsLocal(peer) || desiredById.put(peer.peerDeviceId(), peer) != null) {
                throw new AgentControlException("TM-CTRL-004", 400,
                        "desired-state contains duplicate, null, or self peer entries");
            }
        }

        boolean changed = false;
        for (UUID peerDeviceId : new ArrayList<>(appliedPeers.keySet())) {
            AgentPeer previous = appliedPeers.get(peerDeviceId);
            AgentPeer next = desiredById.get(peerDeviceId);
            if (next == null || !Objects.equals(previous, next)) {
                if (peerHandles.remove(peerDeviceId) != null) {
                    tailcatEngine.stopPeerProxy(peerDeviceId);
                }
                peerRuntimes.remove(peerDeviceId);
                changed = true;
            }
        }

        for (AgentPeer peer : desiredById.values()) {
            TailcatPeerProxyHandle current = peerHandles.get(peer.peerDeviceId());
            if (peer.connBlob() == null) {
                if (current != null) {
                    peerHandles.remove(peer.peerDeviceId());
                    tailcatEngine.stopPeerProxy(peer.peerDeviceId());
                    changed = true;
                }
                peerRuntimes.put(peer.peerDeviceId(), peerRuntime(
                        peer, "UNKNOWN", TailcatPathType.UNKNOWN, -1, null, null,
                        "Peer ConnBlob is not available yet"));
                continue;
            }
            if (current != null
                    && current.connBlob().equals(peer.connBlob())
                    && current.process().state() != ProcessState.STOPPED) {
                continue;
            }
            if (current != null) {
                peerHandles.remove(peer.peerDeviceId());
                tailcatEngine.stopPeerProxy(peer.peerDeviceId());
            }
            try {
                TailcatPeerProxyHandle started = tailcatEngine.startPeerProxy(
                        peer.peerDeviceId(), peer.connBlob(),
                        new TailcatPeerProxyConfig(identity.clientKeyPath(), "127.0.0.1", 0));
                peerHandles.put(peer.peerDeviceId(), started);
                peerRuntimes.put(peer.peerDeviceId(), peerRuntime(
                        peer, "UNKNOWN", TailcatPathType.UNKNOWN, -1, null, null,
                        null));
            } catch (RuntimeException exception) {
                peerHandles.remove(peer.peerDeviceId());
                peerRuntimes.put(peer.peerDeviceId(), peerRuntime(
                        peer, "DEGRADED", TailcatPathType.UNKNOWN, -1, null, null,
                        safeError(exception)));
                LOGGER.warn(
                        "Peer SOCKS could not start for peer {} (connBlobHash={}); "
                                + "path checks will retry: {}",
                        peer.peerDeviceId(),
                        hashConnBlob(peer.connBlob()),
                        exception.getMessage(),
                        exception);
            }
            changed = true;
        }

        appliedPeers.clear();
        appliedPeers.putAll(desiredById);
        if (desiredById.isEmpty()) {
            peerRuntimeReported = false;
        }
        return changed;
    }

    /** Reconciles fixed-port loopback listeners after their Peer SOCKS processes. */
    private boolean reconcileLocalForwards(List<AgentForward> desired) {
        Map<UUID, AgentForward> desiredById = new HashMap<>();
        for (AgentForward forward : desired) {
            if (forward == null || peerDeviceIsLocal(forward.peerDeviceId())
                    || desiredById.put(forward.forwardId(), forward) != null) {
                throw new AgentControlException("TM-CTRL-004", 400,
                        "desired-state contains duplicate, null, or self Local Forward entries");
            }
        }

        boolean changed = false;
        for (UUID forwardId : new ArrayList<>(appliedForwards.keySet())) {
            AgentForward previous = appliedForwards.get(forwardId);
            AgentForward next = desiredById.get(forwardId);
            if (next == null || !next.enabled() || !Objects.equals(previous, next)) {
                forwardManager.stop(forwardId);
                forwardRuntimes.remove(forwardId);
                changed = true;
            }
        }

        for (AgentForward forward : desiredById.values()) {
            if (!forward.enabled()) {
                forwardManager.stop(forward.forwardId());
                forwardRuntimes.put(forward.forwardId(),
                        new AgentForwardRuntime(forward.forwardId(), "STOPPED", null, null));
                continue;
            }
            LocalForwardHandle current = forwardManager.handle(forward.forwardId()).orElse(null);
            AgentForward previous = appliedForwards.get(forward.forwardId());
            if (current != null && forward.equals(previous) && current.isRunning()) {
                forwardRuntimes.put(forward.forwardId(), current.runtime());
                continue;
            }
            if (current != null) {
                forwardManager.stop(forward.forwardId());
            }
            try {
                LocalForwardHandle started = forwardManager.start(forward);
                forwardRuntimes.put(forward.forwardId(), started.runtime());
            } catch (LocalForwardException exception) {
                forwardRuntimes.put(forward.forwardId(), new AgentForwardRuntime(
                        forward.forwardId(), "ERROR", exception.code(), safeError(exception)));
                LOGGER.warn("Local Forward {} could not start: {}", forward.forwardId(), exception.getMessage());
            } catch (RuntimeException exception) {
                forwardRuntimes.put(forward.forwardId(), new AgentForwardRuntime(
                        forward.forwardId(), "ERROR", "TM-AGENT-008", safeError(exception)));
                LOGGER.warn("Local Forward {} could not start", forward.forwardId());
            }
            changed = true;
        }

        appliedForwards.clear();
        appliedForwards.putAll(desiredById);
        if (desiredById.isEmpty()) {
            forwardRuntimeReported = false;
            lastReportedForwardRuntimes = List.of();
        }
        return changed;
    }

    private Optional<PeerSocksEndpoint> resolvePeerSocks(UUID peerDeviceId) {
        synchronized (serverReconcileLock) {
            TailcatPeerProxyHandle handle = peerHandles.get(peerDeviceId);
            if (handle == null || handle.process().state() != ProcessState.RUNNING) {
                return Optional.empty();
            }
            return Optional.of(new PeerSocksEndpoint(handle.localSocksHost(), handle.localSocksPort()));
        }
    }

    private boolean peerDeviceIsLocal(AgentPeer peer) {
        return state != null && state.deviceId().equals(peer.peerDeviceId());
    }

    private boolean peerDeviceIsLocal(UUID peerDeviceId) {
        return state != null && state.deviceId().equals(peerDeviceId);
    }

    private List<Integer> activeServicePorts() {
        return serviceHandles.values().stream()
                .filter(handle -> handle.isRunning() && "READY".equals(handle.status()))
                .map(ServiceBridgeHandle::bridgePort)
                .distinct()
                .sorted()
                .toList();
    }

    /** Runs the documented Tailcat ping for every desired Peer. */
    private void peerPingSafely() {
        if (closed.get() || state == null) {
            return;
        }
        try {
            refreshPeerPaths();
        } catch (RuntimeException exception) {
            LOGGER.warn("peer path refresh failed; will retry on the next interval: {}",
                    exception.getMessage(), exception);
        }
    }

    private void refreshPeerPaths() {
        List<AgentPeer> peers;
        synchronized (serverReconcileLock) {
            peers = appliedPeers.values().stream()
                    .sorted(java.util.Comparator.comparing(AgentPeer::peerDeviceId))
                    .toList();
        }
        if (peers.isEmpty()) {
            return;
        }

        for (AgentPeer peer : peers) {
            TailcatPeerProxyHandle handle;
            synchronized (serverReconcileLock) {
                handle = peerHandles.get(peer.peerDeviceId());
            }
            if (peer.connBlob() == null) {
                updatePeerRuntime(peerRuntime(peer, "UNKNOWN", TailcatPathType.UNKNOWN, -1,
                        null, null, "Peer ConnBlob is not available yet"));
                continue;
            }
            if (handle == null || handle.process().state() != ProcessState.RUNNING) {
                String message = handle == null ? "Peer SOCKS is not running"
                        : "Peer SOCKS is " + handle.status();
                updatePeerRuntime(peerRuntime(peer, "DEGRADED", TailcatPathType.UNKNOWN, -1,
                        null, null, message));
                continue;
            }
            try {
                TailcatPingResult result = tailcatEngine.ping(peer.connBlob(), Duration.ofSeconds(5));
                String status = switch (result.pathType()) {
                    case DIRECT, DERP -> "ONLINE";
                    case OFFLINE -> "OFFLINE";
                    case UNKNOWN -> "UNKNOWN";
                };
                updatePeerRuntime(peerRuntime(peer, status, result.pathType(), result.latencyMs(),
                        result.derpRegion(), result.endpoint(), null));
            } catch (RuntimeException exception) {
                LOGGER.warn("Tailcat peer ping failed for peer {}; will retry on the next interval: {}",
                        peer.peerDeviceId(), exception.getMessage(), exception);
                updatePeerRuntime(peerRuntime(peer, "DEGRADED", TailcatPathType.UNKNOWN, -1,
                        null, null, safeError(exception)));
            }
        }
        try {
            reportPeerRuntimes(peerRuntimeSnapshot());
        } catch (RuntimeException exception) {
            LOGGER.warn("could not report Peer path state; will retry on the next interval: {}",
                    exception.getMessage(), exception);
        }
    }

    private void updatePeerRuntime(AgentPeerRuntime runtime) {
        synchronized (serverReconcileLock) {
            if (appliedPeers.containsKey(runtime.peerDeviceId())) {
                peerRuntimes.put(runtime.peerDeviceId(), runtime);
            }
        }
    }

    private AgentPeerRuntime peerRuntime(AgentPeer peer, String status, TailcatPathType pathType,
                                         double latencyMs, String derpRegion, String endpoint,
                                         String lastError) {
        return new AgentPeerRuntime(peer.peerDeviceId(), status, pathType.name(), latencyMs,
                derpRegion, endpoint, lastError);
    }

    private List<AgentPeerRuntime> peerRuntimeSnapshot() {
        synchronized (serverReconcileLock) {
            return appliedPeers.values().stream()
                    .sorted(java.util.Comparator.comparing(AgentPeer::peerDeviceId))
                    .map(peer -> peerRuntimes.getOrDefault(peer.peerDeviceId(),
                            peerRuntime(peer, "UNKNOWN", TailcatPathType.UNKNOWN, -1,
                                    null, null, null)))
                    .toList();
        }
    }

    private List<AgentPeerRuntime> stoppedPeerRuntimes() {
        synchronized (serverReconcileLock) {
            return appliedPeers.values().stream()
                    .sorted(java.util.Comparator.comparing(AgentPeer::peerDeviceId))
                    .map(peer -> peerRuntime(peer, "STOPPED", TailcatPathType.UNKNOWN, -1,
                            null, null, null))
                    .toList();
        }
    }

    private List<AgentServiceRuntime> serviceRuntimes() {
        List<AgentServiceRuntime> runtimes = new ArrayList<>();
        for (AgentService service : appliedServices.values().stream()
                .sorted(java.util.Comparator.comparing(AgentService::serviceId)).toList()) {
            if (!service.enabled()) {
                runtimes.add(new AgentServiceRuntime(service.serviceId(), null, "STOPPED", null));
                continue;
            }
            ServiceBridgeHandle handle = serviceHandles.get(service.serviceId());
            if (handle == null || !handle.isRunning()) {
                runtimes.add(new AgentServiceRuntime(service.serviceId(), null, "FAILED",
                        handle == null ? "ServiceBridge is not running" : handle.lastError()));
                continue;
            }
            runtimes.add(new AgentServiceRuntime(
                    service.serviceId(),
                    handle.bridgePort(),
                    handle.status(),
                    handle.lastError()));
        }
        return List.copyOf(runtimes);
    }

    private List<AgentServiceRuntime> stoppedServiceRuntimes() {
        return appliedServices.values().stream()
                .sorted(java.util.Comparator.comparing(AgentService::serviceId))
                .map(service -> new AgentServiceRuntime(service.serviceId(), null, "STOPPED", null))
                .toList();
    }

    private void reportServiceRuntimes(List<AgentServiceRuntime> runtimes) {
        if (state == null || runtimes == null || runtimes.isEmpty()) {
            return;
        }
        controlClient.reportRuntimeServices(state.agentCredential(),
                new AgentServiceRuntimeReport(runtimes, Instant.now()));
        serviceRuntimeReported = true;
    }

    private void reportPeerRuntimes(List<AgentPeerRuntime> runtimes) {
        if (state == null || runtimes == null) {
            return;
        }
        controlClient.reportRuntimePeers(state.agentCredential(),
                new AgentPeerRuntimeReport(runtimes, Instant.now()));
        peerRuntimeReported = true;
    }

    private void reportForwardRuntimes(List<AgentForwardRuntime> runtimes) {
        if (state == null || runtimes == null) {
            return;
        }
        List<AgentForwardRuntime> snapshot = List.copyOf(runtimes);
        controlClient.reportRuntimeForwards(state.agentCredential(),
                new AgentForwardRuntimeReport(snapshot, Instant.now()));
        forwardRuntimeReported = true;
        lastReportedForwardRuntimes = snapshot;
    }

    private void reportVirtualNetworkRuntimes(List<AgentVirtualNetworkRuntime> runtimes) {
        if (state == null || runtimes == null) {
            return;
        }
        List<AgentVirtualNetworkRuntime> snapshot = List.copyOf(runtimes);
        controlClient.reportRuntimeVirtualNetworks(state.agentCredential(),
                new AgentVirtualNetworkRuntimeReport(snapshot, Instant.now()));
        virtualNetworkRuntimeReported = true;
        lastReportedVirtualNetworkRuntimes = snapshot;
    }

    private int readyServiceCount() {
        synchronized (serverReconcileLock) {
            return (int) serviceHandles.values().stream()
                    .filter(handle -> handle.isRunning() && "READY".equals(handle.status()))
                    .count();
        }
    }

    private int readyForwardCount() {
        synchronized (serverReconcileLock) {
            return forwardManager.readyCount();
        }
    }

    private void refreshServiceRuntime() {
        AgentDesiredState current = desiredState;
        if (current == null || current.services().isEmpty()) {
            return;
        }
        boolean unhealthy = false;
        synchronized (serverReconcileLock) {
            for (AgentService service : current.services()) {
                if (service.enabled()) {
                    ServiceBridgeHandle handle = serviceHandles.get(service.serviceId());
                    if (handle == null || !handle.isRunning()) {
                        unhealthy = true;
                        break;
                    }
                }
            }
        }
        if (unhealthy) {
            reconcileDesiredState(current, false);
        }
    }

    private void refreshForwardRuntime() {
        AgentDesiredState current = desiredState;
        if (current == null || current.forwards().isEmpty()) {
            return;
        }
        boolean unhealthy = false;
        synchronized (serverReconcileLock) {
            for (AgentForward forward : current.forwards()) {
                if (forward.enabled()) {
                    LocalForwardHandle handle = forwardManager.handle(forward.forwardId()).orElse(null);
                    if (handle == null || !handle.isRunning()) {
                        unhealthy = true;
                        break;
                    }
                }
            }
        }
        if (unhealthy) {
            reconcileDesiredState(current, false);
        }
        List<AgentForwardRuntime> snapshot = forwardRuntimeSnapshot();
        if (!snapshot.equals(lastReportedForwardRuntimes)) {
            try {
                reportForwardRuntimes(snapshot);
            } catch (RuntimeException exception) {
                LOGGER.warn("could not report Local Forward runtime; will retry");
            }
        }
    }

    private void refreshVirtualNetworkRuntime() {
        AgentDesiredState current = desiredState;
        if (current == null || current.virtualNetworks().isEmpty()) {
            return;
        }
        List<AgentVirtualNetworkRuntime> before = virtualNetworkManager.snapshot();
        boolean unhealthy = before.stream()
                .anyMatch(runtime -> "ERROR".equals(runtime.status())
                        || "STOPPED".equals(runtime.status()));
        unhealthy = unhealthy || !virtualNetworkManager.isDataPlaneHealthy(current.virtualNetworks());
        if (unhealthy) {
            virtualNetworkManager.reconcile(current.virtualNetworks());
        }
        List<AgentVirtualNetworkRuntime> after = virtualNetworkManager.snapshot();
        if (!after.equals(lastReportedVirtualNetworkRuntimes)) {
            try {
                reportVirtualNetworkRuntimes(after);
            } catch (RuntimeException exception) {
                LOGGER.warn("could not report virtual-network runtime; will retry");
            }
        }
    }

    private void runMaintenanceSafely(String operation, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            // A failing optional component must not prevent the control-plane
            // heartbeat from being sent. Its own runtime snapshot/log carries
            // the detailed failure and the next maintenance cycle retries it.
            LOGGER.warn("{} failed; will retry on the next maintenance cycle: {}",
                    operation, exception.getMessage());
        }
    }

    private List<AgentForwardRuntime> forwardRuntimeSnapshot() {
        synchronized (serverReconcileLock) {
            return appliedForwards.values().stream()
                    .sorted(java.util.Comparator.comparing(AgentForward::forwardId))
                    .map(forward -> {
                        if (!forward.enabled()) {
                            return new AgentForwardRuntime(forward.forwardId(), "STOPPED", null, null);
                        }
                        LocalForwardHandle handle = forwardManager.handle(forward.forwardId()).orElse(null);
                        if (handle != null) {
                            AgentForwardRuntime runtime = handle.runtime();
                            forwardRuntimes.put(forward.forwardId(), runtime);
                            return runtime;
                        }
                        return forwardRuntimes.getOrDefault(forward.forwardId(),
                                new AgentForwardRuntime(forward.forwardId(), "ERROR", "TM-AGENT-007",
                                        "Local Forward listener is not running"));
                    })
                    .toList();
        }
    }

    private List<AgentForwardRuntime> stoppedForwardRuntimes() {
        synchronized (serverReconcileLock) {
            return appliedForwards.values().stream()
                    .sorted(java.util.Comparator.comparing(AgentForward::forwardId))
                    .map(forward -> new AgentForwardRuntime(
                            forward.forwardId(), "STOPPED", null, null))
                    .toList();
        }
    }

    private AgentDesiredState normalizeDesiredState(AgentDesiredState candidate) {
        if (candidate == null) {
            return new AgentDesiredState(state.deviceId(), 0, List.of(), List.of(), List.of(),
                    List.of(), java.util.Map.of(), java.util.Map.of(), List.of());
        }
        if (candidate.deviceId() != null && !state.deviceId().equals(candidate.deviceId())) {
            throw new AgentControlException("TM-CTRL-004", 400,
                    "desired-state deviceId does not match this Agent");
        }
        if (candidate.revision() < 0) {
            throw new AgentControlException("TM-CTRL-004", 400,
                    "desired-state revision is invalid");
        }
        return new AgentDesiredState(
                state.deviceId(),
                candidate.revision(),
                candidate.allowedClientPublicKeys(),
                candidate.services(),
                candidate.peers(),
                candidate.forwards(),
                candidate.derp(),
                candidate.settings(),
                candidate.virtualNetworks());
    }

    /** Reports the current ConnBlob only through the authenticated control channel. */
    private void reportRuntimeServer(boolean running) {
        if (state == null) {
            return;
        }
        String connBlob = running && serverHandle != null ? serverHandle.listenAddress() : null;
        String connBlobHash = connBlob == null ? null : hashConnBlob(connBlob);
        controlClient.reportRuntimeServer(state.agentCredential(), new AgentRuntimeServerRequest(
                running, connBlob, connBlob, Instant.now()));
        serverConnBlobHash = connBlobHash;
        runtimeServerReported = running;
    }

    /** Detects a supervisor restart and re-uploads a changed token or runtime state. */
    private void refreshRuntimeState() {
        TailcatServerHandle currentHandle = serverHandle;
        if (currentHandle == null) {
            AgentDesiredState current = desiredState;
            if (current != null) {
                // A failed initial Tailcat launch leaves the Agent useful for
                // status/control purposes. Retry only the missing runtime on a
                // normal heartbeat instead of exiting the whole process.
                reconcileDesiredState(current, false);
            }
            return;
        }
        TailcatRuntimeStatus runtimeStatus = safeRuntimeStatus();
        boolean running = runtimeStatus.state() == ProcessState.RUNNING
                && runtimeStatus.listenAddress() != null;
        boolean addressChanged = running
                && !runtimeStatus.listenAddress().equals(currentHandle.listenAddress());
        if (addressChanged) {
            synchronized (serverReconcileLock) {
                if (serverHandle == currentHandle) {
                    serverHandle = new TailcatServerHandle(
                            currentHandle.process(), runtimeStatus.listenAddress(), currentHandle.startedAt());
                }
            }
        }
        if (running && (!runtimeServerReported || addressChanged)) {
            try {
                reportRuntimeServer(true);
            } catch (RuntimeException exception) {
                LOGGER.warn("could not report recovered Tailcat Server runtime; will retry");
            }
        } else if (!running && runtimeServerReported) {
            try {
                reportRuntimeServer(false);
            } catch (RuntimeException exception) {
                LOGGER.debug("could not report degraded Tailcat Server runtime");
            }
        }
        if (!running && desiredState != null) {
            reconcileDesiredState(desiredState, false);
        }
    }

    private String currentServerStatus() {
        if (!initialSyncFinished) {
            return "STARTING";
        }
        if (serverHandle == null) {
            return "DEGRADED";
        }
        return controlPlaneStatus;
    }

    private TailcatRuntimeStatus safeRuntimeStatus() {
        try {
            return tailcatEngine.getRuntimeStatus();
        } catch (RuntimeException exception) {
            return new TailcatRuntimeStatus(ProcessState.FAILED, null, null,
                    safeError(exception), 0);
        }
    }

    private void markControlPlaneFailure(String operation, Throwable exception) {
        if (closed.get()) {
            return;
        }
        if (exception instanceof AgentControlException controlException
                && controlException.status() >= 400 && controlException.status() < 500
                && controlException.status() != 401) {
            // A pending/disabled device is still reaching the Control Server;
            // do not misrepresent that as a network outage in the Desktop UI.
            controlPlaneStatus = "PENDING";
        } else {
            controlPlaneStatus = "OFFLINE";
        }
        LOGGER.warn("{}; Agent remains running and will retry: {}",
                operation, exception == null ? "unknown error" : exception.getMessage());
    }

    private static boolean isControlPlaneOffline(String value) {
        return "OFFLINE".equalsIgnoreCase(value)
                || "DISCONNECTED".equalsIgnoreCase(value)
                || "UNREACHABLE".equalsIgnoreCase(value)
                || "ERROR".equalsIgnoreCase(value);
    }

    private static String localHostname() {
        String fromEnvironment = System.getenv("COMPUTERNAME");
        if (fromEnvironment != null && !fromEnvironment.isBlank()) {
            return fromEnvironment.trim();
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception exception) {
            return "unknown-host";
        }
    }

    private String configuredDeviceName() {
        return config.deviceName() == null || config.deviceName().isBlank()
                ? localHostname() : config.deviceName();
    }

    private String safeTailcatVersion() {
        try {
            return tailcatEngine.getVersion().toString();
        } catch (RuntimeException exception) {
            return "unknown";
        }
    }

    private static String normalizedOs() {
        return System.getProperty("os.name", "unknown").toLowerCase(java.util.Locale.ROOT);
    }

    private static String hashConnBlob(String connBlob) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(connBlob.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                hex.append(String.format("%02x", item));
            }
            return CONN_BLOB_HASH_PREFIX + hex;
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String safeError(Throwable exception) {
        String message = exception == null ? "Local Forward failed" : exception.getMessage();
        if (message == null || message.isBlank()) {
            message = exception == null ? "Local Forward failed" : exception.getClass().getSimpleName();
        }
        message = message.replace("\r", "\\r").replace("\n", "\\n");
        return message.length() <= 2_000 ? message : message.substring(0, 2_000);
    }

    private static String safeError(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String message = value.replace("\r", "\\r").replace("\n", "\\n").trim();
        return message.length() <= 2_000 ? message : message.substring(0, 2_000);
    }

    public record StartupResult(UUID deviceId, String status, String listenAddress) {
    }
}
