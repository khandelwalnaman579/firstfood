package com.firstfood.subscription;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class SubscriptionLifecycleServiceImpl implements SubscriptionLifecycleService {

    private final SubscriptionRepository subscriptions;
    private final Clock clock;

    SubscriptionLifecycleServiceImpl(SubscriptionRepository subscriptions, Clock clock) {
        this.subscriptions = subscriptions;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SubscriptionKey> findLapsed(LocalDate today, UUID afterId, int limit) {
        return subscriptions.findLapsedKeys(today, afterId, PageRequest.of(0, requirePositive(limit)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<SubscriptionKey> findExtensionCandidates(LocalDate today, UUID afterId, int limit) {
        return subscriptions.findExtensionCandidateKeys(today, afterId, PageRequest.of(0, requirePositive(limit)));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean expireIfLapsed(UUID subscriptionId, LocalDate today) {
        Subscription subscription = subscriptions.findById(subscriptionId)
                .orElseThrow(SubscriptionException::subscriptionNotFound);
        if (!subscription.isLapsedOn(today)) {
            return false;
        }
        subscription.expire(clock.instant());
        subscriptions.saveAndFlush(subscription);
        return true;
    }

    private static int requirePositive(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return limit;
    }
}
