package com.firstfood.extension;

import com.firstfood.common.error.DomainException;
import org.springframework.http.HttpStatus;

/** Business-rule violations in the extension engine. Use the factories. */
public class ExtensionException extends DomainException {

    private ExtensionException(String code, String message, HttpStatus status) {
        super(code, message, status);
    }

    public static ExtensionException subscriptionNotFound() {
        return new ExtensionException("SUBSCRIPTION_NOT_FOUND", "Subscription not found.", HttpStatus.NOT_FOUND);
    }

    /** Same code/status as the provider module's ProviderClosedException (no dependency on its type). */
    public static ExtensionException providerClosed() {
        return new ExtensionException("PROVIDER_CLOSED",
                "This provider is closed and can no longer be modified.", HttpStatus.CONFLICT);
    }

    /** The refusal that matches why the subscription cannot be extended. */
    static ExtensionException blocked(ExtensionBlock block) {
        return switch (block) {
            case NOT_SUPPORTED_FOR_MEAL -> new ExtensionException("EXTENSION_NOT_SUPPORTED",
                    "Meal-based subscriptions are not extended by absence: they last until the meals are used.",
                    HttpStatus.CONFLICT);
            case NOT_ALLOWED_BY_POLICY -> new ExtensionException("EXTENSION_NOT_ALLOWED",
                    "This subscription was sold without extension for absence.", HttpStatus.CONFLICT);
            case SUBSCRIPTION_CANCELLED -> new ExtensionException("SUBSCRIPTION_NOT_ACTIVE",
                    "This subscription was cancelled, so it can no longer be extended.", HttpStatus.CONFLICT);
            case SUBSCRIPTION_EXPIRED -> new ExtensionException("SUBSCRIPTION_NOT_ACTIVE",
                    "This subscription has expired, so it can no longer be extended.", HttpStatus.CONFLICT);
            case ALREADY_RENEWED -> new ExtensionException("SUBSCRIPTION_ALREADY_RENEWED",
                    "This subscription has already been renewed, so it can no longer be extended.",
                    HttpStatus.CONFLICT);
        };
    }

    /** Extending would run into another active subscription of the same customer at this provider. */
    static ExtensionException overlapsAnotherSubscription() {
        return new ExtensionException("EXTENSION_OVERLAPS_SUBSCRIPTION",
                "Extending would overlap another active subscription of this customer.", HttpStatus.CONFLICT);
    }

    /** Backstop for a unique-key hit that the subscription row lock should make unreachable. */
    static ExtensionException concurrentChange() {
        return new ExtensionException("EXTENSION_CONFLICT",
                "The subscription was changed at the same moment. Please try again.", HttpStatus.CONFLICT);
    }
}
