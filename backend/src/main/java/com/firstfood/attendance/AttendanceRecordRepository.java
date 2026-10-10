package com.firstfood.attendance;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence only. "Current" rows are the ones a day's outcome is read from; the rest is history. */
public interface AttendanceRecordRepository extends JpaRepository<AttendanceRecord, UUID> {

    Optional<AttendanceRecord> findBySubscriptionIdAndAttendanceDateAndCurrentRowTrue(
            UUID subscriptionId, LocalDate date);

    List<AttendanceRecord> findBySubscriptionIdAndAttendanceDateBetweenAndCurrentRowTrue(
            UUID subscriptionId, LocalDate from, LocalDate to);

    /** The daily sheet: every current outcome the provider has for the day. */
    List<AttendanceRecord> findByProviderIdAndAttendanceDateAndCurrentRowTrue(UUID providerId, LocalDate date);

    /** One day's full history, oldest first. */
    List<AttendanceRecord> findBySubscriptionIdAndAttendanceDateOrderByRecordedAtAscCreatedAtAsc(
            UUID subscriptionId, LocalDate date);
}
