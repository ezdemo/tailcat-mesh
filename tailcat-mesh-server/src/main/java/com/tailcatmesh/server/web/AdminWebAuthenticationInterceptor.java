package com.tailcatmesh.server.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Redirects unauthenticated browser requests to the server-rendered login page. */
public final class AdminWebAuthenticationInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (AdminWebSession.principal(request) != null) {
            return true;
        }

        String target = request.getRequestURI();
        if (request.getQueryString() != null && !request.getQueryString().isBlank()) {
            target += "?" + request.getQueryString();
        }
        String loginUrl = request.getContextPath() + "/login?redirect="
                + URLEncoder.encode(target, StandardCharsets.UTF_8);
        response.sendRedirect(loginUrl);
        return false;
    }
}
