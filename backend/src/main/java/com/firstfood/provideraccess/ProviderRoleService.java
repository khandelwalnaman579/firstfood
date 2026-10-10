package com.firstfood.provideraccess;

import java.util.List;
import java.util.UUID;

/**
 * Provider role lifecycle (Phase 4 Checkpoint 1). The acting account always
 * comes from the authenticated principal, never from a request body.
 *
 * Every operation authorizes through
 * {@link ProviderAccessService#requirePermission} - no role comparisons here.
 */
public interface ProviderRoleService {

    /**
     * OWNER assigns MANAGER or WORKER to the account registered under
     * {@code targetPhone}. OWNER cannot be assigned here (transfer only).
     */
    RoleAssignmentView assignRole(UUID actorAccountId, UUID providerId, String targetPhone, ProviderRole role);

    /** OWNER revokes a MANAGER/WORKER assignment. OWNER assignments are not revocable. */
    RoleAssignmentView revokeRole(UUID actorAccountId, UUID providerId, UUID assignmentId);

    /** OWNER or MANAGER may list the provider's ACTIVE assignments. */
    List<RoleAssignmentView> findRoles(UUID actorAccountId, UUID providerId);

    /**
     * Atomically makes the account registered under {@code targetPhone} the
     * OWNER; the current OWNER (the actor) becomes MANAGER in the same
     * transaction. Returns the new OWNER's and the former OWNER's assignments.
     * Any prior assignment of the target is superseded.
     */
    List<RoleAssignmentView> transferOwnership(UUID actorAccountId, UUID providerId, String targetPhone);

    /** True if the account holds exactly this role, ACTIVE, on the provider. No actor check (internal use). */
    boolean hasRole(UUID providerId, UUID accountId, ProviderRole role);
}
