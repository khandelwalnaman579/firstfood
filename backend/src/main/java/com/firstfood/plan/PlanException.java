package com.firstfood.plan;

import com.firstfood.common.error.DomainException;
import org.springframework.http.HttpStatus;

/** Business-rule violations in plan management. Use the factories. */
public class PlanException extends DomainException {

    private PlanException(String code, String message, HttpStatus status) {
        super(code, message, status);
    }

    /** Consumption-specific fields wrong for the plan type (e.g. a DAY plan without durationDays). */
    public static PlanException termsInvalid(String message) {
        return new PlanException("PLAN_TERMS_INVALID", message, HttpStatus.BAD_REQUEST);
    }

    /** Policy fields inconsistent with each other or with the plan type. */
    public static PlanException policyInvalid(String message) {
        return new PlanException("PLAN_POLICY_INVALID", message, HttpStatus.BAD_REQUEST);
    }

    public static PlanException planNotFound() {
        return new PlanException("PLAN_NOT_FOUND", "Plan not found.", HttpStatus.NOT_FOUND);
    }

    public static PlanException nameInUse() {
        return new PlanException("PLAN_NAME_IN_USE",
                "Another active plan of this provider already has that name.", HttpStatus.CONFLICT);
    }

    public static PlanException alreadyActive() {
        return new PlanException("PLAN_ALREADY_ACTIVE", "This plan is already active.", HttpStatus.CONFLICT);
    }

    public static PlanException alreadyInactive() {
        return new PlanException("PLAN_ALREADY_INACTIVE", "This plan is already inactive.", HttpStatus.CONFLICT);
    }

    /** Same code/status as the provider module's ProviderClosedException (no dependency on its type). */
    public static PlanException providerClosed() {
        return new PlanException("PROVIDER_CLOSED",
                "This provider is closed and can no longer be modified.", HttpStatus.CONFLICT);
    }
}
