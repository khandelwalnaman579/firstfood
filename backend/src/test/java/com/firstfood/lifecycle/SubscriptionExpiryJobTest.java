package com.firstfood.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.firstfood.common.time.BusinessCalendar;
import com.firstfood.subscription.SubscriptionKey;
import com.firstfood.subscription.SubscriptionLifecycleService;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The job's own logic (paging, per-item isolation, counting) with the collaborators stubbed. */
class SubscriptionExpiryJobTest {

    private static final UUID FIRST = new UUID(0L, 0L);

    private final SubscriptionLifecycleService lifecycle = mock(SubscriptionLifecycleService.class);
    private final SubscriptionLapseProcessor processor = mock(SubscriptionLapseProcessor.class);
    private final SubscriptionExpiryJob job = new SubscriptionExpiryJob(lifecycle, processor,
            new BusinessCalendar(Clock.systemUTC(), "Asia/Kolkata"),
            new JobProperties(true, Duration.ofMinutes(5), 100, Duration.ofHours(24), Duration.ofDays(7)));

    private static SubscriptionKey key() {
        return new SubscriptionKey(UUID.randomUUID(), UUID.randomUUID());
    }

    private static JobRunContext context() {
        return new JobRunContext(UUID.randomUUID(), SubscriptionExpiryJob.NAME, null, Clock.systemUTC(),
                Duration.ofMinutes(5), Clock.systemUTC().instant());
    }

    @Test
    void oneFailingSubscriptionDoesNotStopTheOthersAndIsCounted() {
        SubscriptionKey a = key();
        SubscriptionKey b = key();
        SubscriptionKey c = key();
        when(lifecycle.findLapsed(any(), eq(FIRST), anyInt())).thenReturn(List.of(a, b, c));
        when(lifecycle.findLapsed(any(), eq(c.subscriptionId()), anyInt())).thenReturn(List.of());
        when(processor.process(eq(a), any())).thenReturn(SubscriptionLapseProcessor.Result.EXPIRED);
        when(processor.process(eq(b), any())).thenThrow(new IllegalStateException("boom"));
        when(processor.process(eq(c), any())).thenReturn(SubscriptionLapseProcessor.Result.EXTENDED);

        JobRunContext ctx = context();
        job.execute(ctx);

        assertThat(ctx.processed()).isEqualTo(3);
        assertThat(ctx.changed()).isEqualTo(2);
        assertThat(ctx.failed()).isEqualTo(1);
        assertThat(ctx.failureSummary()).isEqualTo("IllegalStateException(" + b.subscriptionId() + ")");
    }

    @Test
    void skippedSubscriptionsAreExaminedButNotChanged() {
        SubscriptionKey a = key();
        when(lifecycle.findLapsed(any(), eq(FIRST), anyInt())).thenReturn(List.of(a));
        when(lifecycle.findLapsed(any(), eq(a.subscriptionId()), anyInt())).thenReturn(List.of());
        when(processor.process(eq(a), any())).thenReturn(SubscriptionLapseProcessor.Result.SKIPPED);

        JobRunContext ctx = context();
        job.execute(ctx);

        assertThat(ctx.processed()).isEqualTo(1);
        assertThat(ctx.changed()).isZero();
        assertThat(ctx.failed()).isZero();
    }

    @Test
    void anEmptyScanDoesNothing() {
        when(lifecycle.findLapsed(any(), eq(FIRST), anyInt())).thenReturn(List.of());
        JobRunContext ctx = context();
        job.execute(ctx);
        assertThat(ctx.processed()).isZero();
        verify(processor, never()).process(any(), any());
    }

    @Test
    void theScanResumesAfterTheLastIdSoAFailingItemIsNotRetriedInTheSameRun() {
        SubscriptionKey a = key();
        SubscriptionKey b = key();
        when(lifecycle.findLapsed(any(), eq(FIRST), anyInt())).thenReturn(List.of(a));
        when(lifecycle.findLapsed(any(), eq(a.subscriptionId()), anyInt())).thenReturn(List.of(b));
        when(lifecycle.findLapsed(any(), eq(b.subscriptionId()), anyInt())).thenReturn(List.of());
        when(processor.process(eq(a), any())).thenThrow(new IllegalStateException());
        when(processor.process(eq(b), any())).thenReturn(SubscriptionLapseProcessor.Result.EXPIRED);

        JobRunContext ctx = context();
        job.execute(ctx);

        verify(processor).process(eq(a), any()); // exactly once
        assertThat(ctx.failed()).isEqualTo(1);
        assertThat(ctx.changed()).isEqualTo(1);
    }
}
