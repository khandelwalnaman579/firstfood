package com.firstfood.lifecycle;

import com.firstfood.extension.SystemExtensionService;
import com.firstfood.subscription.SubscriptionKey;
import com.firstfood.subscription.SubscriptionLifecycleService;
import com.firstfood.subscription.SubscriptionLookupService;
import com.firstfood.subscription.SubscriptionRef;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles ONE lapsed subscription in ONE transaction (rules.md Rule 20.1): lock it, give the extension engine its last
 * chance to extend it, and only then expire it if it is still over.
 *
 * <h3>Why the extension runs first (decision B8)</h3>
 * An EXPIRED subscription can never be extended, so expiring first would silently throw away days the customer earned
 * by being absent. Extending first can also move the expiry to today or later, in which case the subscription simply
 * stays ACTIVE. Both steps share this transaction and the subscription row lock, so a customer, an owner or a renewal
 * can never slip in between "extended" and "expired".
 *
 * <h3>Retry safety (Rule 22.1)</h3>
 * Re-checks under the lock whether the subscription is still lapsed, so a run that finds it already expired, extended,
 * renewed or cancelled changes nothing. The extension engine is idempotent by itself.
 *
 * The extension step uses the non-throwing {@code applyAutomaticallyIfExtendable}: a "cannot be extended" refusal is
 * an exception thrown through a transactional proxy, which would mark THIS transaction rollback-only and make it
 * impossible to expire a MEAL or no-extension subscription.
 */
@Service
public class SubscriptionLapseProcessor {

    /** What happened to the subscription. */
    public enum Result {
        /** Not lapsed any more (or gone): nothing to do. */
        SKIPPED,
        /** An earned extension moved its expiry to today or later; it stays ACTIVE. */
        EXTENDED,
        /** Expired (possibly after an extension that was not enough to keep it alive). */
        EXPIRED
    }

    private final SubscriptionLookupService lookup;
    private final SubscriptionLifecycleService lifecycle;
    private final SystemExtensionService extensions;

    SubscriptionLapseProcessor(SubscriptionLookupService lookup, SubscriptionLifecycleService lifecycle,
            SystemExtensionService extensions) {
        this.lookup = lookup;
        this.lifecycle = lifecycle;
        this.extensions = extensions;
    }

    @Transactional
    public Result process(SubscriptionKey key, LocalDate today) {
        SubscriptionRef ref = lookup.lock(key.providerId(), key.subscriptionId()).orElse(null);
        if (ref == null || !ref.isLapsedOn(today)) {
            return Result.SKIPPED;
        }
        boolean extended = extensions.applyAutomaticallyIfExtendable(key.providerId(), key.subscriptionId())
                .event() != null;
        if (lifecycle.expireIfLapsed(key.subscriptionId(), today)) {
            return Result.EXPIRED;
        }
        return extended ? Result.EXTENDED : Result.SKIPPED;
    }
}
