package com.firstfood.provider.dto;

import com.firstfood.identity.dto.PhonePattern;
import com.firstfood.provider.ProviderStatus;
import com.firstfood.provider.ProviderType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Partial update (PATCH): a null field means "leave unchanged".
 *
 * Because null already means "unchanged", removing a capacity limit needs its
 * own switch: {@code unlimitedCapacity = true} sets max_active_subscriptions
 * to NULL (and can't be combined with maxActiveSubscriptions).
 *
 * Closing a provider is {@code status = CLOSED} (+ optional closureReason).
 */
public record UpdateProviderRequest(
        @Size(max = 120) @Pattern(regexp = ".*\\S.*", message = "must not be blank") String name,
        ProviderType providerType,
        @Size(max = 1000) String description,
        @Size(max = 255) @Pattern(regexp = ".*\\S.*", message = "must not be blank") String addressLine,
        @Size(max = 120) @Pattern(regexp = ".*\\S.*", message = "must not be blank") String locality,
        @Size(max = 100) @Pattern(regexp = ".*\\S.*", message = "must not be blank") String city,
        @Pattern(regexp = "^[1-9][0-9]{5}$", message = "Enter a valid 6-digit pincode") String pincode,
        @Pattern(regexp = PhonePattern.REGEX, message = "Enter a valid phone number") String contactPhone,
        @Min(1) Integer maxActiveSubscriptions,
        Boolean unlimitedCapacity,
        ProviderStatus status,
        Boolean acceptingNewCustomers,
        @Size(max = 500) String closureReason) {
}
