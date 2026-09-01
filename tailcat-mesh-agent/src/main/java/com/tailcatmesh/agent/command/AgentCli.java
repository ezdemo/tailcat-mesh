package com.tailcatmesh.agent.command;

import com.tailcatmesh.agent.bootstrap.AgentRuntime;
import com.tailcatmesh.agent.config.AgentConfig;
import com.tailcatmesh.agent.config.AgentConfigException;
import com.tailcatmesh.agent.config.AgentConfigLoader;
import com.tailcatmesh.agent.config.AgentConfigOverrides;
import com.tailcatmesh.agent.control.AgentControlClient;
import com.tailcatmesh.agent.control.AgentControlException;
import com.tailcatmesh.agent.identity.AgentStateStore;
import com.tailcatmesh.agent.service.ServiceBridgeException;
import com.tailcatmesh.agent.tailcat.TailcatBinaryDownloader;
import com.tailcatmesh.agent.tailcat.TailcatCliEngine;
import com.tailcatmesh.agent.tailcat.TailcatCliEngineConfig;
import com.tailcatmesh.agent.tailcat.TailcatEngineException;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/** User-facing command facade; Tailcat remains reachable only through TailcatCliEngine. */
public final class AgentCli {

    public static final String VERSION = "0.1.0-SNAPSHOT";

    public int run(String[] args, PrintWriter stdout, PrintWriter stderr) {
        try {
            AgentCliOptions options = AgentCliOptions.parse(args);
            if (options.help() || options.command() == AgentCliOptions.Command.HELP) {
                printHelp(stdout);
                return 0;
            }
            if (options.command() == AgentCliOptions.Command.VERSION) {
                stdout.println("Tailcat Mesh Agent " + VERSION);
                return 0;
            }

            AgentConfigLoader loader = new AgentConfigLoader();
            AgentConfig config = loader.load(options.configPath(), new AgentConfigOverrides(
                    options.serverUrl(), options.tailcatBinary(), options.dataDir()));
            Files.createDirectories(config.dataDir());
            if (config.tailcatAutoDownload() && !Files.isRegularFile(config.tailcatBinary())) {
                stdout.println("Downloading official Tailcat " + config.tailcatVersion()
                        + " for " + TailcatBinaryDownloader.currentPlatform() + "...");
            }
            Path tailcatBinary = new TailcatBinaryDownloader(config.proxy()).ensure(
                    config.tailcatBinary(), config.tailcatVersion(), config.tailcatAutoDownload());
            TailcatCliEngine engine = new TailcatCliEngine(new TailcatCliEngineConfig(
                    tailcatBinary,
                    config.dataDir(),
                    config.proxy() == null ? Map.of() : config.proxy().environment(),
                    Duration.ofSeconds(15),
                    Duration.ofSeconds(20),
                    false
            ));
            AgentControlClient controlClient = new AgentControlClient(config, Duration.ofSeconds(15));
            AgentRuntime runtime = new AgentRuntime(
                    config, engine, controlClient, new AgentStateStore(config.dataDir()), VERSION);
            try {
                return runtime.run(options.enrollmentToken(), options.once(), stdout);
            } finally {
                runtime.close();
            }
        } catch (IllegalArgumentException exception) {
            stderr.println("Error: " + exception.getMessage());
            return 2;
        } catch (AgentConfigException | AgentControlException | TailcatEngineException
                 | ServiceBridgeException exception) {
            String code = exception instanceof AgentConfigException configException
                    ? configException.code()
                    : exception instanceof AgentControlException controlException
                    ? controlException.code()
                    : exception instanceof TailcatEngineException tailcatException
                    ? tailcatException.code()
                    : ((ServiceBridgeException) exception).code();
            stderr.println("Error [" + code + "]: " + exception.getMessage());
            return 1;
        } catch (Exception exception) {
            Throwable rootCause = rootCause(exception);
            String detail = rootCause.getMessage();
            if (detail == null || detail.isBlank()) {
                detail = rootCause.getClass().getSimpleName();
            } else {
                detail = rootCause.getClass().getSimpleName() + ": " + detail;
            }
            stderr.println("Error: Agent startup failed - " + detail);
            return 1;
        }
    }

    private static Throwable rootCause(Throwable exception) {
        Throwable current = exception;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static void printHelp(PrintWriter output) {
        output.println("Tailcat Mesh Agent " + VERSION);
        output.println();
        output.println("First-time connect:");
        output.println("  tailcat-mesh-agent connect --server https://mesh.example.com --token tm_enroll_xxx");
        output.println();
        output.println("Run again with the saved credential:");
        output.println("  tailcat-mesh-agent run --config agent.yml");
        output.println();
        output.println("Options:");
        output.println("  --config <path>          YAML configuration (default: agent.yml)");
        output.println("  --server <url>           control-plane URL; overrides YAML");
        output.println("  --token <value>          one-time enrollment token");
        output.println("  --tailcat-binary <path>  official Tailcat binary; overrides YAML");
        output.println("  --data-dir <path>        local identity/state directory; overrides YAML");
        output.println("  --once                   start, report, then stop (diagnostic mode)");
        output.println("  --version                print Agent version");
        output.println();
        output.println("YAML tailcat settings:");
        output.println("  tailcat.version          pinned official Tailcat release (default: 0.3.0)");
        output.println("  tailcat.autoDownload     download absent binary to ~/.tailcat-mesh");
        output.println("  proxy.type               optional http or socks5 proxy");
        output.println("  proxy.host / proxy.port  local proxy endpoint; credentials are not supported");
    }
}
