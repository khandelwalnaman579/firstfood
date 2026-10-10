package com.firstfood.subscription;

/**
 * Persisted lifecycle of a subscription (design.md §16). ACTIVE can become EXPIRED or CANCELLED;
 * both are final - a renewal is a NEW subscription, never a revival. Status says where the
 * subscription is in its lifecycle; it is not an entitlement calculation (see
 * {@link SubscriptionPhase} and the date/meal fields for that).
 */
public enum SubscriptionStatus {
    ACTIVE,
    EXPIRED,
    CANCELLED
}
