package com.tailcatmesh.agent.virtual;

/** Adapter boundary for a platform TUN implementation. */
public interface TunRuntime extends AutoCloseable {

    /**
     * Removes platform state left by an earlier crashed owner before the
     * external TUN engine attempts to create its device.
     */
    default void prepare(TunConfig config) {
        // Most TUN implementations do not persist a device after a crash.
    }

    TunHandle open(TunConfig config);

    @Override
    void close();
}
