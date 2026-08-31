package com.tailcatmesh.agent.tailcat;

import com.tailcatmesh.agent.tailcat.model.TailcatIdentity;
import com.tailcatmesh.agent.tailcat.model.TailcatIdentityConfig;
import com.tailcatmesh.agent.tailcat.model.TailcatPeerProxyConfig;
import com.tailcatmesh.agent.tailcat.model.TailcatPeerProxyHandle;
import com.tailcatmesh.agent.tailcat.model.TailcatPingResult;
import com.tailcatmesh.agent.tailcat.model.TailcatRuntimeStatus;
import com.tailcatmesh.agent.tailcat.model.TailcatServerConfig;
import com.tailcatmesh.agent.tailcat.model.TailcatServerHandle;
import com.tailcatmesh.agent.tailcat.model.TailcatTokenInfo;
import com.tailcatmesh.agent.tailcat.model.TailcatVersion;
import com.tailcatmesh.agent.tailcat.model.TailcatVirtualNetworkServerConfig;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

/**
 * The only Tailcat dependency surface allowed to Agent business code.
 */
public interface TailcatEngine {

    TailcatVersion getVersion();

    TailcatIdentity ensureIdentity(TailcatIdentityConfig config);

    TailcatServerHandle startServer(TailcatServerConfig config);

    void stopServer();

    void restartServer(TailcatServerConfig config);

    TailcatPeerProxyHandle startPeerProxy(
            UUID peerDeviceId,
            String connBlob,
            TailcatPeerProxyConfig config
    );

    void stopPeerProxy(UUID peerDeviceId);

    TailcatPingResult ping(String connBlob, Duration timeout);

    TailcatTokenInfo parseToken(String connBlob);

    TailcatRuntimeStatus getRuntimeStatus();

    /** Ensures a private server key dedicated to one Mesh Network exists locally. */
    default void ensureVirtualNetworkServerKey(UUID networkId, Path serverKeyPath) {
        throw new UnsupportedOperationException("virtual-network Tailcat runtime is not supported");
    }

    /** Starts one independent virtual-network Tailcat server with --serve=all. */
    default TailcatServerHandle startVirtualNetworkServer(
            UUID networkId, TailcatVirtualNetworkServerConfig config) {
        throw new UnsupportedOperationException("virtual-network Tailcat runtime is not supported");
    }

    /** Stops only the runtime belonging to the specified Mesh Network. */
    default void stopVirtualNetworkServer(UUID networkId) {
        // Older test doubles and alternative engines have no virtual runtime.
    }

    /** Returns the status of one isolated virtual-network runtime. */
    default TailcatRuntimeStatus getVirtualNetworkRuntimeStatus(UUID networkId) {
        return new TailcatRuntimeStatus(
                com.tailcatmesh.agent.tailcat.model.ProcessState.STOPPED,
                null, null, "", 0);
    }

    /** Starts the peer SOCKS process used only by one Network x Peer route. */
    default TailcatPeerProxyHandle startVirtualNetworkPeerProxy(
            UUID networkId, UUID peerDeviceId, String connBlob,
            TailcatPeerProxyConfig config) {
        throw new UnsupportedOperationException("virtual-network peer SOCKS is not supported");
    }

    /** Stops only one Network x Peer SOCKS process. */
    default void stopVirtualNetworkPeerProxy(UUID networkId, UUID peerDeviceId) {
        // Older test doubles and alternative engines have no virtual peer proxy.
    }

    void shutdown();
}
