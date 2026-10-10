package com.firstfood.identity.error;

import com.firstfood.common.error.DomainException;
import org.springframework.http.HttpStatus;

/**
 * The JWT was cryptographically valid, but by the time the request
 * reached the controller the account it names no longer exists (an
 * extremely narrow race, since {@code JwtAuthenticationFilter} already
 * checks the account exists and is ACTIVE - this mostly guards against
 * that race rather than a common case). Treated as unauthenticated
 * (rather than a raw 500) per FirstFood_V2_Phase2_Final_Review.md #13.
 */
public class AuthenticatedAccountNotFoundException extends DomainException {

    public AuthenticatedAccountNotFoundException() {
        super("ACCOUNT_NOT_FOUND", "This session is no longer valid. Please log in again.",
                HttpStatus.UNAUTHORIZED);
    }
}
