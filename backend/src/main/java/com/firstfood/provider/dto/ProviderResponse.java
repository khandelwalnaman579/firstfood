package com.firstfood.provider.dto;

import com.firstfood.provider.ProviderStatus;
import com.firstfood.provider.ProviderType;
import com.firstfood.provideraccess.ProviderRole;
import java.time.Instant;
import java.util.UUID;

/** {@code myRole} is resolved server-side for the caller - informational only. */
public record ProviderResponse(
        UUID id,
        String name,
        ProviderType providerType,
        String description,
        String addressLine,
        String locality,
        String city,
        String pincode,
        String contactPhone,
        ProviderStatus status,
        boolean acceptingNewCustomers,
        Integer maxActiveSubscriptions,
        ProviderRole myRole,
        Instant createdAt,
        Instant updatedAt,
        Instant closedAt,
        UUID closedByAccountId,
        String closureReason) {
}
