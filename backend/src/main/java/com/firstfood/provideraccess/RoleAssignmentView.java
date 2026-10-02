package com.firstfood.provideraccess;

import java.time.Instant;
import java.util.UUID;

/**
 * Read model for a role assignment. {@code accountPhone} is shown to callers
 * who may view the team (OWNER/MANAGER) so the UI can identify members; it is
 * never stored in audit records.
 */
public record RoleAssignmentView(
        UUID id,
        UUID providerId,
        UUID accountId,
        String accountPhone,
        ProviderRole role,
        RoleAssignmentStatus status,
        UUID assignedBy,
        Instant assignedAt,
        Instant revokedAt,
        UUID revokedBy) {

    static RoleAssignmentView from(ProviderRoleAssignment a, String accountPhone) {
        return new RoleAssignmentView(a.getId(), a.getProviderId(), a.getAccountId(), accountPhone, a.getRole(),
                a.getStatus(), a.getAssignedBy(), a.getCreatedAt(), a.getRevokedAt(), a.getRevokedBy());
    }
}
