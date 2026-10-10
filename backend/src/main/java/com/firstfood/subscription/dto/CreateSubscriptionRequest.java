package com.firstfood.subscription.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;

/** {@code startDate} defaults to today (business time zone). Price and terms are never client input. */
public record CreateSubscriptionRequest(@NotNull UUID membershipId, @NotNull UUID planId, LocalDate startDate) {
}
