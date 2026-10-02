package com.firstfood.identity.dto;

import java.time.Instant;
import java.util.UUID;

public record ProfileResponse(UUID accountId, String phone, String email, String status, Instant createdAt) {
}
