package com.firstfood.attendance;

/**
 * Where an outcome came from (rules.md Rule 11.4).
 * SYSTEM: the default (assumed present) restored. CUSTOMER: the customer's own declaration.
 * OWNER_CORRECTION: provider staff decided - the owner, or a manager/worker acting for the provider.
 */
public enum AttendanceSource {
    SYSTEM,
    CUSTOMER,
    OWNER_CORRECTION
}
