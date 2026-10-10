package com.firstfood.lifecycle;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What a running job sees: the item counters it reports into and the lease it must keep alive. Single-threaded by
 * design (one run = one thread); not for sharing.
 *
 * <p>{@link #keepGoing()} is the heartbeat: called between items it extends the lease when a third of it has gone, so
 * a long run is never mistaken for a dead one, and it tells the job to stop if the lease was lost (its row was
 * abandoned because it stalled for longer than the lease). A run that lost its lease must stop touching things: another
 * runner may already be handling the same items. Even then nothing is corrupted - every item is re-checked under its
 * own row lock - but stopping keeps two runners from duplicating effort.
 */
public final class JobRunContext {

    private static final int MAX_LISTED_FAILURES = 20;
    private static final int MAX_SUMMARY_LENGTH = 1000;

    private final UUID runId;
    private final String jobName;
    private final JobRunStore store;
    private final Clock clock;
    private final Duration lease;

    private Instant lastHeartbeat;
    private boolean leaseLost;
    private int processed;
    private int changed;
    private int failed;
    private final List<String> failures = new ArrayList<>();

    JobRunContext(UUID runId, String jobName, JobRunStore store, Clock clock, Duration lease, Instant startedAt) {
        this.runId = runId;
        this.jobName = jobName;
        this.store = store;
        this.clock = clock;
        this.lease = lease;
        this.lastHeartbeat = startedAt;
    }

    /** True while this run still holds the lease. Refreshes the lease when due. */
    public boolean keepGoing() {
        if (leaseLost) {
            return false;
        }
        Instant now = clock.instant();
        if (Duration.between(lastHeartbeat, now).compareTo(lease.dividedBy(3)) >= 0) {
            if (!store.heartbeat(runId, now.plus(lease))) {
                leaseLost = true;
                return false;
            }
            lastHeartbeat = now;
        }
        return true;
    }

    /** The item was examined and needed nothing. */
    public void recordUnchanged() {
        processed++;
    }

    /** The item was examined and its business state (or, for cleanup, a row) was changed. */
    public void recordChanged() {
        processed++;
        changed++;
    }

    /** {@code count} cleanup rows deleted: each counts as processed AND changed (V14). */
    public void recordChanged(int count) {
        processed += count;
        changed += count;
    }

    /**
     * The item failed. Only the exception CLASS and the item id are kept (rules.md Rule 17.4: no messages, which can
     * carry data, and never a stack trace); the full detail is logged by the caller if it wants it.
     */
    public void recordFailure(String itemId, Throwable error) {
        processed++;
        failed++;
        if (failures.size() < MAX_LISTED_FAILURES) {
            failures.add(error.getClass().getSimpleName() + "(" + itemId + ")");
        }
    }

    public String jobName() {
        return jobName;
    }

    int processed() {
        return processed;
    }

    int changed() {
        return changed;
    }

    int failed() {
        return failed;
    }

    boolean leaseLost() {
        return leaseLost;
    }

    /** Null when nothing failed; otherwise "Class(id); Class(id); ... (+N more)", at most 1000 characters. */
    String failureSummary() {
        if (failed == 0) {
            return null;
        }
        String summary = String.join("; ", failures);
        if (failed > failures.size()) {
            summary += " (+" + (failed - failures.size()) + " more)";
        }
        return summary.length() <= MAX_SUMMARY_LENGTH ? summary : summary.substring(0, MAX_SUMMARY_LENGTH);
    }
}
