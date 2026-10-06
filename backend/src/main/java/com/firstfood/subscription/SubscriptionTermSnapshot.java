package com.firstfood.subscription;

import com.firstfood.plan.ConsumptionType;
import com.firstfood.plan.PlanOffer;
import com.firstfood.plan.PolicyTerms;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/**
 * The immutable record of the terms a subscription was SOLD under: commercial terms and the policy
 * terms then in force, in one row (frozen decision 1 - there is no separate policy snapshot).
 * Exactly one per subscription (frozen decision 2). Every column is non-updatable and the database
 * rejects UPDATE/DELETE. Nothing about a past sale is ever read from the plan again.
 */
@Entity
@Table(name = "subscription_term_snapshot")
public class SubscriptionTermSnapshot {

    @Id
    private UUID id;

    @Column(name = "subscription_id", nullable = false, updatable = false)
    private UUID subscriptionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "consumption_type", nullable = false, updatable = false, length = 10)
    private ConsumptionType consumptionType;

    @Column(name = "plan_name", nullable = false, updatable = false, length = 120)
    private String planName;

    @Column(nullable = false, updatable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "purchased_days", updatable = false)
    private Integer purchasedDays;

    @Column(name = "purchased_meals", updatable = false)
    private Integer purchasedMeals;

    @Column(name = "policy_version", nullable = false, updatable = false)
    private int policyVersion;

    @Column(name = "extension_allowed", nullable = false, updatable = false)
    private boolean extensionAllowed;

    @Column(name = "min_consecutive_absence_days", updatable = false)
    private Integer minConsecutiveAbsenceDays;

    @Column(name = "same_day_absence_allowed", nullable = false, updatable = false)
    private boolean sameDayAbsenceAllowed;

    @Column(name = "absence_cutoff_time", updatable = false)
    private LocalTime absenceCutoffTime;

    @Column(name = "max_calendar_window_days", updatable = false)
    private Integer maxCalendarWindowDays;

    @Column(name = "captured_at", nullable = false, updatable = false)
    private Instant capturedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SubscriptionTermSnapshot() {
        // JPA
    }

    /** Copies the offer's terms as they are right now. */
    static SubscriptionTermSnapshot capture(UUID subscriptionId, PlanOffer offer, Instant capturedAt) {
        SubscriptionTermSnapshot s = new SubscriptionTermSnapshot();
        PolicyTerms policy = offer.policy();
        s.id = UUID.randomUUID();
        s.subscriptionId = Objects.requireNonNull(subscriptionId, "subscriptionId");
        s.consumptionType = offer.consumptionType();
        s.planName = offer.name();
        s.price = offer.price();
        s.currency = offer.currency();
        s.purchasedDays = offer.durationDays();
        s.purchasedMeals = offer.mealQuantity();
        s.policyVersion = offer.policyVersion();
        s.extensionAllowed = policy.extensionAllowed();
        s.minConsecutiveAbsenceDays = policy.minConsecutiveAbsenceDays();
        s.sameDayAbsenceAllowed = policy.sameDayAbsenceAllowed();
        s.absenceCutoffTime = policy.absenceCutoffTime();
        s.maxCalendarWindowDays = policy.maxCalendarWindowDays();
        s.capturedAt = Objects.requireNonNull(capturedAt, "capturedAt");
        return s;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSubscriptionId() {
        return subscriptionId;
    }

    public ConsumptionType getConsumptionType() {
        return consumptionType;
    }

    public String getPlanName() {
        return planName;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public String getCurrency() {
        return currency;
    }

    public Integer getPurchasedDays() {
        return purchasedDays;
    }

    public Integer getPurchasedMeals() {
        return purchasedMeals;
    }

    public int getPolicyVersion() {
        return policyVersion;
    }

    public boolean isExtensionAllowed() {
        return extensionAllowed;
    }

    public Integer getMinConsecutiveAbsenceDays() {
        return minConsecutiveAbsenceDays;
    }

    public boolean isSameDayAbsenceAllowed() {
        return sameDayAbsenceAllowed;
    }

    public LocalTime getAbsenceCutoffTime() {
        return absenceCutoffTime;
    }

    public Integer getMaxCalendarWindowDays() {
        return maxCalendarWindowDays;
    }

    public Instant getCapturedAt() {
        return capturedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
