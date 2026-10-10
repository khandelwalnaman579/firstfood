package com.firstfood.extension;

import com.firstfood.subscription.SubscriptionRef;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Where a subscription stands on extension today, computed by the backend so no client re-implements the rule.
 * Carries no price, phone or staff id, so the same view serves provider staff and the customer.
 *
 * {@code pendingExtensionDays} is what applying would add right now (after the cap). {@code canApply} is true exactly
 * when the subscription is extendable and that is more than zero.
 */
public record ExtensionStatusView(
        UUID subscriptionId,
        boolean extensionAllowed,
        Integer minConsecutiveAbsenceDays,
        LocalDate startDate,
        LocalDate baseExpiryDate,
        LocalDate effectiveExpiryDate,
        /** start + calendar window - 1; null = the policy has no window, so nothing caps an extension. */
        LocalDate maximumExpiryDate,
        LocalDate evaluatedOn,
        /** Absent days up to and including this date are counted; today and later are not (yet). */
        LocalDate countedThrough,
        ExtensionBlock blockedReason,
        int countedAbsentDays,
        List<Run> qualifyingRuns,
        int eligibleAbsenceDays,
        int appliedExtensionDays,
        int pendingExtensionDays,
        boolean capped,
        /** Where the expiry would land if the pending days were applied now. */
        LocalDate projectedExpiryDate,
        boolean canApply,
        /** More days applied than are eligible now: an absence that was counted has since been overridden. */
        boolean overApplied,
        /** Applied days that are no longer eligible (reported, never reversed). */
        int overAppliedDays,
        int eventCount) {

    public record Run(LocalDate from, LocalDate to, int days) {
    }

    static ExtensionStatusView of(SubscriptionRef ref, ExtensionBlock block, ExtensionRules.Evaluation eval,
            LocalDate today, int eventCount) {
        int appliedSoFar = ref.baseExpiryDate() == null || ref.effectiveExpiryDate() == null ? 0
                : (int) java.time.temporal.ChronoUnit.DAYS.between(ref.baseExpiryDate(), ref.effectiveExpiryDate());
        if (eval == null) {
            return new ExtensionStatusView(ref.subscriptionId(), ref.extensionAllowed(),
                    ref.minConsecutiveAbsenceDays(), ref.startDate(), ref.baseExpiryDate(),
                    ref.effectiveExpiryDate(), ref.maximumExpiryDate(), today, today.minusDays(1), block, 0, List.of(),
                    0, appliedSoFar, 0, false, ref.effectiveExpiryDate(), false, false, 0, eventCount);
        }
        return new ExtensionStatusView(ref.subscriptionId(), ref.extensionAllowed(), ref.minConsecutiveAbsenceDays(),
                ref.startDate(), ref.baseExpiryDate(), ref.effectiveExpiryDate(), ref.maximumExpiryDate(), today,
                eval.countedThrough(), null, eval.countedAbsentDays(),
                eval.qualifyingRuns().stream().map(r -> new Run(r.from(), r.to(), r.days())).toList(),
                eval.eligibleDays(), eval.appliedSoFar(), eval.appliedDays(), eval.capped(), eval.newExpiry(),
                eval.appliedDays() > 0, eval.overAppliedDays() > 0, eval.overAppliedDays(), eventCount);
    }
}
