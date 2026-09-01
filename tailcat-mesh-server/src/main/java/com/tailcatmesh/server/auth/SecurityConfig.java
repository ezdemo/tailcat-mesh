package com.tailcatmesh.server.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.util.matcher.RequestMatcher;

/** Keeps Spring Security's HTTP defaults explicit while domain filters own API auth. */
@Configuration
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            AdminAuthenticationFilter adminAuthenticationFilter,
            com.tailcatmesh.server.agentws.AgentAuthenticationFilter agentAuthenticationFilter
    ) throws Exception {
        RequestMatcher apiRequest = request -> request.getRequestURI().startsWith("/api/");
        http.csrf(csrf -> csrf.ignoringRequestMatchers(apiRequest))
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .addFilterBefore(agentAuthenticationFilter, AnonymousAuthenticationFilter.class)
                .addFilterBefore(adminAuthenticationFilter, AnonymousAuthenticationFilter.class);
        return http.build();
    }
}
