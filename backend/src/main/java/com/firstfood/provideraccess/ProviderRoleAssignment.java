package com.firstfood.provideraccess;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Links a UserAccount to a FoodProvider with a role. Deliberately holds plain
 * UUIDs, not entity references: provideraccess must not depend on the provider
 * module's entity (execution plan §4), otherwise provider -> provideraccess
 * (creating the owner) and provideraccess -> provider would form a cycle.
 * Referential integrity is enforced by the database foreign keys instead.
 *
 * Phase 4 lifecycle: an assignment is ACTIVE until revoked; it is never
 * deleted. The role itself never changes on a row - a role change is a
 * revocation plus a new assignment, so history stays intact.
 */
@Entity
@Table(name = "provider_role_assignment")
public class ProviderRoleAssignment {

    @Id
    private UUID id;

    @Column(name = "provider_id", nullable = false, updatable = false)
    private UUID providerId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private ProviderRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RoleAssignmentStatus status = RoleAssignmentStatus.ACTIVE;

    @Column(name = "assigned_by", updatable = false)
    private UUID assignedBy;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_by")
    private UUID revokedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ProviderRoleAssignment() {
        // JPA
    }

    public ProviderRoleAssignment(UUID providerId, UUID accountId, ProviderRole role, UUID assignedBy) {
        this.id = UUID.randomUUID();
        this.providerId = providerId;
        this.accountId = accountId;
        this.role = role;
        this.assignedBy = assignedBy;
    }

    /** Marks the assignment revoked. Callers must have validated authority and invariants. */
    public void revoke(UUID byAccountId, Instant at) {
        if (status == RoleAssignmentStatus.REVOKED) {
            throw new IllegalStateException("Assignment already revoked");
        }
        this.status = RoleAssignmentStatus.REVOKED;
        this.revokedBy = byAccountId;
        this.revokedAt = at;
    }

    public boolean isActive() {
        return status == RoleAssignmentStatus.ACTIVE;
    }

    public UUID getId() {
        return id;
    }

    public UUID getProviderId() {
        return providerId;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public ProviderRole getRole() {
        return role;
    }

    public RoleAssignmentStatus getStatus() {
        return status;
    }

    public UUID getAssignedBy() {
        return assignedBy;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public UUID getRevokedBy() {
        return revokedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
