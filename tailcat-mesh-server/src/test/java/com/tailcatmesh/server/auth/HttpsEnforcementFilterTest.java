package com.tailcatmesh.server.auth;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpsEnforcementFilterTest {

    @Test
    void rejectsPlainHttpControlPlaneRequestsWhenRequired() throws Exception {
        HttpsEnforcementFilter filter = new HttpsEnforcementFilter(true);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(426, response.getStatus());
        assertTrue(response.getContentAsString().contains("TM-CTRL-005"));
    }

    @Test
    void allowsSecureRequestsAndLocalDevelopmentMode() throws Exception {
        HttpsEnforcementFilter required = new HttpsEnforcementFilter(true);
        MockHttpServletRequest secureRequest = new MockHttpServletRequest("GET", "/api/v1/devices");
        secureRequest.setSecure(true);
        MockHttpServletResponse secureResponse = new MockHttpServletResponse();
        FilterChain secureChain = new MockFilterChain();
        required.doFilter(secureRequest, secureResponse, secureChain);
        assertEquals(200, secureResponse.getStatus());

        HttpsEnforcementFilter development = new HttpsEnforcementFilter(false);
        MockHttpServletRequest localRequest = new MockHttpServletRequest("GET", "/api/v1/devices");
        MockHttpServletResponse localResponse = new MockHttpServletResponse();
        development.doFilter(localRequest, localResponse, new MockFilterChain());
        assertEquals(200, localResponse.getStatus());
    }
}
