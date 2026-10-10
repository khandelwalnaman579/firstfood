package com.firstfood.attendance;

/**
 * Lifecycle of one absence declaration. DECLARED is the only live state: it ends as CANCELLED (the declarer took
 * it back) or OVERRIDDEN (provider staff decided otherwise). Both are final - declaring the day again is a new row.
 */
public enum AbsenceStatus {
    DECLARED,
    CANCELLED,
    OVERRIDDEN
}
