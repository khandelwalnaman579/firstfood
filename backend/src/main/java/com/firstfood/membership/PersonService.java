package com.firstfood.membership;

import java.util.UUID;

/** A customer managing their own Person profile. Always acts on the caller's own account. */
public interface PersonService {

    /** @throws MembershipException PERSON_NOT_FOUND if the account has no profile yet */
    PersonView getMyPerson(UUID accountId);

    /** Creates the account's primary person or renames the existing one. */
    PersonView saveMyPerson(UUID accountId, String fullName);
}
