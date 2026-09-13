package com.firstfood.identity.error;

import com.firstfood.common.error.DomainException;
import org.springframework.http.HttpStatus;

public class OtpRateLimitExceededException extends DomainException {

    public OtpRateLimitExceededException() {
        super("OTP_RATE_LIMIT_EXCEEDED",
                "Too many OTP requests for this number. Please try again later.",
                HttpStatus.TOO_MANY_REQUESTS);
    }
}
