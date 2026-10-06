package com.firstfood.common.config;

import com.firstfood.identity.security.JwtAuthenticationFilter;
import java.time.Instant;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Security baseline, now wired for real JWT auth (phases.md Phase 2).
 * Public: OTP request/verify and refresh under /api/v1/auth/**, plus the
 * Phase 1 health/version endpoints. Everything else - including
 * /api/v1/me/** - requires a valid Bearer access token.
 *
 * CSRF stays disabled and sessions stay stateless (rationale unchanged
 * from Phase 1): this is a bearer-token JSON API, not a cookie-session
 * app.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final CorsProperties corsProperties;

    public SecurityConfig(CorsProperties corsProperties) {
        this.corsProperties = corsProperties;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtAuthenticationFilter)
            throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/version").permitAll()
                        .requestMatchers("/actuator/health/**").permitAll()
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                // Spring Security's AnonymousAuthenticationFilter is on by
                // default, so an unauthenticated request is technically
                // "authenticated as anonymous" - without this, a missing/
                // invalid token trips AccessDeniedException (403), not
                // AuthenticationException (401). For a bearer-token API,
                // "no valid credentials" should be 401.
                .exceptionHandling(ex -> ex.authenticationEntryPoint(unauthorizedEntryPoint()))
                .build();
    }

    /**
     * Explicit CORS allowlist (FirstFood_V2_Phase2_Final_Review.md #20) -
     * a browser frontend on a different origin (e.g. localhost:3000
     * calling localhost:8080) gets rejected by the browser's same-origin
     * policy without this, since there was previously no CORS config at
     * all. Origins come from app.cors.allowed-origins - local defaults to
     * http://localhost:3000, production requires it set explicitly (see
     * application-production.yml) to the real frontend origin. Never use
     * "*" here: allowCredentials(true) with a wildcard origin is both
     * invalid per the CORS spec and unsafe for an authenticated API.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(corsProperties.allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    private AuthenticationEntryPoint unauthorizedEntryPoint() {
        return (request, response, authException) -> {
            response.setStatus(401);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(
                    "{\"code\":\"UNAUTHENTICATED\",\"message\":\"Authentication required.\",\"timestamp\":\""
                            + Instant.now() + "\"}");
        };
    }

    /**
     * Used to hash OTPs (identity.OtpServiceImpl) - a slow, salted KDF is
     * appropriate there since a 6-digit OTP is low-entropy. Refresh
     * tokens use a different, deterministic hash instead - see
     * identity.RefreshTokenHasher's javadoc for why.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
