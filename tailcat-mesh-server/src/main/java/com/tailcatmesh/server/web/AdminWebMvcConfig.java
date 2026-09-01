package com.tailcatmesh.server.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers the session guard for the HTML administrator surface. */
@Configuration
public class AdminWebMvcConfig implements WebMvcConfigurer {

    private final AdminWebAuthenticationInterceptor authenticationInterceptor =
            new AdminWebAuthenticationInterceptor();

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authenticationInterceptor)
                .addPathPatterns("/admin", "/admin/**");
    }
}
