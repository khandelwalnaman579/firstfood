package com.firstfood.lifecycle;

/**
 * One scheduled unit of work. Implementations are retry-safe by construction (rules.md Rule 22.1): each item is handled
 * in its own transaction, re-checked under its own lock, and a second run finds nothing left to do. The
 * {@link JobRunner} supplies the lease, the run record and the counts; the job only reports what happened to each item.
 */
public interface BackgroundJob {

    /** Stable identifier, lower-case letters, digits and hyphens (the {@code job_run.job_name} check). */
    String name();

    /**
     * Does the work. Call {@link JobRunContext#keepGoing()} before each item and stop when it says false; report every
     * item through the context. A failure of ONE item is recorded with {@link JobRunContext#recordFailure} and the job
     * carries on; an exception that escapes here means the job itself failed.
     */
    void execute(JobRunContext context) throws Exception;
}
