package com.firstfood.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The date and policy rules of attendance, as pure logic (rules.md Rule 29.1/29.2): no Spring, no database.
 * "Today" is passed in, so nothing here depends on the clock.
 */
class AttendanceRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 7);
    private static final LocalTime CUTOFF = LocalTime.of(9, 30);

    // ------------------------------------------------------------ customerChangeBlock

    @Test
    void aDayInThePastIsNeverChangeable() {
        assertThat(block(TODAY.minusDays(1), LocalTime.of(8, 0), true, null)).contains(ChangeBlock.PAST);
        // Not even with the most generous policy imaginable.
        assertThat(block(TODAY.minusDays(30), LocalTime.of(0, 0), true, null)).contains(ChangeBlock.PAST);
    }

    @Test
    void aFutureDayIsAlwaysChangeableWhateverTheSameDayPolicy() {
        assertThat(block(TODAY.plusDays(1), LocalTime.of(23, 59), false, null)).isEmpty();
        assertThat(block(TODAY.plusDays(1), LocalTime.of(23, 59), true, CUTOFF)).isEmpty();
        assertThat(block(TODAY.plusDays(400), LocalTime.of(23, 59), false, null)).isEmpty();
    }

    @Test
    void todayIsBlockedWhenTheProviderDoesNotAllowSameDayAbsence() {
        assertThat(block(TODAY, LocalTime.of(0, 1), false, null)).contains(ChangeBlock.SAME_DAY_NOT_ALLOWED);
        assertThat(block(TODAY, LocalTime.of(23, 59), false, null)).contains(ChangeBlock.SAME_DAY_NOT_ALLOWED);
    }

    @Test
    void todayWithSameDayAllowedAndNoCutoffIsOpenAllDay() {
        assertThat(block(TODAY, LocalTime.of(0, 0), true, null)).isEmpty();
        assertThat(block(TODAY, LocalTime.of(23, 59, 59), true, null)).isEmpty();
    }

    @Test
    void todayRespectsTheCutoffAndTheCutoffInstantItselfIsStillInTime() {
        assertThat(block(TODAY, CUTOFF.minusMinutes(1), true, CUTOFF)).isEmpty();
        assertThat(block(TODAY, CUTOFF, true, CUTOFF)).isEmpty();
        assertThat(block(TODAY, CUTOFF.plusSeconds(1), true, CUTOFF)).contains(ChangeBlock.CUTOFF_PASSED);
        assertThat(block(TODAY, LocalTime.of(23, 0), true, CUTOFF)).contains(ChangeBlock.CUTOFF_PASSED);
    }

    // ------------------------------------------------------------ inWindow

    @Test
    void theWindowIncludesBothItsFirstAndItsLastDay() {
        LocalDate start = LocalDate.of(2026, 9, 1);
        LocalDate end = LocalDate.of(2026, 9, 30);
        assertThat(AttendanceRules.inWindow(start, start, end)).isTrue();
        assertThat(AttendanceRules.inWindow(end, start, end)).isTrue();
        assertThat(AttendanceRules.inWindow(start.minusDays(1), start, end)).isFalse();
        assertThat(AttendanceRules.inWindow(end.plusDays(1), start, end)).isFalse();
    }

    @Test
    void aWindowWithoutAnEndHasNoUpperBound() {
        assertThat(AttendanceRules.inWindow(LocalDate.of(2099, 1, 1), LocalDate.of(2026, 9, 1), null)).isTrue();
        assertThat(AttendanceRules.inWindow(LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 1), null)).isFalse();
    }

    // ------------------------------------------------------------ lastEntitledDay

    @Test
    void aSubscriptionThatWasNotCancelledIsEntitledUpToItsEffectiveExpiry() {
        assertThat(AttendanceRules.lastEntitledDay(LocalDate.of(2026, 9, 30), null))
                .isEqualTo(LocalDate.of(2026, 9, 30));
    }

    @Test
    void aCancelledSubscriptionIsNotEntitledOnTheDayItWasCancelled() {
        // Cancelled on the 20th: the 19th is the last entitled day.
        assertThat(AttendanceRules.lastEntitledDay(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 9, 20)))
                .isEqualTo(LocalDate.of(2026, 9, 19));
    }

    @Test
    void theEarlierOfExpiryAndTheDayBeforeCancellationWins() {
        // Cancelled after it already ended: the expiry is still the limit.
        assertThat(AttendanceRules.lastEntitledDay(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 5)))
                .isEqualTo(LocalDate.of(2026, 9, 30));
        // Cancelled on the day right after expiry: the 30th is both.
        assertThat(AttendanceRules.lastEntitledDay(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 1)))
                .isEqualTo(LocalDate.of(2026, 9, 30));
    }

    // ------------------------------------------------------------ days / validateSpan

    @Test
    void daysAreInclusiveAndInOrder() {
        assertThat(AttendanceRules.days(LocalDate.of(2026, 9, 29), LocalDate.of(2026, 10, 2)))
                .containsExactly(LocalDate.of(2026, 9, 29), LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 1),
                        LocalDate.of(2026, 10, 2));
        assertThat(AttendanceRules.days(TODAY, TODAY)).containsExactly(TODAY);
    }

    @Test
    void aSpanOfExactlyTheMaximumIsAcceptedAndOneMoreIsNot() {
        LocalDate from = LocalDate.of(2026, 10, 1);
        int max = AttendanceRules.MAX_DECLARE_SPAN_DAYS;
        assertThat(AttendanceRules.validateSpan(from, from, max)).isEqualTo(1);
        assertThat(AttendanceRules.validateSpan(from, from.plusDays(max - 1), max)).isEqualTo(max);
        assertThatThrownBy(() -> AttendanceRules.validateSpan(from, from.plusDays(max), max))
                .isInstanceOfSatisfying(AttendanceException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("DATE_RANGE_INVALID"));
    }

    @Test
    void aReversedRangeIsRejected() {
        assertThatThrownBy(() -> AttendanceRules.validateSpan(TODAY, TODAY.minusDays(1), 31))
                .isInstanceOfSatisfying(AttendanceException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("DATE_RANGE_INVALID"));
    }

    @Test
    void everyChangeBlockMapsToAStableErrorCodeAndAConflictStatus() {
        List<String> codes = java.util.Arrays.stream(ChangeBlock.values())
                .map(b -> AttendanceException.blocked(b, TODAY).getErrorCode())
                .toList();
        assertThat(codes).containsExactly("ABSENCE_DATE_IN_PAST", "SAME_DAY_ABSENCE_NOT_ALLOWED",
                "ABSENCE_CUTOFF_PASSED", "ATTENDANCE_LOCKED_BY_PROVIDER", "SUBSCRIPTION_NOT_ACTIVE");
        for (ChangeBlock b : ChangeBlock.values()) {
            assertThat(AttendanceException.blocked(b, TODAY).getStatus().value()).isEqualTo(409);
        }
    }

    private static Optional<ChangeBlock> block(LocalDate date, LocalTime now, boolean sameDay, LocalTime cutoff) {
        return AttendanceRules.customerChangeBlock(date, TODAY, now, sameDay, cutoff);
    }
}
