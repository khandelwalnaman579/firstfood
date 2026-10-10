package com.firstfood.attendance;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One day of a subscription as its customer sees it. {@code changeable} and {@code lockedReason} are decided by
 * the backend from the provider's policy: the client shows them and never re-derives the rule.
 *
 * @param assumed true when nothing is recorded and the day is PRESENT only because attendance is opt-out
 * @param providerSet true when provider staff decided this day
 * @param changeable whether the customer may declare (PRESENT day) or cancel (ABSENT day) an absence now
 * @param lockedReason why not, when {@code changeable} is false
 */
public record MyAttendanceDayView(
        LocalDate date,
        AttendanceStatus status,
        boolean assumed,
        UUID absenceId,
        String reason,
        boolean providerSet,
        boolean changeable,
        ChangeBlock lockedReason) {
}
