package com.tailcatmesh.protocol.agent;

/** Registration payload sent by an unregistered Agent. */
public record AgentEnrollmentRequest(
        String enrollmentToken,
        String hostname,
        String os,
        String arch,
        String agentVersion,
        String tailcatVersion,
        String clientPublicKey
) {
}
