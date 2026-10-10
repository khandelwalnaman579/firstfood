package com.firstfood.lifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The only place that decides WHEN jobs run. Crons come from {@code app.jobs.*-cron} and are read in the business time
 * zone; the whole class is absent when {@code app.jobs.enabled=false}. Every instance of the application schedules every
 * job; the lease in {@link JobRunner} makes sure only one of them actually runs it.
 *
 * Schedules are frequent on purpose (hourly for the subscription jobs): the jobs are idempotent, so running often costs
 * almost nothing and means a missed run (deploy, restart, outage) is made up within the hour instead of the next day.
 */
@Configuration(proxyBeanMethods = false) // no @Bean methods: nothing for a CGLIB proxy to do on a package-private class
@EnableScheduling
@ConditionalOnProperty(prefix = "app.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
class JobScheduler {

    private static final Logger log = LoggerFactory.getLogger(JobScheduler.class);

    private final JobRunner runner;
    private final SubscriptionExpiryJob expiry;
    private final ExtensionEvaluationJob extension;
    private final OtpCleanupJob otpCleanup;
    private final SessionCleanupJob sessionCleanup;

    JobScheduler(JobRunner runner, SubscriptionExpiryJob expiry, ExtensionEvaluationJob extension,
            OtpCleanupJob otpCleanup, SessionCleanupJob sessionCleanup) {
        this.runner = runner;
        this.expiry = expiry;
        this.extension = extension;
        this.otpCleanup = otpCleanup;
        this.sessionCleanup = sessionCleanup;
    }

    @Scheduled(cron = "${app.jobs.expiry-cron:0 5 * * * *}", zone = "${app.business.time-zone:Asia/Kolkata}")
    void subscriptionExpiry() {
        runSafely(expiry);
    }

    @Scheduled(cron = "${app.jobs.extension-cron:0 20 * * * *}", zone = "${app.business.time-zone:Asia/Kolkata}")
    void extensionEvaluation() {
        runSafely(extension);
    }

    @Scheduled(cron = "${app.jobs.otp-cleanup-cron:0 30 3 * * *}", zone = "${app.business.time-zone:Asia/Kolkata}")
    void otpCleanup() {
        runSafely(otpCleanup);
    }

    @Scheduled(cron = "${app.jobs.session-cleanup-cron:0 40 3 * * *}", zone = "${app.business.time-zone:Asia/Kolkata}")
    void sessionCleanup() {
        runSafely(sessionCleanup);
    }

    /** A scheduler thread must survive anything: the next tick tries again. */
    private void runSafely(BackgroundJob job) {
        try {
            runner.run(job);
        } catch (RuntimeException e) {
            log.error("job={} could not be run (database unreachable?): {}", job.name(), e.getClass().getName());
        }
    }
}
