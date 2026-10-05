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

/** Append-only audit record (the database also rejects UPDATE/DELETE). Ids only - no secrets. */
@Entity
@Table(name = "provider_role_audit")
public class ProviderRoleAudit {

    @Id
    private UUID id;

    @Column(name = "provider_id", nullable = false, updatable = false)
    private UUID providerId;

    @Column(name = "actor_account_id", nullable = false, updatable = false)
    private UUID actorAccountId;

    @Column(name = "target_account_id", nullable = false, updatable = false)
    private UUID targetAccountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 30)
    private RoleAuditAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "old_role", updatable = false, length = 20)
    private ProviderRole oldRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_role", updatable = false, length = 20)
    private ProviderRole newRole;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ProviderRoleAudit() {
        // JPA
    }

    public ProviderRoleAudit(UUID providerId, UUID actorAccountId, UUID targetAccountId, RoleAuditAction action,
            ProviderRole oldRole, ProviderRole newRole) {
        this.id = UUID.randomUUID();
        this.providerId = providerId;
        this.actorAccountId = actorAccountId;
        this.targetAccountId = targetAccountId;
        this.action = action;
        this.oldRole = oldRole;
        this.newRole = newRole;
    }

    public UUID getId() {
        return id;
    }

    public UUID getProviderId() {
        return providerId;
    }

    public UUID getActorAccountId() {
        return actorAccountId;
    }

    public UUID getTargetAccountId() {
        return targetAccountId;
    }

    public RoleAuditAction getAction() {
        return action;
    }

    public ProviderRole getOldRole() {
        return oldRole;
    }

    public ProviderRole getNewRole() {
        return newRole;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
