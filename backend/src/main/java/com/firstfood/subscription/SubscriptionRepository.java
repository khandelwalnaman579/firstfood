package com.firstfood.subscription;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence only - every business decision lives in {@link SubscriptionServiceImpl} / {@link SubscriptionRules}. */
public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

    /** Scoped by provider so a subscription id from another provider is never resolvable (no IDOR). */
    Optional<Subscription> findByIdAndProviderId(UUID id, UUID providerId);

    /**
     * Same, with the row lock (SELECT ... FOR UPDATE): the caller's change runs after any cancel or attendance
     * change already in flight on this subscription, and READ COMMITTED re-reads the fresh row once the lock is won.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Subscription s where s.id = :id and s.providerId = :providerId")
    Optional<Subscription> findByIdAndProviderIdForUpdate(
            @Param("id") UUID id, @Param("providerId") UUID providerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Subscription s where s.id = :id and s.personId in :personIds")
    Optional<Subscription> findByIdAndPersonIdInForUpdate(
            @Param("id") UUID id, @Param("personIds") Collection<UUID> personIds);

    /**
     * DAY subscriptions of the provider that were entitled on {@code date}: started on or before it and not over
     * (effective expiry on or after it). A CANCELLED one counts only while it was still in force on that day, i.e.
     * cancelled at or after {@code cancelledNotBefore} (the first instant of the NEXT day) - it is not entitled on
     * the day it was cancelled.
     */
    @Query("""
            select s from Subscription s
            where s.providerId = :providerId
              and s.consumptionType = com.firstfood.plan.ConsumptionType.DAY
              and s.startDate <= :date
              and s.effectiveExpiryDate >= :date
              and (s.status <> com.firstfood.subscription.SubscriptionStatus.CANCELLED
                   or s.cancelledAt >= :cancelledNotBefore)
            """)
    List<Subscription> findDayEntitledOn(@Param("providerId") UUID providerId, @Param("date") LocalDate date,
            @Param("cancelledNotBefore") java.time.Instant cancelledNotBefore);

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

    /**
     * Phase 10 expiry scan: ACTIVE subscriptions whose effective expiry is before {@code today}, keyset-paged by id
     * (backed by the partial index idx_subscription_active_expiry). A candidate list only - the expiry job re-checks
     * each one under its row lock.
     */
    @Query("""
            select new com.firstfood.subscription.SubscriptionKey(s.providerId, s.id) from Subscription s
            where s.status = com.firstfood.subscription.SubscriptionStatus.ACTIVE
              and s.effectiveExpiryDate < :today
              and s.id > :afterId
            order by s.id
            """)
    List<SubscriptionKey> findLapsedKeys(@Param("today") LocalDate today, @Param("afterId") UUID afterId,
            org.springframework.data.domain.Pageable page);

    /**
     * Phase 10 extension scan: ACTIVE, already started, not lapsed DAY subscriptions that were sold with extension
     * (term snapshot, never the current plan) and have not been renewed. Keyset-paged by id; a hint only.
     */
    @Query("""
            select new com.firstfood.subscription.SubscriptionKey(s.providerId, s.id)
            from Subscription s, SubscriptionTermSnapshot t
            where t.subscriptionId = s.id
              and t.extensionAllowed = true
              and s.status = com.firstfood.subscription.SubscriptionStatus.ACTIVE
              and s.consumptionType = com.firstfood.plan.ConsumptionType.DAY
              and s.startDate <= :today
              and s.effectiveExpiryDate >= :today
              and s.id > :afterId
              and not exists (select 1 from Subscription r where r.renewedFromSubscriptionId = s.id)
            order by s.id
            """)
    List<SubscriptionKey> findExtensionCandidateKeys(@Param("today") LocalDate today,
            @Param("afterId") UUID afterId, org.springframework.data.domain.Pageable page);
}
