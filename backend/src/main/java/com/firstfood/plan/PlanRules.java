package com.firstfood.plan;

/**
 * The plan invariants that depend on more than one field. Pure logic (no Spring, no
 * database) so it is unit-tested directly (rules.md Rule 29.1); the single place these
 * rules live (controllers and repositories decide nothing - Rules 18.2, 18.3). The same
 * simple per-row invariants are repeated as CHECK constraints in V8 as a last defence.
 *
 * <ol>
 *   <li>DAY needs {@code durationDays} and no {@code mealQuantity}; MEAL is the reverse
 *       (Rules 7.3, 7.4).</li>
 *   <li>Extension applies to DAY plans only: a MEAL entitlement is quantity based, absence
 *       does not consume it, so there is nothing to extend. (Frozen here on purpose -
 *       loosening later is easy, tightening once data exists is not.)</li>
 *   <li>Extension on => a minimum consecutive absence is required; off => it must be absent.</li>
 *   <li>Same-day absence off => no cutoff time.</li>
 *   <li>DAY maximum calendar window: only meaningful when extension is allowed (it is the
 *       cap for extensions) and can never be shorter than the purchased duration.</li>
 * </ol>
 */
final class PlanRules {

    private PlanRules() {
    }

    static void validate(ConsumptionType type, Integer durationDays, Integer mealQuantity, PolicyTerms policy) {
        validateQuantity(type, durationDays, mealQuantity);
        validatePolicy(type, durationDays, policy);
    }

    private static void validateQuantity(ConsumptionType type, Integer durationDays, Integer mealQuantity) {
        switch (type) {
            case DAY -> {
                if (durationDays == null) {
                    throw PlanException.termsInvalid("A DAY plan needs durationDays.");
                }
                if (mealQuantity != null) {
                    throw PlanException.termsInvalid("A DAY plan must not set mealQuantity.");
                }
            }
            case MEAL -> {
                if (mealQuantity == null) {
                    throw PlanException.termsInvalid("A MEAL plan needs mealQuantity.");
                }
                if (durationDays != null) {
                    throw PlanException.termsInvalid("A MEAL plan must not set durationDays.");
                }
            }
        }
    }

    private static void validatePolicy(ConsumptionType type, Integer durationDays, PolicyTerms policy) {
        if (policy.extensionAllowed()) {
            if (type != ConsumptionType.DAY) {
                throw PlanException.policyInvalid("Extension on absence applies to DAY plans only.");
            }
            if (policy.minConsecutiveAbsenceDays() == null) {
                throw PlanException.policyInvalid(
                        "minConsecutiveAbsenceDays is required when extension is allowed.");
            }
        } else if (policy.minConsecutiveAbsenceDays() != null) {
            throw PlanException.policyInvalid(
                    "minConsecutiveAbsenceDays must be empty when extension is not allowed.");
        }

        if (!policy.sameDayAbsenceAllowed() && policy.absenceCutoffTime() != null) {
            throw PlanException.policyInvalid("absenceCutoffTime must be empty when same-day absence is not allowed.");
        }

        Integer window = policy.maxCalendarWindowDays();
        if (type == ConsumptionType.DAY && window != null) {
            if (!policy.extensionAllowed()) {
                throw PlanException.policyInvalid(
                        "maxCalendarWindowDays for a DAY plan only applies when extension is allowed.");
            }
            if (window < durationDays) {
                throw PlanException.policyInvalid(
                        "maxCalendarWindowDays cannot be shorter than the plan's durationDays.");
            }
        }
    }
}
