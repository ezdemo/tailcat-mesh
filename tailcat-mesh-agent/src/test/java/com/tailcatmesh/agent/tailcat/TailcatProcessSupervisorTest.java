package com.tailcatmesh.agent.tailcat;

import com.tailcatmesh.agent.tailcat.model.ProcessState;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TailcatProcessSupervisorTest {

    @Test
    void drainsBothStreamsAndStopsChildWithoutRestart() throws Exception {
        Path java = Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java"
        );
        String classPath = System.getProperty("java.class.path");

        try (TailcatProcessSupervisor supervisor = new TailcatProcessSupervisor()) {
            TailcatProcessSupervisor.ManagedProcessHandle handle = supervisor.start(
                    List.of(java.toString(), "-cp", classPath, SupervisorFixture.class.getName()),
                    Path.of(".").toAbsolutePath().normalize(),
                    Map.of(),
                    false
            );

            assertEquals("ready", handle.awaitStdoutLine(Duration.ofSeconds(5)));
            assertEquals("stderr-line", handle.awaitStderrLine(Duration.ofSeconds(5)));
            assertTrue(waitFor(() -> handle.stderrTail().contains("stderr-line"), Duration.ofSeconds(2)));
            assertEquals(ProcessState.RUNNING, handle.state());

            handle.stop(Duration.ofSeconds(2));

            assertEquals(ProcessState.STOPPED, handle.state());
            assertFalse(handle.isAlive());
        }
    }

    private static boolean waitFor(java.util.function.BooleanSupplier condition, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(25);
        }
        return condition.getAsBoolean();
    }
}
