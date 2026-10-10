package com.firstfood.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Counting and failure-summary rules of {@link JobRunContext} (no database: the lease is never due here). */
class JobRunContextTest {

    private JobRunContext context() {
        return new JobRunContext(UUID.randomUUID(), "unit-job", null, Clock.systemUTC(), Duration.ofMinutes(5),
                Clock.systemUTC().instant());
    }

    @Test
    void countsMatchTheDatabaseCheckChangedPlusFailedNeverExceedsProcessed() {
        JobRunContext c = context();
        c.recordUnchanged();
        c.recordChanged();
        c.recordChanged(3);
        c.recordFailure("a", new IllegalStateException("secret detail"));
        assertThat(c.processed()).isEqualTo(6);
        assertThat(c.changed()).isEqualTo(4);
        assertThat(c.failed()).isEqualTo(1);
        assertThat(c.changed() + c.failed()).isLessThanOrEqualTo(c.processed());
    }

    @Test
    void failureSummaryHasClassAndItemIdButNeverTheMessage() {
        JobRunContext c = context();
        c.recordFailure("item-1", new IllegalStateException("phone +919876500000 leaked"));
        assertThat(c.failureSummary()).isEqualTo("IllegalStateException(item-1)").doesNotContain("phone");
    }

    @Test
    void noFailuresMeansNoSummary() {
        JobRunContext c = context();
        c.recordChanged();
        assertThat(c.failureSummary()).isNull();
    }

    @Test
    void manyFailuresAreSummarisedAndCapped() {
        JobRunContext c = context();
        for (int i = 0; i < 50; i++) {
            c.recordFailure(UUID.randomUUID().toString(), new RuntimeException());
        }
        assertThat(c.failed()).isEqualTo(50);
        assertThat(c.failureSummary()).hasSizeLessThanOrEqualTo(1000);
    }

    @Test
    void keepGoingIsTrueWhileTheLeaseIsFresh() {
        // lease/3 = 100 s has not passed, so no database call is made (the store is null on purpose)
        assertThat(context().keepGoing()).isTrue();
    }
}
