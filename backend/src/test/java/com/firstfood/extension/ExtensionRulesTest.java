package com.firstfood.extension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstfood.plan.ConsumptionType;
import com.firstfood.subscription.SubscriptionRef;
import com.firstfood.subscription.SubscriptionStatus;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The extension arithmetic with no Spring and no database (rules.md Rules 29.1-29.3): the PRD formula, the
 * consecutive-absence minimum at its boundaries, the cap, "a day counts once it is over", never shortening, and
 * idempotency (evaluating again after applying finds nothing to add).
 */
class ExtensionRulesTest {

    private static final LocalDate SEP_1 = LocalDate.of(2026, 9, 1);
    private static final LocalDate SEP_30 = LocalDate.of(2026, 9, 30);
    private static final LocalDate OCT_15 = LocalDate.of(2026, 10, 15);
    /** Well after everything below, so every absence is "over" unless a test says otherwise. */
    private static final LocalDate LATE = LocalDate.of(2026, 12, 1);

    private static LocalDate sep(int day) {
        return LocalDate.of(2026, 9, day);
    }

    private static List<LocalDate> sepDays(int... days) {
        List<LocalDate> dates = new ArrayList<>();
        for (int d : days) {
            dates.add(sep(d));
        }
        return dates;
    }

    /** The pilot plan: 1 Sep, 30 days, optional window. */
    private static ExtensionRules.Evaluation eval(Integer windowMaxDay, int min, List<LocalDate> absent,
            LocalDate today) {
        LocalDate max = windowMaxDay == null ? null : SEP_1.plusDays(windowMaxDay - 1);
        return ExtensionRules.evaluate(SEP_1, SEP_30, SEP_30, max, min, absent, today);
    }

    // ------------------------------------------------------------ runs

    @Test
    void runsGroupConsecutiveDatesOnly() {
        assertThat(ExtensionRules.runs(List.of())).isEmpty();
        assertThat(ExtensionRules.runs(sepDays(5))).hasSize(1);
        List<ExtensionRules.Run> runs = ExtensionRules.runs(sepDays(1, 2, 3, 5, 7, 8));
        assertThat(runs).hasSize(3);
        assertThat(runs.get(0)).isEqualTo(new ExtensionRules.Run(sep(1), sep(3)));
        assertThat(runs.get(0).days()).isEqualTo(3);
        assertThat(runs.get(1).days()).isEqualTo(1);
        assertThat(runs.get(2)).isEqualTo(new ExtensionRules.Run(sep(7), sep(8)));
    }

    @Test
    void runsAcrossAMonthBoundaryAreConsecutive() {
        List<ExtensionRules.Run> runs = ExtensionRules.runs(List.of(sep(30), LocalDate.of(2026, 10, 1)));
        assertThat(runs).hasSize(1);
        assertThat(runs.get(0).days()).isEqualTo(2);
    }

    // ------------------------------------------------------------ the minimum, at its boundaries

    @Test
    void noAbsenceMeansNoExtension() {
        ExtensionRules.Evaluation e = eval(45, 2, List.of(), LATE);
        assertThat(e.eligibleDays()).isZero();
        assertThat(e.appliedDays()).isZero();
        assertThat(e.newExpiry()).isEqualTo(SEP_30);
        assertThat(e.capped()).isFalse();
    }

    @Test
    void oneAbsentDayWithAMinimumOfTwoIsNotEnough() {
        ExtensionRules.Evaluation e = eval(45, 2, sepDays(10), LATE);
        assertThat(e.countedAbsentDays()).isEqualTo(1);
        assertThat(e.eligibleDays()).isZero();
        assertThat(e.appliedDays()).isZero();
    }

    @Test
    void exactlyTheMinimumCountsAndOneLessDoesNot() {
        assertThat(eval(45, 3, sepDays(10, 11, 12), LATE).eligibleDays()).isEqualTo(3);
        assertThat(eval(45, 3, sepDays(10, 11), LATE).eligibleDays()).isZero();
        assertThat(eval(45, 2, sepDays(10, 11), LATE).appliedDays()).isEqualTo(2);
    }

    @Test
    void aMinimumOfOneCountsEveryAbsentDay() {
        assertThat(eval(45, 1, sepDays(3, 9, 20), LATE).eligibleDays()).isEqualTo(3);
    }

    @Test
    void separateRunsAreEachJudgedOnTheirOwn() {
        // Runs of 3, 1 and 2 with a minimum of 2: the lone day is dropped, 3 + 2 count.
        ExtensionRules.Evaluation e = eval(null, 2, sepDays(1, 2, 3, 5, 7, 8), LATE);
        assertThat(e.qualifyingRuns()).hasSize(2);
        assertThat(e.eligibleDays()).isEqualTo(5);
        assertThat(e.countedAbsentDays()).isEqualTo(6);
    }

    @Test
    void aGapBreaksARun() {
        // 10, 11, (12 present), 13, 14 are two runs of 2, not one of 5; with a minimum of 3 nothing counts.
        assertThat(eval(45, 3, sepDays(10, 11, 13, 14), LATE).eligibleDays()).isZero();
        assertThat(eval(45, 2, sepDays(10, 11, 13, 14), LATE).eligibleDays()).isEqualTo(4);
    }

    @Test
    void aRunLongerThanTheMinimumCountsEveryDayOfIt() {
        // The PRD example: 5 eligible absent days.
        assertThat(eval(45, 2, sepDays(10, 11, 12, 13, 14), LATE).eligibleDays()).isEqualTo(5);
    }

    // ------------------------------------------------------------ the PRD formula

    @Test
    void prdExampleFiveDaysWithAFortyFiveDayWindowEndsOnThe5thOfOctober() {
        ExtensionRules.Evaluation e = eval(45, 2, sepDays(10, 11, 12, 13, 14), LATE);
        assertThat(e.calculatedExpiry()).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(e.newExpiry()).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(e.appliedDays()).isEqualTo(5);
        assertThat(e.requestedDays()).isEqualTo(5);
        assertThat(e.capped()).isFalse();
        // And the cap itself is 15 Oct for that window.
        assertThat(SEP_1.plusDays(44)).isEqualTo(OCT_15);
    }

    @Test
    void extensionBeyondTheMaximumIsCutToTheMaximum() {
        List<LocalDate> twenty = new ArrayList<>();
        for (int d = 1; d <= 20; d++) {
            twenty.add(sep(d));
        }
        ExtensionRules.Evaluation e = eval(45, 2, twenty, LATE);
        assertThat(e.eligibleDays()).isEqualTo(20);
        assertThat(e.calculatedExpiry()).isEqualTo(LocalDate.of(2026, 10, 20));
        assertThat(e.newExpiry()).isEqualTo(OCT_15);
        assertThat(e.requestedDays()).isEqualTo(20);
        assertThat(e.appliedDays()).isEqualTo(15);
        assertThat(e.capped()).isTrue();
    }

    @Test
    void landingExactlyOnTheMaximumIsNotCapped() {
        List<LocalDate> fifteen = new ArrayList<>();
        for (int d = 1; d <= 15; d++) {
            fifteen.add(sep(d));
        }
        ExtensionRules.Evaluation e = eval(45, 2, fifteen, LATE);
        assertThat(e.newExpiry()).isEqualTo(OCT_15);
        assertThat(e.appliedDays()).isEqualTo(15);
        assertThat(e.capped()).isFalse();
    }

    @Test
    void withoutACalendarWindowNothingCapsTheExtension() {
        // Absent for the whole 30-day subscription: it is extended by all 30 days, far past any 45-day window.
        List<LocalDate> all = new ArrayList<>();
        for (int d = 0; d < 30; d++) {
            all.add(SEP_1.plusDays(d));
        }
        ExtensionRules.Evaluation e = eval(null, 2, all, LATE);
        assertThat(e.newExpiry()).isEqualTo(SEP_30.plusDays(30));
        assertThat(e.capped()).isFalse();
    }

    @Test
    void theMaximumIsCountedFromTheOriginalStartNotFromTheCurrentExpiry() {
        // 10 days already applied (effective 10 Oct) and 20 eligible: only 5 more fit under 15 Oct.
        LocalDate effective = LocalDate.of(2026, 10, 10);
        List<LocalDate> twenty = new ArrayList<>();
        for (int d = 1; d <= 20; d++) {
            twenty.add(sep(d));
        }
        ExtensionRules.Evaluation e =
                ExtensionRules.evaluate(SEP_1, SEP_30, effective, OCT_15, 2, twenty, LATE);
        assertThat(e.appliedSoFar()).isEqualTo(10);
        assertThat(e.requestedDays()).isEqualTo(10);
        assertThat(e.appliedDays()).isEqualTo(5);
        assertThat(e.newExpiry()).isEqualTo(OCT_15);
        assertThat(e.capped()).isTrue();
    }

    @Test
    void alreadyAtTheMaximumAppliesNothingMore() {
        List<LocalDate> twenty = new ArrayList<>();
        for (int d = 1; d <= 20; d++) {
            twenty.add(sep(d));
        }
        ExtensionRules.Evaluation e = ExtensionRules.evaluate(SEP_1, SEP_30, OCT_15, OCT_15, 2, twenty, LATE);
        assertThat(e.appliedDays()).isZero();
        assertThat(e.newExpiry()).isEqualTo(OCT_15);
    }

    // ------------------------------------------------------------ idempotency and increments

    @Test
    void evaluatingAgainAfterApplyingFindsNothingToAdd() {
        List<LocalDate> absent = sepDays(10, 11, 12);
        ExtensionRules.Evaluation first = eval(45, 2, absent, LATE);
        assertThat(first.appliedDays()).isEqualTo(3);
        // The subscription now expires on first.newExpiry(); run the engine again on the same absences.
        ExtensionRules.Evaluation second = ExtensionRules.evaluate(
                SEP_1, SEP_30, first.newExpiry(), OCT_15, 2, absent, LATE);
        assertThat(second.appliedSoFar()).isEqualTo(3);
        assertThat(second.requestedDays()).isZero();
        assertThat(second.appliedDays()).isZero();
        assertThat(second.newExpiry()).isEqualTo(first.newExpiry());
    }

    @Test
    void laterAbsencesAddOnlyTheDifference() {
        ExtensionRules.Evaluation first = eval(45, 2, sepDays(10, 11, 12), LATE);
        List<LocalDate> more = sepDays(10, 11, 12, 20, 21);
        ExtensionRules.Evaluation second = ExtensionRules.evaluate(
                SEP_1, SEP_30, first.newExpiry(), OCT_15, 2, more, LATE);
        assertThat(second.eligibleDays()).isEqualTo(5);
        assertThat(second.requestedDays()).isEqualTo(2);
        assertThat(second.appliedDays()).isEqualTo(2);
        assertThat(second.newExpiry()).isEqualTo(SEP_30.plusDays(5));
    }

    @Test
    void anExtensionNeverShortensASubscription() {
        // 5 days were applied, but an absence has since been overridden: only 3 are eligible now.
        LocalDate effective = SEP_30.plusDays(5);
        ExtensionRules.Evaluation e = ExtensionRules.evaluate(
                SEP_1, SEP_30, effective, OCT_15, 2, sepDays(10, 11, 12), LATE);
        assertThat(e.eligibleDays()).isEqualTo(3);
        assertThat(e.appliedSoFar()).isEqualTo(5);
        assertThat(e.requestedDays()).isZero();
        assertThat(e.appliedDays()).isZero();
        assertThat(e.newExpiry()).isEqualTo(effective);
    }

    @Test
    void absenceInsideTheExtensionItselfExtendsFurther() {
        // Absent 28, 29, 30 Sep (3 days) -> expiry 3 Oct. Then absent 1 and 2 Oct too: one run of 5.
        List<LocalDate> absent = sepDays(28, 29, 30);
        absent.add(LocalDate.of(2026, 10, 1));
        absent.add(LocalDate.of(2026, 10, 2));
        ExtensionRules.Evaluation e = ExtensionRules.evaluate(
                SEP_1, SEP_30, SEP_30.plusDays(3), OCT_15, 2, absent, LATE);
        assertThat(e.eligibleDays()).isEqualTo(5);
        assertThat(e.appliedDays()).isEqualTo(2);
        assertThat(e.newExpiry()).isEqualTo(LocalDate.of(2026, 10, 5));
    }

    @Test
    void reconciliationAfterAnAbsenceCorrectionNeverReversesAndAddsOnlyTheDifference() {
        // Minimum 2. Days 10, 11, 12 -> +3.
        ExtensionRules.Evaluation first = eval(60, 2, sepDays(10, 11, 12), LATE);
        assertThat(first.appliedDays()).isEqualTo(3);
        LocalDate afterFirst = first.newExpiry();
        // Day 12 is overridden: eligibility 2, three days already applied -> over-applied, nothing to add or take back.
        ExtensionRules.Evaluation corrected = ExtensionRules.evaluate(
                SEP_1, SEP_30, afterFirst, SEP_1.plusDays(59), 2, sepDays(10, 11), LATE);
        assertThat(corrected.eligibleDays()).isEqualTo(2);
        assertThat(corrected.appliedSoFar()).isEqualTo(3);
        assertThat(corrected.appliedDays()).isZero();
        assertThat(corrected.newExpiry()).isEqualTo(afterFirst);
        // New absences on 20 and 21: eligibility 4 -> only +1 more; total 4.
        ExtensionRules.Evaluation later = ExtensionRules.evaluate(
                SEP_1, SEP_30, afterFirst, SEP_1.plusDays(59), 2, sepDays(10, 11, 20, 21), LATE);
        assertThat(later.eligibleDays()).isEqualTo(4);
        assertThat(later.requestedDays()).isEqualTo(1);
        assertThat(later.appliedDays()).isEqualTo(1);
        assertThat(later.newExpiry()).isEqualTo(SEP_30.plusDays(4));
    }

    // ------------------------------------------------------------ episodes that cross the expiry

    private static final LocalDate AFTER_ALL = LocalDate.of(2026, 12, 1);

    private static LocalDate oct(int day) {
        return LocalDate.of(2026, 10, day);
    }

    private static LocalDate nov(int day) {
        return LocalDate.of(2026, 11, day);
    }

    /** Base expiry 30 Oct (start 1 Oct), no cap, minimum {@code min}, evaluated well after. */
    private static ExtensionRules.Evaluation octEval(int min, List<LocalDate> absent) {
        return ExtensionRules.evaluate(oct(1), oct(30), oct(30), null, min, absent, AFTER_ALL);
    }

    private static List<LocalDate> range(LocalDate from, LocalDate to) {
        List<LocalDate> l = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            l.add(d);
        }
        return l;
    }

    private static List<LocalDate> concat(List<LocalDate> a, List<LocalDate> b) {
        List<LocalDate> l = new ArrayList<>(a);
        l.addAll(b);
        return l;
    }

    @Test
    void anEpisodeThatCrossesTheExpiryCountsInFull() {
        ExtensionRules.Evaluation e = octEval(2, range(oct(27), nov(10)));
        assertThat(e.eligibleDays()).isEqualTo(15);
        assertThat(e.appliedDays()).isEqualTo(15);
        assertThat(e.newExpiry()).isEqualTo(nov(14));
    }

    @Test
    void anEpisodeStartingOnTheExpiryDayCountsAndOneStartingAfterDoesNot() {
        assertThat(octEval(2, range(oct(30), nov(5))).newExpiry()).isEqualTo(nov(6));
        assertThat(octEval(2, range(oct(29), nov(2))).appliedDays()).isEqualTo(5);
        assertThat(octEval(2, range(oct(31), nov(10))).appliedDays()).isZero();
        assertThat(octEval(2, range(nov(1), nov(10))).appliedDays()).isZero();
    }

    @Test
    void anEpisodeWhichStartsWithinTheExtendedEntitlementCascadesInOneEvaluation() {
        // 27-29 Oct earns 3 (-> 2 Nov); 31 Oct-2 Nov then begins inside that entitlement and earns 3 more (-> 5 Nov).
        List<LocalDate> absent = concat(range(oct(27), oct(29)), range(oct(31), nov(2)));
        ExtensionRules.Evaluation e = octEval(2, absent);
        assertThat(e.eligibleDays()).isEqualTo(6);
        assertThat(e.newExpiry()).isEqualTo(nov(5));
        // An episode beginning after the final entitlement still does not count.
        assertThat(octEval(2, concat(absent, range(nov(20), nov(25)))).newExpiry()).isEqualTo(nov(5));
    }

    @Test
    void separateEpisodesOneInsideAndOneStartingBeforeExpiry() {
        // 11-13 and 18-23 with expiry 20 Oct: 3 + 6 = 9 -> 29 Oct.
        ExtensionRules.Evaluation e = ExtensionRules.evaluate(oct(1), oct(20), oct(20), null, 2,
                concat(range(oct(11), oct(13)), range(oct(18), oct(23))), AFTER_ALL);
        assertThat(e.eligibleDays()).isEqualTo(9);
        assertThat(e.newExpiry()).isEqualTo(oct(29));
    }

    @Test
    void aGrowingEpisodeAddsOnlyTheIncrement() {
        // 27 Oct -> 5 Nov applied (+10, expiry 9 Nov); the episode later runs to 10 Nov: +5, not +15.
        ExtensionRules.Evaluation later = ExtensionRules.evaluate(oct(1), oct(30), nov(9), null, 2,
                range(oct(27), nov(10)), AFTER_ALL);
        assertThat(later.appliedSoFar()).isEqualTo(10);
        assertThat(later.eligibleDays()).isEqualTo(15);
        assertThat(later.appliedDays()).isEqualTo(5);
        ExtensionRules.Evaluation again = ExtensionRules.evaluate(oct(1), oct(30), later.newExpiry(), null, 2,
                range(oct(27), nov(10)), AFTER_ALL);
        assertThat(again.appliedDays()).isZero();
        assertThat(again.overAppliedDays()).isZero();
    }

    @Test
    void correctionsReportOverAppliedDaysAndNeverShorten() {
        // 27-30 Oct applied (+4, expiry 3 Nov); 30 Oct corrected to present: eligible 3, applied 4, gap 1.
        ExtensionRules.Evaluation e = ExtensionRules.evaluate(oct(1), oct(30), nov(3), null, 2,
                range(oct(27), oct(29)), AFTER_ALL);
        assertThat(e.overAppliedDays()).isEqualTo(1);
        assertThat(e.appliedDays()).isZero();
        assertThat(e.newExpiry()).isEqualTo(nov(3));
        // The whole episode corrected away: all 4 over-applied, expiry stays.
        ExtensionRules.Evaluation none = ExtensionRules.evaluate(oct(1), oct(30), nov(3), null, 2, List.of(),
                AFTER_ALL);
        assertThat(none.overAppliedDays()).isEqualTo(4);
        assertThat(none.newExpiry()).isEqualTo(nov(3));
        // Later absences 20-21 Oct join: eligible 3 + 2 = 5, applied 4 -> only +1, gap absorbed.
        ExtensionRules.Evaluation absorbed = ExtensionRules.evaluate(oct(1), oct(30), nov(3), null, 2,
                concat(range(oct(20), oct(21)), range(oct(27), oct(29))), AFTER_ALL);
        assertThat(absorbed.eligibleDays()).isEqualTo(5);
        assertThat(absorbed.appliedDays()).isEqualTo(1);
        assertThat(absorbed.overAppliedDays()).isZero();
    }

    @Test
    void theMinimumIsJudgedOnTheWholeEpisode() {
        assertThat(octEval(3, concat(range(oct(27), oct(28)), range(oct(30), oct(31)))).appliedDays()).isZero();
        assertThat(octEval(3, range(oct(29), oct(29))).appliedDays()).isZero();
        assertThat(octEval(3, range(oct(27), oct(29))).appliedDays()).isEqualTo(3);
    }

    // ------------------------------------------------------------ which days count

    @Test
    void aDayCountsOnlyOnceItIsOver() {
        LocalDate today = sep(12);
        // 10 and 11 are over; 12 (today) and 13 (tomorrow) are not.
        ExtensionRules.Evaluation e = eval(45, 2, sepDays(10, 11, 12, 13), today);
        assertThat(e.countedThrough()).isEqualTo(sep(11));
        assertThat(e.countedAbsentDays()).isEqualTo(2);
        assertThat(e.eligibleDays()).isEqualTo(2);
        // The same absences a day later: today's has become over.
        assertThat(eval(45, 2, sepDays(10, 11, 12, 13), sep(13)).eligibleDays()).isEqualTo(3);
    }

    @Test
    void aFutureOnlyAbsenceCountsForNothingYet() {
        assertThat(eval(45, 2, sepDays(20, 21, 22), sep(10)).eligibleDays()).isZero();
    }

    @Test
    void daysOutsideTheSubscriptionAreIgnoredAndDuplicatesCountOnce() {
        List<LocalDate> absent = new ArrayList<>(sepDays(10, 10, 11));
        absent.add(SEP_1.minusDays(1));
        absent.add(SEP_30.plusDays(20)); // a lone day long after the subscription ended
        ExtensionRules.Evaluation e = eval(45, 2, absent, LATE);
        assertThat(e.countedAbsentDays()).isEqualTo(2);
        assertThat(e.eligibleDays()).isEqualTo(2);
    }

    @Test
    void aMinimumBelowOneIsRejected() {
        assertThatThrownBy(() -> eval(45, 0, List.of(), LATE)).isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------ who can be extended at all

    private static SubscriptionRef ref(ConsumptionType type, SubscriptionStatus status, boolean allowed,
            boolean renewed) {
        return new SubscriptionRef(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), type,
                status, SEP_1, SEP_30, SEP_30, OCT_15, null, "Monthly", false, null, allowed, allowed ? 2 : null,
                renewed);
    }

    @Test
    void onlyAnActiveDayPlanSoldWithExtensionAndNotRenewedCanBeExtended() {
        assertThat(ExtensionRules.block(ref(ConsumptionType.DAY, SubscriptionStatus.ACTIVE, true, false))).isEmpty();
        assertThat(ExtensionRules.block(ref(ConsumptionType.MEAL, SubscriptionStatus.ACTIVE, false, false)))
                .contains(ExtensionBlock.NOT_SUPPORTED_FOR_MEAL);
        assertThat(ExtensionRules.block(ref(ConsumptionType.DAY, SubscriptionStatus.ACTIVE, false, false)))
                .contains(ExtensionBlock.NOT_ALLOWED_BY_POLICY);
        assertThat(ExtensionRules.block(ref(ConsumptionType.DAY, SubscriptionStatus.CANCELLED, true, false)))
                .contains(ExtensionBlock.SUBSCRIPTION_CANCELLED);
        assertThat(ExtensionRules.block(ref(ConsumptionType.DAY, SubscriptionStatus.EXPIRED, true, false)))
                .contains(ExtensionBlock.SUBSCRIPTION_EXPIRED);
        // A renewal expires its predecessor: the clearer reason wins.
        assertThat(ExtensionRules.block(ref(ConsumptionType.DAY, SubscriptionStatus.EXPIRED, true, true)))
                .contains(ExtensionBlock.ALREADY_RENEWED);
        assertThat(ExtensionRules.block(ref(ConsumptionType.DAY, SubscriptionStatus.ACTIVE, true, true)))
                .contains(ExtensionBlock.ALREADY_RENEWED);
    }
}
