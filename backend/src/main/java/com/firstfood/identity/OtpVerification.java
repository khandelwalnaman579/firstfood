package com.firstfood.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/**
 * An OTP challenge. Only otpHash is ever persisted - the plaintext code
 * exists only in memory long enough to hash it and hand it to
 * {@link OtpSender} (architecture.md #12 "Never store plaintext OTPs
 * where avoidable"; rules.md Rule 17.4 "Never log ... OTP values").
 *
 * newPhone is only set for {@link OtpPurpose#PHONE_CHANGE}: the OTP is
 * sent to the *new* number to prove the caller controls it, per rules.md
 * Rule 3.3's Mobile Number -> OTP -> Verified authorization -> Profile
 * change chain.
 */
@Entity
@Table(name = "otp_verification")
public class OtpVerification {

    @Id
    private UUID id;

    @Column(nullable = false, length = 20)
    private String phone;

    @Column(name = "new_phone", length = 20)
    private String newPhone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OtpPurpose purpose;

    @Column(name = "otp_hash", nullable = false)
    private String otpHash;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OtpStatus status = OtpStatus.PENDING;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected OtpVerification() {
        // JPA
    }

    public OtpVerification(String phone, OtpPurpose purpose, String otpHash, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.phone = phone;
        this.purpose = purpose;
        this.otpHash = otpHash;
        this.expiresAt = expiresAt;
    }

    public boolean isExpired(Instant now) {
        return now.isAfter(expiresAt);
    }

    public boolean isPending() {
        return status == OtpStatus.PENDING;
    }

    public void recordFailedAttempt(int maxAttempts) {
        this.attemptCount++;
        if (this.attemptCount >= maxAttempts) {
            this.status = OtpStatus.EXPIRED;
        }
    }

    public void markConsumed() {
        this.status = OtpStatus.CONSUMED;
    }

    public void markExpired() {
        this.status = OtpStatus.EXPIRED;
    }

    public UUID getId() {
        return id;
    }

    public String getPhone() {
        return phone;
    }

    public String getNewPhone() {
        return newPhone;
    }

    public void setNewPhone(String newPhone) {
        this.newPhone = newPhone;
    }

    public OtpPurpose getPurpose() {
        return purpose;
    }

    public String getOtpHash() {
        return otpHash;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public OtpStatus getStatus() {
        return status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
