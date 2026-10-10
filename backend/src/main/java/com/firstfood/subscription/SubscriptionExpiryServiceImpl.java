package com.firstfood.subscription;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class SubscriptionExpiryServiceImpl implements SubscriptionExpiryService {

    private final SubscriptionRepository subscriptions;

    SubscriptionExpiryServiceImpl(SubscriptionRepository subscriptions) {
        this.subscriptions = subscriptions;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void moveEffectiveExpiry(UUID subscriptionId, LocalDate newExpiry) {
        Subscription subscription = subscriptions.findById(subscriptionId)
                .orElseThrow(SubscriptionException::subscriptionNotFound);
        subscription.extendTo(newExpiry);
        subscriptions.saveAndFlush(subscription);
    }
}
