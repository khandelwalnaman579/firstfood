package com.firstfood.provideraccess;

import static com.firstfood.provideraccess.ProviderPermission.ATTENDANCE_MANAGE;
import static com.firstfood.provideraccess.ProviderPermission.ATTENDANCE_VIEW;
import static com.firstfood.provideraccess.ProviderPermission.MEMBERSHIP_MANAGE;
import static com.firstfood.provideraccess.ProviderPermission.MEMBERSHIP_VIEW;
import static com.firstfood.provideraccess.ProviderPermission.PLAN_VIEW;
import static com.firstfood.provideraccess.ProviderPermission.PROVIDER_EDIT;
import static com.firstfood.provideraccess.ProviderPermission.PROVIDER_VIEW;
import static com.firstfood.provideraccess.ProviderPermission.ROLE_VIEW;
import static com.firstfood.provideraccess.ProviderPermission.SUBSCRIPTION_MANAGE;
import static com.firstfood.provideraccess.ProviderPermission.SUBSCRIPTION_VIEW;

import java.util.EnumSet;
import java.util.Set;

/**
 * Provider-scoped roles and the single role-to-permission mapping (the Phase 4
 * permission matrix). Roles are NOT ordered any more: authorization is decided
 * by {@link #grants(ProviderPermission)}, never by comparing roles.
 *
 * WORKER is read-only for provider + customers after Phase 5 and takes attendance from Phase 8;
 * further operational permissions are reserved for the phases that build those domains.
 *
 * Phase 6 (plans = pricing + absence/extension policy): PLAN_MANAGE is OWNER-only. MANAGER
 * gets PLAN_VIEW because creating subscriptions (Phase 7) means choosing a plan. WORKER gets
 * no plan access. PRD §9 only says MANAGER "possibly plans" - least privilege applies until
 * the pilot says otherwise; widening is a one-line change here plus the matrix test.
 *
 * Phase 7 (subscriptions): PRD §9 lists subscriptions among MANAGER's responsibilities, so MANAGER
 * gets SUBSCRIPTION_VIEW and SUBSCRIPTION_MANAGE (sell, renew, cancel) - the price they sell at is
 * always the owner's plan price, a manager cannot change it. WORKER gets neither: a subscription
 * carries what the customer paid, and a worker's need (who is entitled today) belongs to the
 * attendance view Phase 8 builds, not to this one.
 *
 * Phase 8 (attendance): PRD §9 gives WORKER "Attendance" and MANAGER "attendance" too, so all three roles get
 * ATTENDANCE_VIEW and ATTENDANCE_MANAGE (the daily sheet, recording/overriding an absence for a customer).
 * Nothing in the attendance views carries a price or a phone number, which is what lets a WORKER hold it
 * without SUBSCRIPTION_VIEW. A correction always needs a reason and is recorded with the actor's account id.
 */
public enum ProviderRole {
    // OWNER: everything, including PROVIDER_CLOSE, ROLE_ASSIGN, ROLE_REVOKE and OWNER_TRANSFER.
    OWNER(EnumSet.allOf(ProviderPermission.class)),
    // Phase 5 (PRD §9: MANAGER = customers, attendance, subscriptions; WORKER = attendance):
    // MANAGER manages customers; WORKER may only view them (needed to take attendance later).
    MANAGER(EnumSet.of(PROVIDER_VIEW, PROVIDER_EDIT, ROLE_VIEW, MEMBERSHIP_VIEW, MEMBERSHIP_MANAGE, PLAN_VIEW,
            SUBSCRIPTION_VIEW, SUBSCRIPTION_MANAGE, ATTENDANCE_VIEW, ATTENDANCE_MANAGE)),
    WORKER(EnumSet.of(PROVIDER_VIEW, MEMBERSHIP_VIEW, ATTENDANCE_VIEW, ATTENDANCE_MANAGE));

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
