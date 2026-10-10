package com.firstfood.lifecycle;

import com.firstfood.common.time.BusinessCalendar;
import com.firstfood.subscription.SubscriptionKey;
import com.firstfood.subscription.SubscriptionLifecycleService;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Expiry processing: every ACTIVE subscription whose effective expiry is before today (business time zone) gets its
 * last extension chance and is then EXPIRED - see {@link SubscriptionLapseProcessor}. Until this job runs the
 * stored status may still say ACTIVE for a lapsed subscription; the phase shown to people is derived from dates, so
 * nothing is wrong in between (Phase 7 decision D3), but renewal, capacity and reports read the stored status.
 *
 * Scans in id order pages of {@code app.jobs.batch-size}; an item that fails is recorded and skipped for the rest of the
 * run, and picked up again by the next one.
 */
@Component
public class SubscriptionExpiryJob implements BackgroundJob {

    public static final String NAME = "subscription-expiry";
    private static final Logger log = LoggerFactory.getLogger(SubscriptionExpiryJob.class);
    private static final UUID FIRST = new UUID(0L, 0L);

    private final SubscriptionLifecycleService lifecycle;
    private final SubscriptionLapseProcessor processor;
    private final BusinessCalendar calendar;
    private final JobProperties properties;

    SubscriptionExpiryJob(SubscriptionLifecycleService lifecycle, SubscriptionLapseProcessor processor,
            BusinessCalendar calendar, JobProperties properties) {
        this.lifecycle = lifecycle;
        this.processor = processor;
        this.calendar = calendar;
        this.properties = properties;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void execute(JobRunContext context) {
        LocalDate today = calendar.today();
        int expired = 0;
        int extended = 0;
        UUID after = FIRST;
        while (true) {
            List<SubscriptionKey> page = lifecycle.findLapsed(today, after, properties.batchSize());
            if (page.isEmpty()) {
                break;
            }
            for (SubscriptionKey key : page) {
                if (!context.keepGoing()) {
                    return;
                }
                after = key.subscriptionId();
                try {
                    switch (processor.process(key, today)) {
                        case EXPIRED -> {
                            expired++;
                            context.recordChanged();
                        }
                        case EXTENDED -> {
                            extended++;
                            context.recordChanged();
                        }
                        case SKIPPED -> context.recordUnchanged();
                    }
                } catch (RuntimeException e) {
                    log.warn("job={} subscription={} could not be processed: {}", NAME, key.subscriptionId(),
                            e.getClass().getSimpleName());
                    context.recordFailure(key.subscriptionId().toString(), e);
                }
            }
        }
        if (expired > 0 || extended > 0) {
            log.info("job={} expired={} keptAliveByExtension={}", NAME, expired, extended);
        }
    }
}
