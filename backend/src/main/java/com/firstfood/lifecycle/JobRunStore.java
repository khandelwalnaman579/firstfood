package com.firstfood.lifecycle;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Persistence for {@code job_run}. Plain SQL on purpose: every call is ONE short statement that commits on its own
 * (no surrounding transaction), so the lease and the heartbeats are visible to other instances immediately and are never
 * held back by - or rolled back with - a job's own work. No business decisions here (Rule 18.3); the lease RULE is in
 * {@link JobRunner}, the lease GUARANTEE is the partial unique index {@code uq_job_run_one_running}.
 */
@Component
class JobRunStore {

    private final JdbcClient jdbc;

    JobRunStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Tries to take the lease for {@code jobName}. False when another run already holds it (the unique index refused
     * the insert), which is the normal "someone else is running this" answer, not an error.
     */
    boolean tryStart(UUID id, String jobName, String instanceId, Instant now, Instant leaseExpiresAt) {
        return jdbc.sql("""
                insert into job_run (id, job_name, status, instance_id, started_at, lease_expires_at)
                values (:id, :job, 'RUNNING', :instance, :now, :lease)
                on conflict do nothing
                """)
                .param("id", id).param("job", jobName).param("instance", instanceId)
                .param("now", ts(now)).param("lease", ts(leaseExpiresAt))
                .update() == 1;
    }

    /** Closes RUNNING rows of this job whose lease ran out: their instance died. Returns how many. */
    int abandonExpired(String jobName, Instant now) {
        return jdbc.sql("""
                update job_run
                   set status = 'ABANDONED', finished_at = greatest(:now, started_at),
                       error_summary = 'lease expired: the running instance stopped reporting progress'
                 where job_name = :job and status = 'RUNNING' and lease_expires_at < :now
                """)
                .param("job", jobName).param("now", ts(now))
                .update();
    }

    /** Extends the lease of a still-RUNNING run. False means the run is no longer RUNNING (it was abandoned). */
    boolean heartbeat(UUID id, Instant newLeaseExpiresAt) {
        return jdbc.sql("""
                update job_run set lease_expires_at = greatest(:lease, started_at)
                 where id = :id and status = 'RUNNING'
                """)
                .param("id", id).param("lease", ts(newLeaseExpiresAt))
                .update() == 1;
    }

    /** Closes a RUNNING run with its outcome. False when it was no longer RUNNING (abandoned meanwhile). */
    boolean finish(UUID id, JobRunStatus status, int processed, int changed, int failed, String errorSummary,
            Instant now) {
        return jdbc.sql("""
                update job_run
                   set status = :status, finished_at = greatest(:now, started_at),
                       processed_count = :processed, changed_count = :changed, failed_count = :failed,
                       error_summary = :error
                 where id = :id and status = 'RUNNING'
                """)
                .param("id", id).param("status", status.name()).param("now", ts(now))
                .param("processed", processed).param("changed", changed).param("failed", failed)
                .param("error", errorSummary)
                .update() == 1;
    }

    /** Newest first. */
    List<JobRunRecord> recent(String jobName, int limit) {
        return jdbc.sql("select * from job_run where job_name = :job order by started_at desc, id limit :limit")
                .param("job", jobName).param("limit", limit)
                .query(JobRunStore::map).list();
    }

    private static JobRunRecord map(ResultSet rs, int row) throws SQLException {
        return new JobRunRecord(
                rs.getObject("id", UUID.class),
                rs.getString("job_name"),
                JobRunStatus.valueOf(rs.getString("status")),
                rs.getString("instance_id"),
                instant(rs, "started_at"),
                instant(rs, "lease_expires_at"),
                instant(rs, "finished_at"),
                rs.getInt("processed_count"),
                rs.getInt("changed_count"),
                rs.getInt("failed_count"),
                rs.getString("error_summary"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    /** pgjdbc binds OffsetDateTime to timestamptz everywhere; Instant support varies, so convert explicitly. */
    private static OffsetDateTime ts(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
