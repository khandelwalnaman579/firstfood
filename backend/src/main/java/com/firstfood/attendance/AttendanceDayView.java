package com.firstfood.attendance;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One day of a subscription as provider staff see it. No price or terms: attendance staff may be WORKERs. */
public record AttendanceDayView(
        LocalDate date,
        AttendanceStatus status,
        AttendanceSource source,
        boolean assumed,
        UUID absenceId,
        String reason,
        UUID recordedBy,
        Instant recordedAt) {

    static AttendanceDayView of(LocalDate date, AttendanceRecord current) {
        if (current == null) {
            return new AttendanceDayView(date, AttendanceStatus.PRESENT, AttendanceSource.SYSTEM, true, null, null,
                    null, null);
        }
        return new AttendanceDayView(date, current.getStatus(), current.getSource(), false, current.getAbsenceId(),
                current.getReason(), current.getRecordedBy(), current.getRecordedAt());
    }
}
