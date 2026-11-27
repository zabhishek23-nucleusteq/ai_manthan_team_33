package com.expenseflow.security;

import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Base security from the scaffold (T-002). L3 (security owner) extends this: users/roles, login/signup,
 * JwtEncoder, JSON 401/403 handlers. Stateless; JWT (HS256, secret from JWT_SECRET) with a "roles" claim
 * mapped to ROLE_* authorities so @PreAuthorize("hasRole('X')") works everywhere.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
            .cors(cors -> {})
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/signup", "/api/auth/login").permitAll()
                .requestMatchers("/error").permitAll()
                // Role check in the filter chain, before body parsing/validation: a non-admin always gets 403,
                // never a 400 that leaks the request schema (@PreAuthorize on AdminController stays as 2nd layer).
                .requestMatchers("/api/admin/**").hasRole("SUPER_ADMIN")
                // L2 (T-016): same for claims/receipts - 403 before body validation or the Idempotency-Key filter can
                // answer 400 (rbac.md section 3: claims/receipts = EMP, MGR, AUD, HEAD)
                .requestMatchers("/api/claims", "/api/claims/**", "/api/receipts", "/api/receipts/**")
                    .hasAnyRole("EMPLOYEE", "MANAGER", "FINANCE_AUDITOR", "FINANCE_HEAD")
                // L2 (T-017): payouts - read/export AUD + HEAD, create/submit HEAD only (before the Idempotency-Key filter)
                .requestMatchers(HttpMethod.GET, "/api/payout-batches", "/api/payout-batches/**").hasAnyRole("FINANCE_AUDITOR", "FINANCE_HEAD")
                .requestMatchers("/api/payout-batches", "/api/payout-batches/**").hasRole("FINANCE_HEAD")
                .anyRequest().authenticated())
            .exceptionHandling(e -> e
                .authenticationEntryPoint(JsonSecurityErrorHandlers.entryPoint())
                .accessDeniedHandler(JsonSecurityErrorHandlers.accessDeniedHandler()))
            .oauth2ResourceServer(o -> o
                .authenticationEntryPoint(JsonSecurityErrorHandlers.entryPoint())
                .accessDeniedHandler(JsonSecurityErrorHandlers.accessDeniedHandler())
                .jwt(j -> j.jwtAuthenticationConverter(jwtAuthenticationConverter())));
        return http.build();
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        return NimbusJwtEncoder.withSecretKey(jwtSecretKey).algorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecretKey jwtSecretKey(@Value("${app.jwt.secret}") String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("JWT_SECRET must be at least 32 bytes");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSecretKey) {
        return NimbusJwtDecoder.withSecretKey(jwtSecretKey).macAlgorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("roles");
        roles.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(roles);
        return converter;
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${app.cors.allowed-origins}") List<String> origins) {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(origins);
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key"));
        cfg.setExposedHeaders(List.of("Content-Disposition"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }
}
