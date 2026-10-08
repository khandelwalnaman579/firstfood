package com.firstfood.attendance;

/**
 * Why a CUSTOMER cannot declare or cancel an absence for a day right now. Decided by the backend from the
 * subscription's own policy snapshot and sent to the client, so the UI explains the rule instead of
 * re-implementing it (rules.md Rule 28.1, PRD §28). Provider staff are not subject to any of these.
 */
public enum ChangeBlock {
    /** The day has gone; a customer cannot change the past. */
    PAST,
    /** Today, and the provider's policy does not allow same-day absence. */
    SAME_DAY_NOT_ALLOWED,
    /** Today, same-day is allowed, but the provider's cutoff time has passed. */
    CUTOFF_PASSED,
    /** Provider staff decided this day; only provider staff can change it again. */
    PROVIDER_CORRECTION,
    /** The subscription is cancelled or over. */
    SUBSCRIPTION_NOT_ACTIVE
}
