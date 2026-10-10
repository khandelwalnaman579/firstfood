package com.firstfood.membership;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Package-private: finds or creates an account's primary (self) Person.
 * Must be called inside the caller's transaction.
 */
@Component
class PersonProvisioner {

    private final PersonRepository personRepository;

    PersonProvisioner(PersonRepository personRepository) {
        this.personRepository = personRepository;
    }

    Optional<Person> findPrimary(UUID accountId) {
        return personRepository.findByUserAccountIdAndPrimaryPersonTrue(accountId);
    }

    /**
     * Returns the account's primary person, creating it with {@code nameIfNew} when
     * none exists. An existing person is never renamed here.
     *
     * @throws MembershipException PERSON_NAME_REQUIRED when creation is needed but no name was given
     */
    Person ensurePrimary(UUID accountId, String nameIfNew) {
        Optional<Person> existing = findPrimary(accountId);
        if (existing.isPresent()) {
            return existing.get();
        }
        String name = normalize(nameIfNew);
        if (name == null) {
            throw MembershipException.personNameRequired();
        }
        personRepository.insertPrimaryIfAbsent(accountId, name);
        return findPrimary(accountId).orElseThrow(() -> new IllegalStateException("Primary person missing after insert"));
    }

    static String normalize(String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
