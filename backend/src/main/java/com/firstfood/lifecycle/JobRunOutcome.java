package com.firstfood.lifecycle;

/** What {@link JobRunner#run} did. */
public enum JobRunOutcome {
    /** Another run of the same job holds the lease; nothing was done (normal when two instances fire together). */
    SKIPPED,
    SUCCEEDED,
    /** Finished, but some items failed; they are retried by the next run. */
    PARTIAL,
    /** The job itself failed. */
    FAILED,
    /** The run stalled past its lease and was abandoned; it stopped without recording an outcome. */
    LEASE_LOST
}
