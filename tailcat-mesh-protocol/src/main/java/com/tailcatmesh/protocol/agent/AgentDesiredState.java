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
        Map<String, Object> settings,
        List<AgentVirtualNetwork> virtualNetworks
) {
    /** Backward-compatible constructor for projections before M7. */
    public AgentDesiredState(UUID deviceId, long revision, List<String> allowedClientPublicKeys,
                             List<AgentService> services, List<AgentPeer> peers,
                             List<AgentForward> forwards, Map<String, Object> derp,
                             Map<String, Object> settings) {
        this(deviceId, revision, allowedClientPublicKeys, services, peers, forwards,
                derp, settings, List.of());
    }

    /** Backward-compatible constructor for desired-state projections before M5. */
    public AgentDesiredState(UUID deviceId, long revision, List<String> allowedClientPublicKeys,
                             List<AgentService> services, List<AgentForward> forwards,
                             Map<String, Object> derp, Map<String, Object> settings) {
        this(deviceId, revision, allowedClientPublicKeys, services, List.of(), forwards,
                derp, settings, List.of());
    }

    public AgentDesiredState {
        allowedClientPublicKeys = allowedClientPublicKeys == null
                ? List.of() : List.copyOf(allowedClientPublicKeys);
        services = services == null ? List.of() : List.copyOf(services);
        peers = peers == null ? List.of() : List.copyOf(peers);
        forwards = forwards == null ? List.of() : List.copyOf(forwards);
        derp = derp == null ? Map.of() : Map.copyOf(derp);
        settings = settings == null ? Map.of() : Map.copyOf(settings);
        virtualNetworks = virtualNetworks == null ? List.of() : List.copyOf(virtualNetworks);
    }
}
