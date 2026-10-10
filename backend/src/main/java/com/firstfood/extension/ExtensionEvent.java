package com.firstfood.extension;

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

/**
 * One extension fact (days applied, or an over-applied report): an immutable fact (rules.md Rule 13.2/13.6). Every column is non-updatable and V12 makes
 * the table append-only. The {@code consumption_type} column is pinned to 'DAY' by the database and left to its
 * default here (same as {@code AbsenceRecord}).
 */
@Entity
@Table(name = "extension_event")
public class ExtensionEvent {

    @Id
    private UUID id;

    @Column(name = "subscription_id", nullable = false, updatable = false)
    private UUID subscriptionId;

    @Column(name = "provider_id", nullable = false, updatable = false)
    private UUID providerId;

    @Column(name = "sequence_no", nullable = false, updatable = false)
    private int sequenceNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_source", nullable = false, updatable = false, length = 10)
    private ExtensionTrigger triggerSource;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "evaluated_on", nullable = false, updatable = false)
    private LocalDate evaluatedOn;

    @Column(name = "eligible_absence_days", nullable = false, updatable = false)
    private int eligibleAbsenceDays;

    @Column(name = "requested_extension_days", nullable = false, updatable = false)
    private int requestedExtensionDays;

    @Column(name = "applied_extension_days", nullable = false, updatable = false)
    private int appliedExtensionDays;

    @Column(name = "previous_expiry_date", nullable = false, updatable = false)
    private LocalDate previousExpiryDate;

    @Column(name = "new_expiry_date", nullable = false, updatable = false)
    private LocalDate newExpiryDate;

    @Column(name = "maximum_expiry_date", updatable = false)
    private LocalDate maximumExpiryDate;

    @Column(nullable = false, updatable = false)
    private boolean capped;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_kind", nullable = false, updatable = false, length = 20)
    private ExtensionKind kind = ExtensionKind.EXTENSION_APPLIED;

    /** OVER_APPLIED only; 0 for an applied extension. */
    @Column(name = "over_applied_days", nullable = false, updatable = false)
    private int overAppliedDays;

    /** OVER_APPLIED only: the extension days the subscription carried when the gap was measured. */
    @Column(name = "already_applied_extension_days", updatable = false)
    private Integer alreadyAppliedExtensionDays;

    protected ExtensionEvent() {
        // JPA
    }

    /**
     * @param actorAccountId the staff account for {@link ExtensionTrigger#STAFF}; must be null for SYSTEM
     * @param previousExpiry the subscription's effective expiry before this event
     */
    static ExtensionEvent applied(UUID subscriptionId, UUID providerId, int sequenceNo, ExtensionTrigger trigger,
            UUID actorAccountId, LocalDate evaluatedOn, ExtensionRules.Evaluation evaluation, LocalDate previousExpiry,
            LocalDate maximumExpiry) {
        if (evaluation.appliedDays() < 1) {
            throw new IllegalArgumentException("An extension event must add at least one day");
        }
        if ((trigger == ExtensionTrigger.STAFF) != (actorAccountId != null)) {
            throw new IllegalArgumentException("A STAFF event names its account; a SYSTEM event has none");
        }
        ExtensionEvent e = new ExtensionEvent();
        e.id = UUID.randomUUID();
        e.subscriptionId = Objects.requireNonNull(subscriptionId, "subscriptionId");
        e.providerId = Objects.requireNonNull(providerId, "providerId");
        e.sequenceNo = sequenceNo;
        e.triggerSource = Objects.requireNonNull(trigger, "trigger");
        e.createdBy = actorAccountId;
        e.evaluatedOn = Objects.requireNonNull(evaluatedOn, "evaluatedOn");
        e.eligibleAbsenceDays = evaluation.eligibleDays();
        e.requestedExtensionDays = evaluation.requestedDays();
        e.appliedExtensionDays = evaluation.appliedDays();
        e.previousExpiryDate = Objects.requireNonNull(previousExpiry, "previousExpiry");
        e.newExpiryDate = evaluation.newExpiry();
        e.maximumExpiryDate = maximumExpiry;
        e.capped = evaluation.capped();
        return e;
    }

    /**
     * A reconciliation report (never changes the expiry). {@code evaluation.overAppliedDays()} must be positive.
     */
    static ExtensionEvent overApplied(UUID subscriptionId, UUID providerId, int sequenceNo, ExtensionTrigger trigger,
            UUID actorAccountId, LocalDate evaluatedOn, ExtensionRules.Evaluation evaluation, LocalDate currentExpiry,
            LocalDate maximumExpiry) {
        if (evaluation.overAppliedDays() < 1) {
            throw new IllegalArgumentException("An over-applied event needs a positive gap");
        }
        if ((trigger == ExtensionTrigger.STAFF) != (actorAccountId != null)) {
            throw new IllegalArgumentException("A STAFF event names its account; a SYSTEM event has none");
        }
        ExtensionEvent e = new ExtensionEvent();
        e.id = UUID.randomUUID();
        e.subscriptionId = Objects.requireNonNull(subscriptionId, "subscriptionId");
        e.providerId = Objects.requireNonNull(providerId, "providerId");
        e.sequenceNo = sequenceNo;
        e.triggerSource = Objects.requireNonNull(trigger, "trigger");
        e.createdBy = actorAccountId;
        e.evaluatedOn = Objects.requireNonNull(evaluatedOn, "evaluatedOn");
        e.kind = ExtensionKind.OVER_APPLIED;
        e.eligibleAbsenceDays = evaluation.eligibleDays();
        e.requestedExtensionDays = 0;
        e.appliedExtensionDays = 0;
        e.overAppliedDays = evaluation.overAppliedDays();
        e.alreadyAppliedExtensionDays = evaluation.appliedSoFar();
        e.previousExpiryDate = Objects.requireNonNull(currentExpiry, "currentExpiry");
        e.newExpiryDate = currentExpiry;
        e.maximumExpiryDate = maximumExpiry;
        e.capped = false;
        return e;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSubscriptionId() {
        return subscriptionId;
    }

    public UUID getProviderId() {
        return providerId;
    }

    public int getSequenceNo() {
        return sequenceNo;
    }

    public ExtensionTrigger getTriggerSource() {
        return triggerSource;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public LocalDate getEvaluatedOn() {
        return evaluatedOn;
    }

    public int getEligibleAbsenceDays() {
        return eligibleAbsenceDays;
    }

    public int getRequestedExtensionDays() {
        return requestedExtensionDays;
    }

    public int getAppliedExtensionDays() {
        return appliedExtensionDays;
    }

    public LocalDate getPreviousExpiryDate() {
        return previousExpiryDate;
    }

    public LocalDate getNewExpiryDate() {
        return newExpiryDate;
    }

    public LocalDate getMaximumExpiryDate() {
        return maximumExpiryDate;
    }

    public boolean isCapped() {
        return capped;
    }

    public ExtensionKind getKind() {
        return kind;
    }

    public int getOverAppliedDays() {
        return overAppliedDays;
    }

    public Integer getAlreadyAppliedExtensionDays() {
        return alreadyAppliedExtensionDays;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
