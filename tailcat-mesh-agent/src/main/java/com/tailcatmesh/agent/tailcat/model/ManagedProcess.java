package com.tailcatmesh.agent.tailcat.model;

import java.time.Duration;
import java.time.Instant;

/** A long-lived child process managed by the Agent's Tailcat supervisor. */
public interface ManagedProcess {

    ProcessState state();

    long pid();

    Instant startedAt();

    int restartCount();

    void stop(Duration timeout);
}
