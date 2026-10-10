package com.firstfood.plan;

import java.util.Optional;
import java.util.UUID;

/**
 * Narrow lookup for other modules (subscription). Exists so they never touch {@link Plan},
 * {@link SubscriptionPolicy} or their repositories directly (rules.md Rule 27.3/27.4).
 * Performs NO authorization - callers must have called
 * {@link com.firstfood.provideraccess.ProviderAccessService#requirePermission} first.
 */
public interface PlanLookupService {

    /**
     * The plan as offered right now, with its current policy version. Scoped by provider: a plan
     * of another provider is simply absent (no IDOR). Inactive plans are returned too (see
     * {@link PlanOffer#active()}) so the caller can say why a sale is refused.
     *
     * Must run inside an existing transaction. Callers that sell the plan should already hold the
     * provider row lock: every plan edit takes it, so the plan and its policy version cannot change
     * between this read and the sale.
     */
    Optional<PlanOffer> findOffer(UUID providerId, UUID planId);
}
