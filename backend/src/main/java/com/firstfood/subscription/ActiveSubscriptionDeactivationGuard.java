package com.firstfood.subscription;

import com.firstfood.common.time.BusinessCalendar;
import com.firstfood.membership.MembershipDeactivationGuard;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * A customer with a running or upcoming subscription cannot be removed: the provider cancels the
 * subscription first, which is an explicit, recorded decision (who/when/why), rather than ending
 * the membership silently stranding a paid entitlement.
 */
@Component
class ActiveSubscriptionDeactivationGuard implements MembershipDeactivationGuard {

    private final SubscriptionRepository subscriptionRepository;
    private final BusinessCalendar calendar;

    ActiveSubscriptionDeactivationGuard(SubscriptionRepository subscriptionRepository, BusinessCalendar calendar) {
        this.subscriptionRepository = subscriptionRepository;
        this.calendar = calendar;
    }

    @Override
    public void assertCanDeactivate(UUID providerId, UUID membershipId) {
        if (subscriptionRepository.countLiveByMembership(membershipId, calendar.today()) > 0) {
            throw SubscriptionException.membershipHasActiveSubscription();
        }
    }
}
