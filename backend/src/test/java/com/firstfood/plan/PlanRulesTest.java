package com.firstfood.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalTime;
import org.junit.jupiter.api.Test;

/**
 * The cross-field plan invariants, with no Spring and no database (rules.md Rule 29.1/29.2).
 * Boundaries are tested deliberately: window == duration (allowed) vs duration - 1 (rejected),
 * min consecutive absence of 1, and every "this field must be absent" case.
 */
class PlanRulesTest {

    private static final PolicyTerms NO_POLICY = new PolicyTerms(false, null, false, null, null);

    private static PolicyTerms extension(Integer min, Integer window) {
        return new PolicyTerms(true, min, false, null, window);
    }

    // ------------------------------------------------------------ consumption-specific quantity

    @Test
    void dayPlanNeedsDurationAndNoMealQuantity() {
        assertThatCode(() -> PlanRules.validate(ConsumptionType.DAY, 30, null, NO_POLICY)).doesNotThrowAnyException();
        assertTermsInvalid(() -> PlanRules.validate(ConsumptionType.DAY, null, null, NO_POLICY));
        assertTermsInvalid(() -> PlanRules.validate(ConsumptionType.DAY, 30, 60, NO_POLICY));
        assertTermsInvalid(() -> PlanRules.validate(ConsumptionType.DAY, null, 60, NO_POLICY));
    }

    @Test
    void mealPlanNeedsMealQuantityAndNoDuration() {
        assertThatCode(() -> PlanRules.validate(ConsumptionType.MEAL, null, 60, NO_POLICY)).doesNotThrowAnyException();
        assertTermsInvalid(() -> PlanRules.validate(ConsumptionType.MEAL, null, null, NO_POLICY));
        assertTermsInvalid(() -> PlanRules.validate(ConsumptionType.MEAL, 30, 60, NO_POLICY));
        assertTermsInvalid(() -> PlanRules.validate(ConsumptionType.MEAL, 30, null, NO_POLICY));
    }

    // ------------------------------------------------------------ extension / min consecutive

    @Test
    void extensionRequiresMinimumConsecutiveAbsence() {
        assertThatCode(() -> PlanRules.validate(ConsumptionType.DAY, 30, null, extension(2, 45)))
                .doesNotThrowAnyException();
        // The smallest meaningful minimum (1 day) is accepted.
        assertThatCode(() -> PlanRules.validate(ConsumptionType.DAY, 30, null, extension(1, null)))
                .doesNotThrowAnyException();
        assertPolicyInvalid(() -> PlanRules.validate(ConsumptionType.DAY, 30, null, extension(null, 45)));
    }

    @Test
    void minimumConsecutiveAbsenceMustBeAbsentWithoutExtension() {
        PolicyTerms stray = new PolicyTerms(false, 2, false, null, null);
        assertPolicyInvalid(() -> PlanRules.validate(ConsumptionType.DAY, 30, null, stray));
    }

    @Test
    void extensionIsForDayPlansOnly() {
        assertPolicyInvalid(() -> PlanRules.validate(ConsumptionType.MEAL, null, 60, extension(2, null)));
    }

    // ------------------------------------------------------------ same-day absence / cutoff

    @Test
    void cutoffOnlyWhenSameDayAbsenceAllowed() {
        PolicyTerms withCutoff = new PolicyTerms(false, null, true, LocalTime.of(9, 30), null);
        assertThatCode(() -> PlanRules.validate(ConsumptionType.DAY, 30, null, withCutoff)).doesNotThrowAnyException();
        // Same-day allowed with no cutoff = no time limit; also valid.
        PolicyTerms noCutoff = new PolicyTerms(false, null, true, null, null);
        assertThatCode(() -> PlanRules.validate(ConsumptionType.DAY, 30, null, noCutoff)).doesNotThrowAnyException();

        PolicyTerms contradictory = new PolicyTerms(false, null, false, LocalTime.of(9, 30), null);
        assertPolicyInvalid(() -> PlanRules.validate(ConsumptionType.DAY, 30, null, contradictory));
    }

    @Test
    void cutoffIsNormalisedToMinutesSoEqualPoliciesCompareEqual() {
        PolicyTerms a = new PolicyTerms(false, null, true, LocalTime.of(9, 30, 45), null);
        PolicyTerms b = new PolicyTerms(false, null, true, LocalTime.of(9, 30), null);
        assertThat(a).isEqualTo(b);
        assertThat(a.absenceCutoffTime()).isEqualTo(LocalTime.of(9, 30));
    }

    // ------------------------------------------------------------ maximum calendar window

    @Test
    void dayWindowCannotBeShorterThanTheDuration() {
        // window == duration is the boundary: allowed (the cap simply leaves no room).
        assertThatCode(() -> PlanRules.validate(ConsumptionType.DAY, 30, null, extension(2, 30)))
                .doesNotThrowAnyException();
        assertPolicyInvalid(() -> PlanRules.validate(ConsumptionType.DAY, 30, null, extension(2, 29)));
    }

    @Test
    void dayWindowWithoutExtensionIsRejected() {
        PolicyTerms windowOnly = new PolicyTerms(false, null, false, null, 45);
        assertPolicyInvalid(() -> PlanRules.validate(ConsumptionType.DAY, 30, null, windowOnly));
    }

    @Test
    void mealPlanMayHaveAnIndependentCalendarWindow() {
        PolicyTerms windowOnly = new PolicyTerms(false, null, false, null, 90);
        assertThatCode(() -> PlanRules.validate(ConsumptionType.MEAL, null, 60, windowOnly)).doesNotThrowAnyException();
    }

    @Test
    void dayPlanWithoutAnyWindowIsUncapped() {
        assertThatCode(() -> PlanRules.validate(ConsumptionType.DAY, 30, null, extension(2, null)))
                .doesNotThrowAnyException();
    }

    // ------------------------------------------------------------ helpers

    private static void assertTermsInvalid(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOf(PlanException.class)
                .extracting(e -> ((PlanException) e).getErrorCode()).isEqualTo("PLAN_TERMS_INVALID");
    }

    private static void assertPolicyInvalid(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOf(PlanException.class)
                .extracting(e -> ((PlanException) e).getErrorCode()).isEqualTo("PLAN_POLICY_INVALID");
    }
}
