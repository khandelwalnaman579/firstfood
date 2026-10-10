package com.firstfood.provider.error;

import com.firstfood.common.error.DomainException;
import com.firstfood.provider.ProviderStatus;
import org.springframework.http.HttpStatus;

public class InvalidProviderTransitionException extends DomainException {

    public InvalidProviderTransitionException(ProviderStatus from, ProviderStatus to) {
        super("PROVIDER_INVALID_TRANSITION",
                "A provider cannot move from " + from + " to " + to + ".", HttpStatus.CONFLICT);
    }
}
