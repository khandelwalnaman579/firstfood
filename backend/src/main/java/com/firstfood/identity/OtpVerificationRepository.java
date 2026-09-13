package com.firstfood.identity;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OtpVerificationRepository extends JpaRepository<OtpVerification, java.util.UUID> {

    /**
     * The most recent still-pending OTP for a phone number and purpose.
     * Used on verify - older pending OTPs for the same phone/purpose are
     * implicitly superseded (a new request always issues a fresh code).
     */
    Optional<OtpVerification> findFirstByPhoneAndPurposeAndStatusOrderByCreatedAtDesc(
            String phone, OtpPurpose purpose, OtpStatus status);
}
