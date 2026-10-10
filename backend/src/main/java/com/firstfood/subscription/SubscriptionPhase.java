package com.firstfood.subscription;

/**
 * Where a subscription stands TODAY, derived from its status and dates by the backend so no client
 * has to recompute it. Differs from {@link SubscriptionStatus} because status is only updated when
 * something closes the subscription out: an ACTIVE row whose last day has passed is already ENDED
 * here, even if the (Phase 10) expiry job has not flipped it yet.
 */
public enum SubscriptionPhase {
    /** ACTIVE but the start date is still ahead. */
    UPCOMING,
    /** ACTIVE and today is within its dates (or it has no calendar limit). */
    RUNNING,
    /** Expired, or ACTIVE past its effective expiry date. Renewable. */
    ENDED,
    /** Cancelled by the provider. */
    CANCELLED
}
