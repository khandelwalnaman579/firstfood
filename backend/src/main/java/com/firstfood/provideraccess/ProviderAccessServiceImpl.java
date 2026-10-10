package com.firstfood.provideraccess;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProviderAccessServiceImpl implements ProviderAccessService {

    private final ProviderRoleAssignmentRepository repository;

    public ProviderAccessServiceImpl(ProviderRoleAssignmentRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public ProviderRole requirePermission(UUID accountId, UUID providerId, ProviderPermission permission) {
        // One ACTIVE assignment per (provider, account); revoked rows grant nothing.
        ProviderRole role = repository
                .findByProviderIdAndAccountIdAndStatus(providerId, accountId, RoleAssignmentStatus.ACTIVE)
                .map(ProviderRoleAssignment::getRole)
                .orElseThrow(ProviderNotFoundException::new);
        if (!role.grants(permission)) {
            throw new InsufficientPermissionException();
        }
        return role;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void assignOwner(UUID providerId, UUID accountId) {
        // The creator is both the owner and the assigner (same as the V4 backfill).
        repository.saveAndFlush(new ProviderRoleAssignment(providerId, accountId, ProviderRole.OWNER, accountId));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, ProviderRole> rolesFor(UUID accountId) {
        Map<UUID, ProviderRole> roles = new HashMap<>();
        for (ProviderRoleAssignment assignment :
                repository.findByAccountIdAndStatus(accountId, RoleAssignmentStatus.ACTIVE)) {
            roles.put(assignment.getProviderId(), assignment.getRole());
        }
        return roles;
    }
}
