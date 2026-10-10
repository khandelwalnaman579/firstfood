package com.firstfood.provideraccess.dto;

import com.firstfood.provideraccess.ProviderRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * {@code phone} identifies the TARGET account (lookup only). There is
 * deliberately no actor field: the acting account always comes from the JWT.
 */
public record AssignRoleRequest(
        @NotBlank @Size(max = 32) String phone,
        @NotNull ProviderRole role) {
}
