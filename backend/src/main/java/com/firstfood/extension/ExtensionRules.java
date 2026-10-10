package com.firstfood.extension;

import com.firstfood.plan.ConsumptionType;
import com.firstfood.subscription.SubscriptionRef;
import com.firstfood.subscription.SubscriptionStatus;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The extension arithmetic. Pure logic (no Spring, no database) so it is unit-tested directly (rules.md Rule
 * 29.1/29.2); the service decides who may do what, this decides how many days.
 *
 * <h3>The rule, in the PRD's words</h3>
 * <pre>
 *   final expiry = minimum( calculated expiry + eligible extension , maximum allowed expiry )
 * </pre>
 * where, in this implementation:
 * <ul>
 *   <li><b>calculated expiry</b> = the BASE expiry (what was bought, before any extension);</li>
 *   <li><b>eligible extension</b> = the cumulative number of absent days that belong to an ABSENCE EPISODE (a run of
 *       consecutive absent days) of at least {@code minConsecutiveAbsenceDays} days that BEGAN while the customer was
 *       still entitled (its first day is on or before the current effective expiry). An episode may continue past
 *       that expiry and all of it counts (27 Oct - 10 Nov on a 30 Oct expiry = 15 days -> 14 Nov); an episode that
 *       begins after the expiry never counts - unless an earlier extension has since moved the expiry past its first
 *       day, which is what makes cascading extension work. A 5-day episode with a minimum of 2 is 5 eligible days; a
 *       lone day with a minimum of 2 is none;</li>
 *   <li><b>maximum allowed expiry</b> = start + calendar window - 1, counted from the ORIGINAL start and never
 *       reset by an extension (Rule 13.4); no window = no cap;</li>
 *   <li>an extension never shortens a subscription: the result is never earlier than the current effective expiry.</li>
 * </ul>
 *
 * <h3>Order independence</h3>
 * Because a new expiry can make another episode eligible, the evaluation repeats (boundary = the expiry reached so
 * far) until the expiry stops moving. The result is therefore the same whether the engine runs once or many times,
 * today or later: one run reaches the fixed point.
 *
 * <h3>Corrections never reverse an extension</h3>
 * When an absence that was counted is later overridden, eligible days can fall below the days already applied. The
 * expiry is never reduced; {@link Evaluation#overAppliedDays()} reports the gap (the service records it as an
 * OVER_APPLIED event).
 *
 * <h3>Why it is idempotent</h3>
 * The engine does not add "+5". It compares the TARGET (computed from all absence records every time) with what is
 * already applied ({@code effective - base}) and applies only the difference. Run twice, the second run finds a
 * difference of zero.
 *
 * <h3>Which days count</h3>
 * Only DECLARED absences (cancelled and overridden ones are not absences), only days from the subscription's start,
 * and only days strictly BEFORE today: a day counts once it is over, because until then the customer (A2) or the provider can
 * still change it. Consecutive means consecutive calendar dates.
 */
final class ExtensionRules {

    private ExtensionRules() {
    }

    /** A maximal stretch of consecutive absent days, both ends inclusive. */
    record Run(LocalDate from, LocalDate to) {
        int days() {
            return (int) ChronoUnit.DAYS.between(from, to) + 1;
        }
    }

    /**
     * The outcome of evaluating a subscription on a day.
     *
     * @param countedThrough the last day that could be counted (yesterday)
     * @param countedAbsentDays declared absent days (over) of episodes that began while the customer was entitled
     * @param qualifyingRuns the runs long enough to count
     * @param eligibleDays total days in {@code qualifyingRuns}
     * @param appliedSoFar what earlier extensions already added ({@code effective - base})
     * @param requestedDays {@code max(0, eligibleDays - appliedSoFar)}
     * @param calculatedExpiry base expiry + eligible days (before the cap)
     * @param newExpiry the final expiry; equal to the current effective expiry when there is nothing to add
     * @param appliedDays what applying would add ({@code newExpiry - effective}), after the cap
     * @param capped true when the maximum allowed expiry cut the request short
     * @param overAppliedDays {@code max(0, appliedSoFar - eligibleDays)}: days applied earlier that are no longer
     *     eligible (never taken back)
     */
    record Evaluation(
            LocalDate countedThrough,
            int countedAbsentDays,
            List<Run> qualifyingRuns,
            int eligibleDays,
            int appliedSoFar,
            int requestedDays,
            LocalDate calculatedExpiry,
            LocalDate newExpiry,
            int appliedDays,
            boolean capped,
            int overAppliedDays) {
    }

    /** Why the subscription cannot be extended at all, or empty. Pure function of the subscription's facts. */
    static Optional<ExtensionBlock> block(SubscriptionRef ref) {
        if (ref.consumptionType() != ConsumptionType.DAY) {
            return Optional.of(ExtensionBlock.NOT_SUPPORTED_FOR_MEAL);
        }
        if (!ref.extensionAllowed()) {
            return Optional.of(ExtensionBlock.NOT_ALLOWED_BY_POLICY);
        }
        if (ref.status() == SubscriptionStatus.CANCELLED) {
            return Optional.of(ExtensionBlock.SUBSCRIPTION_CANCELLED);
        }
        // A renewal expires its predecessor, so "renewed" is checked first for the clearer reason.
        if (ref.renewed()) {
            return Optional.of(ExtensionBlock.ALREADY_RENEWED);
        }
        if (ref.status() == SubscriptionStatus.EXPIRED) {
            return Optional.of(ExtensionBlock.SUBSCRIPTION_EXPIRED);
        }
        return Optional.empty();
    }

    /** Evaluates an extendable subscription (see {@link #block}) on {@code today}. */
    static Evaluation evaluate(SubscriptionRef ref, Collection<LocalDate> declaredAbsences, LocalDate today) {
        return evaluate(ref.startDate(), ref.baseExpiryDate(), ref.effectiveExpiryDate(), ref.maximumExpiryDate(),
                ref.minConsecutiveAbsenceDays(), declaredAbsences, today);
    }

    static Evaluation evaluate(LocalDate start, LocalDate base, LocalDate effective, LocalDate maximumExpiry,
            int minConsecutive, Collection<LocalDate> declaredAbsences, LocalDate today) {
        if (minConsecutive < 1) {
            throw new IllegalArgumentException("minConsecutive must be at least 1");
        }
        LocalDate countedThrough = today.minusDays(1);
        List<LocalDate> counted = declaredAbsences.stream()
                .filter(d -> !d.isBefore(start) && !d.isAfter(countedThrough))
                .distinct()
                .sorted()
                .toList();
        List<Run> episodes = runs(counted);

        // Fixed point: the boundary is the expiry reached so far; a longer expiry can bring another episode in range.
        LocalDate boundary = effective;
        List<Run> qualifying;
        int eligible;
        LocalDate calculated;
        while (true) {
            LocalDate b = boundary;
            qualifying = episodes.stream()
                    .filter(r -> r.days() >= minConsecutive && !r.from().isAfter(b))
                    .toList();
            eligible = qualifying.stream().mapToInt(Run::days).sum();
            calculated = base.plusDays(eligible);
            LocalDate capped = maximumExpiry == null || calculated.isBefore(maximumExpiry) ? calculated : maximumExpiry;
            // The PRD formula, plus "an extension never shortens": never earlier than what the customer already has.
            LocalDate next = capped.isBefore(boundary) ? boundary : capped;
            if (next.equals(boundary)) {
                break;
            }
            boundary = next;
        }
        LocalDate finalBoundary = boundary;
        int countedAbsent = episodes.stream().filter(r -> !r.from().isAfter(finalBoundary)).mapToInt(Run::days).sum();

        int appliedSoFar = (int) ChronoUnit.DAYS.between(base, effective);
        int requested = Math.max(0, eligible - appliedSoFar);
        int applied = (int) ChronoUnit.DAYS.between(effective, boundary);
        return new Evaluation(countedThrough, countedAbsent, List.copyOf(qualifying), eligible, appliedSoFar,
                requested, calculated, boundary, applied, applied < requested, Math.max(0, appliedSoFar - eligible));
    }

    /** Maximal runs of consecutive dates in an ascending, duplicate-free list. */
    static List<Run> runs(List<LocalDate> ascendingDates) {
        List<Run> runs = new ArrayList<>();
        LocalDate from = null;
        LocalDate previous = null;
        for (LocalDate d : ascendingDates) {
            if (previous != null && !d.equals(previous.plusDays(1))) {
                runs.add(new Run(from, previous));
                from = d;
            } else if (previous == null) {
                from = d;
            }
            previous = d;
        }
        if (previous != null) {
            runs.add(new Run(from, previous));
        }
        return runs;
    }
}
