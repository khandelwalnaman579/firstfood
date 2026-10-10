package com.firstfood.attendance;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence only - every decision lives in the services / {@link AttendanceRules}. */
public interface AbsenceRecordRepository extends JpaRepository<AbsenceRecord, UUID> {

    /** Scoped by subscription so an absence id from another subscription is never resolvable (no IDOR). */
    Optional<AbsenceRecord> findByIdAndSubscriptionId(UUID id, UUID subscriptionId);

    /** At most one exists (uq_absence_one_declared_per_day). */
    Optional<AbsenceRecord> findBySubscriptionIdAndAbsenceDateAndStatus(
            UUID subscriptionId, java.time.LocalDate date, AbsenceStatus status);

    /** The live declarations in a date range (at most one per day, uq_absence_one_declared_per_day). */
    List<AbsenceRecord> findBySubscriptionIdAndAbsenceDateBetweenAndStatus(
            UUID subscriptionId, java.time.LocalDate from, java.time.LocalDate to, AbsenceStatus status);

    /** The days with a live (DECLARED) absence, oldest first: what the Phase 9 extension engine counts. */
    @org.springframework.data.jpa.repository.Query("""
            select a.absenceDate from AbsenceRecord a
            where a.subscriptionId = :subscriptionId and a.status = com.firstfood.attendance.AbsenceStatus.DECLARED
            order by a.absenceDate
            """)
    List<java.time.LocalDate> findDeclaredDates(
            @org.springframework.data.repository.query.Param("subscriptionId") UUID subscriptionId);

    /** Newest day first; every status, so cancelled and overridden declarations stay visible. */
    List<AbsenceRecord> findBySubscriptionIdOrderByAbsenceDateDescDeclaredAtDesc(UUID subscriptionId);
}
