package com.firstfood.provideraccess;

import static com.firstfood.provideraccess.ProviderPermission.PROVIDER_EDIT;
import static com.firstfood.provideraccess.ProviderPermission.PROVIDER_VIEW;
import static com.firstfood.provideraccess.ProviderPermission.ROLE_VIEW;

import java.util.EnumSet;
import java.util.Set;

/**
 * Provider-scoped roles and the single role-to-permission mapping (the Phase 4
 * permission matrix). Roles are NOT ordered any more: authorization is decided
 * by {@link #grants(ProviderPermission)}, never by comparing roles.
 *
 * WORKER is read-only in Phase 4; its operational permissions are reserved for
 * the phases that build those domains.
 */
public enum ProviderRole {
    // OWNER: everything, including PROVIDER_CLOSE, ROLE_ASSIGN, ROLE_REVOKE and OWNER_TRANSFER.
    OWNER(EnumSet.allOf(ProviderPermission.class)),
    MANAGER(EnumSet.of(PROVIDER_VIEW, PROVIDER_EDIT, ROLE_VIEW)),
    WORKER(EnumSet.of(PROVIDER_VIEW));

    private final Set<ProviderPermission> permissions;

    ProviderRole(Set<ProviderPermission> permissions) {
        this.permissions = permissions;
    }

    public boolean grants(ProviderPermission permission) {
        return permissions.contains(permission);
    }

    public Set<ProviderPermission> permissions() {
        return Set.copyOf(permissions);
    }
}
