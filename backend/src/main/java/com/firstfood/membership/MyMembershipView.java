package com.firstfood.membership;

import java.time.Instant;
import java.util.UUID;

/** A membership as seen by the customer's own account (all providers, current and past). */
public record MyMembershipView(
        UUID membershipId,
        UUID providerId,
        String providerName,
        UUID personId,
        MembershipStatus status,
        Instant joinedAt,
        Instant leftAt) {
}
