package com.firstfood.identity;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface RefreshSessionRepository extends JpaRepository<RefreshSession, UUID> {

    /**
     * Refresh tokens are hashed with a deterministic digest (see
     * {@link RefreshTokenHasher}), not BCrypt - unlike an OTP or
     * password, a refresh token is already a high-entropy random secret,
     * so a deterministic hash is safe here and (unlike BCrypt) supports a
     * direct equality lookup instead of needing a separate lookup key.
     */
    Optional<RefreshSession> findByRefreshTokenHash(String refreshTokenHash);

    /**
     * Same lookup, but takes a {@code SELECT ... FOR UPDATE} row lock for
     * the rest of the transaction (FirstFood_V2_Phase2_Final_Review.md
     * #11). Without this, two concurrent refresh requests for the same
     * token can both read the session as "still active" before either
     * has committed its revocation, letting the same refresh token be
     * used twice. Use this - not the plain lookup above - anywhere a
     * refresh token is about to be validated-then-mutated (rotate,
     * revoke).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RefreshSession r where r.refreshTokenHash = :hash")
    Optional<RefreshSession> findByRefreshTokenHashForUpdate(String hash);

    /**
     * Phase 10 cleanup: deletes up to {@code limit} sessions that are dead for good - expired before {@code cutoff}, or
     * revoked before it. A live session (not revoked, not expired) never matches, and neither does a session that
     * only recently died (the retention period keeps it for investigation). Returns the number deleted.
     */
    @Modifying
    @Query(value = """
            delete from refresh_session where id in (
                select id from refresh_session
                 where expires_at < :cutoff or (revoked_at is not null and revoked_at < :cutoff)
                 order by issued_at limit :limit)
            """, nativeQuery = true)
    int deleteDeadBefore(@org.springframework.data.repository.query.Param("cutoff") Instant cutoff,
            @org.springframework.data.repository.query.Param("limit") int limit);
}
