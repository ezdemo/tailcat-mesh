package com.tailcatmesh.agent.virtual;

import java.util.UUID;

/** Native Wintun adapter boundary, kept separate from the Windows network commands. */
public interface WintunAdapterProvider {

    Adapter openOrCreate(String interfaceName, UUID requestedGuid);

    /** Opens an adapter created by the external TUN engine without creating a second one. */
    default Adapter openExisting(String interfaceName, UUID requestedGuid) {
        return openOrCreate(interfaceName, requestedGuid);
    }

    void close(Adapter adapter);

    record Adapter(String interfaceName, UUID requestedGuid, Object nativeHandle, boolean createdByAgent) {
        public Adapter {
            if (interfaceName == null || interfaceName.isBlank()) {
                throw new IllegalArgumentException("interfaceName must not be blank");
            }
            if (requestedGuid == null) {
                throw new IllegalArgumentException("requestedGuid must not be null");
            }
            if (nativeHandle == null) {
                throw new IllegalArgumentException("nativeHandle must not be null");
            }
        }
    }
}
