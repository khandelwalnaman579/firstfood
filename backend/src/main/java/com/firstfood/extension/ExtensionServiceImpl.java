package com.firstfood.extension;

import com.firstfood.attendance.AbsenceLookupService;
import com.firstfood.common.time.BusinessCalendar;
import com.firstfood.membership.MembershipLookupService;
import com.firstfood.provider.ProviderLookupService;
import com.firstfood.provideraccess.ProviderAccessService;
import com.firstfood.provideraccess.ProviderPermission;
import com.firstfood.subscription.SubscriptionExpiryService;
import com.firstfood.subscription.SubscriptionLookupService;
import com.firstfood.subscription.SubscriptionRef;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use-case orchestration for extension. The arithmetic lives in {@link ExtensionRules}; this class decides who may do
 * what, takes the locks, and keeps the event and the subscription's expiry telling one story.
 *
 * <h3>Apply = one transaction (rules.md Rule 20.2)</h3>
 * authorize -> lock the subscription row -> read its absences -> evaluate -> insert the {@code ExtensionEvent} ->
 * move the effective expiry. The event goes in first so V12's guard can check that it starts from the expiry the
 * subscription really has; V12 checks again at commit that expiry = base + the days all events applied.
 *
 * <h3>Concurrency and idempotency (Rules 21.1-21.3, 13.5)</h3>
 * Every apply takes the subscription row lock, as every absence write, cancellation and renewal does, so a customer
 * declaring an absence, the owner correcting a day, a renewal and two extension runs on one subscription happen one
 * after another, each re-reading the others' committed result (READ COMMITTED). The second of two identical runs
 * therefore evaluates against the already-extended expiry, finds nothing left to add and writes nothing. If something
 * ever raced past the lock, the unique keys on (subscription, sequence) and (subscription, eligible basis) refuse the
 * duplicate and the caller gets a clean 409.
 *
 * <h3>Idempotency key caveat</h3>
 * {@code uq_extension_subscription_basis} on (subscription, eligible_absence_days) is an idempotency guard under the
 * monotonic entitlement model, NOT the identity of a particular set of absences (different histories can have the
 * same count). The subscription row lock and V12's expiry invariant are the stronger correctness mechanisms.
 *
 * <h3>Authorization boundary</h3>
 * STAFF actor/provider membership is decided here, in the application layer, by {@code requirePermission} before
 * anything is written; {@code created_by} is audit attribution only (V12 checks its presence, not membership).
 *
 * Lock order across the product is provider row -> subscription row. Extension takes only the subscription row (like
 * attendance: it must not make a provider's customers queue), and only reads the provider's closed state.
 */
@Service
public class ExtensionServiceImpl implements ExtensionService, SystemExtensionService {

    private final ProviderAccessService accessService;
    private final ProviderLookupService providerLookup;
    private final MembershipLookupService membershipLookup;
    private final SubscriptionLookupService subscriptionLookup;
    private final SubscriptionExpiryService expiryService;
    private final AbsenceLookupService absenceLookup;
    private final ExtensionEventRepository events;
    private final BusinessCalendar calendar;

    public ExtensionServiceImpl(
            ProviderAccessService accessService,
            ProviderLookupService providerLookup,
            MembershipLookupService membershipLookup,
            SubscriptionLookupService subscriptionLookup,
            SubscriptionExpiryService expiryService,
            AbsenceLookupService absenceLookup,
            ExtensionEventRepository events,
            BusinessCalendar calendar) {
        this.accessService = accessService;
        this.providerLookup = providerLookup;
        this.membershipLookup = membershipLookup;
        this.subscriptionLookup = subscriptionLookup;
        this.expiryService = expiryService;
        this.absenceLookup = absenceLookup;
        this.events = events;
        this.calendar = calendar;
    }

    // ================================================================ provider staff

    @Override
    @Transactional(readOnly = true)
    public ExtensionStatusView status(UUID actorAccountId, UUID providerId, UUID subscriptionId) {
        accessService.requirePermission(actorAccountId, providerId, ProviderPermission.EXTENSION_VIEW);
        SubscriptionRef ref = subscriptionLookup.find(providerId, subscriptionId)
                .orElseThrow(ExtensionException::subscriptionNotFound);
        return statusOf(ref);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExtensionEventView> events(UUID actorAccountId, UUID providerId, UUID subscriptionId) {
        accessService.requirePermission(actorAccountId, providerId, ProviderPermission.EXTENSION_VIEW);
        subscriptionLookup.find(providerId, subscriptionId).orElseThrow(ExtensionException::subscriptionNotFound);
        return events.findBySubscriptionIdOrderBySequenceNoAsc(subscriptionId).stream()
                .map(ExtensionEventView::from)
                .toList();
    }

    @Override
    @Transactional
    public ApplyExtensionResult apply(UUID actorAccountId, UUID providerId, UUID subscriptionId) {
        // Authorize BEFORE locking or touching anything of the provider's (rules.md Rule 20.3).
        accessService.requirePermission(actorAccountId, providerId, ProviderPermission.EXTENSION_MANAGE);
        return applyNow(providerId, subscriptionId, ExtensionTrigger.STAFF, actorAccountId);
    }

    /** SYSTEM ONLY - see {@link SystemExtensionService}. Not part of {@link ExtensionService} on purpose. */
    @Override
    @Transactional
    public ApplyExtensionResult applyAutomatically(UUID providerId, UUID subscriptionId) {
        return applyNow(providerId, subscriptionId, ExtensionTrigger.SYSTEM, null);
    }

    // ================================================================ customer

    @Override
    @Transactional(readOnly = true)
    public ExtensionStatusView myStatus(UUID accountId, UUID subscriptionId) {
        return statusOf(owned(accountId, subscriptionId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<MyExtensionEventView> myEvents(UUID accountId, UUID subscriptionId) {
        owned(accountId, subscriptionId);
        return events.findBySubscriptionIdOrderBySequenceNoAsc(subscriptionId).stream()
                .map(MyExtensionEventView::from)
                .toList();
    }

    // ================================================================ the one place an extension is applied

    private ApplyExtensionResult applyNow(
            UUID providerId, UUID subscriptionId, ExtensionTrigger trigger, UUID actorAccountId) {
        SubscriptionRef ref = subscriptionLookup.lock(providerId, subscriptionId)
                .orElseThrow(ExtensionException::subscriptionNotFound);
        // A closed provider is read-only for staff; the system still reconciles earned entitlement (decision B9).
        if (trigger == ExtensionTrigger.STAFF && providerLookup.intakeOf(providerId).closed()) {
            throw ExtensionException.providerClosed();
        }
        Optional<ExtensionBlock> block = ExtensionRules.block(ref);
        if (block.isPresent()) {
            throw ExtensionException.blocked(block.get());
        }

        LocalDate today = calendar.today();
        ExtensionRules.Evaluation evaluation = ExtensionRules.evaluate(
                ref, absenceLookup.declaredAbsenceDates(subscriptionId), today);
        if (evaluation.appliedDays() == 0) {
            // Nothing (left) to add: a retry, a job that ran twice, no eligible run yet, or the maximum reached.
            // If corrections left more days applied than are eligible, say so once per distinct gap (B12); the expiry
            // is never touched.
            ExtensionEvent report = evaluation.overAppliedDays() > 0
                    ? reportOverApplied(ref, evaluation, trigger, actorAccountId, today) : null;
            return new ApplyExtensionResult(false, report == null ? null : ExtensionEventView.from(report),
                    statusOf(ref));
        }

        int sequence = (int) events.countBySubscriptionId(subscriptionId) + 1;
        ExtensionEvent event = ExtensionEvent.applied(subscriptionId, providerId, sequence, trigger, actorAccountId,
                today, evaluation, ref.effectiveExpiryDate(), ref.maximumExpiryDate());
        ExtensionEvent saved = guarded(() -> events.saveAndFlush(event));
        guarded(() -> {
            expiryService.moveEffectiveExpiry(subscriptionId, evaluation.newExpiry());
            return null;
        });

        SubscriptionRef after = subscriptionLookup.find(providerId, subscriptionId)
                .orElseThrow(ExtensionException::subscriptionNotFound);
        return new ApplyExtensionResult(true, ExtensionEventView.from(saved), statusOf(after));
    }

    /**
     * Records the over-applied gap unless the newest event already says exactly this (same eligible count and same
     * days applied): re-running changes nothing, a changed gap is a new fact. Returns null when nothing was written.
     */
    private ExtensionEvent reportOverApplied(SubscriptionRef ref, ExtensionRules.Evaluation evaluation,
            ExtensionTrigger trigger, UUID actorAccountId, LocalDate today) {
        Optional<ExtensionEvent> last = events.findTopBySubscriptionIdOrderBySequenceNoDesc(ref.subscriptionId());
        if (last.isPresent() && last.get().getKind() == ExtensionKind.OVER_APPLIED
                && last.get().getEligibleAbsenceDays() == evaluation.eligibleDays()
                && last.get().getOverAppliedDays() == evaluation.overAppliedDays()) {
            return null;
        }
        int sequence = (int) events.countBySubscriptionId(ref.subscriptionId()) + 1;
        ExtensionEvent event = ExtensionEvent.overApplied(ref.subscriptionId(), ref.providerId(), sequence, trigger,
                actorAccountId, today, evaluation, ref.effectiveExpiryDate(), ref.maximumExpiryDate());
        return guarded(() -> events.saveAndFlush(event));
    }

    // ================================================================ helpers

    private ExtensionStatusView statusOf(SubscriptionRef ref) {
        LocalDate today = calendar.today();
        Optional<ExtensionBlock> block = ExtensionRules.block(ref);
        ExtensionRules.Evaluation evaluation = block.isPresent() ? null
                : ExtensionRules.evaluate(ref, absenceLookup.declaredAbsenceDates(ref.subscriptionId()), today);
        int count = (int) events.countBySubscriptionId(ref.subscriptionId());
        return ExtensionStatusView.of(ref, block.orElse(null), evaluation, today, count);
    }

    private SubscriptionRef owned(UUID accountId, UUID subscriptionId) {
        return subscriptionLookup.findOwned(membershipLookup.personIdsOf(accountId), subscriptionId)
                .orElseThrow(ExtensionException::subscriptionNotFound);
    }

    /**
     * Backstop: a database refusal that the subscription row lock and the evaluation should make unreachable becomes
     * a clean 409 instead of a 500. (V12's commit-time consistency check cannot fire here - it only fails if this
     * class wrote an event and an expiry that disagree, which would be a bug.)
     */
    private static <T> T guarded(java.util.function.Supplier<T> write) {
        try {
            return write.get();
        } catch (DataIntegrityViolationException e) {
            String detail = String.valueOf(e.getMostSpecificCause().getMessage());
            if (detail.contains("subscription_no_overlapping_active")) {
                throw ExtensionException.overlapsAnotherSubscription();
            }
            if (detail.contains("uq_extension_subscription") || detail.contains("is not the next one")
                    || detail.contains("currently expires on")) {
                throw ExtensionException.concurrentChange();
            }
            if (detail.contains("has been renewed")) {
                throw ExtensionException.blocked(ExtensionBlock.ALREADY_RENEWED);
            }
            throw e;
        }
    }
}
