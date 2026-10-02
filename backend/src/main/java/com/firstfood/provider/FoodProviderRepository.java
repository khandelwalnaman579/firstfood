package com.firstfood.provider;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface FoodProviderRepository extends JpaRepository<FoodProvider, UUID> {

    List<FoodProvider> findByIdInOrderByCreatedAtDesc(Collection<UUID> ids);

    /** Row lock so two concurrent PATCHes can't race a status transition. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from FoodProvider p where p.id = :id")
    Optional<FoodProvider> findByIdForUpdate(UUID id);
}
