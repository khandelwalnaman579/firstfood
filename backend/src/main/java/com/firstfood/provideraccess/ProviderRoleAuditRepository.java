package com.firstfood.provideraccess;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProviderRoleAuditRepository extends JpaRepository<ProviderRoleAudit, UUID> {
}
