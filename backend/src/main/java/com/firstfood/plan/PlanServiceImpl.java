package com.firstfood.plan;

import com.firstfood.plan.dto.CreatePlanRequest;
import com.firstfood.plan.dto.UpdatePlanRequest;
import com.firstfood.provider.ProviderIntake;
import com.firstfood.provider.ProviderLookupService;
import com.firstfood.provideraccess.ProviderAccessService;
import com.firstfood.provideraccess.ProviderPermission;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlanServiceImpl implements PlanService {

    private final ProviderAccessService accessService;
    private final ProviderLookupService providerLookup;
    private final PlanRepository planRepository;
    private final SubscriptionPolicyRepository policyRepository;

    public PlanServiceImpl(
            ProviderAccessService accessService,
            ProviderLookupService providerLookup,
            PlanRepository planRepository,
            SubscriptionPolicyRepository policyRepository) {
        this.accessService = accessService;
        this.providerLookup = providerLookup;
        this.planRepository = planRepository;
        this.policyRepository = policyRepository;
    }

    @Override
    @Transactional
    public PlanView create(UUID actorAccountId, UUID providerId, CreatePlanRequest request) {
        // Authorize BEFORE locking so outsiders can never hold or contend for a provider's row lock.
        accessService.requirePermission(actorAccountId, providerId, ProviderPermission.PLAN_MANAGE);
        lockOpenProvider(providerId);

        PolicyTerms terms = request.policy().toTerms();
        PlanRules.validate(request.consumptionType(), request.durationDays(), request.mealQuantity(), terms);

        String name = request.name().trim();
        if (planRepository.existsByProviderIdAndStatusAndNameIgnoreCase(providerId, PlanStatus.ACTIVE, name)) {
            throw PlanException.nameInUse();
        }

        Instant now = now();
        Plan plan = new Plan(providerId, name, blankToNull(request.description()), request.consumptionType(),
                request.durationDays(), request.mealQuantity(), money(request.price()), actorAccountId, now);
        SubscriptionPolicy policy;
        try {
            planRepository.saveAndFlush(plan);
            policy = policyRepository.saveAndFlush(new SubscriptionPolicy(plan.getId(), 1, terms, actorAccountId));
        } catch (DataIntegrityViolationException e) {
            // Backstop for uq_plan_active_name_per_provider; the provider lock normally prevents reaching it.
            throw PlanException.nameInUse();
        }
        return PlanView.from(plan, policy);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PlanView> list(UUID actorAccountId, UUID providerId, boolean includeInactive) {
        accessService.requirePermission(actorAccountId, providerId, ProviderPermission.PLAN_VIEW);
        List<Plan> plans = includeInactive
                ? planRepository.findByProviderIdOrderByCreatedAtDesc(providerId)
                : planRepository.findByProviderIdAndStatusOrderByCreatedAtDesc(providerId, PlanStatus.ACTIVE);
        if (plans.isEmpty()) {
            return List.of();
        }
        Map<UUID, SubscriptionPolicy> current = new HashMap<>();
        policyRepository.findCurrentForPlans(plans.stream().map(Plan::getId).collect(Collectors.toSet()))
                .forEach(p -> current.put(p.getPlanId(), p));
        return plans.stream().map(p -> PlanView.from(p, current.get(p.getId()))).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PlanView get(UUID actorAccountId, UUID providerId, UUID planId) {
        accessService.requirePermission(actorAccountId, providerId, ProviderPermission.PLAN_VIEW);
        Plan plan = planRepository.findByIdAndProviderId(planId, providerId).orElseThrow(PlanException::planNotFound);
        return PlanView.from(plan, currentPolicy(plan));
    }

    @Override
    @Transactional
    public PlanView update(UUID actorAccountId, UUID providerId, UUID planId, UpdatePlanRequest request) {
        accessService.requirePermission(actorAccountId, providerId, ProviderPermission.PLAN_MANAGE);
        lockOpenProvider(providerId);
        Plan plan = planRepository.findByIdAndProviderId(planId, providerId).orElseThrow(PlanException::planNotFound);

        PolicyTerms terms = request.policy().toTerms();
        PlanRules.validate(plan.getConsumptionType(), request.durationDays(), request.mealQuantity(), terms);

        String name = request.name().trim();
        String description = blankToNull(request.description());
        // Only an ACTIVE plan holds its name; an INACTIVE one is checked when it is reactivated.
        if (plan.isActive() && nameTakenByAnotherActivePlan(providerId, name, plan.getId())) {
            throw PlanException.nameInUse();
        }

        SubscriptionPolicy current = currentPolicy(plan);
        BigDecimal price = money(request.price());
        boolean termsChanged = !plan.hasTerms(name, description, request.durationDays(), request.mealQuantity(),
                price);
        boolean policyChanged = !current.terms().equals(terms);

        if (termsChanged) {
            plan.updateTerms(name, description, request.durationDays(), request.mealQuantity(), price);
        }
        if (policyChanged) {
            // Never edit a version in place: the next version is a new immutable row. The provider
            // lock held above serializes edits, so version numbers cannot collide.
            current = policyRepository.saveAndFlush(
                    new SubscriptionPolicy(plan.getId(), current.getVersion() + 1, terms, actorAccountId));
        }
        if (termsChanged || policyChanged) {
            plan.markEdited(actorAccountId, now());
            try {
                planRepository.saveAndFlush(plan);
            } catch (DataIntegrityViolationException e) {
                throw PlanException.nameInUse();
            }
        }
        return PlanView.from(plan, current);
    }

    @Override
    @Transactional
    public PlanView activate(UUID actorAccountId, UUID providerId, UUID planId) {
        accessService.requirePermission(actorAccountId, providerId, ProviderPermission.PLAN_MANAGE);
        lockOpenProvider(providerId);
        Plan plan = planRepository.findByIdAndProviderId(planId, providerId).orElseThrow(PlanException::planNotFound);
        if (plan.isActive()) {
            throw PlanException.alreadyActive();
        }
        if (nameTakenByAnotherActivePlan(providerId, plan.getName(), plan.getId())) {
            throw PlanException.nameInUse();
        }
        plan.activate(actorAccountId, now());
        try {
            planRepository.saveAndFlush(plan);
        } catch (DataIntegrityViolationException e) {
            throw PlanException.nameInUse();
        }
        return PlanView.from(plan, currentPolicy(plan));
    }

    @Override
    @Transactional
    public PlanView deactivate(UUID actorAccountId, UUID providerId, UUID planId) {
        accessService.requirePermission(actorAccountId, providerId, ProviderPermission.PLAN_MANAGE);
        lockOpenProvider(providerId);
        Plan plan = planRepository.findByIdAndProviderId(planId, providerId).orElseThrow(PlanException::planNotFound);
        if (!plan.isActive()) {
            throw PlanException.alreadyInactive();
        }
        plan.deactivate(actorAccountId, now());
        planRepository.saveAndFlush(plan);
        return PlanView.from(plan, currentPolicy(plan));
    }

    /**
     * Takes the provider row lock (serializes plan changes for this provider and gives a stable
     * view of its status) and rejects closed providers: like roles and customers, a closed
     * provider is read-only history.
     */
    private void lockOpenProvider(UUID providerId) {
        ProviderIntake intake = providerLookup.lockForIntake(providerId);
        if (intake.closed()) {
            throw PlanException.providerClosed();
        }
    }

    private boolean nameTakenByAnotherActivePlan(UUID providerId, String name, UUID planId) {
        return planRepository.existsByProviderIdAndStatusAndNameIgnoreCaseAndIdNot(
                providerId, PlanStatus.ACTIVE, name, planId);
    }

    private SubscriptionPolicy currentPolicy(Plan plan) {
        // A plan is always created together with policy version 1, so this cannot be absent.
        return policyRepository.findFirstByPlanIdOrderByVersionDesc(plan.getId())
                .orElseThrow(() -> new IllegalStateException("Plan " + plan.getId() + " has no policy"));
    }

    /** Request validation already limits the price to 2 decimals, so this never rounds. */
    private static BigDecimal money(BigDecimal price) {
        return price.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
