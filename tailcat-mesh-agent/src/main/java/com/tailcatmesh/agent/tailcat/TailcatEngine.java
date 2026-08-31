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

    void shutdown();
}
