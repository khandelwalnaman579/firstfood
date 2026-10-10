package com.firstfood.plan;

/**
 * Plan lifecycle. INACTIVE plans can no longer be chosen for NEW subscriptions but are
 * never deleted: existing subscriptions keep their plan reference (rules.md Rule 8.4, 15.3).
 */
public enum PlanStatus {
    ACTIVE,
    INACTIVE
}
