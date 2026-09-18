package com.firstfood.identity;

import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists OTP outcome updates (a failed attempt, an expiry) in their own
 * transaction, independent of the caller's.
 *
 * {@link OtpServiceImpl#verifyOtp} always throws right after recording
 * one of these outcomes (that's the point - a wrong code or an expired
 * OTP is a failure). If that persistence happened inside the same
 * `@Transactional` method that then throws, Spring's default
 * rollback-on-RuntimeException would undo the very update we're trying
 * to keep - so a wrong-code attempt would never actually increment
 * `attemptCount`, and the max-attempts limit could never be reached.
 * This exact bug was caught by
 * maxOtpAttemptsExhaustsTheChallengeEvenForTheRightCode failing in CI
 * (5 wrong attempts didn't exhaust the challenge; the 6th, correct,
 * attempt still succeeded).
 *
 * This has to be a separate bean, not just a differently-annotated
 * private method on {@link OtpServiceImpl}: REQUIRES_NEW (like all
 * Spring transaction advice) is applied via a proxy, and calling a
 * method on `this` from within the same class bypasses that proxy
 * entirely - the annotation would be silently ignored.
 */
@Component
public class OtpOutcomeRecorder {

    private final OtpVerificationRepository otpVerificationRepository;

    public OtpOutcomeRecorder(OtpVerificationRepository otpVerificationRepository) {
        this.otpVerificationRepository = otpVerificationRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailedAttempt(UUID otpId, int maxAttempts) {
        otpVerificationRepository.findById(otpId).ifPresent(otp -> {
            otp.recordFailedAttempt(maxAttempts);
            otpVerificationRepository.save(otp);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markExpired(UUID otpId) {
        otpVerificationRepository.findById(otpId).ifPresent(otp -> {
            otp.markExpired();
            otpVerificationRepository.save(otp);
        });
    }
}