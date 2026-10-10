package com.firstfood.subscription.dto;

import java.time.LocalDate;
import java.util.UUID;

/** Both optional: the same plan, starting today, is the default. */
public record RenewSubscriptionRequest(UUID planId, LocalDate startDate) {
}
