package com.firstfood.lifecycle;

import io.micrometer.core.instrument.MeterRegistry;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Runs a {@link BackgroundJob} under a lease and leaves a record of what it did (rules.md Rules 22.1-22.3).
 *
 * <ol>
 *   <li>Closes any RUNNING run of this job whose lease expired (its instance died) as ABANDONED.</li>
 *   <li>Takes the lease: inserts a RUNNING {@code job_run} row. The partial unique index
 *       {@code uq_job_run_one_running} lets only one instance succeed; the others SKIP.</li>
 *   <li>Runs the job; it heartbeats through {@link JobRunContext#keepGoing()}.</li>
 *   <li>Closes the row SUCCEEDED / PARTIAL / FAILED with the counts.</li>
 * </ol>
 *
 * The lease is an efficiency and observability measure, NOT the correctness mechanism: every job handles each item
 * under its own row lock and re-checks it, so even two runners that both got through would find nothing left to do.
 * That is why a stalled run is simply abandoned rather than fenced.
 *
 * Never throws for a job's own failure - that is the FAILED outcome - so a scheduler thread is never killed by one. If
 * the database itself is unreachable the lease calls throw; the caller (the scheduler) logs it and the next tick retries.
 * An {@link Error} (e.g. out of memory) is not caught: the run stays RUNNING until its lease expires and is then
 * abandoned by the next runner.
 */
@Service
public class JobRunner {

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);

    private final JobRunStore store;
    private final JobProperties properties;
    private final Clock clock;
    private final ObjectProvider<MeterRegistry> meters;
    private final String instanceId;

    JobRunner(JobRunStore store, JobProperties properties, Clock clock, ObjectProvider<MeterRegistry> meters) {
        this.store = store;
        this.properties = properties;
        this.clock = clock;
        this.meters = meters;
        this.instanceId = newInstanceId();
    }

    public JobRunResult run(BackgroundJob job) {
        String name = job.name();
        Duration lease = properties.leaseDuration();
        Instant startedAt = clock.instant();

        int abandoned = store.abandonExpired(name, startedAt);
        if (abandoned > 0) {
            log.warn("job={} closed {} earlier run(s) as ABANDONED: their instance stopped reporting progress",
                    name, abandoned);
        }

        UUID runId = UUID.randomUUID();
        if (!store.tryStart(runId, name, instanceId, startedAt, startedAt.plus(lease))) {
            log.info("job={} skipped: another run holds the lease", name);
            count(name, JobRunOutcome.SKIPPED);
            return JobRunResult.skipped(name);
        }

        JobRunContext context = new JobRunContext(runId, name, store, clock, lease, startedAt);
        JobRunStatus status;
        String error;
        try {
            job.execute(context);
            status = context.failed() > 0 ? JobRunStatus.PARTIAL : JobRunStatus.SUCCEEDED;
            error = context.failureSummary();
        } catch (Exception e) {
            // Class name only: a message can carry data, a stack trace is noise in a table (Rule 17.4).
            log.error("job={} run={} failed", name, runId, e);
            status = JobRunStatus.FAILED;
            error = e.getClass().getName();
        }

        if (context.leaseLost()) {
            // Another runner already closed this row as ABANDONED and may be working on the same items.
            log.error("job={} run={} lost its lease and stopped; processed={} changed={} failed={}",
                    name, runId, context.processed(), context.changed(), context.failed());
            count(name, JobRunOutcome.LEASE_LOST);
            return new JobRunResult(name, runId, JobRunOutcome.LEASE_LOST,
                    context.processed(), context.changed(), context.failed());
        }

        Instant finishedAt = clock.instant();
        if (!store.finish(runId, status, context.processed(), context.changed(), context.failed(), error, finishedAt)) {
            // Abandoned between the last heartbeat and now (the job stalled right at the end).
            log.error("job={} run={} finished but its row was no longer RUNNING", name, runId);
            count(name, JobRunOutcome.LEASE_LOST);
            return new JobRunResult(name, runId, JobRunOutcome.LEASE_LOST,
                    context.processed(), context.changed(), context.failed());
        }

        JobRunOutcome outcome = JobRunOutcome.valueOf(status.name());
        log.info("job={} run={} status={} processed={} changed={} failed={} durationMs={}", name, runId, status,
                context.processed(), context.changed(), context.failed(), Duration.between(startedAt, finishedAt).toMillis());
        if (context.failed() > 0) {
            log.warn("job={} run={} failures: {}", name, runId, context.failureSummary());
        }
        count(name, outcome);
        items(name, "changed", context.changed());
        items(name, "failed", context.failed());
        return new JobRunResult(name, runId, outcome, context.processed(), context.changed(), context.failed());
    }

    /** Recent runs of a job, newest first - the observable history (architecture.md §28). */
    public java.util.List<JobRunRecord> recentRuns(String jobName, int limit) {
        return store.recent(jobName, limit);
    }

    private void count(String job, JobRunOutcome outcome) {
        MeterRegistry registry = meters.getIfAvailable();
        if (registry != null) {
            registry.counter("firstfood.job.runs", "job", job, "outcome", outcome.name()).increment();
        }
    }

    private void items(String job, String result, int amount) {
        MeterRegistry registry = meters.getIfAvailable();
        if (registry != null && amount > 0) {
            registry.counter("firstfood.job.items", "job", job, "result", result).increment(amount);
        }
    }

    private static String newInstanceId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown-host";
        }
        String id = host + "/" + ProcessHandle.current().pid() + "/" + UUID.randomUUID().toString().substring(0, 8);
        return id.length() <= 120 ? id : id.substring(id.length() - 120);
    }
}
