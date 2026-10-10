package com.firstfood.plan;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Read model of a plan and its CURRENT policy version. Describes today's offering only -
 * it must not be used to explain a past subscription (that is the Phase 7 snapshot's job).
 */
public record PlanView(
        UUID id,
        UUID providerId,
        String name,
        String description,
        ConsumptionType consumptionType,
        Integer durationDays,
        Integer mealQuantity,
        BigDecimal price,
        String currency,
        PlanStatus status,
        PolicyView policy,
        UUID createdBy,
        UUID updatedBy,
        Instant createdAt,
        Instant updatedAt) {

    public record PolicyView(
            int version,
            boolean extensionAllowed,
            Integer minConsecutiveAbsenceDays,
            boolean sameDayAbsenceAllowed,
            LocalTime absenceCutoffTime,
            Integer maxCalendarWindowDays,
            Instant effectiveFrom) {

        static PolicyView from(SubscriptionPolicy policy) {
            PolicyTerms t = policy.terms();
            return new PolicyView(policy.getVersion(), t.extensionAllowed(), t.minConsecutiveAbsenceDays(),
                    t.sameDayAbsenceAllowed(), t.absenceCutoffTime(), t.maxCalendarWindowDays(),
                    policy.getCreatedAt());
        }
    }

    static PlanView from(Plan p, SubscriptionPolicy policy) {
        return new PlanView(p.getId(), p.getProviderId(), p.getName(), p.getDescription(), p.getConsumptionType(),
                p.getDurationDays(), p.getMealQuantity(), p.getPrice(), p.getCurrency(), p.getStatus(),
                PolicyView.from(policy), p.getCreatedBy(), p.getUpdatedBy(), p.getCreatedAt(), p.getUpdatedAt());
    }
}
