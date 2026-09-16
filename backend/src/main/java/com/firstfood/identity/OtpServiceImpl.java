package com.firstfood.identity;

import com.firstfood.identity.error.InvalidOtpException;
import com.firstfood.identity.error.OtpExpiredException;
import com.firstfood.identity.error.OtpRateLimitExceededException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements {@link OtpService}. Rate limiting (rules.md Rule 17.1) uses
 * a Redis fixed-window counter rather than a database row, since it's a
 * high-frequency, short-lived check that doesn't need to be durable
 * business history.
 */
@Service
public class OtpServiceImpl implements OtpService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String RATE_LIMIT_KEY_PREFIX = "otp:rate:request:";

    private final OtpVerificationRepository otpVerificationRepository;
    private final OtpSender otpSender;
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate redisTemplate;
    private final OtpProperties otpProperties;

    public OtpServiceImpl(
            OtpVerificationRepository otpVerificationRepository,
            OtpSender otpSender,
            PasswordEncoder passwordEncoder,
            StringRedisTemplate redisTemplate,
            OtpProperties otpProperties) {
        this.otpVerificationRepository = otpVerificationRepository;
        this.otpSender = otpSender;
        this.passwordEncoder = passwordEncoder;
        this.redisTemplate = redisTemplate;
        this.otpProperties = otpProperties;
    }

    @Override
    @Transactional
    public void requestOtp(String phone, OtpPurpose purpose) {
        enforceRateLimit(phone);

        String code = generateSixDigitCode();
        String hash = passwordEncoder.encode(code);
        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(otpProperties.ttlMinutes()));

        otpVerificationRepository.save(new OtpVerification(phone, purpose, hash, expiresAt));
        otpSender.send(phone, code);
    }

    @Override
    @Transactional
    public void verifyOtp(String phone, OtpPurpose purpose, String code) {
        OtpVerification otp = otpVerificationRepository
                .findFirstByPhoneAndPurposeAndStatusOrderByCreatedAtDesc(phone, purpose, OtpStatus.PENDING)
                .orElseThrow(OtpExpiredException::new);

        if (otp.isExpired(Instant.now())) {
            otp.markExpired();
            otpVerificationRepository.save(otp);
            throw new OtpExpiredException();
        }

        if (!passwordEncoder.matches(code, otp.getOtpHash())) {
            otp.recordFailedAttempt(otpProperties.maxAttempts());
            otpVerificationRepository.save(otp);
            throw new InvalidOtpException();
        }

        otp.markConsumed();
        otpVerificationRepository.save(otp);
    }

    private void enforceRateLimit(String phone) {
        String key = RATE_LIMIT_KEY_PREFIX + phone;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, Duration.ofHours(1));
        }
        if (count != null && count > otpProperties.requestRateLimitPerHour()) {
            throw new OtpRateLimitExceededException();
        }
    }

    private String generateSixDigitCode() {
        int value = RANDOM.nextInt(1_000_000);
        return String.format("%06d", value);
    }
}
