package com.tailcatmesh.server.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebCorsConfigTest {

    @Test
    void wildcardConfigurationAllowsAnyOriginAndPreflightShape() {
        CorsConfigurationSource source = new WebCorsConfig().corsConfigurationSource("*");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/networks");

        CorsConfiguration configuration = source.getCorsConfiguration(request);

        assertNotNull(configuration);
        assertEquals(List.of("*"), configuration.getAllowedOriginPatterns());
        assertEquals(List.of("*"), configuration.getAllowedMethods());
        assertEquals(List.of("*"), configuration.getAllowedHeaders());
        assertEquals(List.of("*"), configuration.getExposedHeaders());
        assertEquals("https://frontend.example", configuration.checkOrigin("https://frontend.example"));
        assertTrue(configuration.getMaxAge() >= 3_600L);
    }
}
