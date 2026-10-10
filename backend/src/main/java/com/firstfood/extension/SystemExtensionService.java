package com.firstfood.extension;

import java.util.UUID;

/**
 * <b>SYSTEM ONLY.</b> The extension engine's entry point for trusted lifecycle / background infrastructure (the
 * Phase 10 jobs). It is deliberately a separate interface from {@link ExtensionService}, which is the user-facing
 * boundary: nothing under a {@code web} package, and no class that handles a client request, may depend on this one
 * ({@code ExtensionSystemBoundaryTest} fails the build if one does).
 *
 * <ul>
 *   <li>No authorization is performed: the caller is trusted application code, never a request. There is no actor and
 *       no fake "system account": the event records source SYSTEM and {@code created_by = NULL}.</li>
 *   <li>Same calculation, same eligibility rules, same transaction and locking as a staff apply - only the recorded
 *       source and the missing actor differ.</li>
 *   <li>Idempotent: safe to run any number of times for one subscription.</li>
 *   <li>A CLOSED provider does not stop it (frozen decision B9): extension is an entitlement already earned from
 *       historical absences, not new business; closing a provider must not make a customer lose it. A closed
 *       provider remains read-only for STAFF.</li>
 *   <li>Phase 10 must call this BEFORE deciding a lapsed subscription is EXPIRED (B8).</li>
 * </ul>
 */
public interface SystemExtensionService {

    /**
     * Applies whatever extension the subscription's terms entitle it to right now, as the system. Returns
     * {@code applied=false} when there is nothing to add. Refuses (409) a subscription that cannot be extended at all.
     */
    ApplyExtensionResult applyAutomatically(UUID providerId, UUID subscriptionId);
}
