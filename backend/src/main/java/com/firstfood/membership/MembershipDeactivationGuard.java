package com.firstfood.membership;

import java.util.UUID;

/**
 * A veto other modules can hold over ending a membership, without membership having to know
 * them (the subscription module depends on membership, so membership cannot depend back on it).
 * Every Spring bean implementing this is consulted by {@link MembershipService#deactivate}.
 *
 * Called inside the deactivation transaction, after authorization and after the provider row lock
 * is taken - so anything that sells against this provider is serialized with it. Throw a domain
 * exception to refuse; never mutate state from here.
 */
public interface MembershipDeactivationGuard {

    void assertCanDeactivate(UUID providerId, UUID membershipId);
}
