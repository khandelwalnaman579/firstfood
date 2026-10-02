package com.firstfood.provider;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Narrow lookup for other modules (e.g. membership). Exists so those modules never
 * touch {@link FoodProvider} or its repository directly (rules.md Rule 27.3/27.4).
 * Performs NO authorization - callers must have called
 * {@link com.firstfood.provideraccess.ProviderAccessService#requirePermission} first.
 */
public interface ProviderLookupService {

    /**
     * Takes the provider row lock for the rest of the surrounding transaction and
     * returns its intake state. Must run inside an existing transaction.
     *
     * @throws com.firstfood.provideraccess.ProviderNotFoundException if the provider doesn't exist
     */
    ProviderIntake lockForIntake(UUID providerId);

    /** Provider names for the given ids (unknown ids are simply absent). */
    Map<UUID, String> findNames(Collection<UUID> providerIds);
}
