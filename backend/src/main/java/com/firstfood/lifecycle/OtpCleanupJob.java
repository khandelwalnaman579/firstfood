package com.firstfood.lifecycle;

import com.firstfood.identity.IdentityCleanupService;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Deletes OTP challenges that expired more than {@code app.jobs.otp-retention} ago (architecture.md §21; closes the
 * "otp_verification rows accumulate indefinitely" item in memory.md §7). Challenges are authentication artifacts, not
 * business history. Deletes in batches until a batch comes back short; each deleted row counts as processed and changed.
 */
@Component
public class OtpCleanupJob implements BackgroundJob {

    public static final String NAME = "otp-cleanup";

    private final IdentityCleanupService cleanup;
    private final JobProperties properties;
    private final Clock clock;

    OtpCleanupJob(IdentityCleanupService cleanup, JobProperties properties, Clock clock) {
        this.cleanup = cleanup;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void execute(JobRunContext context) {
        Instant cutoff = clock.instant().minus(properties.otpRetention());
        int batch = properties.batchSize();
        int deleted;
        do {
            if (!context.keepGoing()) {
                return;
            }
            deleted = cleanup.deleteExpiredOtpChallenges(cutoff, batch);
            context.recordChanged(deleted);
        } while (deleted == batch);
    }
}
