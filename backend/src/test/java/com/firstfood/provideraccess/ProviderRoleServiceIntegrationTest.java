package com.firstfood.provideraccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstfood.AbstractIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Phase 4 role assignment lifecycle tests at the service level (HTTP-level
 * tests are in ProviderRoleApiIntegrationTest).
 *
 * Accounts/providers are inserted with JDBC to keep these tests independent of
 * the OTP login flow. Phones use the +91987652xxxx range (others use ...650xxxx
 * and ...651xxxx); the database is a shared singleton container, so do not reuse.
 */
class ProviderRoleServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    ProviderRoleService roleService;

    @Autowired
    ProviderAccessService accessService;

    @Autowired
    JdbcTemplate jdbc;

    // -------------------------------------------------------------- assignment

    @Test
    void ownerCanAssignManager() {
        UUID owner = account("+919876520001");
        UUID target = account("+919876520002");
        UUID provider = provider(owner);

        RoleAssignmentView view = roleService.assignRole(owner, provider, "+919876520002", ProviderRole.MANAGER);

        assertThat(view.accountId()).isEqualTo(target);
        assertThat(view.providerId()).isEqualTo(provider);
        assertThat(view.role()).isEqualTo(ProviderRole.MANAGER);
        assertThat(view.status()).isEqualTo(RoleAssignmentStatus.ACTIVE);
        assertThat(view.assignedBy()).isEqualTo(owner);
        assertThat(roleService.hasRole(provider, target, ProviderRole.MANAGER)).isTrue();
        assertThat(roleService.hasRole(provider, target, ProviderRole.WORKER)).isFalse();
    }

    @Test
    void ownerCanAssignWorker() {
        UUID owner = account("+919876520003");
        UUID target = account("+919876520004");
        UUID provider = provider(owner);

        RoleAssignmentView view = roleService.assignRole(owner, provider, "+919876520004", ProviderRole.WORKER);

        assertThat(view.role()).isEqualTo(ProviderRole.WORKER);
        assertThat(roleService.hasRole(provider, target, ProviderRole.WORKER)).isTrue();
    }

    @Test
    void invalidRoleIsRejectedAndOwnerCannotBeAssigned() {
        UUID owner = account("+919876520005");
        account("+919876520006");
        UUID provider = provider(owner);

        assertThatThrownBy(() -> roleService.assignRole(owner, provider, "+919876520006", null))
                .isInstanceOfSatisfying(RoleManagementException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("ROLE_INVALID"));
        assertThatThrownBy(() -> roleService.assignRole(owner, provider, "+919876520006", ProviderRole.OWNER))
                .isInstanceOfSatisfying(RoleManagementException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("OWNER_ASSIGNMENT_NOT_ALLOWED"));
        assertThat(activeCount(provider)).isEqualTo(1); // still just the owner
    }

    @Test
    void duplicateActiveAssignmentIsRejectedEvenForADifferentRole() {
        UUID owner = account("+919876520007");
        account("+919876520008");
        UUID provider = provider(owner);
        roleService.assignRole(owner, provider, "+919876520008", ProviderRole.MANAGER);

        assertThatThrownBy(() -> roleService.assignRole(owner, provider, "+919876520008", ProviderRole.MANAGER))
                .isInstanceOfSatisfying(RoleManagementException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("ROLE_ALREADY_ASSIGNED"));
        // One effective role per account: a second, different role is also refused.
        assertThatThrownBy(() -> roleService.assignRole(owner, provider, "+919876520008", ProviderRole.WORKER))
                .isInstanceOfSatisfying(RoleManagementException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("ROLE_ALREADY_ASSIGNED"));
        // The owner cannot be given a second role either.
        assertThatThrownBy(() -> roleService.assignRole(owner, provider, "+919876520007", ProviderRole.MANAGER))
                .isInstanceOfSatisfying(RoleManagementException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("ROLE_ALREADY_ASSIGNED"));
        assertThat(activeCount(provider)).isEqualTo(2);
    }

    @Test
    void unregisteredMalformedAndSuspendedTargetsAreRejectedIdentically() {
        UUID owner = account("+919876520009");
        UUID suspended = account("+919876520010");
        jdbc.update("update user_account set status = 'SUSPENDED' where id = ?", suspended);
        UUID provider = provider(owner);

        for (String phone : new String[] {"+919876529999", "not-a-phone", "", null, "+919876520010"}) {
            assertThatThrownBy(() -> roleService.assignRole(owner, provider, phone, ProviderRole.WORKER))
                    .isInstanceOfSatisfying(RoleManagementException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo("TARGET_ACCOUNT_NOT_FOUND"));
        }
        assertThat(activeCount(provider)).isEqualTo(1);
    }

    @Test
    void phoneInputIsNormalized() {
        UUID owner = account("+919876520011");
        UUID target = account("+919876520012");
        UUID provider = provider(owner);

        RoleAssignmentView view = roleService.assignRole(owner, provider, " +91 98765-20012 ", ProviderRole.WORKER);

        assertThat(view.accountId()).isEqualTo(target);
    }

    // ----------------------------------------------------------- authorization

    @Test
    void managerAndWorkerCannotAssignOrRevokeButGet403() {
        UUID owner = account("+919876520013");
        UUID manager = account("+919876520014");
        UUID worker = account("+919876520015");
        account("+919876520016");
        UUID provider = provider(owner);
        RoleAssignmentView managerRow = roleService.assignRole(owner, provider, "+919876520014", ProviderRole.MANAGER);
        roleService.assignRole(owner, provider, "+919876520015", ProviderRole.WORKER);

        for (UUID actor : new UUID[] {manager, worker}) {
            assertThatThrownBy(() -> roleService.assignRole(actor, provider, "+919876520016", ProviderRole.WORKER))
                    .isInstanceOf(InsufficientPermissionException.class);
            assertThatThrownBy(() -> roleService.revokeRole(actor, provider, managerRow.id()))
                    .isInstanceOf(InsufficientPermissionException.class);
        }
        assertThat(roleService.hasRole(provider, manager, ProviderRole.MANAGER)).isTrue();
    }

    @Test
    void nonMemberGets404AndCannotProbeProviderExistence() {
        UUID owner = account("+919876520017");
        UUID outsider = account("+919876520018");
        account("+919876520019");
        UUID provider = provider(owner);

        assertThatThrownBy(() -> roleService.assignRole(outsider, provider, "+919876520019", ProviderRole.WORKER))
                .isInstanceOf(ProviderNotFoundException.class);
        assertThatThrownBy(() -> roleService.findRoles(outsider, provider))
                .isInstanceOf(ProviderNotFoundException.class);
        // A provider that doesn't exist looks exactly the same.
        assertThatThrownBy(() -> roleService.assignRole(owner, UUID.randomUUID(), "+919876520019", ProviderRole.WORKER))
                .isInstanceOf(ProviderNotFoundException.class);
    }

    @Test
    void ownerOfProviderAIsNobodyOnProviderB() {
        UUID ownerA = account("+919876520020");
        UUID ownerB = account("+919876520021");
        account("+919876520022");
        UUID providerA = provider(ownerA);
        UUID providerB = provider(ownerB);

        assertThatThrownBy(() -> roleService.assignRole(ownerA, providerB, "+919876520022", ProviderRole.MANAGER))
                .isInstanceOf(ProviderNotFoundException.class);
        assertThat(activeCount(providerB)).isEqualTo(1);

        // An assignment id that belongs to provider A is not resolvable through provider B.
        RoleAssignmentView onA = roleService.assignRole(ownerA, providerA, "+919876520022", ProviderRole.MANAGER);
        assertThatThrownBy(() -> roleService.revokeRole(ownerB, providerB, onA.id()))
                .isInstanceOfSatisfying(RoleManagementException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("ROLE_ASSIGNMENT_NOT_FOUND"));
        assertThat(roleService.hasRole(providerA, onA.accountId(), ProviderRole.MANAGER)).isTrue();
    }

    @Test
    void missingActorIsRejected() {
        UUID owner = account("+919876520023");
        account("+919876520024");
        UUID provider = provider(owner);

        assertThatThrownBy(() -> roleService.assignRole(null, provider, "+919876520024", ProviderRole.WORKER))
                .isInstanceOf(NullPointerException.class);
        assertThat(activeCount(provider)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ listing

    @Test
    void ownerAndManagerCanListRolesWorkerCannot() {
        UUID owner = account("+919876520025");
        UUID manager = account("+919876520026");
        UUID worker = account("+919876520027");
        UUID provider = provider(owner);
        roleService.assignRole(owner, provider, "+919876520026", ProviderRole.MANAGER);
        roleService.assignRole(owner, provider, "+919876520027", ProviderRole.WORKER);

        List<RoleAssignmentView> asOwner = roleService.findRoles(owner, provider);
        assertThat(asOwner).extracting(RoleAssignmentView::accountId).containsExactly(owner, manager, worker);
        assertThat(asOwner).extracting(RoleAssignmentView::role)
                .containsExactly(ProviderRole.OWNER, ProviderRole.MANAGER, ProviderRole.WORKER);
        assertThat(roleService.findRoles(manager, provider)).hasSize(3);
        assertThatThrownBy(() -> roleService.findRoles(worker, provider))
                .isInstanceOf(InsufficientPermissionException.class);
    }

    // --------------------------------------------------------------- revocation

    @Test
    void revocationRemovesAccessButKeepsHistory() {
        UUID owner = account("+919876520028");
        UUID manager = account("+919876520029");
        UUID provider = provider(owner);
        RoleAssignmentView assigned = roleService.assignRole(owner, provider, "+919876520029", ProviderRole.MANAGER);
        assertThat(accessService.requirePermission(manager, provider, ProviderPermission.PROVIDER_EDIT))
                .isEqualTo(ProviderRole.MANAGER);

        RoleAssignmentView revoked = roleService.revokeRole(owner, provider, assigned.id());

        assertThat(revoked.status()).isEqualTo(RoleAssignmentStatus.REVOKED);
        assertThat(revoked.revokedBy()).isEqualTo(owner);
        assertThat(revoked.revokedAt()).isNotNull();
        // Access disappears immediately, for both the new and the Phase 3 access paths.
        assertThat(roleService.hasRole(provider, manager, ProviderRole.MANAGER)).isFalse();
        assertThatThrownBy(() -> accessService.requirePermission(manager, provider, ProviderPermission.PROVIDER_VIEW))
                .isInstanceOf(ProviderNotFoundException.class);
        assertThat(accessService.rolesFor(manager)).doesNotContainKey(provider);
        assertThat(roleService.findRoles(owner, provider)).extracting(RoleAssignmentView::accountId)
                .doesNotContain(manager);
        // History row is still there.
        assertThat(jdbc.queryForObject(
                "select count(*) from provider_role_assignment where id = ? and status = 'REVOKED'",
                Integer.class, assigned.id())).isEqualTo(1);
    }

    @Test
    void accountCanBeReassignedAfterRevocation() {
        UUID owner = account("+919876520030");
        UUID target = account("+919876520031");
        UUID provider = provider(owner);
        RoleAssignmentView first = roleService.assignRole(owner, provider, "+919876520031", ProviderRole.MANAGER);
        roleService.revokeRole(owner, provider, first.id());

        RoleAssignmentView second = roleService.assignRole(owner, provider, "+919876520031", ProviderRole.WORKER);

        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(roleService.hasRole(provider, target, ProviderRole.WORKER)).isTrue();
        assertThat(jdbc.queryForObject(
                "select count(*) from provider_role_assignment where provider_id = ? and account_id = ?",
                Integer.class, provider, target)).isEqualTo(2); // one REVOKED, one ACTIVE
    }

    @Test
    void ownerAssignmentCannotBeRevokedAndRevokedRowsCannotBeRevokedAgain() {
        UUID owner = account("+919876520032");
        account("+919876520033");
        UUID provider = provider(owner);
        UUID ownerRow = jdbc.queryForObject(
                "select id from provider_role_assignment where provider_id = ? and role = 'OWNER'",
                UUID.class, provider);

        assertThatThrownBy(() -> roleService.revokeRole(owner, provider, ownerRow))
                .isInstanceOfSatisfying(RoleManagementException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("OWNER_REVOCATION_NOT_ALLOWED"));
        assertThat(roleService.hasRole(provider, owner, ProviderRole.OWNER)).isTrue();

        RoleAssignmentView worker = roleService.assignRole(owner, provider, "+919876520033", ProviderRole.WORKER);
        roleService.revokeRole(owner, provider, worker.id());
        assertThatThrownBy(() -> roleService.revokeRole(owner, provider, worker.id()))
                .isInstanceOfSatisfying(RoleManagementException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("ROLE_ALREADY_REVOKED"));
    }

    // -------------------------------------------------------- closed providers

    @Test
    void closedProviderRejectsRoleMutationsButStillListsRoles() {
        UUID owner = account("+919876520034");
        account("+919876520035");
        UUID provider = provider(owner);
        RoleAssignmentView worker = roleService.assignRole(owner, provider, "+919876520035", ProviderRole.WORKER);
        jdbc.update("update food_provider set status = 'CLOSED', accepting_new_customers = false, "
                + "closed_at = now(), closed_by_account_id = ? where id = ?", owner, provider);

        assertThatThrownBy(() -> roleService.assignRole(owner, provider, "+919876520035", ProviderRole.MANAGER))
                .isInstanceOfSatisfying(RoleManagementException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("PROVIDER_CLOSED"));
        assertThatThrownBy(() -> roleService.revokeRole(owner, provider, worker.id()))
                .isInstanceOfSatisfying(RoleManagementException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("PROVIDER_CLOSED"));
        assertThat(roleService.findRoles(owner, provider)).hasSize(2);
    }

    // ------------------------------------------------------ database invariants

    @Test
    void revokedOwnerHistoryDoesNotBlockANewActiveOwner() {
        UUID owner = account("+919876520036");
        UUID next = account("+919876520037");
        UUID provider = provider(owner);

        // What Checkpoint 2's transfer will do, done by hand: revoke old OWNER, then add new OWNER.
        jdbc.update("update provider_role_assignment set status = 'REVOKED', revoked_at = now(), revoked_by = ? "
                + "where provider_id = ? and role = 'OWNER'", owner, provider);
        jdbc.update("insert into provider_role_assignment (id, provider_id, account_id, role, assigned_by) "
                + "values (?, ?, ?, 'OWNER', ?)", UUID.randomUUID(), provider, next, owner);

        assertThat(roleService.hasRole(provider, next, ProviderRole.OWNER)).isTrue();
        assertThat(roleService.hasRole(provider, owner, ProviderRole.OWNER)).isFalse();
    }

    // ------------------------------------------------------------------ helpers

    private UUID account(String phone) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into user_account (id, phone) values (?, ?)", id, phone);
        return id;
    }

    private UUID provider(UUID ownerId) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into food_provider (id, name, provider_type, address_line, locality, city) "
                + "values (?, 'Role Test Mess', 'MESS', '1 Test Road', 'MP Nagar', 'Bhopal')", id);
        jdbc.update("insert into provider_role_assignment (id, provider_id, account_id, role, assigned_by) "
                + "values (?, ?, ?, 'OWNER', ?)", UUID.randomUUID(), id, ownerId, ownerId);
        return id;
    }

    private int activeCount(UUID providerId) {
        return jdbc.queryForObject(
                "select count(*) from provider_role_assignment where provider_id = ? and status = 'ACTIVE'",
                Integer.class, providerId);
    }
}
