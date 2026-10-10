package com.firstfood.plan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/**
 * A provider's CURRENT commercial offering. It is never the historical source of truth
 * for a subscription (rules.md Rules 7.2, 15.2) - Phase 7 snapshots the terms. Holds plain
 * ids across modules; database foreign keys enforce integrity. Never deleted.
 *
 * The consumption type is fixed at creation: changing DAY to MEAL is a different
 * product, so the provider creates a new plan instead. The absence/extension terms live in
 * {@link SubscriptionPolicy} versions, not here.
 */
@Entity
@Table(name = "plan")
public class Plan {

    @Id
    private UUID id;

    @Column(name = "provider_id", nullable = false, updatable = false)
    private UUID providerId;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "consumption_type", nullable = false, updatable = false, length = 10)
    private ConsumptionType consumptionType;

    @Column(name = "duration_days")
    private Integer durationDays;

    @Column(name = "meal_quantity")
    private Integer mealQuantity;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency = "INR";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PlanStatus status = PlanStatus.ACTIVE;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "updated_by", nullable = false)
    private UUID updatedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // Set explicitly by the domain methods (not @UpdateTimestamp): a policy-only edit does
    // not dirty this row, but is still an edit of the plan and must move updatedAt.
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Plan() {
        // JPA
    }

    public Plan(UUID providerId, String name, String description, ConsumptionType consumptionType,
            Integer durationDays, Integer mealQuantity, BigDecimal price, UUID createdBy, Instant now) {
        this.id = UUID.randomUUID();
        this.providerId = Objects.requireNonNull(providerId, "providerId");
        this.name = Objects.requireNonNull(name, "name");
        this.description = description;
        this.consumptionType = Objects.requireNonNull(consumptionType, "consumptionType");
        this.durationDays = durationDays;
        this.mealQuantity = mealQuantity;
        this.price = Objects.requireNonNull(price, "price");
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
        this.updatedBy = createdBy;
        this.updatedAt = Objects.requireNonNull(now, "now");
    }

    /** True when the commercial columns already hold exactly these values. */
    public boolean hasTerms(String name, String description, Integer durationDays, Integer mealQuantity,
            BigDecimal price) {
        return this.name.equals(name)
                && Objects.equals(this.description, description)
                && Objects.equals(this.durationDays, durationDays)
                && Objects.equals(this.mealQuantity, mealQuantity)
                && this.price.compareTo(price) == 0;
    }

    /** Replaces the commercial terms. Existing subscriptions are unaffected (Rule 7.5). */
    public void updateTerms(String name, String description, Integer durationDays, Integer mealQuantity,
            BigDecimal price) {
        this.name = Objects.requireNonNull(name, "name");
        this.description = description;
        this.durationDays = durationDays;
        this.mealQuantity = mealQuantity;
        this.price = Objects.requireNonNull(price, "price");
    }

    /** Records that the plan (its terms and/or its policy) was edited. */
    public void markEdited(UUID byAccountId, Instant at) {
        this.updatedBy = Objects.requireNonNull(byAccountId, "byAccountId");
        this.updatedAt = Objects.requireNonNull(at, "at");
    }

    public void activate(UUID byAccountId, Instant at) {
        if (status == PlanStatus.ACTIVE) {
            throw new IllegalStateException("Plan already active");
        }
        this.status = PlanStatus.ACTIVE;
        markEdited(byAccountId, at);
    }

    public void deactivate(UUID byAccountId, Instant at) {
        if (status == PlanStatus.INACTIVE) {
            throw new IllegalStateException("Plan already inactive");
        }
        this.status = PlanStatus.INACTIVE;
        markEdited(byAccountId, at);
    }

    public boolean isActive() {
        return status == PlanStatus.ACTIVE;
    }

    public UUID getId() {
        return id;
    }

    public UUID getProviderId() {
        return providerId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public ConsumptionType getConsumptionType() {
        return consumptionType;
    }

    public Integer getDurationDays() {
        return durationDays;
    }

    public Integer getMealQuantity() {
        return mealQuantity;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public String getCurrency() {
        return currency;
    }

    public PlanStatus getStatus() {
        return status;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public UUID getUpdatedBy() {
        return updatedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
