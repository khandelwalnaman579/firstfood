package com.firstfood.identity.security;

import java.util.UUID;

/**
 * The Spring Security {@code Authentication} principal for a
 * JWT-authenticated request. Controllers can resolve it directly via
 * {@code @AuthenticationPrincipal AuthenticatedAccount principal}.
 */
public record AuthenticatedAccount(UUID accountId) {
}
