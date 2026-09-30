package com.firstfood.provideraccess;

import java.util.Map;
import java.util.UUID;

/**
 * The one reusable, server-side provider-scoped authorization mechanism
 * (freeze #5). Every provider-scoped operation must call
 * {@link #assertProviderRole} before touching the provider - controllers and
 * services must not implement their own provider access checks.
 *
 * Authorization is always (accountId, providerId, role) resolved from the
 * database. The accountId comes from the authenticated principal; a
 * client-supplied providerId or role is never proof of anything.
 */
public interface ProviderAccessService {

    /**
     * Verifies the account holds {@code requiredRole} (or a role that
     * satisfies it) on the provider.
     *
     * @return the strongest role the account actually holds
     * @throws ProviderNotFoundException if the account has no sufficient role
     *     on that provider, or the provider doesn't exist
     */
    ProviderRole assertProviderRole(UUID accountId, UUID providerId, ProviderRole requiredRole);

    /**
     * Creates the mandatory OWNER assignment for a newly created provider.
     * Must be called in the same transaction as the provider insert. The
     * caller (backend code) chooses the account - never a client value.
     */
    void assignOwner(UUID providerId, UUID accountId);

    /** Every provider the account holds a role on, with its strongest role. */
    Map<UUID, ProviderRole> rolesFor(UUID accountId);
}
