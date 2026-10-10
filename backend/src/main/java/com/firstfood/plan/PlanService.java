package com.firstfood.plan;

import com.firstfood.plan.dto.CreatePlanRequest;
import com.firstfood.plan.dto.UpdatePlanRequest;
import java.util.List;
import java.util.UUID;

/**
 * Provider plan management. Every method authorizes through ProviderAccessService first;
 * the acting account always comes from the authenticated principal, never from request data.
 * Plans are never deleted - only deactivated.
 */
public interface PlanService {

    /** Requires PLAN_MANAGE. Creates an ACTIVE plan with policy version 1. Blocked on closed providers. */
    PlanView create(UUID actorAccountId, UUID providerId, CreatePlanRequest request);

    /** Requires PLAN_VIEW. Active plans only unless {@code includeInactive}. */
    List<PlanView> list(UUID actorAccountId, UUID providerId, boolean includeInactive);

    /** Requires PLAN_VIEW. A plan of another provider is reported as not found. */
    PlanView get(UUID actorAccountId, UUID providerId, UUID planId);

    /**
     * Requires PLAN_MANAGE. Replaces the plan's current terms; a changed policy becomes a
     * NEW policy version (an unchanged one creates none). Never affects existing subscriptions.
     */
    PlanView update(UUID actorAccountId, UUID providerId, UUID planId, UpdatePlanRequest request);

    /** Requires PLAN_MANAGE. The plan becomes available for new subscriptions again. */
    PlanView activate(UUID actorAccountId, UUID providerId, UUID planId);

    /** Requires PLAN_MANAGE. No new subscriptions; existing ones are untouched. History is kept. */
    PlanView deactivate(UUID actorAccountId, UUID providerId, UUID planId);
}
