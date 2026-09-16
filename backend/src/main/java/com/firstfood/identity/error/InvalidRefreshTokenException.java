package com.firstfood.identity.error;

import com.firstfood.common.error.DomainException;
import org.springframework.http.HttpStatus;

public class InvalidRefreshTokenException extends DomainException {

    public InvalidRefreshTokenException() {
        super("INVALID_REFRESH_TOKEN", "This session is no longer valid. Please log in again.",
                HttpStatus.UNAUTHORIZED);
    }
}
