package com.firstfood.identity;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefreshSessionRepository extends JpaRepository<RefreshSession, UUID> {

    /**
     * Refresh tokens are hashed with a deterministic digest (see
     * {@link RefreshTokenHasher}), not BCrypt - unlike an OTP or
     * password, a refresh token is already a high-entropy random secret,
     * so a deterministic hash is safe here and (unlike BCrypt) supports a
     * direct equality lookup instead of needing a separate lookup key.
     */
    Optional<RefreshSession> findByRefreshTokenHash(String refreshTokenHash);
}
