package com.firstfood.provider;

import com.firstfood.provider.dto.CreateProviderRequest;
import com.firstfood.provider.dto.ProviderResponse;
import com.firstfood.provider.dto.UpdateProviderRequest;
import java.util.List;
import java.util.UUID;

/**
 * Provider application service. Every method takes the authenticated
 * {@code accountId} (from the JWT principal) - never an owner or role from the
 * request body. Provider-scoped methods authorize via
 * {@link com.firstfood.provideraccess.ProviderAccessService} before touching
 * the provider.
 */
public interface ProviderService {

    /** Any authenticated account may create; the caller becomes OWNER atomically. */
    ProviderResponse create(UUID accountId, CreateProviderRequest request);

    ProviderResponse get(UUID accountId, UUID providerId);

    /** Only providers the account holds a role on - never anyone else's. */
    List<ProviderResponse> list(UUID accountId);

    ProviderResponse update(UUID accountId, UUID providerId, UpdateProviderRequest request);
}
