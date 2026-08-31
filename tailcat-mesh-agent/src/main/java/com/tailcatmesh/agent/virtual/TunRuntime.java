package com.tailcatmesh.agent.virtual;

/** Adapter boundary for a platform TUN implementation. */
public interface TunRuntime extends AutoCloseable {

    TunHandle open(TunConfig config);

    @Override
    void close();
}
