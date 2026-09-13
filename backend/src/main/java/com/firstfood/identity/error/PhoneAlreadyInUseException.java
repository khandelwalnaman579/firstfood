package com.firstfood.identity.error;

import com.firstfood.common.error.DomainException;
import org.springframework.http.HttpStatus;

public class PhoneAlreadyInUseException extends DomainException {

    public PhoneAlreadyInUseException() {
        super("PHONE_ALREADY_IN_USE", "This phone number is already associated with an account.",
                HttpStatus.CONFLICT);
    }
}
