package com.firstfood.attendance;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The attendance module's application boundary. Every method takes the acting account from the authenticated
 * principal; a client-supplied actor is never accepted (rules.md Rule 18.4).
 *
 * Provider side: authorized per provider through ATTENDANCE_VIEW / ATTENDANCE_MANAGE (404 for a non-member, 403 for a
 * member without the permission). Customer side: scoped to the caller's own persons - someone else's subscription
 * is simply "not found".
 *
 * Attendance is OPT-OUT (rules.md Rule 11.1): a day with no record is PRESENT. DAY subscriptions only in this phase.
 */
public interface AttendanceService {

    // ---- provider staff ----

    /** Who is eating on {@code date} (null = today), across every day-based subscription entitled that day. */
    DailySheetView dailySheet(UUID actorAccountId, UUID providerId, LocalDate date);

    /** One subscription's days in an inclusive range (null range = 30 days around today, within the subscription). */
    List<AttendanceDayView> days(UUID actorAccountId, UUID providerId, UUID subscriptionId, LocalDate from,
            LocalDate to);

    /** Every ledger row of one day, oldest first: what was recorded, by whom, when and why. */
    List<AttendanceHistoryEntry> history(UUID actorAccountId, UUID providerId, UUID subscriptionId, LocalDate date);

    /** Every absence declaration of the subscription, newest day first, including cancelled and overridden ones. */
    List<AbsenceView> absences(UUID actorAccountId, UUID providerId, UUID subscriptionId);

    /**
     * Provider staff decide a day. ABSENT records an absence on the customer's behalf; PRESENT overrides a declared
     * absence. A reason is mandatory. Repeating the same decision changes nothing (retry-safe).
     */
    AttendanceDayView correct(UUID actorAccountId, UUID providerId, UUID subscriptionId, LocalDate date,
            AttendanceStatus status, String reason);

    // ---- customer ----

    /** The caller's own subscription, day by day, with what the customer may still change. */
    List<MyAttendanceDayView> myDays(UUID accountId, UUID subscriptionId, LocalDate from, LocalDate to);

    List<MyAbsenceView> myAbsences(UUID accountId, UUID subscriptionId);

    /** "I will not be eating" for one day or a range (toDate null = one day). All days or none. */
    DeclareAbsenceResult declare(UUID accountId, UUID subscriptionId, LocalDate fromDate, LocalDate toDate,
            String reason);

    /** Takes back a declaration the customer made, while the provider's policy still allows it. */
    MyAbsenceView cancelAbsence(UUID accountId, UUID subscriptionId, UUID absenceId);
}
