package com.firstfood.attendance;

import java.time.Instant;
import java.util.UUID;

/** One ledger row of a day, oldest first: what was recorded, by whom, when and why (rules.md Rule 15.4). */
public record AttendanceHistoryEntry(
        UUID id,
        AttendanceStatus status,
        AttendanceSource source,
        UUID absenceId,
        UUID recordedBy,
        Instant recordedAt,
        String reason,
        UUID supersedesId,
        boolean current,
        Instant supersededAt) {

    static AttendanceHistoryEntry from(AttendanceRecord r) {
        return new AttendanceHistoryEntry(r.getId(), r.getStatus(), r.getSource(), r.getAbsenceId(),
                r.getRecordedBy(), r.getRecordedAt(), r.getReason(), r.getSupersedesId(), r.isCurrentRow(),
                r.getSupersededAt());
    }
}
