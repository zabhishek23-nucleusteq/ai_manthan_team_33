package com.expenseflow.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Issues HS256 access tokens: {@code sub} = user id, {@code email}, {@code roles} (without ROLE_ prefix),
 * {@code iat}, {@code exp} (default 8 h, NFR-SEC). Decoding/validation is done by the resource server
 * ({@link SecurityConfig#jwtDecoder}).
 */
@Service
public class JwtTokenService {

    public static final String ISSUER = "expenseflow";
    public static final String ROLES_CLAIM = "roles";

    private final JwtEncoder encoder;
    private final Duration ttl;
    private final Clock clock;

    public JwtTokenService(JwtEncoder encoder, @Value("${app.jwt.ttl:PT8H}") Duration ttl) {
        this.encoder = encoder;
        this.ttl = ttl;
        this.clock = Clock.systemUTC();
    }

    public record IssuedToken(String value, long expiresInSeconds) {
    }

    public IssuedToken issue(long userId, String email, Collection<String> roles) {
        return issue(userId, email, roles, Instant.now(clock), ttl);
    }

    /** Explicit issue time / lifetime; used by tests for expired tokens. */
    public IssuedToken issue(long userId, String email, Collection<String> roles, Instant issuedAt, Duration lifetime) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(Long.toString(userId))
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(lifetime))
                .claim("email", email)
                .claim(ROLES_CLAIM, List.copyOf(roles))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, lifetime.toSeconds());
    }

    public long ttlSeconds() {
        return ttl.toSeconds();
    }
}
