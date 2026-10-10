package com.firstfood.lifecycle;

import java.util.UUID;

/** The outcome of one {@link JobRunner#run}: counts are items examined / changed / failed (cleanup: rows deleted). */
public record JobRunResult(String jobName, UUID runId, JobRunOutcome outcome, int processed, int changed, int failed) {

    static JobRunResult skipped(String jobName) {
        return new JobRunResult(jobName, null, JobRunOutcome.SKIPPED, 0, 0, 0);
    }
}
