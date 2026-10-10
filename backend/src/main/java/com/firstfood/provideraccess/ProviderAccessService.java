package com.firstfood.provideraccess;

import java.util.Map;
import java.util.UUID;

/**
 * The one reusable, server-side provider-scoped authorization boundary.
 * Every provider-scoped operation must call {@link #requirePermission} before
 * touching the provider - controllers and services must not implement their
 * own role checks or compare roles.
 *
 * Authorization is always (accountId, providerId) -> ACTIVE role assignment ->
 * permission, resolved from the database. The accountId comes from the
 * authenticated principal; a client-supplied account, provider or role is
 * never proof of anything.
 */
public interface ProviderAccessService {

    /**
     * Verifies the account holds an ACTIVE role on the provider that grants
     * {@code permission}.
     *
     * @return the role the account actually holds
     * @throws ProviderNotFoundException (404) if the provider doesn't exist or the account
     *     has no active role on it - the two cases are indistinguishable on purpose
     * @throws InsufficientPermissionException (403) if the account is an active member
     *     but its role does not grant the permission
     */
    ProviderRole requirePermission(UUID accountId, UUID providerId, ProviderPermission permission);

    /**
     * Creates the mandatory OWNER assignment for a newly created provider.
     * Must be called in the same transaction as the provider insert. The
     * caller (backend code) chooses the account - never a client value.
     */
    void assignOwner(UUID providerId, UUID accountId);

    /** Every provider the account holds an ACTIVE role on, with that role. */
    Map<UUID, ProviderRole> rolesFor(UUID accountId);
}
