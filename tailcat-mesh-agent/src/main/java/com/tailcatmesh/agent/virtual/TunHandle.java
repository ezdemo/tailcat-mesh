package com.tailcatmesh.agent.virtual;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Idempotently closable handle for one Agent-owned TUN/Wintun interface. */
public final class TunHandle implements AutoCloseable {

    private final String interfaceName;
    private final UUID adapterGuid;
    private final boolean createdByAgent;
    private final Runnable closeAction;
    private final AtomicBoolean closed = new AtomicBoolean();

    TunHandle(String interfaceName, UUID adapterGuid, boolean createdByAgent, Runnable closeAction) {
        this.interfaceName = Objects.requireNonNull(interfaceName, "interfaceName");
        this.adapterGuid = adapterGuid;
        this.createdByAgent = createdByAgent;
        this.closeAction = Objects.requireNonNull(closeAction, "closeAction");
    }

    public String interfaceName() {
        return interfaceName;
    }

    public UUID adapterGuid() {
        return adapterGuid;
    }

    public boolean createdByAgent() {
        return createdByAgent;
    }

    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            closeAction.run();
        }
    }
}
