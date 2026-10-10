package com.firstfood.identity;

import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class IdentityCleanupServiceImpl implements IdentityCleanupService {

    private final OtpVerificationRepository otps;
    private final RefreshSessionRepository sessions;

    IdentityCleanupServiceImpl(OtpVerificationRepository otps, RefreshSessionRepository sessions) {
        this.otps = otps;
        this.sessions = sessions;
    }

    @Override
    @Transactional
    public int deleteExpiredOtpChallenges(Instant cutoff, int batchSize) {
        return otps.deleteExpiredBefore(Objects.requireNonNull(cutoff, "cutoff"), requirePositive(batchSize));
    }

    @Override
    @Transactional
    public int deleteDeadRefreshSessions(Instant cutoff, int batchSize) {
        return sessions.deleteDeadBefore(Objects.requireNonNull(cutoff, "cutoff"), requirePositive(batchSize));
    }

    private static int requirePositive(int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        return batchSize;
    }
}
