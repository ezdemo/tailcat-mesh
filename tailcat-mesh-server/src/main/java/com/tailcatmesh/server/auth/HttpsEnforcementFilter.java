package com.tailcatmesh.server.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Enforces the documented HTTPS/WSS boundary for control-plane API traffic. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class HttpsEnforcementFilter extends OncePerRequestFilter {

    private final boolean requireHttps;

    public HttpsEnforcementFilter(
            @Value("${tailcat-mesh.security.require-https:true}") boolean requireHttps) {
        this.requireHttps = requireHttps;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!requireHttps || request.isSecure()) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UPGRADE_REQUIRED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":\"TM-CTRL-005\","
                + "\"message\":\"HTTPS is required for the control plane\"}");
    }
}
