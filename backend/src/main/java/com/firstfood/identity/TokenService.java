package com.firstfood.identity;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * Issues and validates short-lived JWT access tokens (architecture.md
 * #12 "Keep access tokens short-lived"). The refresh token is a
 * separate, opaque, revocable concept handled by {@link RefreshSessionService}
 * - it is deliberately not a JWT, so it can be revoked server-side
 * (rules.md/architecture.md emphasize backend-authoritative auth).
 */
@Service
public class TokenService {

    private final SecretKey signingKey;
    private final Duration accessTokenTtl;

    public TokenService(JwtProperties jwtProperties) {
        this.signingKey = Keys.hmacShaKeyFor(jwtProperties.secret().getBytes(StandardCharsets.UTF_8));
        this.accessTokenTtl = Duration.ofMinutes(jwtProperties.accessTokenTtlMinutes());
    }

    public String issueAccessToken(UUID accountId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(accountId.toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTokenTtl)))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Returns the authenticated account id if the token is structurally
     * valid, unexpired, and correctly signed - empty otherwise. Never
     * throws for a bad/expired token; callers (the security filter)
     * should treat "empty" as "unauthenticated", not as a server error.
     */
    public Optional<UUID> parseAccountId(String token) {
        try {
            String subject = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload()
                    .getSubject();
            return Optional.of(UUID.fromString(subject));
        } catch (JwtException | IllegalArgumentException e) {
            // Never log the token itself here (rules.md Rule 17.4).
            return Optional.empty();
        }
    }
}
