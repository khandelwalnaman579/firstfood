package com.firstfood.attendance;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * "Who is eating on this date" - the page that replaces the notebook. Lists every day-based subscription that was
 * entitled on the date. Counts are computed by the backend. Carries no price, no phone number and no sale terms.
 */
public record DailySheetView(
        LocalDate date,
        int expected,
        int present,
        int absent,
        List<Row> rows) {

    public record Row(
            UUID subscriptionId,
            UUID membershipId,
            UUID personId,
            String customerName,
            String planName,
            LocalDate startDate,
            LocalDate effectiveExpiryDate,
            AttendanceStatus status,
            AttendanceSource source,
            boolean assumed,
            UUID absenceId,
            String reason) {
    }
}
