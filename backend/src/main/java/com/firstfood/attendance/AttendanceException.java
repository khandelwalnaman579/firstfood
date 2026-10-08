package com.firstfood.attendance;

import com.firstfood.common.error.DomainException;
import java.time.LocalDate;
import org.springframework.http.HttpStatus;

/** Business-rule violations in attendance and absence handling. Use the factories. */
public class AttendanceException extends DomainException {

    private AttendanceException(String code, String message, HttpStatus status) {
        super(code, message, status);
    }

    public static AttendanceException subscriptionNotFound() {
        return new AttendanceException("SUBSCRIPTION_NOT_FOUND", "Subscription not found.", HttpStatus.NOT_FOUND);
    }

    public static AttendanceException absenceNotFound() {
        return new AttendanceException("ABSENCE_NOT_FOUND", "Absence not found.", HttpStatus.NOT_FOUND);
    }

    /** Same code/status as the provider module's ProviderClosedException (no dependency on its type). */
    public static AttendanceException providerClosed() {
        return new AttendanceException("PROVIDER_CLOSED",
                "This provider is closed and can no longer be modified.", HttpStatus.CONFLICT);
    }

    /** MEAL granularity is an open decision (memory.md); Phase 8 tracks DAY subscriptions only. */
    public static AttendanceException notSupportedForMeal() {
        return new AttendanceException("ATTENDANCE_NOT_SUPPORTED",
                "Attendance and absence are tracked for day-based subscriptions. Meal-based subscriptions"
                        + " are counted by meals, which is not part of this release.",
                HttpStatus.CONFLICT);
    }

    public static AttendanceException subscriptionCancelled() {
        return new AttendanceException("SUBSCRIPTION_NOT_ACTIVE",
                "This subscription was cancelled, so its attendance can no longer be changed.", HttpStatus.CONFLICT);
    }

    public static AttendanceException dateOutsideSubscription(LocalDate date, LocalDate start, LocalDate end) {
        return new AttendanceException("DATE_OUTSIDE_SUBSCRIPTION",
                date + " is outside this subscription (" + start + " to " + end + ").", HttpStatus.BAD_REQUEST);
    }

    public static AttendanceException reasonRequired() {
        return new AttendanceException("REASON_REQUIRED",
                "A correction needs a reason, so it can be explained later.", HttpStatus.BAD_REQUEST);
    }

    public static AttendanceException rangeInvalid(String message) {
        return new AttendanceException("DATE_RANGE_INVALID", message, HttpStatus.BAD_REQUEST);
    }

    /** A customer-side rule that currently forbids changing {@code date}; the code names the rule. */
    public static AttendanceException blocked(ChangeBlock block, LocalDate date) {
        return switch (block) {
            case PAST -> new AttendanceException("ABSENCE_DATE_IN_PAST",
                    date + " has already passed and can no longer be changed.", HttpStatus.CONFLICT);
            case SAME_DAY_NOT_ALLOWED -> new AttendanceException("SAME_DAY_ABSENCE_NOT_ALLOWED",
                    "This provider does not allow absence to be declared or changed on the same day.",
                    HttpStatus.CONFLICT);
            case CUTOFF_PASSED -> new AttendanceException("ABSENCE_CUTOFF_PASSED",
                    "The provider's cutoff time for today has passed.", HttpStatus.CONFLICT);
            case PROVIDER_CORRECTION -> new AttendanceException("ATTENDANCE_LOCKED_BY_PROVIDER",
                    date + " was set by the provider. Ask them to change it.", HttpStatus.CONFLICT);
            case SUBSCRIPTION_NOT_ACTIVE -> new AttendanceException("SUBSCRIPTION_NOT_ACTIVE",
                    "This subscription has ended or was cancelled.", HttpStatus.CONFLICT);
        };
    }

    public static AttendanceException absenceNotActive() {
        return new AttendanceException("ABSENCE_NOT_ACTIVE",
                "This absence was already overridden by the provider and can no longer be cancelled.",
                HttpStatus.CONFLICT);
    }

    /** Backstop for a unique-index hit that the subscription row lock should make unreachable. */
    public static AttendanceException concurrentChange() {
        return new AttendanceException("ATTENDANCE_CONFLICT",
                "Someone else changed this day at the same moment. Please try again.", HttpStatus.CONFLICT);
    }
}
