package com.firstfood.attendance;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * An absence declaration as the customer who owns the subscription sees it. No account ids of provider staff:
 * the customer learns THAT the provider set or overrode a day (and why), not who the staff member is.
 */
public record MyAbsenceView(
        UUID id,
        UUID subscriptionId,
        LocalDate date,
        AbsenceStatus status,
        AttendanceSource source,
        String reason,
        Instant declaredAt,
        Instant cancelledAt,
        Instant overriddenAt,
        String overrideReason) {

    static MyAbsenceView from(AbsenceRecord a) {
        return new MyAbsenceView(a.getId(), a.getSubscriptionId(), a.getAbsenceDate(), a.getStatus(), a.getSource(),
                a.getReason(), a.getDeclaredAt(), a.getCancelledAt(), a.getOverriddenAt(), a.getOverrideReason());
    }
}
