package com.firstfood.common.error;

import org.springframework.http.HttpStatus;

/**
 * Base type for business-rule violations raised by domain/application
 * services (not controllers or repositories - see rules.md Rule 18.2 and
 * 18.3). Each module should define its own subclasses, e.g.
 * SubscriptionNotEligibleForExtensionException, rather than throwing this
 * class directly.
 */
public class DomainException extends RuntimeException {

    private final String errorCode;
    private final HttpStatus status;

    public DomainException(String errorCode, String message, HttpStatus status) {
        super(message);
        this.errorCode = errorCode;
        this.status = status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
