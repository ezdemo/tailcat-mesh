package com.tailcatmesh.server.agentws;

import com.tailcatmesh.server.auth.AdminAuthenticationFilter;
import com.tailcatmesh.server.common.ControlPlaneException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Protects authenticated Agent REST endpoints with the enrollment credential. */
@Component
public final class AgentAuthenticationFilter extends OncePerRequestFilter {

    public static final String PRINCIPAL_ATTRIBUTE = AgentAuthenticationFilter.class.getName() + ".principal";

    private final AgentAuthenticationService authenticationService;

    public AgentAuthenticationFilter(AgentAuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/v1/agent/") || path.equals("/api/v1/agent/enroll");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            AgentPrincipal principal = authenticationService.authenticate(
                    AdminAuthenticationFilter.bearerToken(request.getHeader("Authorization")));
            request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
            filterChain.doFilter(request, response);
        } catch (ControlPlaneException exception) {
            response.setStatus(exception.status().value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"code\":\"" + exception.code()
                    + "\",\"message\":\"" + safeMessage(exception.getMessage()) + "\"}");
        }
    }

    private static String safeMessage(String value) {
        return value == null ? "authentication failed" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
