package com.firstfood.membership;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PersonRepository extends JpaRepository<Person, UUID> {

    Optional<Person> findByUserAccountIdAndPrimaryPersonTrue(UUID userAccountId);

    /** Ids of every person owned by the account (primary and, later, others). */
    @Query("select p.id from Person p where p.userAccountId = :accountId")
    Set<UUID> findIdsByUserAccountId(@Param("accountId") UUID accountId);

    /**
     * Race-safe provisioning of the account's primary person. Two requests
     * provisioning the same account at once (e.g. two providers adding the same
     * new customer) both run this; the partial unique index makes exactly one
     * insert win and the other a no-op - without aborting the transaction the
     * way a failed plain INSERT would on PostgreSQL.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "insert into person (id, user_account_id, full_name, is_primary) "
            + "values (gen_random_uuid(), :accountId, :fullName, true) "
            + "on conflict (user_account_id) where is_primary do nothing", nativeQuery = true)
    int insertPrimaryIfAbsent(@Param("accountId") UUID accountId, @Param("fullName") String fullName);
}
