package com.firstfood.membership.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SavePersonRequest(@NotBlank @Size(max = 120) String fullName) {
}
