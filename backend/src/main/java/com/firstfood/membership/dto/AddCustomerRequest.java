package com.firstfood.membership.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code phone} identifies the customer's registered account (lookup only - never an
 * authorization credential). {@code fullName} is used only when the customer has no
 * profile yet; an existing profile name is never overwritten by a provider.
 * There is deliberately no actor/provider/person id: the actor comes from the JWT and
 * the provider from the path.
 */
public record AddCustomerRequest(
        @NotBlank @Size(max = 32) String phone,
        @Size(max = 120) String fullName) {
}
