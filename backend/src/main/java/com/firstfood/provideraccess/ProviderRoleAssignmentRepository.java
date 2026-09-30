package com.firstfood.provideraccess;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProviderRoleAssignmentRepository extends JpaRepository<ProviderRoleAssignment, UUID> {

    List<ProviderRoleAssignment> findByProviderIdAndAccountId(UUID providerId, UUID accountId);

    List<ProviderRoleAssignment> findByAccountId(UUID accountId);
}
