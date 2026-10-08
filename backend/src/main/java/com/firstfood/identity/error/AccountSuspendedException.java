package com.firstfood.identity.error;

import com.firstfood.common.error.DomainException;
import org.springframework.http.HttpStatus;

public class AccountSuspendedException extends DomainException {

    public AccountSuspendedException() {
        super("ACCOUNT_SUSPENDED", "This account is suspended.", HttpStatus.FORBIDDEN);
    }
}
