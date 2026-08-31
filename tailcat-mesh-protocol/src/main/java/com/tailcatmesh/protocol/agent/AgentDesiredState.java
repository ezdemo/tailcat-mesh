package com.tailcatmesh.protocol.agent;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Complete desired-state projection returned to an Agent. */
public record AgentDesiredState(
        UUID deviceId,
        long revision,
        List<String> allowedClientPublicKeys,
        List<AgentService> services,
        List<AgentPeer> peers,
        List<AgentForward> forwards,
        Map<String, Object> derp,
        Map<String, Object> settings
) {
    /** Backward-compatible constructor for desired-state projections before M5. */
    public AgentDesiredState(UUID deviceId, long revision, List<String> allowedClientPublicKeys,
                             List<AgentService> services, List<AgentForward> forwards,
                             Map<String, Object> derp, Map<String, Object> settings) {
        this(deviceId, revision, allowedClientPublicKeys, services, List.of(), forwards, derp, settings);
    }

    public AgentDesiredState {
        allowedClientPublicKeys = allowedClientPublicKeys == null
                ? List.of() : List.copyOf(allowedClientPublicKeys);
        services = services == null ? List.of() : List.copyOf(services);
        peers = peers == null ? List.of() : List.copyOf(peers);
        forwards = forwards == null ? List.of() : List.copyOf(forwards);
        derp = derp == null ? Map.of() : Map.copyOf(derp);
        settings = settings == null ? Map.of() : Map.copyOf(settings);
    }
}
