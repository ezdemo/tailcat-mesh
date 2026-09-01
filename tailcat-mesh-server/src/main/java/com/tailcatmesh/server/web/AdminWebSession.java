package com.tailcatmesh.server.web;

import com.tailcatmesh.server.auth.AdminPrincipal;
import com.tailcatmesh.server.auth.AdminSessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * Small session bridge for the server-rendered administrator UI.
 *
 * <p>The REST API deliberately keeps its short-lived bearer-token contract.
 * The browser UI uses the same login service, but stores the resulting
 * principal in an HTTP session so that HTML forms do not need to carry a
 * bearer token around.</p>
 */
public final class AdminWebSession {

    static final String PRINCIPAL_ATTRIBUTE = AdminWebSession.class.getName() + ".principal";
    private static final String TOKEN_ATTRIBUTE = AdminWebSession.class.getName() + ".token";

    private AdminWebSession() {
    }

    public static AdminPrincipal principal(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        Object value = session.getAttribute(PRINCIPAL_ATTRIBUTE);
        return value instanceof AdminPrincipal principal ? principal : null;
    }

    public static void establish(HttpServletRequest request,
                                 AdminSessionService.LoginResult login,
                                 AdminSessionService sessionService) {
        HttpSession previous = request.getSession(false);
        if (previous != null) {
            previous.invalidate();
        }
        AdminPrincipal principal = sessionService.authenticate(login.accessToken())
                .orElseThrow(() -> new IllegalStateException("new admin session could not be authenticated"));
        HttpSession session = request.getSession(true);
        session.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
        session.setAttribute(TOKEN_ATTRIBUTE, login.accessToken());
    }

    public static void clear(HttpServletRequest request, AdminSessionService sessionService) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return;
        }
        Object token = session.getAttribute(TOKEN_ATTRIBUTE);
        if (token instanceof String accessToken) {
            sessionService.logout(accessToken);
        }
        session.invalidate();
    }
}
