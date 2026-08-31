package com.tailcatmesh.server.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/** Configures browser cross-origin access for the separately hosted admin frontend. */
@Configuration(proxyBeanMethods = false)
public class WebCorsConfig {

    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${tailcat-mesh.web.allowed-origins:}") String configuredOrigins) {
        List<String> origins = Arrays.stream(configuredOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .toList();
        if (origins.isEmpty()) {
            return request -> null;
        }

        CorsConfiguration configuration = new CorsConfiguration();
        if (origins.contains("*")) {
            // Origin patterns are required for a wildcard when Spring needs to
            // echo the requesting Origin instead of returning a literal '*'.
            configuration.setAllowedOriginPatterns(List.of("*"));
        } else {
            configuration.setAllowedOrigins(origins);
        }
        configuration.setAllowedMethods(List.of("*"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setExposedHeaders(List.of("*"));
        configuration.setMaxAge(3_600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
