package com.firstfood.subscription.dto;

import jakarta.validation.constraints.Size;

public record CancelSubscriptionRequest(@Size(max = 500) String reason) {
}
