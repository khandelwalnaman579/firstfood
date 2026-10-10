package com.firstfood.provideraccess;

import com.firstfood.common.error.DomainException;
import org.springframework.http.HttpStatus;

/**
 * Thrown both when a provider does not exist AND when the caller has no
 * sufficient role on it. The two cases are deliberately indistinguishable
 * (same status, code and message) so provider IDs can't be probed for
 * existence (execution plan §5).
 */
public class ProviderNotFoundException extends DomainException {

    public ProviderNotFoundException() {
        super("PROVIDER_NOT_FOUND", "Provider not found.", HttpStatus.NOT_FOUND);
    }
}
