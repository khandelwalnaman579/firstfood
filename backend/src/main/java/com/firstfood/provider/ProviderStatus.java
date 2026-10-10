package com.firstfood.provider;

/**
 * Provider lifecycle (freeze #1, #9). Not a boolean on purpose.
 *
 * <pre>
 * ACTIVE                  -> FULL, TEMPORARILY_UNAVAILABLE, CLOSED
 * FULL                    -> ACTIVE, TEMPORARILY_UNAVAILABLE, CLOSED
 * TEMPORARILY_UNAVAILABLE -> ACTIVE, CLOSED
 * CLOSED                  -> (terminal - no reopening in Phase 3)
 * </pre>
 */
public enum ProviderStatus {
    ACTIVE,
    FULL,
    TEMPORARILY_UNAVAILABLE,
    CLOSED;

    public boolean canTransitionTo(ProviderStatus target) {
        return switch (this) {
            case ACTIVE -> target == FULL || target == TEMPORARILY_UNAVAILABLE || target == CLOSED;
            case FULL -> target == ACTIVE || target == TEMPORARILY_UNAVAILABLE || target == CLOSED;
            case TEMPORARILY_UNAVAILABLE -> target == ACTIVE || target == CLOSED;
            case CLOSED -> false;
        };
    }
}
