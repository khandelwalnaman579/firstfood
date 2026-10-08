package com.firstfood.subscription;

import com.firstfood.plan.ConsumptionType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A subscription as the customer who owns it sees it (any provider, current and past). */
public record MySubscriptionView(
        UUID id,
        UUID providerId,
        String providerName,
        UUID personId,
        ConsumptionType consumptionType,
        SubscriptionStatus status,
        SubscriptionPhase phase,
        LocalDate startDate,
        LocalDate effectiveExpiryDate,
        Integer remainingDays,
        Integer remainingMeals,
        Instant cancelledAt,
        TermsView terms) {

    static MySubscriptionView from(Subscription s, SubscriptionTermSnapshot terms, String providerName,
            LocalDate today) {
        SubscriptionPhase phase = s.phaseOn(today);
        return new MySubscriptionView(s.getId(), s.getProviderId(), providerName, s.getPersonId(),
                s.getConsumptionType(), s.getStatus(), phase, s.getStartDate(), s.getEffectiveExpiryDate(),
                SubscriptionRules.remainingDays(phase, s.getStartDate(), s.getEffectiveExpiryDate(), today),
                s.getRemainingMeals(), s.getCancelledAt(), TermsView.from(terms));
    }
}
