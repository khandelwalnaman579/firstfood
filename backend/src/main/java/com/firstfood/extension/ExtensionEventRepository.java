package com.firstfood.extension;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence only - every decision lives in {@link ExtensionServiceImpl} / {@link ExtensionRules}. */
public interface ExtensionEventRepository extends JpaRepository<ExtensionEvent, UUID> {

    /** A subscription's extensions in the order they were applied. */
    List<ExtensionEvent> findBySubscriptionIdOrderBySequenceNoAsc(UUID subscriptionId);

    long countBySubscriptionId(UUID subscriptionId);

    /** The newest event (highest sequence), used to avoid re-reporting an unchanged over-applied gap. */
    java.util.Optional<ExtensionEvent> findTopBySubscriptionIdOrderBySequenceNoDesc(UUID subscriptionId);
}
