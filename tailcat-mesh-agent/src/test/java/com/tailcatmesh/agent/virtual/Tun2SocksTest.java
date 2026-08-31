package com.tailcatmesh.agent.virtual;

import com.tailcatmesh.agent.forward.PeerSocksEndpoint;
import com.tailcatmesh.agent.tailcat.model.ProcessState;
import com.tailcatmesh.agent.tailcat.SupervisorFixture;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Tun2SocksTest {

    @Test
    void expandsOnlyExplicitSidecarPlaceholders() {
        Path java = javaExecutable();
        Tun2SocksConfig config = new Tun2SocksConfig(
                java, "Tailcat Mesh", new PeerSocksEndpoint("127.0.0.1", 46001),
                List.of("--tun=${tun}", "--proxy=${proxy}", "--host=${proxy-host}",
                        "--port=${proxy-port}"), null, null, Duration.ofSeconds(3));

        assertEquals(List.of(java.toAbsolutePath().normalize().toString(),
                        "--tun=Tailcat Mesh", "--proxy=socks5://127.0.0.1:46001",
                        "--host=127.0.0.1", "--port=46001"),
                new Tun2SocksCommandFactory().build(config));
    }

    @Test
    void supervisesSidecarAndDrainsItsOutput() throws Exception {
        String classPath = System.getProperty("java.class.path");
        Tun2SocksConfig config = new Tun2SocksConfig(
                javaExecutable(), "tailcat-mesh",
                new PeerSocksEndpoint("127.0.0.1", 46002),
                List.of("-cp", classPath, SupervisorFixture.class.getName()),
                Path.of(".").toAbsolutePath().normalize(), null, Duration.ofSeconds(5));

        try (Tun2SocksSupervisor supervisor = new Tun2SocksSupervisor()) {
            Tun2SocksSupervisor.ManagedSidecarHandle handle = supervisor.start(config);
            assertTrue(waitFor(() -> handle.stdoutTail().contains("ready"), Duration.ofSeconds(5)));
            assertTrue(waitFor(() -> handle.stderrTail().contains("stderr-line"), Duration.ofSeconds(5)));
            assertEquals(ProcessState.RUNNING, handle.state());
            assertEquals(javaExecutable().toAbsolutePath().normalize().toString(), handle.command().get(0));
            handle.stop(Duration.ofSeconds(2));
            assertEquals(ProcessState.STOPPED, handle.state());
            assertTrue(!handle.isAlive());
        }
    }

    @Test
    void restartsSidecarAfterUnexpectedExit() throws Exception {
        String classPath = System.getProperty("java.class.path");
        Tun2SocksConfig config = new Tun2SocksConfig(
                javaExecutable(), "tailcat-mesh",
                new PeerSocksEndpoint("127.0.0.1", 46003),
                List.of("-cp", classPath, SupervisorFixture.class.getName()),
                Path.of(".").toAbsolutePath().normalize(), null, Duration.ofSeconds(5));

        try (Tun2SocksSupervisor supervisor = new Tun2SocksSupervisor()) {
            Tun2SocksSupervisor.ManagedSidecarHandle handle = supervisor.start(config);
            assertTrue(waitFor(handle::isAlive, Duration.ofSeconds(5)));
            long firstPid = handle.pid();
            ProcessHandle.of(firstPid).ifPresent(ProcessHandle::destroy);
            assertTrue(waitFor(() -> handle.restartCount() > 0
                    && handle.isAlive() && handle.pid() != firstPid, Duration.ofSeconds(8)),
                    "sidecar did not restart after unexpected exit");
            handle.stop(Duration.ofSeconds(2));
        }
    }

    private static Path javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win")
                        ? "java.exe" : "java");
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
