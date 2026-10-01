package com.firstfood.provideraccess;

import com.firstfood.identity.AccountLookupService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Concurrency: every mutation first takes a row lock on the provider
 * ({@code select ... for update}), so concurrent role/ownership mutations on
 * one provider run one after another and each re-reads committed state after
 * acquiring the lock (READ COMMITTED). The partial unique indexes remain the
 * last line of defence. Audit rows are written in the same transaction.
 */
@Service
public class ProviderRoleServiceImpl implements ProviderRoleService {

    private static final String CLOSED = "CLOSED";

    private final ProviderRoleAssignmentRepository repository;
    private final ProviderRoleAuditRepository auditRepository;
    private final ProviderAccessService accessService;
    private final AccountLookupService accountLookup;

    public ProviderRoleServiceImpl(ProviderRoleAssignmentRepository repository,
            ProviderRoleAuditRepository auditRepository, ProviderAccessService accessService,
            AccountLookupService accountLookup) {
        this.repository = repository;
        this.auditRepository = auditRepository;
        this.accessService = accessService;
        this.accountLookup = accountLookup;
    }

    @Override
    @Transactional
    public RoleAssignmentView assignRole(UUID actorAccountId, UUID providerId, String targetPhone, ProviderRole role) {
        requireContext(actorAccountId, providerId);

        String providerStatus = lockProvider(providerId);
        accessService.requirePermission(actorAccountId, providerId, ProviderPermission.ROLE_ASSIGN);
        rejectIfClosed(providerStatus);

        if (role == ProviderRole.OWNER) {
            throw RoleManagementException.ownerAssignmentNotAllowed();
        }
        if (role == null) {
            throw RoleManagementException.invalidRole();
        }

        UUID targetAccountId = resolveTarget(targetPhone);

        // Covers "already has this role", "has another role" and "is the owner".
        if (repository.findByProviderIdAndAccountIdAndStatus(providerId, targetAccountId, RoleAssignmentStatus.ACTIVE)
                .isPresent()) {
            throw RoleManagementException.alreadyAssigned();
        }

        ProviderRoleAssignment saved;
        try {
            saved = repository.saveAndFlush(
                    new ProviderRoleAssignment(providerId, targetAccountId, role, actorAccountId));
        } catch (DataIntegrityViolationException e) {
            // The partial unique index is the last line of defence; the provider lock makes this unlikely.
            throw RoleManagementException.alreadyAssigned();
        }
        audit(RoleAuditAction.ROLE_ASSIGNED, providerId, actorAccountId, targetAccountId, null, role);
        return toViews(List.of(saved)).get(0);
    }

    @Override
    @Transactional
    public RoleAssignmentView revokeRole(UUID actorAccountId, UUID providerId, UUID assignmentId) {
        requireContext(actorAccountId, providerId);
        Objects.requireNonNull(assignmentId, "assignmentId");

        String providerStatus = lockProvider(providerId);
        accessService.requirePermission(actorAccountId, providerId, ProviderPermission.ROLE_REVOKE);
        rejectIfClosed(providerStatus);

        // Provider-scoped lookup: an assignment id from another provider is "not found".
        ProviderRoleAssignment assignment = repository.findByIdAndProviderId(assignmentId, providerId)
                .orElseThrow(RoleManagementException::assignmentNotFound);
        if (!assignment.isActive()) {
            throw RoleManagementException.alreadyRevoked();
        }
        if (assignment.getRole() == ProviderRole.OWNER) {
            throw RoleManagementException.ownerRevocationNotAllowed();
        }

        assignment.revoke(actorAccountId, Instant.now());
        ProviderRoleAssignment saved = repository.saveAndFlush(assignment);
        audit(RoleAuditAction.ROLE_REVOKED, providerId, actorAccountId, saved.getAccountId(), saved.getRole(), null);
        return toViews(List.of(saved)).get(0);
    }

    @Override
    @Transactional
    public List<RoleAssignmentView> transferOwnership(UUID actorAccountId, UUID providerId, String targetPhone) {
        requireContext(actorAccountId, providerId);

        String providerStatus = lockProvider(providerId);
        accessService.requirePermission(actorAccountId, providerId, ProviderPermission.OWNER_TRANSFER);
        rejectIfClosed(providerStatus);

        UUID targetAccountId = resolveTarget(targetPhone);
        if (targetAccountId.equals(actorAccountId)) {
            throw RoleManagementException.transferToSelf();
        }

        // OWNER_TRANSFER is only granted to OWNER, so this is the current (only) active OWNER row.
        ProviderRoleAssignment oldOwner = repository
                .findByProviderIdAndAccountIdAndStatus(providerId, actorAccountId, RoleAssignmentStatus.ACTIVE)
                .orElseThrow(ProviderNotFoundException::new);
        Optional<ProviderRoleAssignment> targetCurrent = repository
                .findByProviderIdAndAccountIdAndStatus(providerId, targetAccountId, RoleAssignmentStatus.ACTIVE);
        ProviderRole targetOldRole = targetCurrent.map(ProviderRoleAssignment::getRole).orElse(null);

        // Order matters and every step is flushed immediately: Hibernate would otherwise
        // run the INSERTs before the UPDATEs, and the partial unique indexes (one ACTIVE
        // OWNER; one ACTIVE role per account) are checked per statement and cannot be
        // deferred. The "zero owners" moment only ever exists inside this uncommitted transaction.
        Instant now = Instant.now();
        oldOwner.revoke(actorAccountId, now);
        repository.saveAndFlush(oldOwner);
        if (targetCurrent.isPresent()) {
            ProviderRoleAssignment superseded = targetCurrent.get();
            superseded.revoke(actorAccountId, now);
            repository.saveAndFlush(superseded);
        }
        ProviderRoleAssignment newOwner = repository.saveAndFlush(
                new ProviderRoleAssignment(providerId, targetAccountId, ProviderRole.OWNER, actorAccountId));
        ProviderRoleAssignment formerOwner = repository.saveAndFlush(
                new ProviderRoleAssignment(providerId, actorAccountId, ProviderRole.MANAGER, actorAccountId));

        audit(RoleAuditAction.OWNER_TRANSFERRED, providerId, actorAccountId, targetAccountId,
                targetOldRole, ProviderRole.OWNER);
        return toViews(List.of(newOwner, formerOwner));
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoleAssignmentView> findRoles(UUID actorAccountId, UUID providerId) {
        requireContext(actorAccountId, providerId);
        accessService.requirePermission(actorAccountId, providerId, ProviderPermission.ROLE_VIEW);
        return toViews(repository.findByProviderIdAndStatusOrderByCreatedAtAsc(providerId, RoleAssignmentStatus.ACTIVE));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasRole(UUID providerId, UUID accountId, ProviderRole role) {
        return repository.findByProviderIdAndAccountIdAndStatus(providerId, accountId, RoleAssignmentStatus.ACTIVE)
                .map(a -> a.getRole() == role)
                .orElse(false);
    }

    private static void requireContext(UUID actorAccountId, UUID providerId) {
        Objects.requireNonNull(actorAccountId, "actorAccountId must come from the authenticated principal");
        Objects.requireNonNull(providerId, "providerId");
    }

    /** Takes the provider row lock; a missing provider is indistinguishable from "no access". */
    private String lockProvider(UUID providerId) {
        return repository.lockProviderAndGetStatus(providerId).orElseThrow(ProviderNotFoundException::new);
    }

    private void rejectIfClosed(String providerStatus) {
        if (CLOSED.equals(providerStatus)) {
            throw RoleManagementException.providerClosed();
        }
    }

    private UUID resolveTarget(String targetPhone) {
        return accountLookup.findActiveAccountIdByPhone(targetPhone)
                .orElseThrow(RoleManagementException::targetAccountNotFound);
    }

    private void audit(RoleAuditAction action, UUID providerId, UUID actor, UUID target, ProviderRole oldRole,
            ProviderRole newRole) {
        auditRepository.save(new ProviderRoleAudit(providerId, actor, target, action, oldRole, newRole));
    }

    private List<RoleAssignmentView> toViews(List<ProviderRoleAssignment> rows) {
        Map<UUID, String> phones = accountLookup.findPhonesByIds(
                rows.stream().map(ProviderRoleAssignment::getAccountId).distinct().toList());
        return rows.stream().map(a -> RoleAssignmentView.from(a, phones.get(a.getAccountId()))).toList();
    }
}
