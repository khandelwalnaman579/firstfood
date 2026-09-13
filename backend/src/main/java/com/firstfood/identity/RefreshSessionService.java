package com.firstfood.identity;

import com.firstfood.identity.error.InvalidRefreshTokenException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues, rotates, and revokes refresh sessions (architecture.md #12
 * "Rotate/revoke refresh sessions where appropriate"). Refresh tokens
 * rotate on every use: the old session is revoked and a brand new one
 * issued, so a stolen-and-replayed refresh token is detectable (the
 * legitimate client's next refresh attempt will fail because its token
 * was already rotated away).
 */
@Service
public class RefreshSessionService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshSessionRepository refreshSessionRepository;
    private final RefreshTokenHasher refreshTokenHasher;
    private final JwtProperties jwtProperties;

    public RefreshSessionService(
            RefreshSessionRepository refreshSessionRepository,
            RefreshTokenHasher refreshTokenHasher,
            JwtProperties jwtProperties) {
        this.refreshSessionRepository = refreshSessionRepository;
        this.refreshTokenHasher = refreshTokenHasher;
        this.jwtProperties = jwtProperties;
    }

    @Transactional
    public String issue(UUID accountId) {
        String rawToken = generateOpaqueToken();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(Duration.ofDays(jwtProperties.refreshTokenTtlDays()));

        refreshSessionRepository.save(
                new RefreshSession(accountId, refreshTokenHasher.hash(rawToken), now, expiresAt));
        return rawToken;
    }

    /**
     * Validates the given refresh token, revokes it, and issues a new one
     * for the same account. Returns {accountId, newRawRefreshToken}.
     */
    @Transactional
    public RotationResult rotate(String rawToken) {
        RefreshSession session = refreshSessionRepository
                .findByRefreshTokenHash(refreshTokenHasher.hash(rawToken))
                .filter(s -> s.isActive(Instant.now()))
                .orElseThrow(InvalidRefreshTokenException::new);

        session.revoke();
        refreshSessionRepository.save(session);

        String newRawToken = issue(session.getUserAccountId());
        return new RotationResult(session.getUserAccountId(), newRawToken);
    }

    @Transactional
    public void revoke(String rawToken) {
        refreshSessionRepository.findByRefreshTokenHash(refreshTokenHasher.hash(rawToken))
                .ifPresent(session -> {
                    session.revoke();
                    refreshSessionRepository.save(session);
                });
    }

    private String generateOpaqueToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record RotationResult(UUID accountId, String rawRefreshToken) {
    }
}
