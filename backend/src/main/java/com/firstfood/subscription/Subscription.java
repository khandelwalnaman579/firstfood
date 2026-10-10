package com.firstfood.subscription;

import com.firstfood.plan.ConsumptionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * The customer's purchased entitlement (rules.md Rule 8.1). Holds plain ids across modules; database
 * foreign keys enforce integrity. Never deleted: it ends as EXPIRED or CANCELLED, both final.
 *
 * What it was sold under (price, quantity, policy) lives on {@link SubscriptionTermSnapshot}, never on
 * the plan. Who/where/what/when it was sold ({@code *Id}, start and base expiry) is fixed at creation;
 * V10 triggers reject any change. Only the lifecycle, {@code effectiveExpiryDate} (Phase 9, only through {@link #extendTo}) and
 * {@code remainingMeals} (Phase 8) move afterwards.
 */
@Entity
@Table(name = "subscription")
public class Subscription {

    @Id
    private UUID id;

    @Column(name = "provider_id", nullable = false, updatable = false)
    private UUID providerId;

    @Column(name = "membership_id", nullable = false, updatable = false)
    private UUID membershipId;

    @Column(name = "person_id", nullable = false, updatable = false)
    private UUID personId;

    @Column(name = "plan_id", nullable = false, updatable = false)
    private UUID planId;

    @Enumerated(EnumType.STRING)
    @Column(name = "consumption_type", nullable = false, updatable = false, length = 10)
    private ConsumptionType consumptionType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SubscriptionStatus status = SubscriptionStatus.ACTIVE;

    @Column(name = "start_date", nullable = false, updatable = false)
    private LocalDate startDate;

    @Column(name = "base_expiry_date", updatable = false)
    private LocalDate baseExpiryDate;

    @Column(name = "effective_expiry_date")
    private LocalDate effectiveExpiryDate;

    @Column(name = "remaining_meals")
    private Integer remainingMeals;

    @Column(name = "renewed_from_subscription_id", updatable = false)
    private UUID renewedFromSubscriptionId;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "expired_at")
    private Instant expiredAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancelled_by")
    private UUID cancelledBy;

    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Subscription() {
        // JPA
    }

    /**
     * @param baseExpiryDate null only for a MEAL subscription without a calendar window
     * @param remainingMeals purchased meals for MEAL, null for DAY
     */
    public Subscription(UUID providerId, UUID membershipId, UUID personId, UUID planId,
            ConsumptionType consumptionType, LocalDate startDate, LocalDate baseExpiryDate, Integer remainingMeals,
            UUID renewedFromSubscriptionId, UUID createdBy) {
        this.id = UUID.randomUUID();
        this.providerId = Objects.requireNonNull(providerId, "providerId");
        this.membershipId = Objects.requireNonNull(membershipId, "membershipId");
        this.personId = Objects.requireNonNull(personId, "personId");
        this.planId = Objects.requireNonNull(planId, "planId");
        this.consumptionType = Objects.requireNonNull(consumptionType, "consumptionType");
        this.startDate = Objects.requireNonNull(startDate, "startDate");
        this.baseExpiryDate = baseExpiryDate;
        this.effectiveExpiryDate = baseExpiryDate;
        this.remainingMeals = remainingMeals;
        this.renewedFromSubscriptionId = renewedFromSubscriptionId;
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
    }

    /** ACTIVE -> EXPIRED. Callers decide that the subscription has ended; this records it. */
    public void expire(Instant at) {
        requireActive();
        this.status = SubscriptionStatus.EXPIRED;
        this.expiredAt = Objects.requireNonNull(at, "at");
    }

    /** ACTIVE -> CANCELLED. Callers must have validated authority. */
    public void cancel(UUID byAccountId, String reason, Instant at) {
        requireActive();
        this.status = SubscriptionStatus.CANCELLED;
        this.cancelledBy = Objects.requireNonNull(byAccountId, "byAccountId");
        this.cancelledAt = Objects.requireNonNull(at, "at");
        this.cancellationReason = reason;
    }

    /**
     * Phase 9: moves the effective expiry of a DAY subscription later, after an extension has been decided and
     * recorded as an {@code ExtensionEvent}. This only records the new date; whether, when and by how much to
     * extend is decided by the extension engine. The database checks at commit that
     * {@code effectiveExpiryDate = baseExpiryDate + the days the extension events applied} (V12).
     */
    void extendTo(LocalDate newExpiry) {
        requireActive();
        if (consumptionType != ConsumptionType.DAY) {
            throw new IllegalStateException("Only a DAY subscription can be extended");
        }
        Objects.requireNonNull(newExpiry, "newExpiry");
        if (!newExpiry.isAfter(effectiveExpiryDate)) {
            throw new IllegalStateException(
                    "An extension must move the expiry later than " + effectiveExpiryDate + ", not to " + newExpiry);
        }
        this.effectiveExpiryDate = newExpiry;
    }

    public SubscriptionPhase phaseOn(LocalDate today) {
        return SubscriptionRules.phase(status, startDate, effectiveExpiryDate, today);
    }

    private void requireActive() {
        if (status != SubscriptionStatus.ACTIVE) {
            throw new IllegalStateException("Subscription is " + status + " and can no longer change");
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getProviderId() {
        return providerId;
    }

    public UUID getMembershipId() {
        return membershipId;
    }

    public UUID getPersonId() {
        return personId;
    }

    public UUID getPlanId() {
        return planId;
    }

    public ConsumptionType getConsumptionType() {
        return consumptionType;
    }

    public SubscriptionStatus getStatus() {
        return status;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getBaseExpiryDate() {
        return baseExpiryDate;
    }

    public LocalDate getEffectiveExpiryDate() {
        return effectiveExpiryDate;
    }

    public Integer getRemainingMeals() {
        return remainingMeals;
    }

    public UUID getRenewedFromSubscriptionId() {
        return renewedFromSubscriptionId;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getExpiredAt() {
        return expiredAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public UUID getCancelledBy() {
        return cancelledBy;
    }

    public String getCancellationReason() {
        return cancellationReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
