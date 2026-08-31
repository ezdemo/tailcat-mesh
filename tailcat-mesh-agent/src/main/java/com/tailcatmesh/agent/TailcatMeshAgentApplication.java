package com.tailcatmesh.agent;

import com.tailcatmesh.agent.command.AgentCli;

import java.io.PrintWriter;

/** Lightweight foreground entry point for the Java Agent. */
public final class TailcatMeshAgentApplication {

    private TailcatMeshAgentApplication() {
    }

    public static void main(String[] args) {
        int exitCode = new AgentCli().run(args, new PrintWriter(System.out, true),
                new PrintWriter(System.err, true));
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }
}
