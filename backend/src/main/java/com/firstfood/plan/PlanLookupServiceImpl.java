package com.firstfood.plan;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlanLookupServiceImpl implements PlanLookupService {

    private final PlanRepository planRepository;
    private final SubscriptionPolicyRepository policyRepository;

    public PlanLookupServiceImpl(PlanRepository planRepository, SubscriptionPolicyRepository policyRepository) {
        this.planRepository = planRepository;
        this.policyRepository = policyRepository;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<PlanOffer> findOffer(UUID providerId, UUID planId) {
        return planRepository.findByIdAndProviderId(planId, providerId).map(plan -> {
            // A plan is always created together with policy version 1, so this cannot be absent.
            SubscriptionPolicy policy = policyRepository.findFirstByPlanIdOrderByVersionDesc(plan.getId())
                    .orElseThrow(() -> new IllegalStateException("Plan " + plan.getId() + " has no policy"));
            return new PlanOffer(plan.getId(), plan.getProviderId(), plan.getName(), plan.getConsumptionType(),
                    plan.getDurationDays(), plan.getMealQuantity(), plan.getPrice(), plan.getCurrency(),
                    plan.isActive(), policy.getVersion(), policy.terms());
        });
    }
}
