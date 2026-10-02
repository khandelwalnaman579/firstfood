package com.firstfood.plan.dto;

import com.firstfood.plan.PolicyTerms;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.LocalTime;

/**
 * The absence/extension terms, always sent in full (no hidden defaults: a provider
 * consciously chooses each switch). Field-level ranges are checked here; the rules that
 * relate fields to each other and to the plan type are in {@code PlanRules}.
 * {@code absenceCutoffTime} is a provider-local time such as "09:30".
 */
public record PolicyRequest(
        @NotNull Boolean extensionAllowed,
        @Min(1) @Max(1000) Integer minConsecutiveAbsenceDays,
        @NotNull Boolean sameDayAbsenceAllowed,
        LocalTime absenceCutoffTime,
        @Min(1) @Max(1000) Integer maxCalendarWindowDays) {

    public PolicyTerms toTerms() {
        return new PolicyTerms(extensionAllowed, minConsecutiveAbsenceDays, sameDayAbsenceAllowed,
                absenceCutoffTime, maxCalendarWindowDays);
    }
}
