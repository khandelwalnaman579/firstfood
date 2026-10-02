package com.firstfood.membership;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * One membership stint of a Person at a FoodProvider. Leaving ends this row
 * (INACTIVE, leftAt/leftBy set); rejoining creates a NEW row, so every earlier
 * period is preserved untouched. Never deleted. Holds plain ids (no entity
 * references across modules); the database foreign keys enforce integrity.
 */
@Entity
@Table(name = "provider_membership")
public class ProviderMembership {

    @Id
    private UUID id;

    @Column(name = "provider_id", nullable = false, updatable = false)
    private UUID providerId;

    @Column(name = "person_id", nullable = false, updatable = false)
    private UUID personId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MembershipStatus status = MembershipStatus.ACTIVE;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;

    @Column(name = "left_at")
    private Instant leftAt;

    @Column(name = "added_by", nullable = false, updatable = false)
    private UUID addedBy;

    @Column(name = "left_by")
    private UUID leftBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ProviderMembership() {
        // JPA
    }

    public ProviderMembership(UUID providerId, UUID personId, UUID addedBy, Instant joinedAt) {
        this.id = UUID.randomUUID();
        this.providerId = Objects.requireNonNull(providerId, "providerId");
        this.personId = Objects.requireNonNull(personId, "personId");
        this.addedBy = Objects.requireNonNull(addedBy, "addedBy");
        this.joinedAt = Objects.requireNonNull(joinedAt, "joinedAt");
    }

    /** Ends this membership stint. Callers must have validated authority. */
    public void deactivate(UUID byAccountId, Instant at) {
        if (status == MembershipStatus.INACTIVE) {
            throw new IllegalStateException("Membership already inactive");
        }
        this.status = MembershipStatus.INACTIVE;
        this.leftBy = Objects.requireNonNull(byAccountId, "byAccountId");
        this.leftAt = Objects.requireNonNull(at, "at");
    }

    public boolean isActive() {
        return status == MembershipStatus.ACTIVE;
    }

    public UUID getId() {
        return id;
    }

    public UUID getProviderId() {
        return providerId;
    }

    public UUID getPersonId() {
        return personId;
    }

    public MembershipStatus getStatus() {
        return status;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }

    public Instant getLeftAt() {
        return leftAt;
    }

    public UUID getAddedBy() {
        return addedBy;
    }

    public UUID getLeftBy() {
        return leftBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
