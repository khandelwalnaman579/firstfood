package com.firstfood.provider.dto;

import com.firstfood.provider.ProviderStatus;
import com.firstfood.provider.ProviderType;
import com.firstfood.provideraccess.ProviderPermission;
import com.firstfood.provideraccess.ProviderRole;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * {@code myRole} and {@code myPermissions} are resolved server-side for the caller.
 * Informational only (the UI uses them to hide controls) - the backend re-checks every request.
 */
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
        String closureReason,
        Set<ProviderPermission> myPermissions) {
}
