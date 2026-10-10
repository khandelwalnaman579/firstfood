package com.firstfood.lifecycle;

import java.time.Instant;
import java.util.UUID;

/** One row of {@code job_run}: what a background job did on one execution. Read-only view for operators and tests. */
public record JobRunRecord(
        UUID id,
        String jobName,
        JobRunStatus status,
        String instanceId,
        Instant startedAt,
        Instant leaseExpiresAt,
        Instant finishedAt,
        int processedCount,
        int changedCount,
        int failedCount,
        String errorSummary) {
}
