package com.firstfood.provider.error;

import com.firstfood.common.error.DomainException;
import org.springframework.http.HttpStatus;

/** CLOSED is terminal and read-only (freeze #9): no edits, no reopening. */
public class ProviderClosedException extends DomainException {

    public ProviderClosedException() {
        super("PROVIDER_CLOSED", "This provider is closed and can no longer be modified.", HttpStatus.CONFLICT);
    }
}
