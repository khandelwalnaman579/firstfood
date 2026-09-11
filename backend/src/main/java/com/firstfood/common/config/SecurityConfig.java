package com.firstfood.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Explicit security baseline for Phase 1 (FirstFood_V2_Phase1_Review.md
 * #4.1/#5, #20). Spring Security defaults to denying everything once
 * spring-boot-starter-security is on the classpath; without this class
 * that default-deny applies to /api/v1/version and /actuator/health too,
 * which breaks the Docker healthcheck.
 *
 * Real authentication (JWT, mobile OTP) lands in Phase 2 - this only
 * establishes: public health/version endpoints, everything else denied
 * by default, stateless sessions (no server-side session state, since
 * auth will be JWT-based), and CSRF disabled (this is a stateless JSON
 * API, not a server-rendered form app - rules.md's API rules assume
 * bearer-token auth, not cookie sessions).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/version").permitAll()
                        .requestMatchers("/actuator/health/**").permitAll()
                        .anyRequest().authenticated()
                )
                .build();
    }
}