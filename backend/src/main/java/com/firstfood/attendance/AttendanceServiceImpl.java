package com.firstfood.attendance;

import com.firstfood.common.time.BusinessCalendar;
import com.firstfood.membership.MemberSummary;
import com.firstfood.membership.MembershipLookupService;
import com.firstfood.plan.ConsumptionType;
import com.firstfood.provider.ProviderLookupService;
import com.firstfood.provideraccess.ProviderAccessService;
import com.firstfood.provideraccess.ProviderPermission;
import com.firstfood.subscription.SubscriptionLookupService;
import com.firstfood.subscription.SubscriptionRef;
import com.firstfood.subscription.SubscriptionStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use-case orchestration for attendance and absence. The date/policy rules live in {@link AttendanceRules}; the
 * ledger shape lives in the entities; this class decides who may do what and keeps the two tables telling one story.
 *
 * <h3>The model in one paragraph</h3>
 * Opt-out: no record = PRESENT. An absence is an {@link AbsenceRecord} (a declaration, never edited - it ends as
 * CANCELLED or OVERRIDDEN). Every outcome is also a row in the append-only {@link AttendanceRecord} ledger, and
 * exactly one row per (subscription, day) is current: a change supersedes it with a new row. A DECLARED absence is
 * exactly a current ABSENT row (V11 checks that at commit); Phase 9 counts DECLARED absences.
 *
 * <h3>Concurrency</h3>
 * Every write first takes the subscription row lock ({@code SELECT ... FOR UPDATE}), so a customer, the owner and
 * a cancellation on the same subscription run one after another and each re-reads fresh state. Lock order across the
 * product is provider row -> subscription row; attendance never takes the provider lock (it only reads the provider's
 * state), so a provider's customers never queue behind one another. The unique indexes in V11 remain the last defence.
 *
 * <h3>Who is subject to which rule</h3>
 * Customers: only their own subscriptions; a day in the past is never changeable; today only if the provider's policy
 * (read from the subscription's TERM SNAPSHOT, never the current plan) allows same-day absence and the cutoff has not
 * passed; a day provider staff decided is locked to staff. Provider staff are not bound by cutoff or past dates -
 * the owner correcting the notebook is the point of Rule 11.3 - but must give a reason, and cannot touch a cancelled
 * subscription or leave the subscription's days.
 */
@Service
public class AttendanceServiceImpl implements AttendanceService {

    private final ProviderAccessService accessService;
    private final ProviderLookupService providerLookup;
    private final MembershipLookupService membershipLookup;
    private final SubscriptionLookupService subscriptionLookup;
    private final AbsenceRecordRepository absences;
    private final AttendanceRecordRepository attendance;
    private final BusinessCalendar calendar;
    private final Clock clock;

    public AttendanceServiceImpl(
            ProviderAccessService accessService,
            ProviderLookupService providerLookup,
            MembershipLookupService membershipLookup,
            SubscriptionLookupService subscriptionLookup,
            AbsenceRecordRepository absences,
            AttendanceRecordRepository attendance,
            BusinessCalendar calendar,
            Clock clock) {
        this.accessService = accessService;
        this.providerLookup = providerLookup;
        this.membershipLookup = membershipLookup;
        this.subscriptionLookup = subscriptionLookup;
        this.absences = absences;
        this.attendance = attendance;
        this.calendar = calendar;
        this.clock = clock;
    }

    // ================================================================ provider staff: reads

    @Override
    @Transactional(readOnly = true)
    public DailySheetView dailySheet(UUID actorAccountId, UUID providerId, LocalDate date) {
        accessService.requirePermission(actorAccountId, providerId, ProviderPermission.ATTENDANCE_VIEW);
        LocalDate day = date != null ? date : calendar.today();

        List<SubscriptionRef> entitled = subscriptionLookup.findDayEntitledOn(providerId, day);
        Map<UUID, AttendanceRecord> current = new HashMap<>();
        attendance.findByProviderIdAndAttendanceDateAndCurrentRowTrue(providerId, day)
                .forEach(r -> current.put(r.getSubscriptionId(), r));
        Map<UUID, MemberSummary> members = membershipLookup.summarize(
                entitled.stream().map(SubscriptionRef::membershipId).collect(Collectors.toSet()));

        List<DailySheetView.Row> rows = new ArrayList<>();
        for (SubscriptionRef ref : entitled) {
            AttendanceDayView d = AttendanceDayView.of(day, current.get(ref.subscriptionId()));
            MemberSummary member = members.get(ref.membershipId());
            rows.add(new DailySheetView.Row(ref.subscriptionId(), ref.membershipId(), ref.personId(),
                    member != null ? member.fullName() : "(unknown)", ref.planName(), ref.startDate(),
                    ref.effectiveExpiryDate(), d.status(), d.source(), d.assumed(), d.absenceId(), d.reason()));
        }
        rows.sort(Comparator.comparing((DailySheetView.Row r) -> r.customerName().toLowerCase())
                .thenComparing(DailySheetView.Row::startDate)
                .thenComparing(DailySheetView.Row::subscriptionId));
        int absent = (int) rows.stream().filter(r -> r.status() == AttendanceStatus.ABSENT).count();
        return new DailySheetView(day, rows.size(), rows.size() - absent, absent, List.copyOf(rows));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AttendanceDayView> days(UUID actorAccountId, UUID providerId, UUID subscriptionId, LocalDate from,
            LocalDate to) {
        accessService.requirePermission(actorAccountId, providerId, ProviderPermission.ATTENDANCE_VIEW);
        SubscriptionRef ref = subscriptionLookup.find(providerId, subscriptionId)
                .orElseThrow(AttendanceException::subscriptionNotFound);
        requireDay(ref);
        LocalDate[] range = resolveRange(from, to, ref);
        if (range == null) {
            return List.of();
        }
        Map<LocalDate, AttendanceRecord> current = currentByDate(subscriptionId, range[0], range[1]);
        return AttendanceRules.days(range[0], range[1]).stream()
                .map(d -> AttendanceDayView.of(d, current.get(d)))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AttendanceHistoryEntry> history(UUID actorAccountId, UUID providerId, UUID subscriptionId,
            LocalDate date) {
        accessService.requirePermission(actorAccountId, providerId, ProviderPermission.ATTENDANCE_VIEW);
        SubscriptionRef ref = subscriptionLookup.find(providerId, subscriptionId)
                .orElseThrow(AttendanceException::subscriptionNotFound);
        requireDay(ref);
        List<AttendanceRecord> rows = attendance.findBySubscriptionIdAndAttendanceDateOrderByRecordedAtAscCreatedAtAsc(
                subscriptionId, date);
        return inLedgerOrder(rows).stream().map(AttendanceHistoryEntry::from).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AbsenceView> absences(UUID actorAccountId, UUID providerId, UUID subscriptionId) {
        accessService.requirePermission(actorAccountId, providerId, ProviderPermission.ATTENDANCE_VIEW);
        SubscriptionRef ref = subscriptionLookup.find(providerId, subscriptionId)
                .orElseThrow(AttendanceException::subscriptionNotFound);
        requireDay(ref);
        return absences.findBySubscriptionIdOrderByAbsenceDateDescDeclaredAtDesc(subscriptionId).stream()
                .map(AbsenceView::from)
                .toList();
    }

    // ================================================================ provider staff: the correction

    @Override
    @Transactional
    public AttendanceDayView correct(UUID actorAccountId, UUID providerId, UUID subscriptionId, LocalDate date,
            AttendanceStatus status, String reason) {
        // Authorize BEFORE touching (or locking) anything of the provider's (rules.md Rule 20.3).
        accessService.requirePermission(actorAccountId, providerId, ProviderPermission.ATTENDANCE_MANAGE);
        SubscriptionRef ref = subscriptionLookup.lock(providerId, subscriptionId)
                .orElseThrow(AttendanceException::subscriptionNotFound);
        requireOpenProvider(providerId);
        requireDay(ref);
        if (ref.status() == SubscriptionStatus.CANCELLED) {
            throw AttendanceException.subscriptionCancelled();
        }
        if (!AttendanceRules.inWindow(date, ref.startDate(), ref.effectiveExpiryDate())) {
            throw AttendanceException.dateOutsideSubscription(date, ref.startDate(), ref.effectiveExpiryDate());
        }
        String why = cleanReason(reason);
        if (why == null) {
            throw AttendanceException.reasonRequired();
        }

        Instant at = now();
        AttendanceRecord current = attendance
                .findBySubscriptionIdAndAttendanceDateAndCurrentRowTrue(subscriptionId, date).orElse(null);
        AbsenceRecord declared = absences
                .findBySubscriptionIdAndAbsenceDateAndStatus(subscriptionId, date, AbsenceStatus.DECLARED)
                .orElse(null);

        if (status == AttendanceStatus.ABSENT) {
            // Already absent (declared by the customer or by staff): nothing to change - a retry is harmless.
            if (declared == null) {
                recordAbsence(ref, date, AttendanceSource.OWNER_CORRECTION, actorAccountId, why, current, at);
            }
        } else if (declared != null) {
            // PRESENT over a declared absence: the absence is OVERRIDDEN and the day is restored by staff.
            declared.override(actorAccountId, why, at);
            guarded(() -> absences.saveAndFlush(declared));
            UUID supersedes = supersedeCurrent(current, at);
            guarded(() -> attendance.saveAndFlush(AttendanceRecord.present(subscriptionId, providerId, date,
                    declared.getId(), AttendanceSource.OWNER_CORRECTION, actorAccountId, why, supersedes, at)));
        }
        // PRESENT with no declared absence: the day is already present. No row - opt-out has no "present" facts.

        return AttendanceDayView.of(date, attendance
                .findBySubscriptionIdAndAttendanceDateAndCurrentRowTrue(subscriptionId, date).orElse(null));
    }

    // ================================================================ customer

    @Override
    @Transactional(readOnly = true)
    public List<MyAttendanceDayView> myDays(UUID accountId, UUID subscriptionId, LocalDate from, LocalDate to) {
        SubscriptionRef ref = subscriptionLookup.findOwned(membershipLookup.personIdsOf(accountId), subscriptionId)
                .orElseThrow(AttendanceException::subscriptionNotFound);
        requireDay(ref);
        LocalDate[] range = resolveRange(from, to, ref);
        if (range == null) {
            return List.of();
        }
        Map<LocalDate, AttendanceRecord> current = currentByDate(subscriptionId, range[0], range[1]);
        LocalDate today = calendar.today();
        LocalTime now = calendar.timeOfDay();
        return AttendanceRules.days(range[0], range[1]).stream()
                .map(d -> myDay(ref, d, current.get(d), today, now))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<MyAbsenceView> myAbsences(UUID accountId, UUID subscriptionId) {
        SubscriptionRef ref = subscriptionLookup.findOwned(membershipLookup.personIdsOf(accountId), subscriptionId)
                .orElseThrow(AttendanceException::subscriptionNotFound);
        requireDay(ref);
        return absences.findBySubscriptionIdOrderByAbsenceDateDescDeclaredAtDesc(subscriptionId).stream()
                .map(MyAbsenceView::from)
                .toList();
    }

    @Override
    @Transactional
    public DeclareAbsenceResult declare(UUID accountId, UUID subscriptionId, LocalDate fromDate, LocalDate toDate,
            String reason) {
        SubscriptionRef ref = lockOwned(accountId, subscriptionId);
        requireOpenProvider(ref.providerId());
        requireDay(ref);
        if (ref.status() == SubscriptionStatus.CANCELLED) {
            throw AttendanceException.subscriptionCancelled();
        }
        LocalDate end = toDate != null ? toDate : fromDate;
        AttendanceRules.validateSpan(fromDate, end, AttendanceRules.MAX_DECLARE_SPAN_DAYS);

        LocalDate last = lastEntitledDay(ref);
        LocalDate today = calendar.today();
        LocalTime now = calendar.timeOfDay();
        List<LocalDate> days = AttendanceRules.days(fromDate, end);

        Map<LocalDate, AbsenceRecord> declared = new HashMap<>();
        absences.findBySubscriptionIdAndAbsenceDateBetweenAndStatus(subscriptionId, fromDate, end,
                AbsenceStatus.DECLARED).forEach(a -> declared.put(a.getAbsenceDate(), a));
        Map<LocalDate, AttendanceRecord> current = currentByDate(subscriptionId, fromDate, end);

        // Validate EVERY day before writing any: all of the request or none of it.
        for (LocalDate d : days) {
            if (!AttendanceRules.inWindow(d, ref.startDate(), last)) {
                throw AttendanceException.dateOutsideSubscription(d, ref.startDate(), last);
            }
            if (declared.containsKey(d)) {
                continue; // already absent: a retried or overlapping request is not an error
            }
            requireCustomerMayChange(ref, d, current.get(d), today, now);
        }

        String why = cleanReason(reason);
        Instant at = now();
        List<MyAbsenceView> result = new ArrayList<>();
        boolean anyCreated = false;
        for (LocalDate d : days) {
            AbsenceRecord existing = declared.get(d);
            if (existing != null) {
                result.add(MyAbsenceView.from(existing));
                continue;
            }
            AbsenceRecord created = recordAbsence(ref, d, AttendanceSource.CUSTOMER, accountId, why, current.get(d),
                    at);
            result.add(MyAbsenceView.from(created));
            anyCreated = true;
        }
        return new DeclareAbsenceResult(List.copyOf(result), anyCreated);
    }

    @Override
    @Transactional
    public MyAbsenceView cancelAbsence(UUID accountId, UUID subscriptionId, UUID absenceId) {
        SubscriptionRef ref = lockOwned(accountId, subscriptionId);
        requireOpenProvider(ref.providerId());
        requireDay(ref);
        AbsenceRecord absence = absences.findByIdAndSubscriptionId(absenceId, subscriptionId)
                .orElseThrow(AttendanceException::absenceNotFound);

        if (absence.getStatus() == AbsenceStatus.CANCELLED) {
            return MyAbsenceView.from(absence); // already taken back: a retry changes nothing
        }
        if (absence.getStatus() == AbsenceStatus.OVERRIDDEN) {
            throw AttendanceException.absenceNotActive();
        }
        if (ref.status() == SubscriptionStatus.CANCELLED) {
            throw AttendanceException.subscriptionCancelled();
        }
        LocalDate date = absence.getAbsenceDate();
        AttendanceRecord current = attendance
                .findBySubscriptionIdAndAttendanceDateAndCurrentRowTrue(subscriptionId, date).orElse(null);
        requireCustomerMayChange(ref, date, current, calendar.today(), calendar.timeOfDay());

        Instant at = now();
        absence.cancel(accountId, at);
        guarded(() -> absences.saveAndFlush(absence));
        UUID supersedes = supersedeCurrent(current, at);
        // The default is restored: a SYSTEM/PRESENT row, attributed to the account that took the absence back.
        guarded(() -> attendance.saveAndFlush(AttendanceRecord.present(subscriptionId, ref.providerId(), date,
                absence.getId(), AttendanceSource.SYSTEM, accountId, null, supersedes, at)));
        return MyAbsenceView.from(absence);
    }

    // ================================================================ shared steps

    /**
     * Writes one absence and its ABSENT ledger row, superseding whatever the day currently says. The absence goes in
     * first (the ledger row references it); the old ledger row is superseded and flushed before the new one is
     * inserted, because the one-current-row-per-day index is checked per statement and Hibernate would otherwise run
     * the INSERT before the UPDATE.
     */
    private AbsenceRecord recordAbsence(SubscriptionRef ref, LocalDate date, AttendanceSource source, UUID actor,
            String reason, AttendanceRecord current, Instant at) {
        AbsenceRecord absence = guarded(() -> absences.saveAndFlush(AbsenceRecord.declare(ref.subscriptionId(),
                ref.providerId(), date, source, actor, reason, at)));
        UUID supersedes = supersedeCurrent(current, at);
        guarded(() -> attendance.saveAndFlush(AttendanceRecord.absent(ref.subscriptionId(), ref.providerId(), date,
                absence.getId(), source, actor, reason, supersedes, at)));
        return absence;
    }

    /** Marks the day's current row (if any) as replaced and flushes it; returns its id for the new row to cite. */
    private UUID supersedeCurrent(AttendanceRecord current, Instant at) {
        if (current == null) {
            return null;
        }
        current.supersede(at);
        guarded(() -> attendance.saveAndFlush(current));
        return current.getId();
    }

    /** The customer-side rules for changing {@code date}; throws the first one that forbids it. */
    private void requireCustomerMayChange(SubscriptionRef ref, LocalDate date, AttendanceRecord current,
            LocalDate today, LocalTime now) {
        Optional<ChangeBlock> block = customerBlock(ref, date, current, today, now);
        if (block.isPresent()) {
            throw AttendanceException.blocked(block.get(), date);
        }
    }

    /**
     * Why the customer cannot change {@code date} right now, or empty if they can. The ONE place this is decided: the
     * calendar the customer sees ({@link #myDay}) and the commands that enforce it use the same answer.
     * Order: subscription over, then the date/policy rules, then "provider staff set this day".
     */
    private Optional<ChangeBlock> customerBlock(SubscriptionRef ref, LocalDate date, AttendanceRecord current,
            LocalDate today, LocalTime now) {
        if (ref.status() == SubscriptionStatus.CANCELLED) {
            return Optional.of(ChangeBlock.SUBSCRIPTION_NOT_ACTIVE);
        }
        Optional<ChangeBlock> policy = AttendanceRules.customerChangeBlock(date, today, now,
                ref.sameDayAbsenceAllowed(), ref.absenceCutoffTime());
        if (policy.isPresent()) {
            return policy;
        }
        if (current != null && current.getSource() == AttendanceSource.OWNER_CORRECTION) {
            return Optional.of(ChangeBlock.PROVIDER_CORRECTION);
        }
        return Optional.empty();
    }

    private MyAttendanceDayView myDay(SubscriptionRef ref, LocalDate date, AttendanceRecord current, LocalDate today,
            LocalTime now) {
        AttendanceStatus status = current == null ? AttendanceStatus.PRESENT : current.getStatus();
        boolean providerSet = current != null && current.getSource() == AttendanceSource.OWNER_CORRECTION;
        ChangeBlock block = customerBlock(ref, date, current, today, now).orElse(null);
        return new MyAttendanceDayView(date, status, current == null,
                current == null ? null : current.getAbsenceId(), current == null ? null : current.getReason(),
                providerSet, block == null, block);
    }

    private SubscriptionRef lockOwned(UUID accountId, UUID subscriptionId) {
        return subscriptionLookup.lockOwned(membershipLookup.personIdsOf(accountId), subscriptionId)
                .orElseThrow(AttendanceException::subscriptionNotFound);
    }

    private void requireOpenProvider(UUID providerId) {
        if (providerLookup.intakeOf(providerId).closed()) {
            throw AttendanceException.providerClosed();
        }
    }

    /** MEAL granularity is an open decision (memory.md); until it is made attendance is DAY-only. */
    private static void requireDay(SubscriptionRef ref) {
        if (ref.consumptionType() != ConsumptionType.DAY) {
            throw AttendanceException.notSupportedForMeal();
        }
    }

    private LocalDate lastEntitledDay(SubscriptionRef ref) {
        LocalDate cancelledOn = ref.cancelledAt() == null ? null : calendar.dateOf(ref.cancelledAt());
        return AttendanceRules.lastEntitledDay(ref.effectiveExpiryDate(), cancelledOn);
    }

    /**
     * The inclusive range to show, clipped to the days the subscription was entitled, or null when none of it
     * overlaps. No range = {@link AttendanceRules#DEFAULT_CUSTOMER_VIEW_DAYS} days starting today (or the nearest
     * entitled day); one end given = that end plus the default length.
     */
    private LocalDate[] resolveRange(LocalDate from, LocalDate to, SubscriptionRef ref) {
        LocalDate start = ref.startDate();
        LocalDate last = lastEntitledDay(ref);
        if (last == null || last.isBefore(start)) {
            return null;
        }
        int span = AttendanceRules.DEFAULT_CUSTOMER_VIEW_DAYS - 1;
        LocalDate f = from;
        LocalDate t = to;
        if (f == null && t == null) {
            LocalDate today = calendar.today();
            f = today.isBefore(start) ? start : today.isAfter(last) ? last : today;
            t = f.plusDays(span);
        } else if (t == null) {
            t = f.plusDays(span);
        } else if (f == null) {
            f = t.minusDays(span);
        }
        AttendanceRules.validateSpan(f, t, AttendanceRules.MAX_VIEW_SPAN_DAYS);
        LocalDate clippedFrom = f.isBefore(start) ? start : f;
        LocalDate clippedTo = t.isAfter(last) ? last : t;
        return clippedFrom.isAfter(clippedTo) ? null : new LocalDate[] {clippedFrom, clippedTo};
    }

    private Map<LocalDate, AttendanceRecord> currentByDate(UUID subscriptionId, LocalDate from, LocalDate to) {
        Map<LocalDate, AttendanceRecord> map = new HashMap<>();
        attendance.findBySubscriptionIdAndAttendanceDateBetweenAndCurrentRowTrue(subscriptionId, from, to)
                .forEach(r -> map.put(r.getAttendanceDate(), r));
        return map;
    }

    /**
     * One day's rows form a chain (each correction supersedes the row before it). Walk it from the first row so the
     * history reads in the order things happened even when two rows share a timestamp.
     */
    private static List<AttendanceRecord> inLedgerOrder(List<AttendanceRecord> rows) {
        Map<UUID, AttendanceRecord> bySupersedes = new HashMap<>();
        AttendanceRecord first = null;
        for (AttendanceRecord r : rows) {
            if (r.getSupersedesId() == null) {
                first = r;
            } else {
                bySupersedes.put(r.getSupersedesId(), r);
            }
        }
        if (first == null) {
            return rows;
        }
        List<AttendanceRecord> ordered = new ArrayList<>();
        for (AttendanceRecord r = first; r != null && ordered.size() <= rows.size(); r = bySupersedes.get(r.getId())) {
            ordered.add(r);
        }
        return ordered.size() == rows.size() ? ordered : rows;
    }

    private static String cleanReason(String reason) {
        return reason == null || reason.isBlank() ? null : reason.trim();
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    /** Backstop: a unique-index hit the subscription row lock should make unreachable becomes a clean 409. */
    private static <T> T guarded(Supplier<T> write) {
        try {
            return write.get();
        } catch (DataIntegrityViolationException e) {
            String detail = String.valueOf(e.getMostSpecificCause().getMessage());
            if (detail.contains("uq_absence_one_declared_per_day")
                    || detail.contains("uq_attendance_one_current_per_day")) {
                throw AttendanceException.concurrentChange();
            }
            throw e;
        }
    }
}
