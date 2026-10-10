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

/**
 * One row of the attendance LEDGER for a (subscription, date). Rows are facts: the only thing that ever changes on
 * one is that a later row supersedes it ({@link #supersede}); a correction is a NEW row that points back at the one
 * it replaces ({@code supersedesId}), so who changed what, when and why is always answerable (rules.md 11.3, 15.4).
 * Exactly one row per day is the current one.
 *
 * The Java field is {@code currentRow} (column {@code is_current}): "current" collides with an HQL keyword.
 */
@Entity
@Table(name = "attendance_record")
public class AttendanceRecord {

    @Id
    private UUID id;

    @Column(name = "subscription_id", nullable = false, updatable = false)
    private UUID subscriptionId;

    @Column(name = "provider_id", nullable = false, updatable = false)
    private UUID providerId;

    @Column(name = "attendance_date", nullable = false, updatable = false)
    private LocalDate attendanceDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 10)
    private AttendanceStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private AttendanceSource source;

    @Column(name = "absence_id", updatable = false)
    private UUID absenceId;

    @Column(name = "recorded_by", updatable = false)
    private UUID recordedBy;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    @Column(updatable = false, length = 500)
    private String reason;

    @Column(name = "supersedes_id", updatable = false)
    private UUID supersedesId;

    @Column(name = "is_current", nullable = false)
    private boolean currentRow = true;

    @Column(name = "superseded_at")
    private Instant supersededAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AttendanceRecord() {
        // JPA
    }

    /** The day is ABSENT because {@code absenceId} was declared (by the customer or on their behalf). */
    static AttendanceRecord absent(UUID subscriptionId, UUID providerId, LocalDate date, UUID absenceId,
            AttendanceSource source, UUID recordedBy, String reason, UUID supersedesId, Instant at) {
        Objects.requireNonNull(absenceId, "absenceId");
        return create(subscriptionId, providerId, date, AttendanceStatus.ABSENT, source, absenceId, recordedBy,
                reason, supersedesId, at);
    }

    /** The day is PRESENT again: the customer took the absence back (SYSTEM) or staff overrode it. */
    static AttendanceRecord present(UUID subscriptionId, UUID providerId, LocalDate date, UUID absenceId,
            AttendanceSource source, UUID recordedBy, String reason, UUID supersedesId, Instant at) {
        if (source == AttendanceSource.CUSTOMER) {
            throw new IllegalArgumentException("A customer can only declare absence, never presence");
        }
        return create(subscriptionId, providerId, date, AttendanceStatus.PRESENT, source, absenceId, recordedBy,
                reason, supersedesId, at);
    }

    private static AttendanceRecord create(UUID subscriptionId, UUID providerId, LocalDate date,
            AttendanceStatus status, AttendanceSource source, UUID absenceId, UUID recordedBy, String reason,
            UUID supersedesId, Instant at) {
        AttendanceRecord r = new AttendanceRecord();
        r.id = UUID.randomUUID();
        r.subscriptionId = Objects.requireNonNull(subscriptionId, "subscriptionId");
        r.providerId = Objects.requireNonNull(providerId, "providerId");
        r.attendanceDate = Objects.requireNonNull(date, "date");
        r.status = status;
        r.source = Objects.requireNonNull(source, "source");
        r.absenceId = absenceId;
        r.recordedBy = Objects.requireNonNull(recordedBy, "recordedBy");
        r.recordedAt = Objects.requireNonNull(at, "at");
        r.reason = reason;
        r.supersedesId = supersedesId;
        return r;
    }

    /** No longer the day's outcome: a later row replaces it. */
    void supersede(Instant at) {
        if (!currentRow) {
            throw new IllegalStateException("Attendance record is already superseded");
        }
        this.currentRow = false;
        this.supersededAt = Objects.requireNonNull(at, "at");
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

    public LocalDate getAttendanceDate() {
        return attendanceDate;
    }

    public AttendanceStatus getStatus() {
        return status;
    }

    public AttendanceSource getSource() {
        return source;
    }

    public UUID getAbsenceId() {
        return absenceId;
    }

    public UUID getRecordedBy() {
        return recordedBy;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    public String getReason() {
        return reason;
    }

    public UUID getSupersedesId() {
        return supersedesId;
    }

    public boolean isCurrentRow() {
        return currentRow;
    }

    public Instant getSupersededAt() {
        return supersededAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
