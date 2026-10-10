package com.firstfood.lifecycle;

import com.firstfood.common.time.BusinessCalendar;
import com.firstfood.extension.ApplyExtensionResult;
import com.firstfood.extension.SystemExtensionService;
import com.firstfood.subscription.SubscriptionKey;
import com.firstfood.subscription.SubscriptionLifecycleService;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Extension evaluation: for every running DAY subscription sold with extension, asks the extension engine to apply
 * whatever absence has earned by now, as the SYSTEM. This keeps an owner from having to press Apply by hand and
 * keeps the renewal guard (409 EXTENSION_PENDING, decision B6) from blocking a renewal merely because nobody pressed it.
 *
 * Each subscription is its own transaction inside the engine; the engine is idempotent, so a second run (or a run
 * racing an owner's Apply) writes nothing new. A subscription that cannot be extended is an ordinary "unchanged", not a
 * failure. "Changed" means the engine wrote an event (an extension, or the over-applied report of decision B4).
 */
@Component
public class ExtensionEvaluationJob implements BackgroundJob {

    public static final String NAME = "extension-evaluation";
    private static final Logger log = LoggerFactory.getLogger(ExtensionEvaluationJob.class);
    private static final UUID FIRST = new UUID(0L, 0L);

    private final SubscriptionLifecycleService lifecycle;
    private final SystemExtensionService extensions;
    private final BusinessCalendar calendar;
    private final JobProperties properties;

    ExtensionEvaluationJob(SubscriptionLifecycleService lifecycle, SystemExtensionService extensions,
            BusinessCalendar calendar, JobProperties properties) {
        this.lifecycle = lifecycle;
        this.extensions = extensions;
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
        UUID after = FIRST;
        while (true) {
            List<SubscriptionKey> page = lifecycle.findExtensionCandidates(today, after, properties.batchSize());
            if (page.isEmpty()) {
                return;
            }
            for (SubscriptionKey key : page) {
                if (!context.keepGoing()) {
                    return;
                }
                after = key.subscriptionId();
                try {
                    ApplyExtensionResult result =
                            extensions.applyAutomaticallyIfExtendable(key.providerId(), key.subscriptionId());
                    if (result.event() != null) {
                        context.recordChanged();
                    } else {
                        context.recordUnchanged();
                    }
                } catch (RuntimeException e) {
                    log.warn("job={} subscription={} could not be processed: {}", NAME, key.subscriptionId(),
                            e.getClass().getSimpleName());
                    context.recordFailure(key.subscriptionId().toString(), e);
                }
            }
        }
    }
}
