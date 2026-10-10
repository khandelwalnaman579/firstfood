package com.firstfood.attendance;

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
 * One declaration that a person will not eat on a date. Never edited and never deleted: it ends as CANCELLED or
 * OVERRIDDEN (V11 triggers enforce this). DAY subscriptions only - the table's {@code consumption_type} column
 * is pinned to 'DAY' by the database and left to its default here.
 */
@Entity
@Table(name = "absence_record")
public class AbsenceRecord {

    @Id
    private UUID id;

    @Column(name = "subscription_id", nullable = false, updatable = false)
    private UUID subscriptionId;

    @Column(name = "provider_id", nullable = false, updatable = false)
    private UUID providerId;

    @Column(name = "absence_date", nullable = false, updatable = false)
    private LocalDate absenceDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private AbsenceStatus status = AbsenceStatus.DECLARED;

    /** CUSTOMER, or OWNER_CORRECTION when provider staff declared it on the customer's behalf. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private AttendanceSource source;

    @Column(name = "declared_by", nullable = false, updatable = false)
    private UUID declaredBy;

    @Column(name = "declared_at", nullable = false, updatable = false)
    private Instant declaredAt;

    @Column(updatable = false, length = 500)
    private String reason;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancelled_by")
    private UUID cancelledBy;

    @Column(name = "overridden_at")
    private Instant overriddenAt;

    @Column(name = "overridden_by")
    private UUID overriddenBy;

    @Column(name = "override_reason", length = 500)
    private String overrideReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AbsenceRecord() {
        // JPA
    }

    static AbsenceRecord declare(UUID subscriptionId, UUID providerId, LocalDate date, AttendanceSource source,
            UUID declaredBy, String reason, Instant at) {
        if (source == AttendanceSource.SYSTEM) {
            throw new IllegalArgumentException("An absence is declared by the customer or by provider staff");
        }
        AbsenceRecord a = new AbsenceRecord();
        a.id = UUID.randomUUID();
        a.subscriptionId = Objects.requireNonNull(subscriptionId, "subscriptionId");
        a.providerId = Objects.requireNonNull(providerId, "providerId");
        a.absenceDate = Objects.requireNonNull(date, "date");
        a.source = Objects.requireNonNull(source, "source");
        a.declaredBy = Objects.requireNonNull(declaredBy, "declaredBy");
        a.declaredAt = Objects.requireNonNull(at, "at");
        a.reason = reason;
        return a;
    }

    /** DECLARED -> CANCELLED. Callers have validated the policy and who may do it. */
    void cancel(UUID byAccountId, Instant at) {
        requireDeclared();
        this.status = AbsenceStatus.CANCELLED;
        this.cancelledBy = Objects.requireNonNull(byAccountId, "byAccountId");
        this.cancelledAt = Objects.requireNonNull(at, "at");
    }

    /** DECLARED -> OVERRIDDEN by provider staff, who must say why. */
    void override(UUID byAccountId, String why, Instant at) {
        requireDeclared();
        if (why == null || why.isBlank()) {
            throw new IllegalArgumentException("An override needs a reason");
        }
        this.status = AbsenceStatus.OVERRIDDEN;
        this.overriddenBy = Objects.requireNonNull(byAccountId, "byAccountId");
        this.overriddenAt = Objects.requireNonNull(at, "at");
        this.overrideReason = why;
    }

    private void requireDeclared() {
        if (status != AbsenceStatus.DECLARED) {
            throw new IllegalStateException("Absence is " + status + " and can no longer change");
        }
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

    public LocalDate getAbsenceDate() {
        return absenceDate;
    }

    public AbsenceStatus getStatus() {
        return status;
    }

    public AttendanceSource getSource() {
        return source;
    }

    public UUID getDeclaredBy() {
        return declaredBy;
    }

    public Instant getDeclaredAt() {
        return declaredAt;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public UUID getCancelledBy() {
        return cancelledBy;
    }

    public Instant getOverriddenAt() {
        return overriddenAt;
    }

    public UUID getOverriddenBy() {
        return overriddenBy;
    }

    public String getOverrideReason() {
        return overrideReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
