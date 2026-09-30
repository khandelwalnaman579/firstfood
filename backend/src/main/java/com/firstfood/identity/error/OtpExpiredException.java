package com.firstfood.identity.error;

import com.firstfood.common.error.DomainException;
import org.springframework.http.HttpStatus;

public class OtpExpiredException extends DomainException {

    public OtpExpiredException() {
        super("OTP_EXPIRED", "This code has expired. Please request a new one.", HttpStatus.UNAUTHORIZED);
    }
}
