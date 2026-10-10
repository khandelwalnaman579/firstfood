package com.firstfood.lifecycle;

import com.firstfood.identity.IdentityCleanupService;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Deletes refresh sessions that expired or were revoked more than {@code app.jobs.session-retention} ago. A live session
 * is never touched. Same batching and counting as {@link OtpCleanupJob}.
 */
@Component
public class SessionCleanupJob implements BackgroundJob {

    public static final String NAME = "session-cleanup";

    private final IdentityCleanupService cleanup;
    private final JobProperties properties;
    private final Clock clock;

    SessionCleanupJob(IdentityCleanupService cleanup, JobProperties properties, Clock clock) {
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
        Instant cutoff = clock.instant().minus(properties.sessionRetention());
        int batch = properties.batchSize();
        int deleted;
        do {
            if (!context.keepGoing()) {
                return;
            }
            deleted = cleanup.deleteDeadRefreshSessions(cutoff, batch);
            context.recordChanged(deleted);
        } while (deleted == batch);
    }
}
