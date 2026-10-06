package com.firstfood.membership;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Narrow read-only lookup for other modules (subscription). Exists so they never touch
 * {@link ProviderMembership}, {@link Person} or their repositories (rules.md Rule 27.3/27.4).
 * Performs NO authorization - callers must have called
 * {@link com.firstfood.provideraccess.ProviderAccessService#requirePermission} first (provider
 * side) or be acting on the caller's own account (customer side).
 */
public interface MembershipLookupService {

    /** Scoped by provider: a membership of another provider is simply absent (no IDOR). */
    Optional<MembershipRef> find(UUID providerId, UUID membershipId);

    /** Name and state for each given membership (unknown ids are absent). */
    Map<UUID, MemberSummary> summarize(Collection<UUID> membershipIds);

    /** Ids of every Person owned by the account (primary and, later, others). */
    Set<UUID> personIdsOf(UUID accountId);
}
