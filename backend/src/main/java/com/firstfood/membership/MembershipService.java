package com.firstfood.membership;

import java.util.List;
import java.util.UUID;

/**
 * Provider-side customer management plus the customer's own view. Every provider-scoped
 * method authorizes through ProviderAccessService; the acting account always comes from
 * the authenticated principal, never from request data.
 */
public interface MembershipService {

    /**
     * Adds the registered account with {@code phone} as a customer (rejoin creates a new
     * membership stint). Requires MEMBERSHIP_MANAGE. The provider must not be closed and
     * must be accepting new customers.
     */
    CustomerView addCustomer(UUID actorAccountId, UUID providerId, String phone, String fullName);

    /** Requires MEMBERSHIP_VIEW. Active members only unless {@code includeInactive}. */
    List<CustomerView> listCustomers(UUID actorAccountId, UUID providerId, boolean includeInactive);

    /** Requires MEMBERSHIP_VIEW. A membership of another provider is reported as not found. */
    CustomerView getCustomer(UUID actorAccountId, UUID providerId, UUID membershipId);

    /** Ends the membership (history kept). Requires MEMBERSHIP_MANAGE; blocked on closed providers. */
    CustomerView deactivate(UUID actorAccountId, UUID providerId, UUID membershipId);

    /** The caller's own memberships (all their persons, all providers, current and past). */
    List<MyMembershipView> listMine(UUID accountId);
}
