package com.firstfood.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstfood.plan.ConsumptionType;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * The subscription date rules with no Spring and no database (rules.md Rule 29.1/29.2): inclusive
 * day counting, the derived phase at every boundary, and the start-date window.
 */
class SubscriptionRulesTest {

    private static final LocalDate SEP_1 = LocalDate.of(2026, 9, 1);

    // ------------------------------------------------------------ expiry arithmetic

    @Test
    void thirtyDaysFromTheFirstEndOnThe30thInclusive() {
        assertThat(SubscriptionRules.lastDay(SEP_1, 30)).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(SubscriptionRules.lastDay(SEP_1, 1)).isEqualTo(SEP_1);
    }

    @Test
    void dayPlanBaseExpiryIsTheEndOfThePurchasedDays() {
        assertThat(SubscriptionRules.baseExpiry(ConsumptionType.DAY, SEP_1, 30, 45))
                .isEqualTo(LocalDate.of(2026, 9, 30));
        // The calendar window never shortens a DAY purchase.
        assertThat(SubscriptionRules.baseExpiry(ConsumptionType.DAY, SEP_1, 30, null))
                .isEqualTo(LocalDate.of(2026, 9, 30));
    }

    @Test
    void mealPlanBaseExpiryIsTheEndOfTheWindowOrNone() {
        assertThat(SubscriptionRules.baseExpiry(ConsumptionType.MEAL, SEP_1, null, 90))
                .isEqualTo(LocalDate.of(2026, 11, 29));
        assertThat(SubscriptionRules.baseExpiry(ConsumptionType.MEAL, SEP_1, null, null)).isNull();
    }

    @Test
    void maximumExpiryMatchesThePrdExample() {
        // PRD: start 1 Sep, 45-day window -> 15 Oct.
        assertThat(SubscriptionRules.maximumExpiry(SEP_1, 45)).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(SubscriptionRules.maximumExpiry(SEP_1, null)).isNull();
    }

    // ------------------------------------------------------------ start window

    @Test
    void startMayBeWithinTheBackdateAndFutureWindowsInclusive() {
        LocalDate today = LocalDate.of(2026, 10, 6);
        assertThatCode(() -> SubscriptionRules.validateStart(today, today)).doesNotThrowAnyException();
        assertThatCode(() -> SubscriptionRules.validateStart(today.minusDays(366), today)).doesNotThrowAnyException();
        assertThatCode(() -> SubscriptionRules.validateStart(today.plusDays(90), today)).doesNotThrowAnyException();
    }

    @Test
    void startOutsideTheWindowsIsRejected() {
        LocalDate today = LocalDate.of(2026, 10, 6);
        assertThatThrownBy(() -> SubscriptionRules.validateStart(today.minusDays(367), today))
                .isInstanceOf(SubscriptionException.class).hasMessageContaining("in the past");
        assertThatThrownBy(() -> SubscriptionRules.validateStart(today.plusDays(91), today))
                .isInstanceOf(SubscriptionException.class).hasMessageContaining("ahead");
    }

    @Test
    void aSubscriptionThatEndedBeforeTodayIsNotSellableButOneEndingTodayIs() {
        LocalDate today = LocalDate.of(2026, 10, 6);
        assertThatCode(() -> SubscriptionRules.validateNotAlreadyEnded(today, today)).doesNotThrowAnyException();
        assertThatCode(() -> SubscriptionRules.validateNotAlreadyEnded(null, today)).doesNotThrowAnyException();
        assertThatThrownBy(() -> SubscriptionRules.validateNotAlreadyEnded(today.minusDays(1), today))
                .isInstanceOf(SubscriptionException.class);
    }

    // ------------------------------------------------------------ phase

    @Test
    void phaseFollowsTheCalendarForAnActiveSubscription() {
        LocalDate start = LocalDate.of(2026, 9, 10);
        LocalDate end = LocalDate.of(2026, 9, 20);
        assertThat(SubscriptionRules.phase(SubscriptionStatus.ACTIVE, start, end, start.minusDays(1)))
                .isEqualTo(SubscriptionPhase.UPCOMING);
        assertThat(SubscriptionRules.phase(SubscriptionStatus.ACTIVE, start, end, start))
                .isEqualTo(SubscriptionPhase.RUNNING);
        assertThat(SubscriptionRules.phase(SubscriptionStatus.ACTIVE, start, end, end))
                .isEqualTo(SubscriptionPhase.RUNNING);
        assertThat(SubscriptionRules.phase(SubscriptionStatus.ACTIVE, start, end, end.plusDays(1)))
                .isEqualTo(SubscriptionPhase.ENDED);
    }

    @Test
    void storedStatusWinsOverTheCalendar() {
        LocalDate start = LocalDate.of(2026, 9, 10);
        LocalDate end = LocalDate.of(2026, 9, 20);
        assertThat(SubscriptionRules.phase(SubscriptionStatus.CANCELLED, start, end, start))
                .isEqualTo(SubscriptionPhase.CANCELLED);
        assertThat(SubscriptionRules.phase(SubscriptionStatus.EXPIRED, start, end, start))
                .isEqualTo(SubscriptionPhase.ENDED);
    }

    @Test
    void aSubscriptionWithoutAnExpiryNeverEndsByDate() {
        LocalDate start = LocalDate.of(2026, 9, 10);
        assertThat(SubscriptionRules.phase(SubscriptionStatus.ACTIVE, start, null, start.plusYears(5)))
                .isEqualTo(SubscriptionPhase.RUNNING);
    }

    // ------------------------------------------------------------ remaining days

    @Test
    void remainingDaysCountTodayAndTheLastDay() {
        LocalDate start = LocalDate.of(2026, 9, 1);
        LocalDate end = LocalDate.of(2026, 9, 30);
        assertThat(SubscriptionRules.remainingDays(SubscriptionPhase.UPCOMING, start, end, start.minusDays(3)))
                .isEqualTo(30);
        assertThat(SubscriptionRules.remainingDays(SubscriptionPhase.RUNNING, start, end, start)).isEqualTo(30);
        assertThat(SubscriptionRules.remainingDays(SubscriptionPhase.RUNNING, start, end, end)).isEqualTo(1);
        assertThat(SubscriptionRules.remainingDays(SubscriptionPhase.ENDED, start, end, end.plusDays(1))).isZero();
        assertThat(SubscriptionRules.remainingDays(SubscriptionPhase.CANCELLED, start, end, start)).isZero();
        assertThat(SubscriptionRules.remainingDays(SubscriptionPhase.RUNNING, start, null, start)).isNull();
    }
}
