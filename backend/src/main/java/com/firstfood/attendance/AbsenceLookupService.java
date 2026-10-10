package com.firstfood.attendance;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Narrow read-only lookup for other modules (the Phase 9 extension engine), so they never touch
 * {@link AbsenceRecord} or its repository (rules.md Rule 27.3/27.4). Performs NO authorization - callers must have
 * authorized the actor and resolved the subscription within the right provider/customer scope first.
 */
public interface AbsenceLookupService {

    /**
     * The dates on which the subscription currently has a DECLARED absence (cancelled and overridden declarations are
     * not included), oldest first, at most one per day. Reading takes no lock: callers that decide something from the
     * answer hold the subscription row lock, which every absence write also takes.
     */
    List<LocalDate> declaredAbsenceDates(UUID subscriptionId);
}
