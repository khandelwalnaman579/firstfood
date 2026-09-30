package com.firstfood.provideraccess;

/**
 * Provider-scoped roles (freeze #4). Phase 3 only ever assigns OWNER; MANAGER
 * and WORKER exist so Phase 4 doesn't need a schema change.
 *
 * Roles are ordered: a higher role satisfies a requirement for a lower one
 * (OWNER satisfies MANAGER satisfies WORKER). Phase 4 may replace this with a
 * fine-grained permission matrix - callers should keep going through
 * {@link ProviderAccessService} rather than comparing roles themselves.
 */
public enum ProviderRole {
    OWNER(3),
    MANAGER(2),
    WORKER(1);

    private final int rank;

    ProviderRole(int rank) {
        this.rank = rank;
    }

    public boolean satisfies(ProviderRole required) {
        return this.rank >= required.rank;
    }
}
