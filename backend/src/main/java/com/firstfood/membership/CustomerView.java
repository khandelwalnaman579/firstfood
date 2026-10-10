package com.firstfood.membership;

import java.time.Instant;
import java.util.UUID;

/**
 * Read model for a provider's customer list. {@code phone} is shown only to
 * callers holding MEMBERSHIP_VIEW on that provider (the owner's notebook view).
 */
public record CustomerView(
        UUID membershipId,
        UUID providerId,
        UUID personId,
        String fullName,
        String phone,
        MembershipStatus status,
        Instant joinedAt,
        Instant leftAt,
        UUID addedBy,
        UUID leftBy) {

    static CustomerView from(ProviderMembership m, Person person, String phone) {
        return new CustomerView(m.getId(), m.getProviderId(), m.getPersonId(), person.getFullName(), phone,
                m.getStatus(), m.getJoinedAt(), m.getLeftAt(), m.getAddedBy(), m.getLeftBy());
    }
}
