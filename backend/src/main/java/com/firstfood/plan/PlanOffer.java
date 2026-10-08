package com.firstfood.plan;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A plan exactly as it is offered at this moment - its commercial terms plus its CURRENT
 * policy version - handed to the subscription module so it can snapshot them. A plain value:
 * the subscription module copies what it needs and never reads the plan again to explain a past
 * sale (rules.md Rules 8.6, 15.2).
 */
public record PlanOffer(
        UUID planId,
        UUID providerId,
        String name,
        ConsumptionType consumptionType,
        Integer durationDays,
        Integer mealQuantity,
        BigDecimal price,
        String currency,
        boolean active,
        int policyVersion,
        PolicyTerms policy) {
}
