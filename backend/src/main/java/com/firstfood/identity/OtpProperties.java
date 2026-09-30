package com.firstfood.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.otp")
public record OtpProperties(int ttlMinutes, int maxAttempts, int requestRateLimitPerHour) {
}
