package com.firstfood.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * Hashes refresh tokens for at-rest storage using a plain deterministic
 * SHA-256 digest, deliberately NOT the {@code BCryptPasswordEncoder} used
 * for OTPs.
 *
 * A refresh token is generated as 256 bits of {@link java.security.SecureRandom}
 * output - it is already a high-entropy secret, not a human-guessable
 * value like a 6-digit OTP or a password, so it doesn't need a slow,
 * salted KDF to resist offline brute force. A deterministic hash lets the
 * refresh/logout endpoints look a session up directly by
 * {@code WHERE refresh_token_hash = ?} instead of needing a separate
 * lookup key alongside a BCrypt hash.
 */
@Component
public class RefreshTokenHasher {

    public String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed available on every JVM - this can't happen.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
