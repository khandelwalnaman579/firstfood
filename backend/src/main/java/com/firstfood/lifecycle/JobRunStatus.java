package com.firstfood.lifecycle;

/** Mirrors {@code job_run.status} (V14). */
public enum JobRunStatus {
    /** Holds the lease. */
    RUNNING,
    /** Finished; every item handled. */
    SUCCEEDED,
    /** Finished; at least one item failed (the rest were processed, the failures are retried next run). */
    PARTIAL,
    /** The job itself failed (e.g. the database was unreachable). */
    FAILED,
    /** A RUNNING row whose lease expired (its instance died); closed by whoever next wanted to run. */
    ABANDONED
}
