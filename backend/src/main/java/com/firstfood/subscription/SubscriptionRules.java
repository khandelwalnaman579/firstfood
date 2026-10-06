package com.firstfood.subscription;

import com.firstfood.plan.ConsumptionType;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * The date rules of a subscription. Pure logic (no Spring, no database) so it is unit-tested
 * directly (rules.md Rule 29.1); controllers and repositories decide nothing (Rules 18.2, 18.3).
 *
 * Days are counted INCLUSIVELY: a 30-day plan starting 1 Sep covers 1 Sep ... 30 Sep, so its last
 * day is start + 29. This is the convention in the PRD example (start 1 Sep, base expiry 30 Sep,
 * 45-day window, maximum expiry 15 Oct) and the maximum-expiry rule uses the same counting.
 */
final class SubscriptionRules {

    /** How far in the past a subscription may start (migrating a customer from the notebook). */
    static final int MAX_BACKDATE_DAYS = 366;
    /** How far ahead a subscription may be scheduled to start. */
    static final int MAX_FUTURE_START_DAYS = 90;

    private SubscriptionRules() {
    }

    /** The last day of a period of {@code days} days beginning on {@code start}. */
    static LocalDate lastDay(LocalDate start, int days) {
        return start.plusDays(days - 1L);
    }

    /**
     * Expiry before any extension. DAY: the end of the purchased days. MEAL: the end of the policy's
     * calendar window, or null when the policy sets none (a MEAL subscription then runs until its
     * meals are used).
     */
    static LocalDate baseExpiry(ConsumptionType type, LocalDate start, Integer durationDays, Integer windowDays) {
        return switch (type) {
            case DAY -> lastDay(start, durationDays);
            case MEAL -> windowDays == null ? null : lastDay(start, windowDays);
        };
    }

    /**
     * The latest date extensions may ever reach: counted from the ORIGINAL start (rules.md Rule 13.4),
     * null when the policy has no window. Used by Phase 9; exposed now so the view can show it.
     */
    static LocalDate maximumExpiry(LocalDate start, Integer windowDays) {
        return windowDays == null ? null : lastDay(start, windowDays);
    }

    static void validateStart(LocalDate start, LocalDate today) {
        if (start.isBefore(today.minusDays(MAX_BACKDATE_DAYS))) {
            throw SubscriptionException.startInvalid(
                    "The start date cannot be more than " + MAX_BACKDATE_DAYS + " days in the past.");
        }
        if (start.isAfter(today.plusDays(MAX_FUTURE_START_DAYS))) {
            throw SubscriptionException.startInvalid(
                    "The start date cannot be more than " + MAX_FUTURE_START_DAYS + " days ahead.");
        }
    }

    /** A subscription that would already be over today is not a subscription to sell. */
    static void validateNotAlreadyEnded(LocalDate baseExpiry, LocalDate today) {
        if (baseExpiry != null && baseExpiry.isBefore(today)) {
            throw SubscriptionException.periodInPast();
        }
    }

    static SubscriptionPhase phase(
            SubscriptionStatus status, LocalDate start, LocalDate effectiveExpiry, LocalDate today) {
        return switch (status) {
            case CANCELLED -> SubscriptionPhase.CANCELLED;
            case EXPIRED -> SubscriptionPhase.ENDED;
            case ACTIVE -> {
                if (effectiveExpiry != null && effectiveExpiry.isBefore(today)) {
                    yield SubscriptionPhase.ENDED;
                }
                yield start.isAfter(today) ? SubscriptionPhase.UPCOMING : SubscriptionPhase.RUNNING;
            }
        };
    }

    /**
     * Days of entitlement left, counting today: 0 once ended or cancelled, the full span while upcoming.
     * Null for a subscription with no calendar limit (MEAL without a window): days are not its measure.
     */
    static Integer remainingDays(SubscriptionPhase phase, LocalDate start, LocalDate effectiveExpiry, LocalDate today) {
        if (effectiveExpiry == null) {
            return null;
        }
        return switch (phase) {
            case ENDED, CANCELLED -> 0;
            case UPCOMING -> (int) ChronoUnit.DAYS.between(start, effectiveExpiry) + 1;
            case RUNNING -> (int) ChronoUnit.DAYS.between(today, effectiveExpiry) + 1;
        };
    }
}
