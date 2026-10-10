package com.firstfood.subscription;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * SYSTEM ONLY (Phase 10): what the scheduled lifecycle jobs may ask of the subscription module. Like
 * {@link SubscriptionExpiryService} it performs no authorization - the caller is trusted application code, never a
 * request, and no controller may depend on it. It exists so jobs never touch {@link Subscription} or its repository
 * (rules.md Rule 27.3/27.4) and so the rule "when is a subscription over" stays inside the module that owns it.
 *
 * The scans are cheap candidate lists, keyset-paged by subscription id so that an item which keeps failing is skipped
 * for the rest of one run (and retried on the next) instead of blocking everything behind it. They are a hint only:
 * every mutation re-checks the rule under the subscription's row lock.
 */
public interface SubscriptionLifecycleService {

    /** ACTIVE subscriptions whose effective expiry is before {@code today}, ordered by id, ids greater than {@code afterId}. */
    List<SubscriptionKey> findLapsed(LocalDate today, UUID afterId, int limit);

    /**
     * ACTIVE, already started DAY subscriptions sold with extension that have not been renewed and have not lapsed:
     * the ones for which absence may have earned an extension. Ordered by id, ids greater than {@code afterId}.
     */
    List<SubscriptionKey> findExtensionCandidates(LocalDate today, UUID afterId, int limit);

    /**
     * ACTIVE -> EXPIRED, but only if the subscription really is lapsed on {@code today} (still ACTIVE, effective expiry
     * before today). Returns whether it was expired; a subscription that was extended, renewed or cancelled since the
     * scan is left alone and the answer is false, which makes a repeated or racing run harmless (Rule 22.1).
     *
     * Must run in a transaction in which the caller already holds the subscription row lock
     * ({@link SubscriptionLookupService#lock}). Flushes, so a database refusal surfaces here.
     */
    boolean expireIfLapsed(UUID subscriptionId, LocalDate today);
}
