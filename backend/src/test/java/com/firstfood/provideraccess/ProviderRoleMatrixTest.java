package com.firstfood.provideraccess;

import static com.firstfood.provideraccess.ProviderPermission.OWNER_TRANSFER;
import static com.firstfood.provideraccess.ProviderPermission.PROVIDER_CLOSE;
import static com.firstfood.provideraccess.ProviderPermission.PROVIDER_EDIT;
import static com.firstfood.provideraccess.ProviderPermission.PROVIDER_VIEW;
import static com.firstfood.provideraccess.ProviderPermission.ROLE_ASSIGN;
import static com.firstfood.provideraccess.ProviderPermission.ROLE_REVOKE;
import static com.firstfood.provideraccess.ProviderPermission.ROLE_VIEW;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The approved Phase 4 permission matrix, asserted for EVERY role/permission
 * combination. Pure unit test (no Spring, no database). Changing the matrix
 * must fail this test and therefore be a deliberate, reviewed decision.
 */
class ProviderRoleMatrixTest {

    @Test
    void ownerHoldsEveryPermission() {
        assertThat(ProviderRole.OWNER.permissions()).isEqualTo(EnumSet.allOf(ProviderPermission.class));
    }

    @Test
    void managerMayViewEditAndViewRolesOnly() {
        assertMatrix(ProviderRole.MANAGER, Set.of(PROVIDER_VIEW, PROVIDER_EDIT, ROLE_VIEW));
        // Explicitly: the owner-only capabilities.
        for (ProviderPermission forbidden : Set.of(PROVIDER_CLOSE, ROLE_ASSIGN, ROLE_REVOKE, OWNER_TRANSFER)) {
            assertThat(ProviderRole.MANAGER.grants(forbidden)).as("MANAGER %s", forbidden).isFalse();
        }
    }

    @Test
    void workerIsReadOnlyInPhase4() {
        assertMatrix(ProviderRole.WORKER, Set.of(PROVIDER_VIEW));
    }

    @Test
    void everyPermissionIsGrantedToAtLeastOneRoleAndOwnerTransferToOwnerOnly() {
        for (ProviderPermission permission : ProviderPermission.values()) {
            assertThat(ProviderRole.OWNER.grants(permission)).isTrue();
        }
        for (ProviderRole role : ProviderRole.values()) {
            assertThat(role.grants(OWNER_TRANSFER)).isEqualTo(role == ProviderRole.OWNER);
            assertThat(role.grants(ROLE_ASSIGN)).isEqualTo(role == ProviderRole.OWNER);
            assertThat(role.grants(ROLE_REVOKE)).isEqualTo(role == ProviderRole.OWNER);
            assertThat(role.grants(PROVIDER_CLOSE)).isEqualTo(role == ProviderRole.OWNER);
        }
    }

    private static void assertMatrix(ProviderRole role, Set<ProviderPermission> expected) {
        for (ProviderPermission permission : ProviderPermission.values()) {
            assertThat(role.grants(permission))
                    .as("%s -> %s", role, permission)
                    .isEqualTo(expected.contains(permission));
        }
    }
}
