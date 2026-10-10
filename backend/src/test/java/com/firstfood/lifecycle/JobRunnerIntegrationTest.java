package com.firstfood.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstfood.AbstractIntegrationTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;

/**
 * Phase 10: the run record and the lease (rules.md Rules 21.2, 22.1-22.3). Real Postgres. Every test uses its own job
 * name ("t-xxxxxxxx") so tests never see each other's rows in the shared database.
 */
class JobRunnerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    JobRunner runner;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    private static String uniqueName() {
        return "t-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static BackgroundJob job(String name, Body body) {
        return new BackgroundJob() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public void execute(JobRunContext context) throws Exception {
                body.run(context);
            }
        };
    }

    @FunctionalInterface
    interface Body {
        void run(JobRunContext context) throws Exception;
    }

    private JobRunRecord only(String name) {
        List<JobRunRecord> runs = runner.recentRuns(name, 10);
        assertThat(runs).hasSize(1);
        return runs.get(0);
    }

    // ------------------------------------------------------------ the run record

    @Test
    void aSuccessfulRunIsRecordedWithItsCounts() {
        String name = uniqueName();
        JobRunResult result = runner.run(job(name, c -> {
            c.recordUnchanged();
            c.recordChanged();
            c.recordChanged(2);
        }));

        assertThat(result.outcome()).isEqualTo(JobRunOutcome.SUCCEEDED);
        assertThat(result.processed()).isEqualTo(4);
        assertThat(result.changed()).isEqualTo(3);
        JobRunRecord row = only(name);
        assertThat(row.status()).isEqualTo(JobRunStatus.SUCCEEDED);
        assertThat(row.processedCount()).isEqualTo(4);
        assertThat(row.changedCount()).isEqualTo(3);
        assertThat(row.failedCount()).isZero();
        assertThat(row.finishedAt()).isNotNull().isAfterOrEqualTo(row.startedAt());
        assertThat(row.errorSummary()).isNull();
        assertThat(row.instanceId()).isNotBlank();
    }

    @Test
    void anItemFailureMakesTheRunPartialAndTheOtherItemsStillCount() {
        String name = uniqueName();
        JobRunResult result = runner.run(job(name, c -> {
            c.recordChanged();
            c.recordFailure("subscription-42", new IllegalStateException("contains a phone number"));
            c.recordChanged();
        }));

        assertThat(result.outcome()).isEqualTo(JobRunOutcome.PARTIAL);
        JobRunRecord row = only(name);
        assertThat(row.status()).isEqualTo(JobRunStatus.PARTIAL);
        assertThat(row.processedCount()).isEqualTo(3);
        assertThat(row.changedCount()).isEqualTo(2);
        assertThat(row.failedCount()).isEqualTo(1);
        assertThat(row.errorSummary()).isEqualTo("IllegalStateException(subscription-42)");
    }

    @Test
    void aJobThatThrowsIsRecordedFailedAndDoesNotThrowOutOfTheRunner() {
        String name = uniqueName();
        JobRunResult result = runner.run(job(name, c -> {
            c.recordChanged();
            throw new IllegalStateException("database exploded: password=hunter2");
        }));

        assertThat(result.outcome()).isEqualTo(JobRunOutcome.FAILED);
        JobRunRecord row = only(name);
        assertThat(row.status()).isEqualTo(JobRunStatus.FAILED);
        assertThat(row.processedCount()).isEqualTo(1);
        assertThat(row.errorSummary()).isEqualTo("java.lang.IllegalStateException").doesNotContain("hunter2");
        assertThat(row.finishedAt()).isNotNull();
    }

    @Test
    void aFailedRunReleasesTheLeaseSoTheNextRunCanStart() {
        String name = uniqueName();
        runner.run(job(name, c -> {
            throw new IllegalStateException();
        }));
        JobRunResult next = runner.run(job(name, c -> c.recordChanged()));
        assertThat(next.outcome()).isEqualTo(JobRunOutcome.SUCCEEDED);
        assertThat(runner.recentRuns(name, 10)).extracting(JobRunRecord::status)
                .containsExactly(JobRunStatus.SUCCEEDED, JobRunStatus.FAILED);
    }

    @Test
    void oneRunningRowAtMostPerJobIsEnforcedByTheDatabase() {
        String name = uniqueName();
        insertRunning(name, Instant.now(), Instant.now().plusSeconds(300));
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DuplicateKeyException.class,
                () -> insertRunning(name, Instant.now(), Instant.now().plusSeconds(300)));
        // a different job is unaffected
        insertRunning(uniqueName(), Instant.now(), Instant.now().plusSeconds(300));
    }

    // ------------------------------------------------------------ the lease

    @Test
    void aSecondRunnerWhileTheFirstIsRunningSkipsAndDoesNothing() throws Exception {
        String name = uniqueName();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger executions = new AtomicInteger();
        BackgroundJob slow = job(name, c -> {
            executions.incrementAndGet();
            inside.countDown();
            assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
            c.recordChanged();
        });

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<JobRunResult> first = pool.submit(() -> runner.run(slow));
            assertThat(inside.await(30, TimeUnit.SECONDS)).isTrue();

            JobRunResult second = runner.run(job(name, c -> executions.incrementAndGet()));
            assertThat(second.outcome()).isEqualTo(JobRunOutcome.SKIPPED);
            assertThat(second.runId()).isNull();

            release.countDown();
            assertThat(first.get(30, TimeUnit.SECONDS).outcome()).isEqualTo(JobRunOutcome.SUCCEEDED);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(executions.get()).isEqualTo(1);
        assertThat(runner.recentRuns(name, 10)).hasSize(1); // the skipped attempt leaves no row
    }

    @Test
    void manyRunnersAtTheSameInstantRunTheJobExactlyOnce() throws Exception {
        String name = uniqueName();
        int runners = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch winnerInside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger executions = new AtomicInteger();
        BackgroundJob j = job(name, c -> {
            executions.incrementAndGet();
            winnerInside.countDown();
            assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
            c.recordChanged();
        });

        ExecutorService pool = Executors.newFixedThreadPool(runners);
        try {
            List<Future<JobRunResult>> futures = new ArrayList<>();
            for (int i = 0; i < runners; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return runner.run(j);
                }));
            }
            start.countDown();
            assertThat(winnerInside.await(30, TimeUnit.SECONDS)).isTrue();
            // Everyone but the winner is refused while the winner still holds the lease.
            Thread.sleep(500);
            release.countDown();
            int succeeded = 0;
            int skipped = 0;
            for (Future<JobRunResult> f : futures) {
                JobRunOutcome o = f.get(30, TimeUnit.SECONDS).outcome();
                if (o == JobRunOutcome.SUCCEEDED) {
                    succeeded++;
                } else if (o == JobRunOutcome.SKIPPED) {
                    skipped++;
                }
            }
            assertThat(succeeded).isEqualTo(1);
            assertThat(skipped).isEqualTo(runners - 1);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(executions.get()).isEqualTo(1);
    }

    @Test
    void aLiveLeaseHeldByAnotherInstanceIsRespected() {
        String name = uniqueName();
        insertRunning(name, Instant.now().minusSeconds(10), Instant.now().plusSeconds(290));
        AtomicInteger executions = new AtomicInteger();

        JobRunResult result = runner.run(job(name, c -> executions.incrementAndGet()));

        assertThat(result.outcome()).isEqualTo(JobRunOutcome.SKIPPED);
        assertThat(executions.get()).isZero();
        assertThat(only(name).status()).isEqualTo(JobRunStatus.RUNNING);
    }

    @Test
    void aDeadInstancesExpiredLeaseIsAbandonedAndTheJobRunsAgain() {
        String name = uniqueName();
        insertRunning(name, Instant.now().minusSeconds(900), Instant.now().minusSeconds(600));

        JobRunResult result = runner.run(job(name, c -> c.recordChanged()));

        assertThat(result.outcome()).isEqualTo(JobRunOutcome.SUCCEEDED);
        List<JobRunRecord> runs = runner.recentRuns(name, 10);
        assertThat(runs).extracting(JobRunRecord::status)
                .containsExactly(JobRunStatus.SUCCEEDED, JobRunStatus.ABANDONED);
        JobRunRecord abandoned = runs.get(1);
        assertThat(abandoned.finishedAt()).isNotNull();
        assertThat(abandoned.errorSummary()).contains("lease expired");
    }

    @Test
    void aLongRunKeepsItsLeaseWithHeartbeats() {
        // A runner on a fake clock so "two minutes later" does not need two real minutes.
        MutableClock clock = new MutableClock();
        JobRunner fake = runnerOn(clock, Duration.ofMinutes(3));
        String name = uniqueName();
        AtomicReference<Instant> leaseBefore = new AtomicReference<>();
        AtomicReference<Instant> leaseAfter = new AtomicReference<>();

        JobRunResult result = fake.run(job(name, c -> {
            leaseBefore.set(fake.recentRuns(name, 1).get(0).leaseExpiresAt());
            clock.advance(Duration.ofMinutes(2)); // past lease/3 = 1 minute
            assertThat(c.keepGoing()).isTrue();
            leaseAfter.set(fake.recentRuns(name, 1).get(0).leaseExpiresAt());
            c.recordChanged();
        }));

        assertThat(result.outcome()).isEqualTo(JobRunOutcome.SUCCEEDED);
        assertThat(leaseAfter.get()).isAfter(leaseBefore.get().plus(Duration.ofMinutes(1)));
    }

    @Test
    void aRunThatLostItsLeaseStopsAndDoesNotOverwriteTheAbandonedRow() {
        MutableClock clock = new MutableClock();
        JobRunner fake = runnerOn(clock, Duration.ofMinutes(3));
        String name = uniqueName();
        AtomicInteger itemsAfterLoss = new AtomicInteger();

        JobRunResult result = fake.run(job(name, c -> {
            c.recordChanged();
            // Someone else declared this run dead (as a runner whose lease had expired would).
            jdbc.update("update job_run set status = 'ABANDONED', finished_at = now() where job_name = ?", name);
            clock.advance(Duration.ofMinutes(2));
            if (c.keepGoing()) {
                itemsAfterLoss.incrementAndGet();
            }
            if (c.keepGoing()) { // stays false once lost
                itemsAfterLoss.incrementAndGet();
            }
        }));

        assertThat(result.outcome()).isEqualTo(JobRunOutcome.LEASE_LOST);
        assertThat(itemsAfterLoss.get()).isZero();
        assertThat(only(name).status()).isEqualTo(JobRunStatus.ABANDONED);
    }

    // ------------------------------------------------------------ helpers

    private void insertRunning(String name, Instant startedAt, Instant leaseExpiresAt) {
        jdbc.update("""
                insert into job_run (id, job_name, status, instance_id, started_at, lease_expires_at)
                values (gen_random_uuid(), ?, 'RUNNING', 'test-instance', ?, ?)
                """, name, java.sql.Timestamp.from(startedAt), java.sql.Timestamp.from(leaseExpiresAt));
    }

    private JobRunner runnerOn(Clock clock, Duration lease) {
        JobProperties props = new JobProperties(true, lease, 100, Duration.ofHours(24), Duration.ofDays(7));
        return new JobRunner(new JobRunStore(JdbcClient.create(dataSource)), props, clock,
                new DefaultListableBeanFactory().getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class));
    }

    /** A clock that starts at the real "now" and only moves when the test says so. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.now();

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
