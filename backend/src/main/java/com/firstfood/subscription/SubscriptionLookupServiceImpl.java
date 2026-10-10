package com.firstfood.subscription;

import com.firstfood.common.time.BusinessCalendar;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubscriptionLookupServiceImpl implements SubscriptionLookupService {

    private final SubscriptionRepository subscriptions;
    private final SubscriptionTermSnapshotRepository snapshots;
    private final BusinessCalendar calendar;

    public SubscriptionLookupServiceImpl(SubscriptionRepository subscriptions,
            SubscriptionTermSnapshotRepository snapshots, BusinessCalendar calendar) {
        this.subscriptions = subscriptions;
        this.snapshots = snapshots;
        this.calendar = calendar;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SubscriptionRef> find(UUID providerId, UUID subscriptionId) {
        return subscriptions.findByIdAndProviderId(subscriptionId, providerId).map(s -> toRefs(List.of(s)).get(0));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SubscriptionRef> findOwned(Collection<UUID> personIds, UUID subscriptionId) {
        if (personIds == null || personIds.isEmpty()) {
            return Optional.empty();
        }
        return subscriptions.findByIdAndPersonIdIn(subscriptionId, personIds)
                .map(s -> toRefs(List.of(s)).get(0));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<SubscriptionRef> lock(UUID providerId, UUID subscriptionId) {
        return subscriptions.findByIdAndProviderIdForUpdate(subscriptionId, providerId)
                .map(s -> toRefs(List.of(s)).get(0));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<SubscriptionRef> lockOwned(Collection<UUID> personIds, UUID subscriptionId) {
        if (personIds == null || personIds.isEmpty()) {
            return Optional.empty();
        }
        return subscriptions.findByIdAndPersonIdInForUpdate(subscriptionId, personIds)
                .map(s -> toRefs(List.of(s)).get(0));
    }

    @Override
    @Transactional(readOnly = true)
    public List<SubscriptionRef> findDayEntitledOn(UUID providerId, LocalDate date) {
        // Cancelled subscriptions count up to (not including) the day they were cancelled.
        return toRefs(subscriptions.findDayEntitledOn(providerId, date, calendar.startOfDay(date.plusDays(1))));
    }

    private List<SubscriptionRef> toRefs(List<Subscription> found) {
        if (found.isEmpty()) {
            return List.of();
        }
        Map<UUID, SubscriptionTermSnapshot> terms = new HashMap<>();
        snapshots.findBySubscriptionIdIn(found.stream().map(Subscription::getId).toList())
                .forEach(t -> terms.put(t.getSubscriptionId(), t));
        Set<UUID> renewed = new HashSet<>(subscriptions.findRenewedIds(found.stream().map(Subscription::getId).toList()));
        return found.stream().map(s -> {
            SubscriptionTermSnapshot t = terms.get(s.getId());
            return new SubscriptionRef(s.getId(), s.getProviderId(), s.getMembershipId(), s.getPersonId(),
                    s.getConsumptionType(), s.getStatus(), s.getStartDate(), s.getBaseExpiryDate(),
                    s.getEffectiveExpiryDate(),
                    SubscriptionRules.maximumExpiry(s.getStartDate(), t.getMaxCalendarWindowDays()),
                    s.getCancelledAt(), t.getPlanName(), t.isSameDayAbsenceAllowed(), t.getAbsenceCutoffTime(),
                    t.isExtensionAllowed(), t.getMinConsecutiveAbsenceDays(), renewed.contains(s.getId()));
        }).toList();
    }
}
