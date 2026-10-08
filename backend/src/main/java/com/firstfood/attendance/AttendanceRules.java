package com.firstfood.attendance;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The date and policy rules of attendance. Pure logic (no Spring, no database) so it is unit-tested directly
 * (rules.md Rule 29.1); controllers and repositories decide nothing (Rules 18.2, 18.3).
 *
 * The policy inputs always come from the subscription's TERM SNAPSHOT, never from the current plan: a provider
 * who tightens the cutoff tomorrow does not change what an existing customer was sold (Rules 14.3, 15.2).
 */
final class AttendanceRules {

    /** The most days one declaration request may cover ("away from the 5th to the 20th"). */
    static final int MAX_DECLARE_SPAN_DAYS = 31;
    /** The most days a calendar request may cover (the policy's own maximum window is 1000 days). */
    static final int MAX_VIEW_SPAN_DAYS = 1000;
    /** How many days a customer's calendar shows when no range is asked for. */
    static final int DEFAULT_CUSTOMER_VIEW_DAYS = 30;

    private AttendanceRules() {
    }

    /**
     * Whether a CUSTOMER may declare or cancel an absence for {@code date}; empty = allowed.
     *
     * <ul>
     *   <li>a day in the past is never changeable;</li>
     *   <li>a future day always is - the cutoff is a same-day concept (V8: a cutoff exists only when same-day
     *       absence is allowed);</li>
     *   <li>today needs the provider's same-day policy, and, if the policy has a cutoff time, "now" at or before it
     *       (the cutoff itself is still in time).</li>
     * </ul>
     */
    static Optional<ChangeBlock> customerChangeBlock(LocalDate date, LocalDate today, LocalTime now,
            boolean sameDayAbsenceAllowed, LocalTime cutoff) {
        if (date.isBefore(today)) {
            return Optional.of(ChangeBlock.PAST);
        }
        if (date.isAfter(today)) {
            return Optional.empty();
        }
        if (!sameDayAbsenceAllowed) {
            return Optional.of(ChangeBlock.SAME_DAY_NOT_ALLOWED);
        }
        if (cutoff != null && now.isAfter(cutoff)) {
            return Optional.of(ChangeBlock.CUTOFF_PASSED);
        }
        return Optional.empty();
    }

    /** Whether {@code date} lies within the subscription's days (start and effective expiry both inclusive). */
    static boolean inWindow(LocalDate date, LocalDate start, LocalDate effectiveExpiry) {
        return !date.isBefore(start) && (effectiveExpiry == null || !date.isAfter(effectiveExpiry));
    }

    /**
     * The last day the subscription was entitled: its effective expiry, or the day BEFORE it was cancelled if that
     * is earlier (a cancelled subscription is not entitled on the day it was cancelled - the same rule as the
     * daily-sheet query). {@code cancelledOn} is null for a subscription that was not cancelled.
     */
    static LocalDate lastEntitledDay(LocalDate effectiveExpiry, LocalDate cancelledOn) {
        if (cancelledOn == null) {
            return effectiveExpiry;
        }
        LocalDate beforeCancel = cancelledOn.minusDays(1);
        return effectiveExpiry != null && effectiveExpiry.isBefore(beforeCancel) ? effectiveExpiry : beforeCancel;
    }

    /** Every day from {@code from} to {@code to}, both inclusive. */
    static List<LocalDate> days(LocalDate from, LocalDate to) {
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            days.add(d);
        }
        return days;
    }

    /** Validates an inclusive range of at most {@code maxDays} days; returns the number of days. */
    static int validateSpan(LocalDate from, LocalDate to, int maxDays) {
        if (to.isBefore(from)) {
            throw AttendanceException.rangeInvalid("The end date cannot be before the start date.");
        }
        long span = ChronoUnit.DAYS.between(from, to) + 1;
        if (span > maxDays) {
            throw AttendanceException.rangeInvalid("A request can cover at most " + maxDays + " days.");
        }
        return (int) span;
    }
}
