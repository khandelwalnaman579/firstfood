package com.firstfood.provideraccess;

/**
 * The fixed, application-defined provider permission set (Phase 4 decision
 * record §4). Owners choose who gets which ROLE; they never define permissions
 * at runtime. Operational permissions (customers, attendance, plans...) are
 * added by the phases that introduce those domains.
 */
public enum ProviderPermission {
    PROVIDER_VIEW,
    PROVIDER_EDIT,
    PROVIDER_CLOSE,
    ROLE_VIEW,
    ROLE_ASSIGN,
    ROLE_REVOKE,
    OWNER_TRANSFER,
    /** Phase 5: see a provider's customers (memberships). */
    MEMBERSHIP_VIEW,
    /** Phase 5: add a customer to / remove a customer from a provider. */
    MEMBERSHIP_MANAGE,
    /** Phase 6: see a provider's plans (and their current policy). */
    PLAN_VIEW,
    /** Phase 6: create, edit, activate and deactivate a provider's plans (pricing + policy). */
    PLAN_MANAGE,
    /** Phase 7: see a provider's subscriptions (with the terms each was sold under). */
    SUBSCRIPTION_VIEW,
    /** Phase 7: sell (create / renew) and cancel subscriptions. */
    SUBSCRIPTION_MANAGE,
    /** Phase 8: see who is eating/absent (daily sheet, a subscription's days and absence history). No prices. */
    ATTENDANCE_VIEW,
    /** Phase 8: record an absence for a customer, override one, or restore a day (always with a reason). */
    ATTENDANCE_MANAGE
}
