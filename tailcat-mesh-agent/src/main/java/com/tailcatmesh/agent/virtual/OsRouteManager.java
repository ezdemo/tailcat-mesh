package com.tailcatmesh.agent.virtual;

import java.util.Collection;
import java.util.List;

/** Owns only the Mesh CIDR routes created by one Virtual LAN data plane. */
public interface OsRouteManager extends AutoCloseable {

    void reconcile(Collection<OsRoute> desiredRoutes);

    void removeAll();

    /** Best-effort removal of routes recovered from the previous Agent run. */
    default void removeKnown(Collection<OsRoute> routes) {
        // Implementations that can address OS routes should override this.
    }

    List<OsRoute> snapshot();

    @Override
    default void close() {
        removeAll();
    }
}
