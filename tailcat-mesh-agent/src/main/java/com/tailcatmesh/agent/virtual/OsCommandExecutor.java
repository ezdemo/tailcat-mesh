package com.tailcatmesh.agent.virtual;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Process boundary for non-Tailcat operating-system commands. */
public interface OsCommandExecutor {

    CommandResult execute(List<String> command, Path workingDirectory,
                          Map<String, String> environment, Duration timeout);

    record CommandResult(int exitCode, String stdout, String stderr) {
        public CommandResult {
            stdout = stdout == null ? "" : stdout;
            stderr = stderr == null ? "" : stderr;
        }
    }
}
