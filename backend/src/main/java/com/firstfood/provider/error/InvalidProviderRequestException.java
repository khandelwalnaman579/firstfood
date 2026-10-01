package com.firstfood.provider.error;

import com.firstfood.common.error.DomainException;
import org.springframework.http.HttpStatus;

/** The request contradicts itself or the provider's state invariants (400). */
public class InvalidProviderRequestException extends DomainException {

    public InvalidProviderRequestException(String message) {
        super("PROVIDER_INVALID_REQUEST", message, HttpStatus.BAD_REQUEST);
    }
}
