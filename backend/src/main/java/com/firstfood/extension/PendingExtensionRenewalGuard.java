package com.firstfood.extension;

import com.firstfood.attendance.AbsenceLookupService;
import com.firstfood.common.time.BusinessCalendar;
import com.firstfood.subscription.RenewalGuard;
import com.firstfood.subscription.SubscriptionException;
import com.firstfood.subscription.SubscriptionLookupService;
import com.firstfood.subscription.SubscriptionRef;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Renewal close to expiry (rules.md Rule 21.1): once a renewal exists the old subscription can no longer be
 * extended, so days the customer already earned but nobody applied would silently be lost. Refuse the renewal until
 * they are applied. Reads only; the renewal holds the subscription row lock, which every absence write and every
 * extension also takes, so the answer cannot change underneath it.
 */
@Component
class PendingExtensionRenewalGuard implements RenewalGuard {

    private final SubscriptionLookupService subscriptions;
    private final AbsenceLookupService absences;
    private final BusinessCalendar calendar;

    PendingExtensionRenewalGuard(
            SubscriptionLookupService subscriptions, AbsenceLookupService absences, BusinessCalendar calendar) {
        this.subscriptions = subscriptions;
        this.absences = absences;
        this.calendar = calendar;
    }

    @Override
    public void assertCanRenew(UUID providerId, UUID subscriptionId) {
        SubscriptionRef ref = subscriptions.find(providerId, subscriptionId).orElse(null);
        if (ref == null || ExtensionRules.block(ref).isPresent()) {
            return; // nothing extendable about it: not this guard's business
        }
        ExtensionRules.Evaluation evaluation = ExtensionRules.evaluate(
                ref, absences.declaredAbsenceDates(subscriptionId), calendar.today());
        if (evaluation.appliedDays() > 0) {
            throw SubscriptionException.extensionPending();
        }
    }
}
