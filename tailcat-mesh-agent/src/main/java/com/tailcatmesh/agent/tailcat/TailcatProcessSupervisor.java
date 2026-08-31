package com.tailcatmesh.agent.tailcat;

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
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Owns Tailcat child processes and all process I/O.
 *
 * <p>Both stdout and stderr are drained concurrently. Long-lived processes are
 * restarted after an unexpected exit using the v0.1 backoff sequence, while
 * an explicit stop suppresses restart.</p>
 */
public final class TailcatProcessSupervisor implements AutoCloseable {

    private static final List<Duration> RESTART_BACKOFF = List.of(
            Duration.ofSeconds(1),
            Duration.ofSeconds(2),
            Duration.ofSeconds(5),
            Duration.ofSeconds(10),
            Duration.ofSeconds(30),
            Duration.ofSeconds(60)
    );
    private static final Duration STABLE_RUNTIME = Duration.ofMinutes(5);
    private static final int MAX_CAPTURED_OUTPUT = 1_048_576;
    private static final int MAX_TAIL_LINES = 200;
    private static final Pattern CONN_BLOB = Pattern.compile("tc[A-Za-z0-9_-]{16,}");

    private final ExecutorService streamExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService restartExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "tailcat-process-supervisor");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<ManagedProcessHandle> processes = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    /** Result of a one-shot Tailcat CLI invocation. */
    public record CommandResult(int exitCode, String stdout, String stderr) {
        public CommandResult {
            stdout = stdout == null ? "" : stdout;
            stderr = stderr == null ? "" : stderr;
        }
    }

    public ManagedProcessHandle start(List<String> command, Path workingDirectory,
                                      Map<String, String> environment) {
        return start(command, workingDirectory, environment, true);
    }

    public ManagedProcessHandle start(List<String> command, Path workingDirectory,
                                      Map<String, String> environment,
                                      boolean restartOnUnexpectedExit) {
        ensureOpen();
        List<String> safeCommand = validateCommand(command);
        Map<String, String> safeEnvironment = environment == null ? Map.of() : Map.copyOf(environment);
        ManagedProcessHandle handle = new ManagedProcessHandle(
                this,
                safeCommand,
                workingDirectory,
                safeEnvironment,
                restartOnUnexpectedExit
        );
        processes.add(handle);
        try {
            handle.launchInitial();
            return handle;
        } catch (RuntimeException exception) {
            processes.remove(handle);
            handle.stop(Duration.ofSeconds(1));
            throw exception;
        }
    }

    /** Runs a short-lived command while still draining both output streams. */
    public CommandResult execute(List<String> command, Path workingDirectory,
                                 Map<String, String> environment, Duration timeout) {
        ensureOpen();
        List<String> safeCommand = validateCommand(command);
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }

        Process process = startProcess(safeCommand, workingDirectory, environment);
        Future<String> stdout = streamExecutor.submit(() -> readAll(process.getInputStream()));
        Future<String> stderr = streamExecutor.submit(() -> readAll(process.getErrorStream()));
        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            destroyProcess(process, Duration.ofSeconds(1));
            throw new TailcatEngineException("TM-AGENT-003", "interrupted while waiting for tailcat command", exception);
        }
        if (!finished) {
            destroyProcess(process, Duration.ofSeconds(1));
            throw new TailcatEngineException("TM-AGENT-003", "tailcat command timed out");
        }
        return new CommandResult(process.exitValue(), awaitOutput(stdout), awaitOutput(stderr));
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (ManagedProcessHandle process : List.copyOf(processes)) {
            process.stop(Duration.ofSeconds(5));
        }
        restartExecutor.shutdownNow();
        streamExecutor.shutdownNow();
        processes.clear();
    }

    private Process startProcess(List<String> command, Path workingDirectory,
                                 Map<String, String> environment) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            if (workingDirectory != null) {
                if (!Files.isDirectory(workingDirectory)) {
                    throw new TailcatEngineException(
                            "TM-AGENT-003",
                            "tailcat working directory does not exist"
                    );
                }
                builder.directory(workingDirectory.toFile());
            }
            if (environment != null) {
                builder.environment().putAll(environment);
            }
            return builder.start();
        } catch (IOException exception) {
            throw new TailcatEngineException("TM-AGENT-001", "failed to start tailcat process", exception);
        }
    }

    private static List<String> validateCommand(List<String> command) {
        Objects.requireNonNull(command, "command");
        if (command.isEmpty() || command.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("command must contain non-null arguments");
        }
        return List.copyOf(command);
    }

    private static String readAll(InputStream input) {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            char[] buffer = new char[8_192];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                if (output.length() < MAX_CAPTURED_OUTPUT) {
                    int remaining = MAX_CAPTURED_OUTPUT - output.length();
                    output.append(buffer, 0, Math.min(read, remaining));
                }
            }
        } catch (IOException exception) {
            output.append("[stream read failed]");
        }
        return output.toString();
    }

    private static String awaitOutput(Future<String> output) {
        try {
            return output.get(2, TimeUnit.SECONDS);
        } catch (Exception exception) {
            return "[stream drain failed]";
        }
    }

    private static void destroyProcess(Process process, Duration timeout) {
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

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Tailcat process supervisor is closed");
        }
    }

    /** Concrete process handle used by the Engine and exposed through ManagedProcess. */
    public static final class ManagedProcessHandle implements ManagedProcess {

        private final TailcatProcessSupervisor owner;
        private final List<String> command;
        private final Path workingDirectory;
        private final Map<String, String> environment;
        private final boolean restartOnUnexpectedExit;
        private final Object lifecycleLock = new Object();
        private final BlockingQueue<String> stdoutLines = new LinkedBlockingQueue<>(MAX_TAIL_LINES);
        private final BlockingQueue<String> stderrLines = new LinkedBlockingQueue<>(MAX_TAIL_LINES);
        private final TextRingBuffer stdoutTail = new TextRingBuffer(MAX_TAIL_LINES);
        private final TextRingBuffer stderrTail = new TextRingBuffer(MAX_TAIL_LINES);

        private volatile Process process;
        private volatile ProcessState state = ProcessState.NEW;
        private volatile Instant startedAt;
        private volatile int restartCount;
        private volatile Integer exitCode;
        private volatile boolean stopRequested;
        private volatile ScheduledFuture<?> restartFuture;
        private volatile ScheduledFuture<?> stableResetFuture;

        private ManagedProcessHandle(TailcatProcessSupervisor owner, List<String> command,
                                     Path workingDirectory, Map<String, String> environment,
                                     boolean restartOnUnexpectedExit) {
            this.owner = owner;
            this.command = command;
            this.workingDirectory = workingDirectory;
            this.environment = environment;
            this.restartOnUnexpectedExit = restartOnUnexpectedExit;
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
            Process started = owner.startProcess(command, workingDirectory, environment);
            process = started;
            if (startedAt == null) {
                startedAt = Instant.now();
            }
            exitCode = null;
            state = ProcessState.RUNNING;
            owner.streamExecutor.submit(() -> drainLines(started.getInputStream(), stdoutTail, stdoutLines));
            owner.streamExecutor.submit(() -> drainLines(started.getErrorStream(), stderrTail, stderrLines));
            owner.streamExecutor.submit(() -> watchExit(started));
            if (stableResetFuture != null) {
                stableResetFuture.cancel(false);
            }
            stableResetFuture = owner.restartExecutor.schedule(this::resetAfterStableRuntime,
                    STABLE_RUNTIME.toMillis(), TimeUnit.MILLISECONDS);
        }

        private void drainLines(InputStream input, TextRingBuffer tail, BlockingQueue<String> queue) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    tail.add(line);
                    if (queue != null) {
                        if (!queue.offer(line)) {
                            queue.poll();
                            queue.offer(line);
                        }
                    }
                }
            } catch (IOException exception) {
                tail.add("[stream read failed]");
            }
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
                if (restartOnUnexpectedExit) {
                    scheduleRestartLocked();
                }
            }
        }

        private void scheduleRestartLocked() {
            if (restartFuture != null) {
                restartFuture.cancel(false);
            }
            int backoffIndex = Math.min(restartCount, RESTART_BACKOFF.size() - 1);
            Duration delay = RESTART_BACKOFF.get(backoffIndex);
            restartCount++;
            restartFuture = owner.restartExecutor.schedule(this::launchAfterCrash,
                    delay.toMillis(), TimeUnit.MILLISECONDS);
        }

        private void launchAfterCrash() {
            synchronized (lifecycleLock) {
                if (stopRequested || owner.closed.get()) {
                    return;
                }
                try {
                    launchLocked();
                } catch (RuntimeException exception) {
                    state = ProcessState.FAILED;
                    stderrTail.add("[tailcat restart failed]");
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

        public String awaitStdoutLine(Duration timeout) throws TimeoutException {
            return awaitLine(stdoutLines, timeout, "stdout");
        }

        /** Waits for the next non-blank line emitted on Tailcat stderr. */
        public String awaitStderrLine(Duration timeout) throws TimeoutException {
            return awaitLine(stderrLines, timeout, "stderr");
        }

        private String awaitLine(BlockingQueue<String> lines, Duration timeout, String stream)
                throws TimeoutException {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            long deadline = System.nanoTime() + timeout.toNanos();
            while (true) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new TimeoutException("timed out waiting for Tailcat " + stream);
                }
                try {
                    String line = lines.poll(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)),
                            TimeUnit.NANOSECONDS);
                    if (line != null && !line.isBlank()) {
                        return line;
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new TimeoutException("interrupted while waiting for Tailcat " + stream);
                }
                ProcessState currentState = state;
                if ((currentState == ProcessState.FAILED || currentState == ProcessState.STOPPED)
                        && lines.isEmpty()) {
                    throw new TimeoutException("Tailcat process exited before emitting " + stream);
                }
            }
        }

        public boolean isAlive() {
            Process current = process;
            return current != null && current.isAlive();
        }

        public Integer exitCode() {
            return exitCode;
        }

        public String stderrTail() {
            return redactConnBlobs(stderrTail.snapshot());
        }

        public String stdoutTail() {
            return redactConnBlobs(stdoutTail.snapshot());
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
            destroyProcess(current, timeout);
            synchronized (lifecycleLock) {
                state = ProcessState.STOPPED;
            }
            owner.processes.remove(this);
        }

        private static String redactConnBlobs(String text) {
            return CONN_BLOB.matcher(text).replaceAll("<redacted-conn-blob>");
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
