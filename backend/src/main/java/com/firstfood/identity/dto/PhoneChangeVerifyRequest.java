package com.firstfood.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record PhoneChangeVerifyRequest(
        @NotBlank @Pattern(regexp = PhonePattern.REGEX, message = "Enter a valid phone number") String newPhone,
        @NotBlank @Pattern(regexp = "^\\d{6}$", message = "Code must be 6 digits") String code) {
}
