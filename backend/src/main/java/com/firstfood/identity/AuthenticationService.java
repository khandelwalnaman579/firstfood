package com.firstfood.identity;

import java.util.UUID;

/**
 * The identity module's public application-service surface (see
 * com.firstfood.identity package-info: other modules should depend on
 * this interface, never on the entities/repositories directly).
 *
 * Covers phases.md Phase 2's full exit-criteria flow: request OTP ->
 * verify OTP -> authenticate -> access protected API -> refresh session
 * -> logout, plus the OTP-gated sensitive phone-number-change flow
 * required by rules.md Rule 3.3.
 */
public interface AuthenticationService {

    void requestLoginOtp(String phone);

    /**
     * Verifies the login OTP and returns a fresh token pair. Creates a
     * new {@link UserAccount} on first successful login for a phone
     * number that hasn't been seen before - FirstFood has no separate
     * "sign up" step (rules.md Rule 3.3: mobile number is the
     * authentication-critical identity).
     */
    AuthTokens verifyLoginOtp(String phone, String code);

    AuthTokens refresh(String rawRefreshToken);

    void logout(String rawRefreshToken);

    /**
     * Starts the sensitive phone-number-change flow (rules.md Rule 3.3):
     * an OTP is sent to the NEW number to prove the caller controls it.
     * Requires an authenticated account - callers must already hold a
     * valid access token for the account being changed.
     */
    void requestPhoneChangeOtp(UUID accountId, String newPhone);

    void verifyPhoneChangeOtp(UUID accountId, String newPhone, String code);
}
