package com.firstfood.identity.dto;

import jakarta.validation.constraints.Email;

/**
 * Email is treated as non-sensitive for Phase 2 purposes - it can be
 * changed with just a valid access token, unlike phone number, which
 * requires OTP re-verification (rules.md Rule 3.3) since it's the
 * authentication-critical identity.
 */
public record UpdateEmailRequest(@Email String email) {
}
