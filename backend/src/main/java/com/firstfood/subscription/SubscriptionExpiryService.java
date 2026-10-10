package com.firstfood.subscription;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The one way another module may move a subscription's effective expiry (Phase 9). The extension engine decides
 * whether and by how much; this records the outcome on the subscription so the {@code Subscription} aggregate stays
 * the only thing that writes its own rows (rules.md Rule 27.3/27.4).
 *
 * Performs NO authorization and decides nothing: the caller must have authorized the actor, taken the subscription
 * row lock ({@link SubscriptionLookupService#lock}) and written the {@code ExtensionEvent} that explains the move
 * (the database refuses, at commit, an expiry that its events do not add up to).
 */
public interface SubscriptionExpiryService {

    /**
     * Moves the effective expiry of an ACTIVE DAY subscription to {@code newExpiry}, which must be later than the
     * current one. Flushes, so a database refusal surfaces here. Must run in a transaction.
     */
    void moveEffectiveExpiry(UUID subscriptionId, LocalDate newExpiry);
}
