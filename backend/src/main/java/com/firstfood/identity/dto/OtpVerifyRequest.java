package com.firstfood.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record OtpVerifyRequest(
        @NotBlank @Pattern(regexp = PhonePattern.REGEX, message = "Enter a valid phone number") String phone,
        @NotBlank @Pattern(regexp = "^\\d{6}$", message = "Code must be 6 digits") String code) {
}
