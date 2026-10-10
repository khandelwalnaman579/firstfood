package com.firstfood.plan;

/**
 * How a plan's entitlement is consumed (rules.md Rule 7.3). Distinct models, never one
 * generic "quantity":
 * <ul>
 *   <li>DAY - time based; the commercial quantity is {@code durationDays}.</li>
 *   <li>MEAL - quantity based; the commercial quantity is {@code mealQuantity}.</li>
 * </ul>
 */
public enum ConsumptionType {
    DAY,
    MEAL
}
