package com.firstfood.identity;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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
}
