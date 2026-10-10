package com.firstfood.provideraccess.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code phone} identifies the new OWNER (lookup only); the actor comes from the JWT. */
public record TransferOwnershipRequest(@NotBlank @Size(max = 32) String phone) {
}
