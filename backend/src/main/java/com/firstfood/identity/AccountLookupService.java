package com.firstfood.identity;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Narrow, read-only identity lookup for other modules (e.g. provideraccess
 * resolving a role-assignment target). Other modules must not reach into
 * {@link UserAccountRepository} directly.
 */
public interface AccountLookupService {

    /**
     * Normalizes the phone (trims, drops spaces and hyphens) and returns the id
     * of the ACTIVE account registered under it. Empty for malformed input,
     * unknown numbers and SUSPENDED accounts - callers must not distinguish them.
     * The phone is only a lookup key, never an authorization credential.
     */
    Optional<UUID> findActiveAccountIdByPhone(String phone);

    /** Phone numbers for the given account ids (unknown ids are simply absent). For team listings only. */
    Map<UUID, String> findPhonesByIds(Collection<UUID> accountIds);
}
