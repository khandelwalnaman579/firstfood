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

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ProviderRoleAssignment() {
        // JPA
    }

    public ProviderRoleAssignment(UUID providerId, UUID accountId, ProviderRole role) {
        this.id = UUID.randomUUID();
        this.providerId = providerId;
        this.accountId = accountId;
        this.role = role;
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
