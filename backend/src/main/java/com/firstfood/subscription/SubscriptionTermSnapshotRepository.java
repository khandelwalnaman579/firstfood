package com.firstfood.subscription;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriptionTermSnapshotRepository extends JpaRepository<SubscriptionTermSnapshot, UUID> {

    List<SubscriptionTermSnapshot> findBySubscriptionIdIn(Collection<UUID> subscriptionIds);
}
