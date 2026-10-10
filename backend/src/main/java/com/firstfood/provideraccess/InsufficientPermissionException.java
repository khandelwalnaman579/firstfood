package com.firstfood.provideraccess;

import com.firstfood.common.error.DomainException;
import org.springframework.http.HttpStatus;

/**
 * The caller is an active member of an existing provider but lacks the
 * required permission (Phase 4 decision: 403). Non-members still get
 * {@link ProviderNotFoundException} (404) so provider existence stays hidden.
 */
public class InsufficientPermissionException extends DomainException {

    public InsufficientPermissionException() {
        super("INSUFFICIENT_PERMISSION", "You do not have permission to perform this action.",
                HttpStatus.FORBIDDEN);
    }
}
