package com.firstfood.plan;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SubscriptionPolicyRepository extends JpaRepository<SubscriptionPolicy, UUID> {

    /** The current policy of a plan = its highest version. */
    Optional<SubscriptionPolicy> findFirstByPlanIdOrderByVersionDesc(UUID planId);

    /** Current policy of each given plan, in one query (list views). */
    @Query("""
            select p from SubscriptionPolicy p
            where p.planId in :planIds
              and p.version = (select max(p2.version) from SubscriptionPolicy p2 where p2.planId = p.planId)
            """)
    List<SubscriptionPolicy> findCurrentForPlans(Collection<UUID> planIds);
}
