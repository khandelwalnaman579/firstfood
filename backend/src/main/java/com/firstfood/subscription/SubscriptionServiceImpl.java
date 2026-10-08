package com.firstfood.subscription;

import com.firstfood.common.time.BusinessCalendar;
import com.firstfood.membership.MemberSummary;
import com.firstfood.membership.MembershipLookupService;
import com.firstfood.membership.MembershipRef;
import com.firstfood.plan.ConsumptionType;
import com.firstfood.plan.PlanLookupService;
import com.firstfood.plan.PlanOffer;
import com.firstfood.provider.ProviderIntake;
import com.firstfood.provider.ProviderLookupService;
import com.firstfood.provideraccess.ProviderAccessService;
import com.firstfood.provideraccess.ProviderPermission;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubscriptionServiceImpl implements SubscriptionService {

    /** Stands in for "no end" when checking overlap of a subscription without a calendar window. */
    private static final LocalDate OPEN_END = LocalDate.of(9999, 12, 31);

    private final ProviderAccessService accessService;
    private final ProviderLookupService providerLookup;
    private final MembershipLookupService membershipLookup;
    private final PlanLookupService planLookup;
    private final SubscriptionRepository subscriptions;
    private final SubscriptionTermSnapshotRepository snapshots;
    private final BusinessCalendar calendar;
    private final Clock clock;

    public SubscriptionServiceImpl(
            ProviderAccessService accessService,
            ProviderLookupService providerLookup,
            MembershipLookupService membershipLookup,
            PlanLookupService planLookup,
            SubscriptionRepository subscriptions,
            SubscriptionTermSnapshotRepository snapshots,
            BusinessCalendar calendar,
            Clock clock) {
        this.accessService = accessService;
        this.providerLookup = providerLookup;
        this.membershipLookup = membershipLookup;
        this.planLookup = planLookup;
        this.subscriptions = subscriptions;
        this.snapshots = snapshots;
        this.calendar = calendar;
        this.clock = clock;
    }

    @Override
    @Transactional
    public SubscriptionView create(
            UUID actorAccountId, UUID providerId, UUID membershipId, UUID planId, LocalDate startDate) {
        // Authorize BEFORE locking so outsiders can never hold or contend for a provider's row lock.
        accessService.requirePermission(actorAccountId, providerId, ProviderPermission.SUBSCRIPTION_MANAGE);
        ProviderIntake intake = lockOpen(providerId);

        MembershipRef membership = membershipLookup.find(providerId, membershipId)
                .orElseThrow(SubscriptionException::membershipNotFound);
        if (!membership.active()) {
            throw SubscriptionException.membershipNotActive();
        }
        PlanOffer offer = requireSellable(providerId, planId);
        return sell(actorAccountId, intake, membership, offer, startDate, null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SubscriptionView> list(
            UUID actorAccountId, UUID providerId, UUID membershipId, SubscriptionStatus status) {
        accessService.requirePermission(actorAccountId, providerId, ProviderPermission.SUBSCRIPTION_VIEW);
        List<Subscription> found;
        if (membershipId != null && status != null) {
            found = subscriptions.findByProviderIdAndMembershipIdAndStatusOrderByCreatedAtDesc(
                    providerId, membershipId, status);
        } else if (membershipId != null) {
            found = subscriptions.findByProviderIdAndMembershipIdOrderByCreatedAtDesc(providerId, membershipId);
        } else if (status != null) {
            found = subscriptions.findByProviderIdAndStatusOrderByCreatedAtDesc(providerId, status);
        } else {
            found = subscriptions.findByProviderIdOrderByCreatedAtDesc(providerId);
        }
        return toViews(found);
    }

    @Override
    @Transactional(readOnly = true)
    public SubscriptionView get(UUID actorAccountId, UUID providerId, UUID subscriptionId) {
        accessService.requirePermission(actorAccountId, providerId, ProviderPermission.SUBSCRIPTION_VIEW);
        Subscription s = subscriptions.findByIdAndProviderId(subscriptionId, providerId)
                .orElseThrow(SubscriptionException::subscriptionNotFound);
        return toViews(List.of(s)).get(0);
    }

    @Override
    @Transactional
    public SubscriptionView cancel(UUID actorAccountId, UUID providerId, UUID subscriptionId, String reason) {
        accessService.requirePermission(actorAccountId, providerId, ProviderPermission.SUBSCRIPTION_MANAGE);
        lockOpen(providerId);
        // Phase 8: the subscription row lock too, so a cancel and an attendance change on the same subscription
        // are serialized (lock order everywhere: provider row, then subscription row).
        Subscription s = subscriptions.findByIdAndProviderIdForUpdate(subscriptionId, providerId)
                .orElseThrow(SubscriptionException::subscriptionNotFound);
        SubscriptionPhase phase = s.phaseOn(calendar.today());
        if (phase != SubscriptionPhase.RUNNING && phase != SubscriptionPhase.UPCOMING) {
            throw SubscriptionException.notActive();
        }
        String cleanReason = reason == null || reason.isBlank() ? null : reason.trim();
        s.cancel(actorAccountId, cleanReason, now());
        s = subscriptions.saveAndFlush(s);
        return toViews(List.of(s)).get(0);
    }

    @Override
    @Transactional
    public SubscriptionView renew(
            UUID actorAccountId, UUID providerId, UUID subscriptionId, UUID planId, LocalDate startDate) {
        accessService.requirePermission(actorAccountId, providerId, ProviderPermission.SUBSCRIPTION_MANAGE);
        ProviderIntake intake = lockOpen(providerId);

        Subscription previous = subscriptions.findByIdAndProviderId(subscriptionId, providerId)
                .orElseThrow(SubscriptionException::subscriptionNotFound);
        LocalDate today = calendar.today();
        if (previous.phaseOn(today) != SubscriptionPhase.ENDED) {
            throw SubscriptionException.notRenewable();
        }
        if (subscriptions.existsByRenewedFromSubscriptionId(previous.getId())) {
            throw SubscriptionException.alreadyRenewed();
        }
        MembershipRef membership = membershipLookup.find(providerId, previous.getMembershipId())
                .orElseThrow(SubscriptionException::membershipNotFound);
        if (!membership.active()) {
            throw SubscriptionException.membershipNotActive();
        }
        PlanOffer offer = requireSellable(providerId, planId != null ? planId : previous.getPlanId());

        LocalDate previousEnd = previous.getEffectiveExpiryDate();
        if (startDate != null && previousEnd != null && !startDate.isAfter(previousEnd)) {
            throw SubscriptionException.startInvalid("A renewal must start after the previous subscription ended ("
                    + previousEnd + ").");
        }
        // Lapsed by date but still stored ACTIVE (no background job yet): record that it has ended,
        // so it stops counting as an ACTIVE row for overlap and capacity.
        if (previous.getStatus() == SubscriptionStatus.ACTIVE) {
            previous.expire(now());
            subscriptions.saveAndFlush(previous);
        }
        return sell(actorAccountId, intake, membership, offer, startDate, previous.getId());
    }

    @Override
    @Transactional(readOnly = true)
    public List<MySubscriptionView> listMine(UUID accountId) {
        Set<UUID> personIds = membershipLookup.personIdsOf(accountId);
        if (personIds.isEmpty()) {
            return List.of();
        }
        return toMyViews(subscriptions.findByPersonIdInOrderByCreatedAtDesc(personIds));
    }

    @Override
    @Transactional(readOnly = true)
    public MySubscriptionView getMine(UUID accountId, UUID subscriptionId) {
        Set<UUID> personIds = membershipLookup.personIdsOf(accountId);
        if (personIds.isEmpty()) {
            throw SubscriptionException.subscriptionNotFound();
        }
        Subscription s = subscriptions.findByIdAndPersonIdIn(subscriptionId, personIds)
                .orElseThrow(SubscriptionException::subscriptionNotFound);
        return toMyViews(List.of(s)).get(0);
    }

    // ---- the one place a subscription is created ----

    /** Shared by sale and renewal: dates, capacity, overlap, then subscription + snapshot together. */
    private SubscriptionView sell(UUID actorAccountId, ProviderIntake intake, MembershipRef membership,
            PlanOffer offer, LocalDate requestedStart, UUID renewedFromId) {
        LocalDate today = calendar.today();
        LocalDate start = requestedStart != null ? requestedStart : today;
        SubscriptionRules.validateStart(start, today);
        LocalDate baseExpiry = SubscriptionRules.baseExpiry(
                offer.consumptionType(), start, offer.durationDays(), offer.policy().maxCalendarWindowDays());
        SubscriptionRules.validateNotAlreadyEnded(baseExpiry, today);

        UUID providerId = membership.providerId();
        Integer max = intake.maxActiveSubscriptions();
        if (max != null && subscriptions.countLiveByProvider(providerId, today) >= max) {
            throw SubscriptionException.providerAtCapacity();
        }
        LocalDate end = baseExpiry != null ? baseExpiry : OPEN_END;
        if (subscriptions.countActiveOverlapping(membership.membershipId(), start, end) > 0) {
            throw SubscriptionException.overlap();
        }

        Integer meals = offer.consumptionType() == ConsumptionType.MEAL ? offer.mealQuantity() : null;
        Subscription subscription = new Subscription(providerId, membership.membershipId(), membership.personId(),
                offer.planId(), offer.consumptionType(), start, baseExpiry, meals, renewedFromId, actorAccountId);
        SubscriptionTermSnapshot terms = SubscriptionTermSnapshot.capture(subscription.getId(), offer, now());
        try {
            subscription = subscriptions.saveAndFlush(subscription);
            terms = snapshots.saveAndFlush(terms);
        } catch (DataIntegrityViolationException e) {
            // Backstops for the database constraints; the provider lock normally prevents reaching them.
            String detail = String.valueOf(e.getMostSpecificCause().getMessage());
            if (detail.contains("subscription_no_overlapping_active")) {
                throw SubscriptionException.overlap();
            }
            if (detail.contains("uq_subscription_one_renewal")) {
                throw SubscriptionException.alreadyRenewed();
            }
            throw e;
        }
        MemberSummary member = membershipLookup.summarize(List.of(membership.membershipId()))
                .get(membership.membershipId());
        return SubscriptionView.from(subscription, terms, member, false, today);
    }

    // ---- helpers ----

    private ProviderIntake lockOpen(UUID providerId) {
        ProviderIntake intake = providerLookup.lockForIntake(providerId);
        if (intake.closed()) {
            throw SubscriptionException.providerClosed();
        }
        return intake;
    }

    private PlanOffer requireSellable(UUID providerId, UUID planId) {
        PlanOffer offer = planLookup.findOffer(providerId, planId).orElseThrow(SubscriptionException::planNotFound);
        if (!offer.active()) {
            throw SubscriptionException.planNotActive();
        }
        return offer;
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    private List<SubscriptionView> toViews(List<Subscription> found) {
        if (found.isEmpty()) {
            return List.of();
        }
        LocalDate today = calendar.today();
        List<UUID> ids = found.stream().map(Subscription::getId).toList();
        Map<UUID, SubscriptionTermSnapshot> terms = termsBySubscription(ids);
        Map<UUID, MemberSummary> members = membershipLookup.summarize(
                found.stream().map(Subscription::getMembershipId).collect(Collectors.toSet()));
        Set<UUID> renewed = new HashSet<>(subscriptions.findRenewedIds(ids));
        return found.stream()
                .map(s -> SubscriptionView.from(s, terms.get(s.getId()), members.get(s.getMembershipId()),
                        renewed.contains(s.getId()), today))
                .toList();
    }

    private List<MySubscriptionView> toMyViews(List<Subscription> found) {
        if (found.isEmpty()) {
            return List.of();
        }
        LocalDate today = calendar.today();
        Map<UUID, SubscriptionTermSnapshot> terms = termsBySubscription(
                found.stream().map(Subscription::getId).toList());
        Map<UUID, String> names = providerLookup.findNames(
                found.stream().map(Subscription::getProviderId).collect(Collectors.toSet()));
        return found.stream()
                .map(s -> MySubscriptionView.from(s, terms.get(s.getId()),
                        names.getOrDefault(s.getProviderId(), "(unknown provider)"), today))
                .toList();
    }

    private Map<UUID, SubscriptionTermSnapshot> termsBySubscription(List<UUID> subscriptionIds) {
        Map<UUID, SubscriptionTermSnapshot> map = new HashMap<>();
        snapshots.findBySubscriptionIdIn(subscriptionIds).forEach(t -> map.put(t.getSubscriptionId(), t));
        return map;
    }
}
