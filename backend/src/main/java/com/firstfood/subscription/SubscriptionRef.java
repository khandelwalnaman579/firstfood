package com.firstfood.subscription;

import com.firstfood.plan.ConsumptionType;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * The facts about a subscription that other modules (attendance) may rely on; no entity leaves this module.
 * Carries the absence and extension policy the subscription was SOLD under (from its term snapshot, never from the current
 * plan - rules.md Rule 15.2) but deliberately no price: staff who may only handle attendance must not receive
 * what the customer paid.
 */
public record SubscriptionRef(
        UUID subscriptionId,
        UUID providerId,
        UUID membershipId,
        UUID personId,
        ConsumptionType consumptionType,
        SubscriptionStatus status,
        LocalDate startDate,
        LocalDate baseExpiryDate,
        LocalDate effectiveExpiryDate,
        /** start + the policy's calendar window - 1 (counted from the ORIGINAL start); null = no window, no cap. */
        LocalDate maximumExpiryDate,
        Instant cancelledAt,
        String planName,
        boolean sameDayAbsenceAllowed,
        LocalTime absenceCutoffTime,
        /** The extension terms it was SOLD under (term snapshot), for the Phase 9 extension engine. */
        boolean extensionAllowed,
        Integer minConsecutiveAbsenceDays,
        /** Whether a renewal of this subscription exists (an extension would then overlap it). */
        boolean renewed) {

    /** Where the subscription stands on {@code today} (same rule as every subscription view). */
    public SubscriptionPhase phaseOn(LocalDate today) {
        return SubscriptionRules.phase(status, startDate, effectiveExpiryDate, today);
    }
}
