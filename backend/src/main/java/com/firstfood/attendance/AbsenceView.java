package com.firstfood.attendance;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** An absence declaration as provider staff see it, including who acted on it. */
public record AbsenceView(
        UUID id,
        UUID subscriptionId,
        LocalDate date,
        AbsenceStatus status,
        AttendanceSource source,
        String reason,
        UUID declaredBy,
        Instant declaredAt,
        Instant cancelledAt,
        UUID cancelledBy,
        Instant overriddenAt,
        UUID overriddenBy,
        String overrideReason) {

    static AbsenceView from(AbsenceRecord a) {
        return new AbsenceView(a.getId(), a.getSubscriptionId(), a.getAbsenceDate(), a.getStatus(), a.getSource(),
                a.getReason(), a.getDeclaredBy(), a.getDeclaredAt(), a.getCancelledAt(), a.getCancelledBy(),
                a.getOverriddenAt(), a.getOverriddenBy(), a.getOverrideReason());
    }
}
