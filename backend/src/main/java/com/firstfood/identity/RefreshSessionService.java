package com.firstfood.identity;

import com.firstfood.identity.error.InvalidRefreshTokenException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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
     *
     * Uses {@link RefreshSessionRepository#findByRefreshTokenHashForUpdate}
     * rather than the plain lookup: without the row lock, two concurrent
     * requests for the same token could both read it as "still active"
     * before either commits its revocation, letting the token be used
     * twice (FirstFood_V2_Phase2_Final_Review.md #11). With the lock, the
     * second concurrent request blocks until the first transaction
     * commits, then correctly sees the row already revoked.
     * Runs in its own transaction (REQUIRES_NEW), deliberately: it's
     * called from AuthenticationServiceImpl.refresh(), which may throw
     * AccountSuspendedException *after* this returns. Without
     * REQUIRES_NEW, Spring's default rollback-on-RuntimeException would
     * undo the revoke/reissue done here too - meaning a suspended
     * account's "already spent" refresh token would silently not
     * actually be spent, and a retry with the same token would hit the
     * same suspended check again instead of "invalid token". This
     * exact bug was caught by suspendedAccountCannotRefresh failing in
     * CI (second refresh attempt got 403 instead of 401).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RotationResult rotate(String rawToken) {
        RefreshSession session = refreshSessionRepository
                .findByRefreshTokenHashForUpdate(refreshTokenHasher.hash(rawToken))
                .filter(s -> s.isActive(Instant.now()))
                .orElseThrow(InvalidRefreshTokenException::new);

        session.revoke();
        refreshSessionRepository.save(session);

        String newRawToken = issue(session.getUserAccountId());
        return new RotationResult(session.getUserAccountId(), newRawToken);
    }

    @Transactional
    public void revoke(String rawToken) {
        refreshSessionRepository.findByRefreshTokenHashForUpdate(refreshTokenHasher.hash(rawToken))
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