package com.tailcatmesh.protocol.agent;

/** Registration payload sent by an unregistered Agent. */
public record AgentEnrollmentRequest(
        String enrollmentToken,
        String hostname,
        String os,
        String arch,
        String agentVersion,
        String tailcatVersion,
        String clientPublicKey,
        String deviceName
) {

    /** Backward-compatible constructor for Agents that only report hostname. */
    public AgentEnrollmentRequest(String enrollmentToken, String hostname, String os, String arch,
                                  String agentVersion, String tailcatVersion, String clientPublicKey) {
        this(enrollmentToken, hostname, os, arch, agentVersion, tailcatVersion, clientPublicKey, null);
    }
}
