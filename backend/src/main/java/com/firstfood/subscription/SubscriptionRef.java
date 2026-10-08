package com.firstfood.subscription;

import com.firstfood.plan.ConsumptionType;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * The facts about a subscription that other modules (attendance) may rely on; no entity leaves this module.
 * Carries the absence policy the subscription was SOLD under (from its term snapshot, never from the current
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
        LocalDate effectiveExpiryDate,
        Instant cancelledAt,
        String planName,
        boolean sameDayAbsenceAllowed,
        LocalTime absenceCutoffTime) {

    /** Where the subscription stands on {@code today} (same rule as every subscription view). */
    public SubscriptionPhase phaseOn(LocalDate today) {
        return SubscriptionRules.phase(status, startDate, effectiveExpiryDate, today);
    }
}
