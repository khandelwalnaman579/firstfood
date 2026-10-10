package com.firstfood.membership;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProviderMembershipRepository extends JpaRepository<ProviderMembership, UUID> {

    /** At most one ACTIVE row per (provider, person) - enforced by a partial unique index. */
    Optional<ProviderMembership> findByProviderIdAndPersonIdAndStatus(
            UUID providerId, UUID personId, MembershipStatus status);

    /** Scoped by provider so a membership id from another provider is never resolvable. */
    Optional<ProviderMembership> findByIdAndProviderId(UUID id, UUID providerId);

    List<ProviderMembership> findByProviderIdAndStatusOrderByJoinedAtDesc(UUID providerId, MembershipStatus status);

    List<ProviderMembership> findByProviderIdOrderByJoinedAtDesc(UUID providerId);

    List<ProviderMembership> findByPersonIdInOrderByJoinedAtDesc(Collection<UUID> personIds);
}
