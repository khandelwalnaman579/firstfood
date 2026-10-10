package com.firstfood.lifecycle;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code app.jobs.*}. Cron expressions are not here: they are read by {@link JobScheduler} straight from
 * {@code app.jobs.*-cron} so a deployment can change a schedule without code.
 *
 * @param enabled         false switches the scheduler off completely (tests, or an instance that must not run jobs);
 *                        the runner and the jobs stay available for explicit calls
 * @param leaseDuration   how long a run may go without a heartbeat before it is considered dead and abandoned
 * @param batchSize       items per scan page / per delete batch
 * @param otpRetention    how long an expired OTP challenge is kept before cleanup deletes it
 * @param sessionRetention how long an expired or revoked refresh session is kept before cleanup deletes it
 */
@ConfigurationProperties(prefix = "app.jobs")
public record JobProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("5m") Duration leaseDuration,
        @DefaultValue("100") int batchSize,
        @DefaultValue("24h") Duration otpRetention,
        @DefaultValue("7d") Duration sessionRetention) {

    public JobProperties {
        if (leaseDuration.isNegative() || leaseDuration.isZero()) {
            throw new IllegalArgumentException("app.jobs.lease-duration must be positive");
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("app.jobs.batch-size must be at least 1");
        }
        if (otpRetention.isNegative() || sessionRetention.isNegative()) {
            throw new IllegalArgumentException("app.jobs retention periods must not be negative");
        }
    }
}
