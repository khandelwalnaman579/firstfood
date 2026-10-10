package com.firstfood.subscription;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Selling and managing subscriptions. Provider-side methods authorize through the provider's roles
 * (SUBSCRIPTION_VIEW / SUBSCRIPTION_MANAGE); customer-side methods are scoped to the caller's own
 * Persons. Attendance, absence, extension and payment are later phases and are not here.
 */
public interface SubscriptionService {

    /** Sells {@code planId} to the membership: subscription + frozen term snapshot, atomically. */
    SubscriptionView create(UUID actorAccountId, UUID providerId, UUID membershipId, UUID planId, LocalDate startDate);

    /** Optionally filtered by membership and/or stored status; newest first. */
    List<SubscriptionView> list(UUID actorAccountId, UUID providerId, UUID membershipId, SubscriptionStatus status);

    SubscriptionView get(UUID actorAccountId, UUID providerId, UUID subscriptionId);

    SubscriptionView cancel(UUID actorAccountId, UUID providerId, UUID subscriptionId, String reason);

    /**
     * A new commercial event following an ended subscription: new subscription, new snapshot at
     * today's price and policy. {@code planId} and {@code startDate} may be null (same plan, today).
     */
    SubscriptionView renew(UUID actorAccountId, UUID providerId, UUID subscriptionId, UUID planId,
            LocalDate startDate);

    List<MySubscriptionView> listMine(UUID accountId);

    MySubscriptionView getMine(UUID accountId, UUID subscriptionId);
}
