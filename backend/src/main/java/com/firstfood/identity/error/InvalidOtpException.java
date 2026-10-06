package com.firstfood.identity.error;

import com.firstfood.common.error.DomainException;
import org.springframework.http.HttpStatus;

public class InvalidOtpException extends DomainException {

    public InvalidOtpException() {
        super("INVALID_OTP", "The code entered is incorrect.", HttpStatus.UNAUTHORIZED);
    }
}
