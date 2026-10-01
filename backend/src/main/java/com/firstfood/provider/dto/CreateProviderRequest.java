package com.firstfood.provider.dto;

import com.firstfood.identity.dto.PhonePattern;
import com.firstfood.provider.ProviderType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Deliberately has no owner/role/status fields: the creator always becomes
 * OWNER (chosen by the backend from the authenticated principal) and a new
 * provider always starts ACTIVE + accepting. Unknown JSON properties sent by
 * a client are ignored, never trusted.
 */
public record CreateProviderRequest(
        @NotBlank @Size(max = 120) String name,
        @NotNull ProviderType providerType,
        @Size(max = 1000) String description,
        @NotBlank @Size(max = 255) String addressLine,
        @NotBlank @Size(max = 120) String locality,
        @NotBlank @Size(max = 100) String city,
        @Pattern(regexp = "^[1-9][0-9]{5}$", message = "Enter a valid 6-digit pincode") String pincode,
        @Pattern(regexp = PhonePattern.REGEX, message = "Enter a valid phone number") String contactPhone,
        @Min(1) Integer maxActiveSubscriptions) {
}
