package com.firstfood.membership;

import java.util.UUID;

/**
 * Who a membership is, for listings built by other modules. Deliberately has no phone number:
 * contact details are shown through the customer list under MEMBERSHIP_VIEW, not smuggled into
 * other views.
 */
public record MemberSummary(UUID membershipId, UUID personId, String fullName, MembershipStatus status) {
}
