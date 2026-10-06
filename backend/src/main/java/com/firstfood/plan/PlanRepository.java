package com.firstfood.plan;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence only - every business decision lives in {@link PlanServiceImpl} / {@link PlanRules}. */
public interface PlanRepository extends JpaRepository<Plan, UUID> {

    /** Scoped by provider so a plan id from another provider is never resolvable (no IDOR). */
    Optional<Plan> findByIdAndProviderId(UUID id, UUID providerId);

    List<Plan> findByProviderIdOrderByCreatedAtDesc(UUID providerId);

    List<Plan> findByProviderIdAndStatusOrderByCreatedAtDesc(UUID providerId, PlanStatus status);

    /** Mirrors uq_plan_active_name_per_provider (case-insensitive); names are stored trimmed. */
    boolean existsByProviderIdAndStatusAndNameIgnoreCase(UUID providerId, PlanStatus status, String name);

    boolean existsByProviderIdAndStatusAndNameIgnoreCaseAndIdNot(
            UUID providerId, PlanStatus status, String name, UUID id);
}
