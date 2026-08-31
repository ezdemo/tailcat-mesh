package com.tailcatmesh.agent.virtual;

import com.tailcatmesh.agent.tailcat.model.ManagedProcess;
import com.tailcatmesh.agent.tailcat.model.ProcessState;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns the replaceable tun2socks child process and its restart lifecycle. */
public final class Tun2SocksSupervisor implements AutoCloseable {

    private static final List<Duration> RESTART_BACKOFF = List.of(
            Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(5),
            Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(60));
    private static final Duration STABLE_RUNTIME = Duration.ofMinutes(5);
    private static final Duration GENERIC_STARTUP_GRACE = Duration.ofSeconds(2);
    private static final int MAX_TAIL_LINES = 200;

    private final Tun2SocksCommandFactory commandFactory;
    private final ExecutorService streamExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService restartExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "tailcat-mesh-tun2socks-supervisor");
        thread.setDaemon(true);
        return thread;
    });
    private final java.util.Set<ManagedSidecarHandle> processes =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    public Tun2SocksSupervisor() {
        this(new Tun2SocksCommandFactory());
    }

    Tun2SocksSupervisor(Tun2SocksCommandFactory commandFactory) {
        this.commandFactory = Objects.requireNonNull(commandFactory, "commandFactory");
    }

    public ManagedSidecarHandle start(Tun2SocksConfig config) {
        ensureOpen();
        Objects.requireNonNull(config, "config");
        if (!Files.isRegularFile(config.binary())) {
            throw new Tun2SocksException("tun2socks binary not found: " + config.binary());
        }
        List<String> command;
        try {
            command = commandFactory.build(config);
        } catch (RuntimeException exception) {
            throw new Tun2SocksException("invalid tun2socks command template", exception);
        }
        ManagedSidecarHandle handle = new ManagedSidecarHandle(this, command, config);
        processes.add(handle);
        try {
            handle.launchInitial();
            handle.awaitStartup(config.startupTimeout());
            return handle;
        } catch (RuntimeException exception) {
            processes.remove(handle);
            handle.stop(Duration.ofSeconds(1));
            throw exception;
        }
    }

    public void stop(ManagedSidecarHandle handle) {
        if (handle != null) {
            handle.stop(Duration.ofSeconds(5));
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (ManagedSidecarHandle handle : List.copyOf(processes)) {
            handle.stop(Duration.ofSeconds(5));
        }
        restartExecutor.shutdownNow();
        streamExecutor.shutdownNow();
        processes.clear();
    }

    private Process startProcess(List<String> command, Tun2SocksConfig config) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            if (config.workingDirectory() != null) {
                if (!Files.isDirectory(config.workingDirectory())) {
                    throw new Tun2SocksException("tun2socks working directory does not exist");
                }
                builder.directory(config.workingDirectory().toFile());
            }
            builder.environment().putAll(config.environment());
            return builder.start();
        } catch (IOException exception) {
            throw new Tun2SocksException("failed to start tun2socks process", exception);
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("tun2socks supervisor is closed");
        }
    }

    private static void destroy(Process process, Duration timeout) {
        if (process == null || !process.isAlive()) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS) && process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    /** Process handle exposed to the Virtual LAN lifecycle without leaking ProcessBuilder. */
    public static final class ManagedSidecarHandle implements ManagedProcess {

        private final Tun2SocksSupervisor owner;
        private final List<String> command;
        private final Tun2SocksConfig config;
        private final Object lifecycleLock = new Object();
        private final TextRingBuffer stdoutTail = new TextRingBuffer(MAX_TAIL_LINES);
        private final TextRingBuffer stderrTail = new TextRingBuffer(MAX_TAIL_LINES);
        private volatile Process process;
        private volatile ProcessState state = ProcessState.NEW;
        private volatile Instant startedAt;
        private volatile Integer exitCode;
        private volatile int restartCount;
        private volatile boolean stopRequested;
        private volatile ScheduledFuture<?> restartFuture;
        private volatile ScheduledFuture<?> stableResetFuture;
        private volatile CountDownLatch startupReady = new CountDownLatch(1);

        private ManagedSidecarHandle(Tun2SocksSupervisor owner, List<String> command,
                                     Tun2SocksConfig config) {
            this.owner = owner;
            this.command = command;
            this.config = config;
        }

        private void launchInitial() {
            synchronized (lifecycleLock) {
                launchLocked();
            }
        }

        private void launchLocked() {
            if (stopRequested || owner.closed.get()) {
                return;
            }
            state = ProcessState.STARTING;
            Process started = owner.startProcess(command, config);
            process = started;
            if (startedAt == null) {
                startedAt = Instant.now();
            }
            exitCode = null;
            state = ProcessState.RUNNING;
            CountDownLatch readiness = new CountDownLatch(1);
            startupReady = readiness;
            owner.streamExecutor.submit(() -> drain(started.getInputStream(), stdoutTail, readiness, true));
            owner.streamExecutor.submit(() -> drain(started.getErrorStream(), stderrTail, readiness, false));
            owner.streamExecutor.submit(() -> watchExit(started));
            if (stableResetFuture != null) {
                stableResetFuture.cancel(false);
            }
            stableResetFuture = owner.restartExecutor.schedule(this::resetAfterStableRuntime,
                    STABLE_RUNTIME.toMillis(), TimeUnit.MILLISECONDS);
        }

        private void watchExit(Process expected) {
            int result;
            try {
                result = expected.waitFor();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
            synchronized (lifecycleLock) {
                if (process != expected) {
                    return;
                }
                exitCode = result;
                if (stopRequested || owner.closed.get()) {
                    state = ProcessState.STOPPED;
                    return;
                }
                state = ProcessState.FAILED;
                scheduleRestartLocked();
            }
        }

        private void scheduleRestartLocked() {
            if (restartFuture != null) {
                restartFuture.cancel(false);
            }
            int index = Math.min(restartCount, RESTART_BACKOFF.size() - 1);
            restartCount++;
            restartFuture = owner.restartExecutor.schedule(this::launchAfterFailure,
                    RESTART_BACKOFF.get(index).toMillis(), TimeUnit.MILLISECONDS);
        }

        private void launchAfterFailure() {
            synchronized (lifecycleLock) {
                if (stopRequested || owner.closed.get()) {
                    return;
                }
                try {
                    launchLocked();
                } catch (RuntimeException exception) {
                    state = ProcessState.FAILED;
                    stderrTail.add("[tun2socks restart failed]");
                    scheduleRestartLocked();
                }
            }
        }

        private void resetAfterStableRuntime() {
            synchronized (lifecycleLock) {
                if (state == ProcessState.RUNNING && process != null && process.isAlive()) {
                    restartCount = 0;
                }
            }
        }

        public List<String> command() {
            return command;
        }

        /**
         * Waits until the sidecar has initialized its TUN stack. Official
         * tun2socks emits a stable {@code [STACK]} line after this point. A
         * generic sidecar may signal readiness on stdout; otherwise a live
         * process is accepted after a short grace period.
         */
        public void awaitStartup(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            long deadline = System.nanoTime() + timeout.toNanos();
            boolean officialTunConfiguration = command.stream()
                    .anyMatch(argument -> argument.contains("tun://"));
            long genericFallbackDeadline = System.nanoTime()
                    + Math.min(timeout.toNanos(), GENERIC_STARTUP_GRACE.toNanos());
            while (true) {
                ProcessState currentState = state;
                if (currentState == ProcessState.FAILED || currentState == ProcessState.STOPPED) {
                    throw new Tun2SocksException("tun2socks exited during startup: "
                            + stderrTail());
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new Tun2SocksException("tun2socks did not become ready: " + stderrTail());
                }
                try {
                    if (startupReady.await(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)),
                            TimeUnit.NANOSECONDS)) {
                        return;
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new Tun2SocksException("interrupted while waiting for tun2socks", exception);
                }
                if (!officialTunConfiguration && System.nanoTime() >= genericFallbackDeadline
                        && isAlive()) {
                    return;
                }
            }
        }

        public boolean isAlive() {
            Process current = process;
            return current != null && current.isAlive();
        }

        public String stdoutTail() {
            return stdoutTail.snapshot();
        }

        public String stderrTail() {
            return stderrTail.snapshot();
        }

        @Override
        public ProcessState state() {
            return state;
        }

        @Override
        public long pid() {
            Process current = process;
            return current == null ? -1 : current.pid();
        }

        @Override
        public Instant startedAt() {
            return startedAt;
        }

        @Override
        public int restartCount() {
            return restartCount;
        }

        @Override
        public void stop(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            Process current;
            synchronized (lifecycleLock) {
                stopRequested = true;
                if (restartFuture != null) {
                    restartFuture.cancel(false);
                }
                if (stableResetFuture != null) {
                    stableResetFuture.cancel(false);
                }
                current = process;
                if (current != null && current.isAlive()) {
                    state = ProcessState.STOPPING;
                }
            }
            destroy(current, timeout);
            synchronized (lifecycleLock) {
                state = ProcessState.STOPPED;
            }
            owner.processes.remove(this);
        }

        private void drain(InputStream input, TextRingBuffer target,
                           CountDownLatch readiness, boolean stdout) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    target.add(line);
                    if (stdout || line.contains("[STACK]")) {
                        readiness.countDown();
                    }
                }
            } catch (IOException exception) {
                target.add("[stream read failed]");
            }
        }
    }

    private static final class TextRingBuffer {
        private final int capacity;
        private final ArrayDeque<String> lines = new ArrayDeque<>();

        private TextRingBuffer(int capacity) {
            this.capacity = capacity;
        }

        private synchronized void add(String line) {
            if (lines.size() == capacity) {
                lines.removeFirst();
            }
            lines.addLast(line);
        }

        private synchronized String snapshot() {
            return String.join(System.lineSeparator(), lines);
        }
    }
}
