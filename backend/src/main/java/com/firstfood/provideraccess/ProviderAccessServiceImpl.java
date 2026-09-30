package com.firstfood.provideraccess;

import java.util.Comparator;
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
    public ProviderRole assertProviderRole(UUID accountId, UUID providerId, ProviderRole requiredRole) {
        return repository.findByProviderIdAndAccountId(providerId, accountId).stream()
                .map(ProviderRoleAssignment::getRole)
                .filter(role -> role.satisfies(requiredRole))
                .min(Comparator.comparingInt(ProviderRole::ordinal)) // OWNER is declared first = strongest
                .orElseThrow(ProviderNotFoundException::new);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void assignOwner(UUID providerId, UUID accountId) {
        repository.saveAndFlush(new ProviderRoleAssignment(providerId, accountId, ProviderRole.OWNER));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, ProviderRole> rolesFor(UUID accountId) {
        Map<UUID, ProviderRole> strongest = new HashMap<>();
        for (ProviderRoleAssignment assignment : repository.findByAccountId(accountId)) {
            // OWNER is declared first, so the lowest ordinal is the strongest role.
            strongest.merge(assignment.getProviderId(), assignment.getRole(),
                    (a, b) -> a.ordinal() <= b.ordinal() ? a : b);
        }
        return strongest;
    }
}
