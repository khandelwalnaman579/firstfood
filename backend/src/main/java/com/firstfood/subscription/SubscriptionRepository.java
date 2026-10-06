package com.firstfood.subscription;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence only - every business decision lives in {@link SubscriptionServiceImpl} / {@link SubscriptionRules}. */
public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

    /** Scoped by provider so a subscription id from another provider is never resolvable (no IDOR). */
    Optional<Subscription> findByIdAndProviderId(UUID id, UUID providerId);

    List<Subscription> findByProviderIdOrderByCreatedAtDesc(UUID providerId);

    List<Subscription> findByProviderIdAndStatusOrderByCreatedAtDesc(UUID providerId, SubscriptionStatus status);

    List<Subscription> findByProviderIdAndMembershipIdOrderByCreatedAtDesc(UUID providerId, UUID membershipId);

    List<Subscription> findByProviderIdAndMembershipIdAndStatusOrderByCreatedAtDesc(
            UUID providerId, UUID membershipId, SubscriptionStatus status);

    /** The customer side: scoped by the caller's own persons. */
    List<Subscription> findByPersonIdInOrderByCreatedAtDesc(Collection<UUID> personIds);

    Optional<Subscription> findByIdAndPersonIdIn(UUID id, Collection<UUID> personIds);

    boolean existsByRenewedFromSubscriptionId(UUID renewedFromSubscriptionId);

    /** Which of the given subscriptions have already been renewed. */
    @Query("select s.renewedFromSubscriptionId from Subscription s where s.renewedFromSubscriptionId in :ids")
    List<UUID> findRenewedIds(@Param("ids") Collection<UUID> ids);

    /**
     * ACTIVE subscriptions of the membership whose days intersect [start, end]. Mirrors the
     * subscription_no_overlapping_active exclusion constraint (the constraint is the last defence).
     */
    @Query("""
            select count(s) from Subscription s
            where s.membershipId = :membershipId
              and s.status = com.firstfood.subscription.SubscriptionStatus.ACTIVE
              and s.startDate <= :end
              and (s.effectiveExpiryDate is null or s.effectiveExpiryDate >= :start)
            """)
    long countActiveOverlapping(
            @Param("membershipId") UUID membershipId, @Param("start") LocalDate start, @Param("end") LocalDate end);

    /** ACTIVE subscriptions of a provider that have not ended yet (running or upcoming): its capacity use. */
    @Query("""
            select count(s) from Subscription s
            where s.providerId = :providerId
              and s.status = com.firstfood.subscription.SubscriptionStatus.ACTIVE
              and (s.effectiveExpiryDate is null or s.effectiveExpiryDate >= :today)
            """)
    long countLiveByProvider(@Param("providerId") UUID providerId, @Param("today") LocalDate today);

    @Query("""
            select count(s) from Subscription s
            where s.membershipId = :membershipId
              and s.status = com.firstfood.subscription.SubscriptionStatus.ACTIVE
              and (s.effectiveExpiryDate is null or s.effectiveExpiryDate >= :today)
            """)
    long countLiveByMembership(@Param("membershipId") UUID membershipId, @Param("today") LocalDate today);
}
