package com.firstfood.provideraccess;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProviderRoleAssignmentRepository extends JpaRepository<ProviderRoleAssignment, UUID> {

    /** At most one ACTIVE row exists per (provider, account) - enforced by a partial unique index. */
    Optional<ProviderRoleAssignment> findByProviderIdAndAccountIdAndStatus(
            UUID providerId, UUID accountId, RoleAssignmentStatus status);

    List<ProviderRoleAssignment> findByAccountIdAndStatus(UUID accountId, RoleAssignmentStatus status);

    List<ProviderRoleAssignment> findByProviderIdAndStatusOrderByCreatedAtAsc(
            UUID providerId, RoleAssignmentStatus status);

    /** Scoped by provider so an assignment id from another provider is never resolvable. */
    Optional<ProviderRoleAssignment> findByIdAndProviderId(UUID id, UUID providerId);

    /**
     * Locks the provider row for the rest of the transaction and returns its
     * status, or empty if the provider doesn't exist. Every role mutation takes
     * this lock first so concurrent mutations on one provider are serialized.
     * Native SQL keeps provideraccess independent of the provider module's entity.
     */
    @Query(value = "select status from food_provider where id = :providerId for update", nativeQuery = true)
    Optional<String> lockProviderAndGetStatus(@Param("providerId") UUID providerId);
}
