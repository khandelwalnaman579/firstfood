package com.firstfood.subscription;

import com.firstfood.common.error.DomainException;
import org.springframework.http.HttpStatus;

/** Business-rule violations in subscription management. Use the factories. */
public class SubscriptionException extends DomainException {

    private SubscriptionException(String code, String message, HttpStatus status) {
        super(code, message, status);
    }

    public static SubscriptionException subscriptionNotFound() {
        return new SubscriptionException("SUBSCRIPTION_NOT_FOUND", "Subscription not found.", HttpStatus.NOT_FOUND);
    }

    public static SubscriptionException membershipNotFound() {
        return new SubscriptionException("MEMBERSHIP_NOT_FOUND", "Membership not found.", HttpStatus.NOT_FOUND);
    }

    public static SubscriptionException membershipNotActive() {
        return new SubscriptionException("MEMBERSHIP_NOT_ACTIVE",
                "This customer is no longer a member of the provider. Add them again first.", HttpStatus.CONFLICT);
    }

    public static SubscriptionException planNotFound() {
        return new SubscriptionException("PLAN_NOT_FOUND", "Plan not found.", HttpStatus.NOT_FOUND);
    }

    public static SubscriptionException planNotActive() {
        return new SubscriptionException("PLAN_NOT_ACTIVE",
                "This plan is inactive and cannot be sold. Activate it or choose another plan.", HttpStatus.CONFLICT);
    }

    public static SubscriptionException startInvalid(String message) {
        return new SubscriptionException("SUBSCRIPTION_START_INVALID", message, HttpStatus.BAD_REQUEST);
    }

    public static SubscriptionException periodInPast() {
        return new SubscriptionException("SUBSCRIPTION_PERIOD_IN_PAST",
                "That start date would make the subscription end before today.", HttpStatus.BAD_REQUEST);
    }

    public static SubscriptionException overlap() {
        return new SubscriptionException("SUBSCRIPTION_OVERLAP",
                "This customer already has an active subscription covering those days.", HttpStatus.CONFLICT);
    }

    public static SubscriptionException providerAtCapacity() {
        return new SubscriptionException("PROVIDER_AT_CAPACITY",
                "This provider has reached its limit of active subscriptions.", HttpStatus.CONFLICT);
    }

    /** Same code/status as the provider module's ProviderClosedException (no dependency on its type). */
    public static SubscriptionException providerClosed() {
        return new SubscriptionException("PROVIDER_CLOSED",
                "This provider is closed and can no longer be modified.", HttpStatus.CONFLICT);
    }

    public static SubscriptionException notActive() {
        return new SubscriptionException("SUBSCRIPTION_NOT_ACTIVE",
                "Only a subscription that has not ended or been cancelled can be cancelled.", HttpStatus.CONFLICT);
    }

    public static SubscriptionException notRenewable() {
        return new SubscriptionException("SUBSCRIPTION_NOT_RENEWABLE",
                "Only a subscription that has ended can be renewed. Wait until it ends, or sell a new one.",
                HttpStatus.CONFLICT);
    }

    public static SubscriptionException alreadyRenewed() {
        return new SubscriptionException("SUBSCRIPTION_ALREADY_RENEWED",
                "This subscription has already been renewed.", HttpStatus.CONFLICT);
    }

    public static SubscriptionException membershipHasActiveSubscription() {
        return new SubscriptionException("MEMBERSHIP_HAS_ACTIVE_SUBSCRIPTION",
                "This customer has an active subscription. Cancel it before removing them.", HttpStatus.CONFLICT);
    }
}
