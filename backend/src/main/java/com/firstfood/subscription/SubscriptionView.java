package com.firstfood.subscription;

import com.firstfood.membership.MemberSummary;
import com.firstfood.membership.MembershipStatus;
import com.firstfood.plan.ConsumptionType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A subscription as the provider's staff see it. {@code phase}, {@code remainingDays},
 * {@code renewable} and {@code cancellable} are derived for "today" in the business time zone, so
 * the UI never re-implements date rules.
 */
public record SubscriptionView(
        UUID id,
        UUID providerId,
        UUID membershipId,
        UUID personId,
        String customerName,
        UUID planId,
        ConsumptionType consumptionType,
        SubscriptionStatus status,
        SubscriptionPhase phase,
        LocalDate startDate,
        LocalDate baseExpiryDate,
        LocalDate effectiveExpiryDate,
        LocalDate maximumExpiryDate,
        Integer remainingDays,
        Integer remainingMeals,
        UUID renewedFromSubscriptionId,
        boolean renewed,
        boolean renewable,
        boolean cancellable,
        UUID createdBy,
        Instant createdAt,
        Instant expiredAt,
        Instant cancelledAt,
        UUID cancelledBy,
        String cancellationReason,
        TermsView terms) {

    static SubscriptionView from(Subscription s, SubscriptionTermSnapshot terms, MemberSummary member,
            boolean renewed, LocalDate today) {
        SubscriptionPhase phase = s.phaseOn(today);
        boolean memberActive = member != null && member.status() == MembershipStatus.ACTIVE;
        boolean live = phase == SubscriptionPhase.RUNNING || phase == SubscriptionPhase.UPCOMING;
        return new SubscriptionView(s.getId(), s.getProviderId(), s.getMembershipId(), s.getPersonId(),
                member == null ? "(unknown)" : member.fullName(), s.getPlanId(), s.getConsumptionType(),
                s.getStatus(), phase, s.getStartDate(), s.getBaseExpiryDate(), s.getEffectiveExpiryDate(),
                SubscriptionRules.maximumExpiry(s.getStartDate(), terms.getMaxCalendarWindowDays()),
                SubscriptionRules.remainingDays(phase, s.getStartDate(), s.getEffectiveExpiryDate(), today),
                s.getRemainingMeals(), s.getRenewedFromSubscriptionId(), renewed,
                phase == SubscriptionPhase.ENDED && !renewed && memberActive, live, s.getCreatedBy(),
                s.getCreatedAt(), s.getExpiredAt(), s.getCancelledAt(), s.getCancelledBy(),
                s.getCancellationReason(), TermsView.from(terms));
    }
}
