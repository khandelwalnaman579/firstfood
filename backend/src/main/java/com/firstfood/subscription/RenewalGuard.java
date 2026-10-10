package com.firstfood.subscription;

import java.util.UUID;

/**
 * A veto other modules can hold over renewing a subscription, without the subscription module having to know them
 * (the extension module depends on subscription, so subscription cannot depend back on it - the same pattern as
 * {@code MembershipDeactivationGuard}). Every Spring bean implementing this is consulted by
 * {@link SubscriptionService#renew}.
 *
 * Called inside the renewal transaction, after authorization, after the provider row lock and the subscription row
 * lock are taken and after the subscription has been confirmed ended and not yet renewed. Throw a domain exception to
 * refuse; never mutate state from here.
 */
public interface RenewalGuard {

    void assertCanRenew(UUID providerId, UUID subscriptionId);
}
