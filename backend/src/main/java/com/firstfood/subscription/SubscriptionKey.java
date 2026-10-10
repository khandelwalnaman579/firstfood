package com.firstfood.subscription;

import java.util.UUID;

/**
 * The two ids a background job needs to address one subscription: the provider it belongs to (every provider-scoped
 * lookup is keyed by it - no IDOR, even for trusted code) and the subscription itself. Deliberately nothing else: jobs
 * must re-read the subscription under its row lock before acting, never trust data gathered by the scan.
 */
public record SubscriptionKey(UUID providerId, UUID subscriptionId) {
}
