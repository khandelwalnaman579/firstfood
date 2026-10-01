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
    OWNER_TRANSFER
}
