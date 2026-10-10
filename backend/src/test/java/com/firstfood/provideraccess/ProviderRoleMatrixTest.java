package com.firstfood.provideraccess;

import static com.firstfood.provideraccess.ProviderPermission.ATTENDANCE_MANAGE;
import static com.firstfood.provideraccess.ProviderPermission.ATTENDANCE_VIEW;
import static com.firstfood.provideraccess.ProviderPermission.EXTENSION_MANAGE;
import static com.firstfood.provideraccess.ProviderPermission.EXTENSION_VIEW;
import static com.firstfood.provideraccess.ProviderPermission.MEMBERSHIP_MANAGE;
import static com.firstfood.provideraccess.ProviderPermission.MEMBERSHIP_VIEW;
import static com.firstfood.provideraccess.ProviderPermission.OWNER_TRANSFER;
import static com.firstfood.provideraccess.ProviderPermission.PLAN_MANAGE;
import static com.firstfood.provideraccess.ProviderPermission.PLAN_VIEW;
import static com.firstfood.provideraccess.ProviderPermission.PROVIDER_CLOSE;
import static com.firstfood.provideraccess.ProviderPermission.PROVIDER_EDIT;
import static com.firstfood.provideraccess.ProviderPermission.PROVIDER_VIEW;
import static com.firstfood.provideraccess.ProviderPermission.ROLE_ASSIGN;
import static com.firstfood.provideraccess.ProviderPermission.ROLE_REVOKE;
import static com.firstfood.provideraccess.ProviderPermission.ROLE_VIEW;
import static com.firstfood.provideraccess.ProviderPermission.SUBSCRIPTION_MANAGE;
import static com.firstfood.provideraccess.ProviderPermission.SUBSCRIPTION_VIEW;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The approved permission matrix (Phase 4 + the Phase 5 membership, Phase 6 plan, Phase 7 subscription, Phase 8 attendance and Phase 9 extension permissions), asserted for EVERY role/permission
 * combination. Pure unit test (no Spring, no database). Changing the matrix
 * must fail this test and therefore be a deliberate, reviewed decision.
 */
class ProviderRoleMatrixTest {

    @Test
    void ownerHoldsEveryPermission() {
        assertThat(ProviderRole.OWNER.permissions()).isEqualTo(EnumSet.allOf(ProviderPermission.class));
    }

    @Test
    void managerMayViewEditViewRolesManageCustomersViewPlansSellSubscriptionsAndTakeAttendanceOnly() {
        assertMatrix(ProviderRole.MANAGER,
                Set.of(PROVIDER_VIEW, PROVIDER_EDIT, ROLE_VIEW, MEMBERSHIP_VIEW, MEMBERSHIP_MANAGE, PLAN_VIEW,
                        SUBSCRIPTION_VIEW, SUBSCRIPTION_MANAGE, ATTENDANCE_VIEW, ATTENDANCE_MANAGE, EXTENSION_VIEW,
                        EXTENSION_MANAGE));
        // Explicitly: the owner-only capabilities (plans are pricing - managing them is OWNER-only).
        for (ProviderPermission forbidden :
                Set.of(PROVIDER_CLOSE, ROLE_ASSIGN, ROLE_REVOKE, OWNER_TRANSFER, PLAN_MANAGE)) {
            assertThat(ProviderRole.MANAGER.grants(forbidden)).as("MANAGER %s", forbidden).isFalse();
        }
    }

    @Test
    void workerMayViewCustomersAndTakeAttendanceButNotSeePlansOrPrices() {
        assertMatrix(ProviderRole.WORKER, Set.of(PROVIDER_VIEW, MEMBERSHIP_VIEW, ATTENDANCE_VIEW, ATTENDANCE_MANAGE));
        assertThat(ProviderRole.WORKER.grants(MEMBERSHIP_MANAGE)).isFalse();
        assertThat(ProviderRole.WORKER.grants(PLAN_VIEW)).isFalse();
        assertThat(ProviderRole.WORKER.grants(PLAN_MANAGE)).isFalse();
        assertThat(ProviderRole.WORKER.grants(SUBSCRIPTION_VIEW)).isFalse();
        assertThat(ProviderRole.WORKER.grants(SUBSCRIPTION_MANAGE)).isFalse();
        // Extending a subscription changes what a customer is owed: not a worker's job.
        assertThat(ProviderRole.WORKER.grants(EXTENSION_VIEW)).isFalse();
        assertThat(ProviderRole.WORKER.grants(EXTENSION_MANAGE)).isFalse();
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
            assertThat(role.grants(PLAN_MANAGE)).isEqualTo(role == ProviderRole.OWNER);
            // Attendance is the one operational area every role works in (PRD §9: WORKER = attendance).
            assertThat(role.grants(ATTENDANCE_VIEW)).isTrue();
            assertThat(role.grants(ATTENDANCE_MANAGE)).isTrue();
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
