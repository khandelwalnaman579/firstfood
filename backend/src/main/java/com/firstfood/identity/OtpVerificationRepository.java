package com.firstfood.identity;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface OtpVerificationRepository extends JpaRepository<OtpVerification, java.util.UUID> {

    /**
     * The most recent still-pending OTP for a phone number and purpose.
     * Used on verify - older pending OTPs for the same phone/purpose are
     * implicitly superseded (a new request always issues a fresh code).
     */
    Optional<OtpVerification> findFirstByPhoneAndPurposeAndStatusOrderByCreatedAtDesc(
            String phone, OtpPurpose purpose, OtpStatus status);

    /**
     * Phase 10 cleanup: deletes up to {@code limit} challenges that expired before {@code cutoff} (any status: an
     * expired challenge can never be used again). Batched because PostgreSQL has no DELETE ... LIMIT. Backed by
     * idx_otp_verification_expires_at. Returns the number deleted.
     */
    @Modifying
    @Query(value = """
            delete from otp_verification where id in (
                select id from otp_verification where expires_at < :cutoff order by expires_at limit :limit)
            """, nativeQuery = true)
    int deleteExpiredBefore(@org.springframework.data.repository.query.Param("cutoff") Instant cutoff,
            @org.springframework.data.repository.query.Param("limit") int limit);
}
