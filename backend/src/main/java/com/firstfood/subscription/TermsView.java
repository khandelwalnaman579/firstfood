package com.firstfood.subscription;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;

/** What the subscription was sold under, exactly as frozen at sale time. */
public record TermsView(
        String planName,
        BigDecimal price,
        String currency,
        Integer purchasedDays,
        Integer purchasedMeals,
        int policyVersion,
        boolean extensionAllowed,
        Integer minConsecutiveAbsenceDays,
        boolean sameDayAbsenceAllowed,
        LocalTime absenceCutoffTime,
        Integer maxCalendarWindowDays,
        Instant capturedAt) {

    static TermsView from(SubscriptionTermSnapshot t) {
        return new TermsView(t.getPlanName(), t.getPrice(), t.getCurrency(), t.getPurchasedDays(),
                t.getPurchasedMeals(), t.getPolicyVersion(), t.isExtensionAllowed(),
                t.getMinConsecutiveAbsenceDays(), t.isSameDayAbsenceAllowed(), t.getAbsenceCutoffTime(),
                t.getMaxCalendarWindowDays(), t.getCapturedAt());
    }
}
