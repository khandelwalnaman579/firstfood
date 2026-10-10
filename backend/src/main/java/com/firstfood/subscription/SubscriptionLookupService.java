package com.firstfood.subscription;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Narrow lookup for other modules (attendance). Exists so they never touch {@link Subscription},
 * {@link SubscriptionTermSnapshot} or their repositories (rules.md Rule 27.3/27.4).
 * Performs NO authorization - callers must have called
 * {@link com.firstfood.provideraccess.ProviderAccessService#requirePermission} first (provider side) or
 * resolve the caller's own person ids (customer side).
 */
public interface SubscriptionLookupService {

    /** Scoped by provider: a subscription of another provider is simply absent (no IDOR). */
    Optional<SubscriptionRef> find(UUID providerId, UUID subscriptionId);

    /** Scoped by the caller's own persons: someone else's subscription is simply absent. */
    Optional<SubscriptionRef> findOwned(Collection<UUID> personIds, UUID subscriptionId);

    /**
     * Same as {@link #find} but takes the subscription row lock until the transaction ends, so the caller's
     * change runs after any cancel/renew/attendance change already in flight. Must run in a transaction.
     */
    Optional<SubscriptionRef> lock(UUID providerId, UUID subscriptionId);

    /** Same as {@link #findOwned} with the subscription row lock. Must run in a transaction. */
    Optional<SubscriptionRef> lockOwned(Collection<UUID> personIds, UUID subscriptionId);

    /** DAY subscriptions of the provider that were entitled on {@code date} (see the repository query). */
    List<SubscriptionRef> findDayEntitledOn(UUID providerId, LocalDate date);
}
