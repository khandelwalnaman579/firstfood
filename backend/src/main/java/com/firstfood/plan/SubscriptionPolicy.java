package com.firstfood.plan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/**
 * One immutable VERSION of a plan's absence/extension terms (rules.md Rule 14.3). Every
 * column is non-updatable and the database rejects UPDATE/DELETE outright: a changed
 * policy is a new row with the next version number. The highest version of a plan is its
 * current policy; Phase 7 records the version it copied in the subscription term snapshot.
 */
@Entity
@Table(name = "subscription_policy")
public class SubscriptionPolicy {

    @Id
    private UUID id;

    @Column(name = "plan_id", nullable = false, updatable = false)
    private UUID planId;

    @Column(nullable = false, updatable = false)
    private int version;

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

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SubscriptionPolicy() {
        // JPA
    }

    public SubscriptionPolicy(UUID planId, int version, PolicyTerms terms, UUID createdBy) {
        if (version < 1) {
            throw new IllegalArgumentException("version must be >= 1");
        }
        Objects.requireNonNull(terms, "terms");
        this.id = UUID.randomUUID();
        this.planId = Objects.requireNonNull(planId, "planId");
        this.version = version;
        this.extensionAllowed = terms.extensionAllowed();
        this.minConsecutiveAbsenceDays = terms.minConsecutiveAbsenceDays();
        this.sameDayAbsenceAllowed = terms.sameDayAbsenceAllowed();
        this.absenceCutoffTime = terms.absenceCutoffTime();
        this.maxCalendarWindowDays = terms.maxCalendarWindowDays();
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
    }

    /** The terms this version carries (the value Phase 7 copies into a snapshot). */
    public PolicyTerms terms() {
        return new PolicyTerms(extensionAllowed, minConsecutiveAbsenceDays, sameDayAbsenceAllowed,
                absenceCutoffTime, maxCalendarWindowDays);
    }

    public UUID getId() {
        return id;
    }

    public UUID getPlanId() {
        return planId;
    }

    public int getVersion() {
        return version;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
