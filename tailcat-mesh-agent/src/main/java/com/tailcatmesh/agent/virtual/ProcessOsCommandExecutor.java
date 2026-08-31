package com.tailcatmesh.agent.virtual;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Default non-shell command executor for the OS adapters.
 *
 * <p>The executable and every argument remain separate {@link ProcessBuilder}
 * arguments. This class is intentionally the only low-level OS command
 * implementation used by the M7 route/TUN adapters.</p>
 */
public final class ProcessOsCommandExecutor implements OsCommandExecutor, AutoCloseable {

    private static final int MAX_OUTPUT = 1_048_576;

    private final ExecutorService streamExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @Override
    public CommandResult execute(List<String> command, Path workingDirectory,
                                 Map<String, String> environment, Duration timeout) {
        List<String> safeCommand = validateCommand(command);
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        Process process = startProcess(safeCommand, workingDirectory, environment);
        Future<String> stdout = streamExecutor.submit(() -> readAll(process.getInputStream()));
        Future<String> stderr = streamExecutor.submit(() -> readAll(process.getErrorStream()));
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                destroy(process, Duration.ofSeconds(1));
                throw new OsCommandException("operating-system command timed out: " + safeCommand.get(0));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            destroy(process, Duration.ofSeconds(1));
            throw new OsCommandException("interrupted while waiting for operating-system command", exception);
        }
        CommandResult result = new CommandResult(process.exitValue(), await(stdout), await(stderr));
        return result;
    }

    @Override
    public void close() {
        streamExecutor.shutdownNow();
    }

    private static Process startProcess(List<String> command, Path workingDirectory,
                                        Map<String, String> environment) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            if (workingDirectory != null) {
                if (!Files.isDirectory(workingDirectory)) {
                    throw new OsCommandException("operating-system command working directory does not exist");
                }
                builder.directory(workingDirectory.toFile());
            }
            if (environment != null) {
                builder.environment().putAll(environment);
            }
            return builder.start();
        } catch (IOException exception) {
            throw new OsCommandException("failed to start operating-system command: " + command.get(0), exception);
        }
    }

    private static List<String> validateCommand(List<String> command) {
        Objects.requireNonNull(command, "command");
        if (command.isEmpty() || command.stream().anyMatch(Objects::isNull)
                || command.stream().anyMatch(value -> value.indexOf('\0') >= 0)) {
            throw new IllegalArgumentException("command must contain non-null arguments without NUL bytes");
        }
        return List.copyOf(command);
    }

    private static String readAll(InputStream input) {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            char[] buffer = new char[8_192];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                if (output.length() < MAX_OUTPUT) {
                    output.append(buffer, 0, Math.min(read, MAX_OUTPUT - output.length()));
                }
            }
        } catch (IOException exception) {
            output.append("[stream read failed]");
        }
        return output.toString();
    }

    private static String await(Future<String> future) {
        try {
            return future.get(2, TimeUnit.SECONDS);
        } catch (Exception exception) {
            return "[stream drain failed]";
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
}
