package com.firstfood.membership;

import java.time.Instant;
import java.util.UUID;

/** Read model of a Person as seen by its own account. */
public record PersonView(UUID id, String fullName, boolean primaryPerson, Instant createdAt) {

    static PersonView from(Person p) {
        return new PersonView(p.getId(), p.getFullName(), p.isPrimaryPerson(), p.getCreatedAt());
    }
}
