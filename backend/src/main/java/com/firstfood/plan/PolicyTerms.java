package com.firstfood.plan;

import java.time.LocalTime;
import java.time.temporal.ChronoUnit;

/**
 * The explicit absence/extension terms of a plan (rules.md Rule 14.1; no generic rules
 * engine - Rule 14.2). A plain value: two PolicyTerms are equal when every term is equal,
 * which is how an unchanged policy is recognised (and given no new version).
 *
 * <ul>
 *   <li>{@code extensionAllowed} - may absences extend the subscription (DAY plans only).</li>
 *   <li>{@code minConsecutiveAbsenceDays} - consecutive absent days needed before an
 *       extension is considered; present exactly when extensions are allowed.</li>
 *   <li>{@code sameDayAbsenceAllowed} - may a customer declare an absence for today.</li>
 *   <li>{@code absenceCutoffTime} - provider-local time after which a same-day absence is
 *       refused; null = no cutoff; only meaningful when same-day absence is allowed.</li>
 *   <li>{@code maxCalendarWindowDays} - cap counted from the ORIGINAL subscription start;
 *       null = no cap.</li>
 * </ul>
 *
 * The cutoff is normalised to minute precision so equality is stable.
 */
public record PolicyTerms(
        boolean extensionAllowed,
        Integer minConsecutiveAbsenceDays,
        boolean sameDayAbsenceAllowed,
        LocalTime absenceCutoffTime,
        Integer maxCalendarWindowDays) {

    public PolicyTerms {
        if (absenceCutoffTime != null) {
            absenceCutoffTime = absenceCutoffTime.truncatedTo(ChronoUnit.MINUTES);
        }
    }
}
