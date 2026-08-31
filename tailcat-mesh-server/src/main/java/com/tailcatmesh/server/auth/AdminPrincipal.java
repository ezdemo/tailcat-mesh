package com.tailcatmesh.server.auth;

import java.util.UUID;

/** Authenticated administrator identity carried by the API filter. */
public record AdminPrincipal(UUID userId, String username, String role) {
}
