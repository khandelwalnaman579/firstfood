package com.firstfood.membership;

import java.util.UUID;

/** The facts about a membership other modules may rely on (no entity leaves this module). */
public record MembershipRef(UUID membershipId, UUID providerId, UUID personId, MembershipStatus status) {

    public boolean active() {
        return status == MembershipStatus.ACTIVE;
    }
}
