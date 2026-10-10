package com.firstfood.attendance;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstfood.AbstractIntegrationTest;
import com.firstfood.identity.TestCapturingOtpSender;
import com.firstfood.identity.dto.AuthTokensResponse;
import com.firstfood.identity.dto.OtpRequestRequest;
import com.firstfood.identity.dto.OtpVerifyRequest;
import com.firstfood.identity.dto.ProfileResponse;
import com.firstfood.membership.CustomerView;
import com.firstfood.plan.PlanView;
import com.firstfood.provider.dto.ProviderResponse;
import com.firstfood.subscription.SubscriptionView;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Phase 8 HTTP-level tests: attendance and absence. Real Postgres + Redis, real OTP login.
 * Phones are generated as +91987657xxxx (earlier phases use ...650xxxx to ...656xxxx); the database is a shared
 * singleton container, so do not reuse that prefix elsewhere.
 *
 * "Today" is read in the business time zone exactly as the service does (Asia/Kolkata by default). The same-day
 * cutoff tests use the two ends of the day (00:00 = always passed, 23:59:59 = not yet) so they do not depend on when
 * the suite runs; the only theoretical flake is a run that straddles 23:59:59.
 *
 * A subscription that has already lapsed cannot be sold, so the "ended" test backdates a freshly sold one through
 * SQL with the identity trigger switched off (the same technique as the Phase 7 renewal tests).
 */
class AttendanceApiIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger PHONE_COUNTER = new AtomicInteger(1);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");

    @Autowired
    RestTestClient restClient;

    @Autowired
    TestCapturingOtpSender otpSender;

    @Autowired
    JdbcTemplate jdbc;

    private static LocalDate today() {
        return LocalDate.now(BUSINESS_ZONE);
    }

    // ------------------------------------------------------------ authentication

    @Test
    void attendanceEndpointsRequireAuthentication() {
        UUID id = UUID.randomUUID();
        String provider = "/api/v1/providers/" + id;
        String mine = "/api/v1/subscriptions/" + id;
        String date = today().toString();
        for (String[] call : new String[][] {
                {"GET", provider + "/attendance"},
                {"GET", provider + "/subscriptions/" + id + "/attendance"},
                {"GET", provider + "/subscriptions/" + id + "/attendance/" + date + "/history"},
                {"PUT", provider + "/subscriptions/" + id + "/attendance/" + date},
                {"GET", provider + "/subscriptions/" + id + "/absences"},
                {"GET", mine + "/attendance"}, {"GET", mine + "/absence"}, {"POST", mine + "/absence"},
                {"POST", mine + "/absence/" + id + "/cancel"}}) {
            Object body = call[0].equals("GET") ? null : Map.of();
            assertThat(statusOf(send(call[0], call[1], null, body))).as(call[0] + " " + call[1]).isEqualTo(401);
        }
    }

    // ------------------------------------------------------------ opt-out: nothing recorded = present

    @Test
    void anActiveSubscriptionIsAssumedPresentAndNothingIsStoredForIt() {
        Fixture f = fixture();
        SubscriptionView sub = sell(f, today());

        DailySheetView sheet = sheet(f.owner, f.provider, today());
        assertThat(sheet.date()).isEqualTo(today());
        assertThat(sheet.expected()).isEqualTo(1);
        assertThat(sheet.present()).isEqualTo(1);
        assertThat(sheet.absent()).isZero();
        DailySheetView.Row row = sheet.rows().get(0);
        assertThat(row.subscriptionId()).isEqualTo(sub.id());
        assertThat(row.customerName()).isEqualTo(f.customerName);
        assertThat(row.planName()).isEqualTo("Monthly");
        assertThat(row.status()).isEqualTo(AttendanceStatus.PRESENT);
        assertThat(row.source()).isEqualTo(AttendanceSource.SYSTEM);
        assertThat(row.assumed()).isTrue();

        // Opt-out: the rows above were derived, not stored.
        assertThat(ledgerRows(sub.id())).isZero();
        assertThat(absenceRows(sub.id())).isZero();
    }

    @Test
    void theDailySheetDefaultsToTodayListsEveryEntitledCustomerSortedByNameAndCountsThem() {
        Fixture f = fixture();
        Customer second = addCustomer(f.owner, f.provider);
        Customer third = addCustomer(f.owner, f.provider);
        PlanView plan = f.plan;
        SubscriptionView s1 = sell(f.owner, f.provider, f.membership, plan.id(), today());
        SubscriptionView s2 = sell(f.owner, f.provider, second.membershipId(), plan.id(), today());
        // The third customer starts tomorrow: not entitled today.
        sell(f.owner, f.provider, third.membershipId(), plan.id(), today().plusDays(1));

        declareOk(second.account(), s2.id(), today().plusDays(2), null, null);
        // Today's sheet: both entitled customers present; the third is not on it.
        DailySheetView todaySheet = send("GET", "/api/v1/providers/" + f.provider + "/attendance",
                f.owner.accessToken(), null).expectStatus().isOk().expectBody(DailySheetView.class).returnResult()
                .getResponseBody();
        assertThat(todaySheet.date()).isEqualTo(today());
        assertThat(todaySheet.expected()).isEqualTo(2);
        assertThat(todaySheet.rows()).extracting(DailySheetView.Row::customerName)
                .isSortedAccordingTo(String.CASE_INSENSITIVE_ORDER);
        assertThat(todaySheet.rows()).extracting(DailySheetView.Row::subscriptionId)
                .containsExactlyInAnyOrder(s1.id(), s2.id());

        // Two days ahead: three customers entitled, one of them has said they will not eat.
        DailySheetView later = sheet(f.owner, f.provider, today().plusDays(2));
        assertThat(later.expected()).isEqualTo(3);
        assertThat(later.absent()).isEqualTo(1);
        assertThat(later.present()).isEqualTo(2);
        assertThat(later.rows().stream().filter(r -> r.status() == AttendanceStatus.ABSENT).toList())
                .singleElement().satisfies(r -> {
                    assertThat(r.subscriptionId()).isEqualTo(s2.id());
                    assertThat(r.source()).isEqualTo(AttendanceSource.CUSTOMER);
                    assertThat(r.assumed()).isFalse();
                });
        assertThat(third.membershipId()).isNotNull();
    }

    @Test
    void theDailySheetCarriesNoPriceNoPhoneAndNoSaleTerms() {
        Fixture f = fixture();
        sell(f, today());
        String json = send("GET", "/api/v1/providers/" + f.provider + "/attendance", f.owner.accessToken(), null)
                .expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        assertThat(json).doesNotContain("price", "2500", "phone", f.customer.phone(), "terms", "currency");
    }

    // ------------------------------------------------------------ the customer declares

    @Test
    void aCustomerDeclaresAFutureDayAndEveryViewAgrees() {
        Fixture f = fixture();
        SubscriptionView sub = sell(f, today());
        LocalDate day = today().plusDays(3);

        DeclareAbsenceResult result = declareOk(f.customer, sub.id(), day, null, "Going home");
        assertThat(result.anyCreated()).isTrue();
        assertThat(result.absences()).singleElement().satisfies(a -> {
            assertThat(a.date()).isEqualTo(day);
            assertThat(a.status()).isEqualTo(AbsenceStatus.DECLARED);
            assertThat(a.source()).isEqualTo(AttendanceSource.CUSTOMER);
            assertThat(a.reason()).isEqualTo("Going home");
            assertThat(a.subscriptionId()).isEqualTo(sub.id());
        });

        // The customer's own calendar.
        MyAttendanceDayView mine = myDays(f.customer, sub.id(), day, day).get(0);
        assertThat(mine.status()).isEqualTo(AttendanceStatus.ABSENT);
        assertThat(mine.assumed()).isFalse();
        assertThat(mine.providerSet()).isFalse();
        assertThat(mine.changeable()).isTrue();
        assertThat(mine.absenceId()).isEqualTo(result.absences().get(0).id());
        // The provider's daily sheet and the subscription's days.
        assertThat(sheet(f.owner, f.provider, day).rows().get(0).status()).isEqualTo(AttendanceStatus.ABSENT);
        AttendanceDayView staffDay = days(f.owner, f.provider, sub.id(), day, day).get(0);
        assertThat(staffDay.status()).isEqualTo(AttendanceStatus.ABSENT);
        assertThat(staffDay.source()).isEqualTo(AttendanceSource.CUSTOMER);
        assertThat(staffDay.recordedBy()).isEqualTo(f.customer.id());
        // Other days are still assumed present.
        assertThat(days(f.owner, f.provider, sub.id(), day.plusDays(1), day.plusDays(1)).get(0).assumed()).isTrue();

        // The ledger: one absence, one current ABSENT row, one row in the day's history.
        assertThat(declaredAbsences(sub.id())).isEqualTo(1);
        assertThat(history(f.owner, f.provider, sub.id(), day)).singleElement().satisfies(h -> {
            assertThat(h.status()).isEqualTo(AttendanceStatus.ABSENT);
            assertThat(h.source()).isEqualTo(AttendanceSource.CUSTOMER);
            assertThat(h.current()).isTrue();
            assertThat(h.supersedesId()).isNull();
            assertThat(h.reason()).isEqualTo("Going home");
        });
        assertLedgerConsistent(sub.id());
    }

    @Test
    void declaringTheSameDayAgainIsAHarmlessRetry() {
        Fixture f = fixture();
        SubscriptionView sub = sell(f, today());
        LocalDate day = today().plusDays(2);
        DeclareAbsenceResult first = declareOk(f.customer, sub.id(), day, null, null);

        DeclareAbsenceResult retry = declareSpec(f.customer, sub.id(), day, null, null).expectStatus().isOk()
                .expectBody(DeclareAbsenceResult.class).returnResult().getResponseBody();
        assertThat(retry.anyCreated()).isFalse();
        assertThat(retry.absences()).extracting(MyAbsenceView::id)
                .containsExactly(first.absences().get(0).id());
        assertThat(absenceRows(sub.id())).isEqualTo(1);
        assertThat(ledgerRows(sub.id(), day)).isEqualTo(1);
    }

    @Test
    void aRangeDeclaresEveryDayAndOnlyCreatesTheMissingOnes() {
        Fixture f = fixture();
        SubscriptionView sub = sell(f, today());
        LocalDate from = today().plusDays(2);

        declareOk(f.customer, sub.id(), from.plusDays(1), null, null); // the 2nd day of the range is already out
        DeclareAbsenceResult range = declareOk(f.customer, sub.id(), from, from.plusDays(3), "Wedding");
        assertThat(range.anyCreated()).isTrue();
        assertThat(range.absences()).extracting(MyAbsenceView::date)
                .containsExactly(from, from.plusDays(1), from.plusDays(2), from.plusDays(3));
        assertThat(absenceRows(sub.id())).isEqualTo(4);
        assertThat(declaredAbsences(sub.id())).isEqualTo(4);
        assertThat(sheet(f.owner, f.provider, from.plusDays(3)).absent()).isEqualTo(1);
        assertLedgerConsistent(sub.id());
    }

    @Test
    void aRangeIsAllOrNothing() {
        Fixture f = fixture();
        SubscriptionView sub = sell(f, today());
        LocalDate lastDay = sub.effectiveExpiryDate();

        // The last two days are fine but the third day runs past the subscription: nothing at all is recorded.
        assertError(declareSpec(f.customer, sub.id(), lastDay.minusDays(1), lastDay.plusDays(1), null),
                HttpStatus.BAD_REQUEST, "DATE_OUTSIDE_SUBSCRIPTION");
        assertThat(absenceRows(sub.id())).isZero();
        assertThat(ledgerRows(sub.id())).isZero();

        // Same for a range that starts today under a no-same-day policy.
        assertError(declareSpec(f.customer, sub.id(), today(), today().plusDays(2), null),
                HttpStatus.CONFLICT, "SAME_DAY_ABSENCE_NOT_ALLOWED");
        assertThat(absenceRows(sub.id())).isZero();
    }

    @Test
    void rangeLengthAndOrderAreValidated() {
        Fixture f = fixture();
        SubscriptionView sub = sell(f, today());
        LocalDate from = today().plusDays(1);
        // 32 days > the 31-day limit of one request.
        assertError(declareSpec(f.customer, sub.id(), from, from.plusDays(31), null),
                HttpStatus.BAD_REQUEST, "DATE_RANGE_INVALID");
        assertError(declareSpec(f.customer, sub.id(), from.plusDays(2), from, null),
                HttpStatus.BAD_REQUEST, "DATE_RANGE_INVALID");
        // Missing from date, malformed date and an over-long reason are validation errors.
        assertError(send("POST", "/api/v1/subscriptions/" + sub.id() + "/absence", f.customer.accessToken(),
                Map.of("reason", "x")), HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        assertError(send("POST", "/api/v1/subscriptions/" + sub.id() + "/absence", f.customer.accessToken(),
                Map.of("fromDate", "tomorrow")), HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        assertError(declareSpec(f.customer, sub.id(), from, null, "x".repeat(501)),
                HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        assertError(send("GET", "/api/v1/subscriptions/" + sub.id() + "/attendance?from=nope",
                f.customer.accessToken(), null), HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        assertThat(absenceRows(sub.id())).isZero();
    }

    // ------------------------------------------------------------ the policy decides, from the snapshot

    @Test
    void todayIsRefusedWhenTheProviderDoesNotAllowSameDayAbsence() {
        Fixture f = fixture(); // the default plan: same-day absence NOT allowed
        SubscriptionView sub = sell(f, today());
        assertError(declareSpec(f.customer, sub.id(), today(), null, null),
                HttpStatus.CONFLICT, "SAME_DAY_ABSENCE_NOT_ALLOWED");
        MyAttendanceDayView todayView = myDays(f.customer, sub.id(), today(), today()).get(0);
        assertThat(todayView.changeable()).isFalse();
        assertThat(todayView.lockedReason()).isEqualTo(ChangeBlock.SAME_DAY_NOT_ALLOWED);
        assertThat(myDays(f.customer, sub.id(), today().plusDays(1), today().plusDays(1)).get(0).changeable())
                .isTrue();
    }

    @Test
    void todayIsOpenWhenSameDayIsAllowedWithoutACutoff() {
        Fixture f = fixture(sameDayPlan(null));
        SubscriptionView sub = sell(f, today());
        DeclareAbsenceResult result = declareOk(f.customer, sub.id(), today(), null, null);
        assertThat(result.absences().get(0).date()).isEqualTo(today());
        assertThat(sheet(f.owner, f.provider, today()).absent()).isEqualTo(1);
        // The same rule lets the customer take it back today.
        cancelAbsence(f.customer, sub.id(), result.absences().get(0).id()).expectStatus().isOk();
        assertThat(sheet(f.owner, f.provider, today()).absent()).isZero();
    }

    @Test
    void aCutoffThatHasPassedBlocksTodayAndOneNotYetReachedDoesNot() {
        Fixture passed = fixture(sameDayPlan("00:00"));
        SubscriptionView late = sell(passed, today());
        assertError(declareSpec(passed.customer, late.id(), today(), null, null),
                HttpStatus.CONFLICT, "ABSENCE_CUTOFF_PASSED");
        assertThat(myDays(passed.customer, late.id(), today(), today()).get(0).lockedReason())
                .isEqualTo(ChangeBlock.CUTOFF_PASSED);
        // A future day is never subject to the cutoff.
        declareOk(passed.customer, late.id(), today().plusDays(1), null, null);

        Fixture open = fixture(sameDayPlan("23:59:59"));
        SubscriptionView early = sell(open, today());
        declareOk(open.customer, early.id(), today(), null, null);
    }

    @Test
    void aProviderEditingItsPlanDoesNotChangeTheRulesOfAnExistingSubscription() {
        Fixture f = fixture(); // sold under: same-day NOT allowed
        SubscriptionView existing = sell(f, today());

        // The owner now allows same-day absence on the plan (policy version 2).
        send("PUT", "/api/v1/providers/" + f.provider + "/plans/" + f.plan.id(), f.owner.accessToken(),
                dayPlanBody("Monthly", sameDayPolicy(null))).expectStatus().isOk();

        // The existing subscription keeps the terms it was sold under (rules.md 14.3, 15.2) ...
        assertError(declareSpec(f.customer, existing.id(), today(), null, null),
                HttpStatus.CONFLICT, "SAME_DAY_ABSENCE_NOT_ALLOWED");
        // ... and a subscription sold afterwards sees the new policy.
        Customer later = addCustomer(f.owner, f.provider);
        SubscriptionView fresh = sell(f.owner, f.provider, later.membershipId(), f.plan.id(), today());
        declareOk(later.account(), fresh.id(), today(), null, null);
    }

    @Test
    void daysOutsideTheSubscriptionAreRejected() {
        Fixture f = fixture();
        SubscriptionView sub = sell(f, today().plusDays(5));
        assertError(declareSpec(f.customer, sub.id(), today().plusDays(4), null, null),
                HttpStatus.BAD_REQUEST, "DATE_OUTSIDE_SUBSCRIPTION");
        assertError(declareSpec(f.customer, sub.id(), sub.effectiveExpiryDate().plusDays(1), null, null),
                HttpStatus.BAD_REQUEST, "DATE_OUTSIDE_SUBSCRIPTION");
        // The first and the last day are inside.
        declareOk(f.customer, sub.id(), sub.startDate(), null, null);
        declareOk(f.customer, sub.id(), sub.effectiveExpiryDate(), null, null);
        // Staff are held to the same window.
        assertError(correct(f.owner, f.provider, sub.id(), sub.startDate().minusDays(1), "ABSENT", "why"),
                HttpStatus.BAD_REQUEST, "DATE_OUTSIDE_SUBSCRIPTION");
    }

    // ------------------------------------------------------------ cancelling an absence

    @Test
    void aCustomerTakesAnAbsenceBackAndTheHistoryKeepsEverything() {
        Fixture f = fixture();
        SubscriptionView sub = sell(f, today());
        LocalDate day = today().plusDays(4);
        MyAbsenceView declared = declareOk(f.customer, sub.id(), day, null, "Unwell").absences().get(0);

        MyAbsenceView cancelled = cancelAbsence(f.customer, sub.id(), declared.id()).expectStatus().isOk()
                .expectBody(MyAbsenceView.class).returnResult().getResponseBody();
        assertThat(cancelled.id()).isEqualTo(declared.id());
        assertThat(cancelled.status()).isEqualTo(AbsenceStatus.CANCELLED);
        assertThat(cancelled.cancelledAt()).isNotNull();

        // The day is PRESENT again - by an explicit SYSTEM row, not by deleting anything.
        AttendanceDayView after = days(f.owner, f.provider, sub.id(), day, day).get(0);
        assertThat(after.status()).isEqualTo(AttendanceStatus.PRESENT);
        assertThat(after.source()).isEqualTo(AttendanceSource.SYSTEM);
        assertThat(after.assumed()).isFalse();
        assertThat(sheet(f.owner, f.provider, day).absent()).isZero();
        List<AttendanceHistoryEntry> history = history(f.owner, f.provider, sub.id(), day);
        assertThat(history).extracting(AttendanceHistoryEntry::status)
                .containsExactly(AttendanceStatus.ABSENT, AttendanceStatus.PRESENT);
        assertThat(history).extracting(AttendanceHistoryEntry::current).containsExactly(false, true);
        assertThat(history.get(1).supersedesId()).isEqualTo(history.get(0).id());
        assertThat(history.get(0).supersededAt()).isNotNull();
        assertThat(declaredAbsences(sub.id())).isZero();

        // Cancelling again is a retry: nothing changes, still 200.
        cancelAbsence(f.customer, sub.id(), declared.id()).expectStatus().isOk();
        assertThat(ledgerRows(sub.id(), day)).isEqualTo(2);

        // The customer can declare the day again: a NEW absence, the cancelled one stays.
        MyAbsenceView again = declareOk(f.customer, sub.id(), day, null, "Unwell again").absences().get(0);
        assertThat(again.id()).isNotEqualTo(declared.id());
        assertThat(absenceRows(sub.id())).isEqualTo(2);
        assertThat(history(f.owner, f.provider, sub.id(), day)).hasSize(3);
        assertThat(absencesAsStaff(f.owner, f.provider, sub.id())).extracting(AbsenceView::status)
                .containsExactlyInAnyOrder(AbsenceStatus.CANCELLED, AbsenceStatus.DECLARED);
        assertThat(myAbsences(f.customer, sub.id())).hasSize(2);
        assertLedgerConsistent(sub.id());
    }

    @Test
    void aCustomerCannotCancelAnAbsenceThePolicyHasClosed() {
        Fixture f = fixture(sameDayPlan("00:00"));
        SubscriptionView sub = sell(f, today());
        // Declared for tomorrow, then made "today" by moving the clock is not possible - instead insert the
        // absence for today directly (as if it had been declared before the cutoff) and try to cancel it.
        UUID absenceId = insertDeclaredAbsenceForToday(sub, f.customer.id());
        assertError(cancelAbsence(f.customer, sub.id(), absenceId), HttpStatus.CONFLICT, "ABSENCE_CUTOFF_PASSED");
        assertThat(declaredAbsences(sub.id())).isEqualTo(1);
    }

    @Test
    void aPastDayCanNeitherBeDeclaredNorCancelledByTheCustomer() {
        Fixture f = fixture();
        SubscriptionView sub = sell(f, today().minusDays(5));
        assertError(declareSpec(f.customer, sub.id(), today().minusDays(2), null, null),
                HttpStatus.CONFLICT, "ABSENCE_DATE_IN_PAST");
        MyAttendanceDayView past = myDays(f.customer, sub.id(), today().minusDays(2), today().minusDays(2)).get(0);
        assertThat(past.changeable()).isFalse();
        assertThat(past.lockedReason()).isEqualTo(ChangeBlock.PAST);

        // Staff may record the past (the notebook correction), and the customer then cannot undo it.
        correct(f.owner, f.provider, sub.id(), today().minusDays(2), "ABSENT", "Told me on WhatsApp")
                .expectStatus().isOk();
        UUID absenceId = absencesAsStaff(f.owner, f.provider, sub.id()).get(0).id();
        assertError(cancelAbsence(f.customer, sub.id(), absenceId), HttpStatus.CONFLICT, "ABSENCE_DATE_IN_PAST");
    }

    // ------------------------------------------------------------ provider staff decide

    @Test
    void theOwnerRecordsAnAbsenceOnTheCustomersBehalfAndTheCustomerCannotUndoIt() {
        Fixture f = fixture();
        SubscriptionView sub = sell(f, today());
        LocalDate day = today().plusDays(2);

        AttendanceDayView result = correct(f.owner, f.provider, sub.id(), day, "ABSENT", "Called me, not eating")
                .expectStatus().isOk().expectBody(AttendanceDayView.class).returnResult().getResponseBody();
        assertThat(result.status()).isEqualTo(AttendanceStatus.ABSENT);
        assertThat(result.source()).isEqualTo(AttendanceSource.OWNER_CORRECTION);
        assertThat(result.recordedBy()).isEqualTo(f.owner.id());
        assertThat(result.reason()).isEqualTo("Called me, not eating");
        AbsenceView absence = absencesAsStaff(f.owner, f.provider, sub.id()).get(0);
        assertThat(absence.status()).isEqualTo(AbsenceStatus.DECLARED);
        assertThat(absence.source()).isEqualTo(AttendanceSource.OWNER_CORRECTION);
        assertThat(absence.declaredBy()).isEqualTo(f.owner.id());

        // The customer sees THAT the provider set it, without who - and cannot change it.
        MyAttendanceDayView mine = myDays(f.customer, sub.id(), day, day).get(0);
        assertThat(mine.status()).isEqualTo(AttendanceStatus.ABSENT);
        assertThat(mine.providerSet()).isTrue();
        assertThat(mine.changeable()).isFalse();
        assertThat(mine.lockedReason()).isEqualTo(ChangeBlock.PROVIDER_CORRECTION);
        assertError(cancelAbsence(f.customer, sub.id(), absence.id()),
                HttpStatus.CONFLICT, "ATTENDANCE_LOCKED_BY_PROVIDER");
        String myJson = send("GET", "/api/v1/subscriptions/" + sub.id() + "/absence", f.customer.accessToken(), null)
                .expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        assertThat(myJson).doesNotContain(f.owner.id().toString());
        // Declaring a day that is already absent is just a retry, not an error.
        declareSpec(f.customer, sub.id(), day, null, null).expectStatus().isOk();
        assertLedgerConsistent(sub.id());
    }

    @Test
    void theOwnerOverridesACustomersAbsenceAndTheOverrideIsTheLastWordForTheCustomer() {
        Fixture f = fixture();
        SubscriptionView sub = sell(f, today());
        LocalDate day = today().plusDays(2);
        MyAbsenceView declared = declareOk(f.customer, sub.id(), day, null, "Maybe").absences().get(0);

        AttendanceDayView result = correct(f.owner, f.provider, sub.id(), day, "PRESENT", "Customer is eating after all")
                .expectStatus().isOk().expectBody(AttendanceDayView.class).returnResult().getResponseBody();
        assertThat(result.status()).isEqualTo(AttendanceStatus.PRESENT);
        assertThat(result.source()).isEqualTo(AttendanceSource.OWNER_CORRECTION);
        assertThat(result.assumed()).isFalse();

        AbsenceView overridden = absencesAsStaff(f.owner, f.provider, sub.id()).get(0);
        assertThat(overridden.id()).isEqualTo(declared.id());
        assertThat(overridden.status()).isEqualTo(AbsenceStatus.OVERRIDDEN);
        assertThat(overridden.overriddenBy()).isEqualTo(f.owner.id());
        assertThat(overridden.overrideReason()).isEqualTo("Customer is eating after all");
        assertThat(overridden.overriddenAt()).isNotNull();
        assertThat(history(f.owner, f.provider, sub.id(), day)).extracting(AttendanceHistoryEntry::source)
                .containsExactly(AttendanceSource.CUSTOMER, AttendanceSource.OWNER_CORRECTION);

        // An overridden absence is final: the customer can neither cancel it nor declare the day over again.
        assertError(cancelAbsence(f.customer, sub.id(), declared.id()), HttpStatus.CONFLICT, "ABSENCE_NOT_ACTIVE");
        assertError(declareSpec(f.customer, sub.id(), day, null, null),
                HttpStatus.CONFLICT, "ATTENDANCE_LOCKED_BY_PROVIDER");
        MyAttendanceDayView view = myDays(f.customer, sub.id(), day, day).get(0);
        assertThat(view.providerSet()).isTrue();
        assertThat(view.changeable()).isFalse();

        // The owner can still change their own mind: a new, owner-recorded absence.
        correct(f.owner, f.provider, sub.id(), day, "ABSENT", "Changed again").expectStatus().isOk();
        assertThat(absenceRows(sub.id())).isEqualTo(2);
        assertThat(declaredAbsences(sub.id())).isEqualTo(1);
        assertLedgerConsistent(sub.id());
    }

    @Test
    void aCorrectionNeedsAReasonAndRepeatingItChangesNothing() {
        Fixture f = fixture();
        SubscriptionView sub = sell(f, today());
        LocalDate day = today().plusDays(2);
        for (Object badBody : List.of(Map.of("status", "ABSENT"), Map.of("status", "ABSENT", "reason", "  "),
                Map.of("reason", "why"), Map.of("status", "MAYBE", "reason", "why"),
                Map.of("status", "ABSENT", "reason", "x".repeat(501)))) {
            assertError(send("PUT", attendanceUrl(f.provider, sub.id(), day), f.owner.accessToken(), badBody),
                    HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        }
        assertThat(absenceRows(sub.id())).isZero();

        correct(f.owner, f.provider, sub.id(), day, "ABSENT", "First").expectStatus().isOk();
        correct(f.owner, f.provider, sub.id(), day, "ABSENT", "Same again").expectStatus().isOk();
        assertThat(absenceRows(sub.id())).isEqualTo(1);
        assertThat(ledgerRows(sub.id(), day)).isEqualTo(1);
        // PRESENT on a day that is already present is a no-op too: opt-out stores no "present" facts.
        LocalDate other = today().plusDays(5);
        AttendanceDayView stillPresent = correct(f.owner, f.provider, sub.id(), other, "PRESENT", "Confirming")
                .expectStatus().isOk().expectBody(AttendanceDayView.class).returnResult().getResponseBody();
        assertThat(stillPresent.status()).isEqualTo(AttendanceStatus.PRESENT);
        assertThat(ledgerRows(sub.id(), other)).isZero();
    }

    @Test
    void ownerCorrectionsReachBackIntoAnEndedSubscription() {
        Fixture f = fixture();
        SubscriptionView sub = sell(f, today());
        backdate(sub.id(), 40); // started 40 days ago, ended 11 days ago, still stored ACTIVE (no job yet)
        LocalDate start = today().minusDays(40);
        LocalDate inside = today().minusDays(20);

        // The customer's calendar: every day locked as past.
        List<MyAttendanceDayView> mine = myDays(f.customer, sub.id(), start, start.plusDays(3));
        assertThat(mine).hasSize(4);
        assertThat(mine).allSatisfy(d -> {
            assertThat(d.changeable()).isFalse();
            assertThat(d.lockedReason()).isEqualTo(ChangeBlock.PAST);
        });
        assertError(declareSpec(f.customer, sub.id(), inside, null, null), HttpStatus.CONFLICT, "ABSENCE_DATE_IN_PAST");
        assertError(declareSpec(f.customer, sub.id(), today().minusDays(5), null, null),
                HttpStatus.BAD_REQUEST, "DATE_OUTSIDE_SUBSCRIPTION");

        // The owner may still correct a day inside it, and not a day outside it.
        correct(f.owner, f.provider, sub.id(), inside, "ABSENT", "Found in the notebook").expectStatus().isOk();
        assertThat(sheet(f.owner, f.provider, inside).absent()).isEqualTo(1);
        assertError(correct(f.owner, f.provider, sub.id(), today().minusDays(5), "ABSENT", "x"),
                HttpStatus.BAD_REQUEST, "DATE_OUTSIDE_SUBSCRIPTION");
        // It has ended: today's sheet no longer lists it.
        assertThat(sheet(f.owner, f.provider, today()).expected()).isZero();
    }

    // ------------------------------------------------------------ cancelled and meal subscriptions

    @Test
    void aCancelledSubscriptionIsFrozenButItsHistoryStaysReadable() {
        Fixture f = fixture();
        SubscriptionView sub = sell(f, today().minusDays(3));
        LocalDate day = today().plusDays(1);
        declareOk(f.customer, sub.id(), day, null, null);

        send("POST", "/api/v1/providers/" + f.provider + "/subscriptions/" + sub.id() + "/cancel",
                f.owner.accessToken(), Map.of("reason", "Left town")).expectStatus().isOk();

        assertError(declareSpec(f.customer, sub.id(), day.plusDays(1), null, null),
                HttpStatus.CONFLICT, "SUBSCRIPTION_NOT_ACTIVE");
        assertError(correct(f.owner, f.provider, sub.id(), day.plusDays(1), "ABSENT", "x"),
                HttpStatus.CONFLICT, "SUBSCRIPTION_NOT_ACTIVE");
        UUID absenceId = myAbsences(f.customer, sub.id()).get(0).id();
        assertError(cancelAbsence(f.customer, sub.id(), absenceId), HttpStatus.CONFLICT, "SUBSCRIPTION_NOT_ACTIVE");

        // It was in force yesterday, not today (it is not entitled on the day it was cancelled).
        assertThat(sheet(f.owner, f.provider, today().minusDays(1)).rows()).extracting(DailySheetView.Row::subscriptionId)
                .contains(sub.id());
        assertThat(sheet(f.owner, f.provider, today()).rows()).extracting(DailySheetView.Row::subscriptionId)
                .doesNotContain(sub.id());
        // The customer's calendar stops at the last entitled day and is locked.
        List<MyAttendanceDayView> mine = myDays(f.customer, sub.id(), null, null);
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).date()).isEqualTo(today().minusDays(1));
        assertThat(mine.get(0).lockedReason()).isEqualTo(ChangeBlock.SUBSCRIPTION_NOT_ACTIVE);
        // History is still readable.
        assertThat(absencesAsStaff(f.owner, f.provider, sub.id())).hasSize(1);
    }

    @Test
    void mealSubscriptionsAreNotTrackedByAttendanceInThisRelease() {
        Fixture f = fixture();
        PlanView meals = createPlan(f.owner, f.provider, mealPlanBody("20 meals"));
        Customer c = addCustomer(f.owner, f.provider);
        SubscriptionView sub = sell(f.owner, f.provider, c.membershipId(), meals.id(), today());

        assertError(send("GET", "/api/v1/subscriptions/" + sub.id() + "/attendance", c.account().accessToken(), null),
                HttpStatus.CONFLICT, "ATTENDANCE_NOT_SUPPORTED");
        assertError(declareSpec(c.account(), sub.id(), today().plusDays(1), null, null),
                HttpStatus.CONFLICT, "ATTENDANCE_NOT_SUPPORTED");
        assertError(correct(f.owner, f.provider, sub.id(), today().plusDays(1), "ABSENT", "x"),
                HttpStatus.CONFLICT, "ATTENDANCE_NOT_SUPPORTED");
        assertThat(sheet(f.owner, f.provider, today()).rows()).extracting(DailySheetView.Row::subscriptionId)
                .doesNotContain(sub.id());
        // The database says the same thing, independently of the application.
        UUID anyOwner = f.owner.id();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("""
                insert into absence_record (id, subscription_id, provider_id, absence_date, source, declared_by, declared_at)
                values (gen_random_uuid(), ?, ?, ?, 'OWNER_CORRECTION', ?, now())
                """, sub.id(), f.provider, today().plusDays(1), anyOwner)).isInstanceOf(DataAccessException.class);
    }

    @Test
    void aClosedProviderIsReadOnlyForAttendanceToo() {
        Fixture f = fixture();
        SubscriptionView sub = sell(f, today());
        LocalDate day = today().plusDays(2);
        MyAbsenceView declared = declareOk(f.customer, sub.id(), day, null, null).absences().get(0);
        send("PATCH", "/api/v1/providers/" + f.provider, f.owner.accessToken(),
                Map.of("status", "CLOSED", "closureReason", "Shut down")).expectStatus().isOk();

        assertError(declareSpec(f.customer, sub.id(), day.plusDays(1), null, null),
                HttpStatus.CONFLICT, "PROVIDER_CLOSED");
        assertError(cancelAbsence(f.customer, sub.id(), declared.id()), HttpStatus.CONFLICT, "PROVIDER_CLOSED");
        assertError(correct(f.owner, f.provider, sub.id(), day.plusDays(1), "ABSENT", "x"),
                HttpStatus.CONFLICT, "PROVIDER_CLOSED");
        // Reading still works.
        assertThat(sheet(f.owner, f.provider, day).absent()).isEqualTo(1);
        assertThat(myAbsences(f.customer, sub.id())).hasSize(1);
    }

    // ------------------------------------------------------------ who may do what

    @Test
    void aWorkerTakesAttendanceButSeesNoPricesAndStillCannotSellOrSeeSubscriptions() {
        Fixture f = fixture();
        Account worker = login();
        assignRole(f.owner, f.provider, worker, "WORKER");
        SubscriptionView sub = sell(f, today());
        LocalDate day = today().plusDays(2);

        assertThat(sheet(worker, f.provider, today()).expected()).isEqualTo(1);
        AttendanceDayView recorded = correct(worker, f.provider, sub.id(), day, "ABSENT", "Told the kitchen")
                .expectStatus().isOk().expectBody(AttendanceDayView.class).returnResult().getResponseBody();
        assertThat(recorded.recordedBy()).isEqualTo(worker.id());
        assertThat(history(worker, f.provider, sub.id(), day)).hasSize(1);
        assertThat(absencesAsStaff(worker, f.provider, sub.id())).hasSize(1);
        assertThat(days(worker, f.provider, sub.id(), day, day)).hasSize(1);
        // No price anywhere in what a worker can read here.
        String json = send("GET", "/api/v1/providers/" + f.provider + "/subscriptions/" + sub.id() + "/absences",
                worker.accessToken(), null).expectStatus().isOk().expectBody(String.class).returnResult()
                .getResponseBody();
        assertThat(json).doesNotContain("price", "2500");
        // ... while the subscription itself stays off limits.
        assertError(send("GET", "/api/v1/providers/" + f.provider + "/subscriptions/" + sub.id(),
                worker.accessToken(), null), HttpStatus.FORBIDDEN, "INSUFFICIENT_PERMISSION");
    }

    @Test
    void aManagerTakesAttendanceToo() {
        Fixture f = fixture();
        Account manager = login();
        assignRole(f.owner, f.provider, manager, "MANAGER");
        SubscriptionView sub = sell(f, today());
        correct(manager, f.provider, sub.id(), today().plusDays(1), "ABSENT", "Phone call").expectStatus().isOk();
        assertThat(sheet(manager, f.provider, today().plusDays(1)).absent()).isEqualTo(1);
    }

    @Test
    void strangersAndOtherProvidersOwnersAreKeptOut() {
        Fixture a = fixture();
        Fixture b = fixture();
        SubscriptionView subA = sell(a, today());
        UUID absenceA = declareOk(a.customer, subA.id(), today().plusDays(2), null, null).absences().get(0).id();
        String denied = "PROVIDER_NOT_FOUND";

        // The owner of provider B has no role on provider A: its id in the URL gets nothing.
        assertError(send("GET", "/api/v1/providers/" + a.provider + "/attendance", b.owner.accessToken(), null),
                HttpStatus.NOT_FOUND, denied);
        assertError(correct(b.owner, a.provider, subA.id(), today().plusDays(1), "ABSENT", "x"),
                HttpStatus.NOT_FOUND, denied);
        assertError(send("GET", "/api/v1/providers/" + a.provider + "/subscriptions/" + subA.id() + "/absences",
                b.owner.accessToken(), null), HttpStatus.NOT_FOUND, denied);
        // Their own provider, someone else's subscription id: not found as well (no IDOR).
        assertError(correct(b.owner, b.provider, subA.id(), today().plusDays(1), "ABSENT", "x"),
                HttpStatus.NOT_FOUND, "SUBSCRIPTION_NOT_FOUND");
        assertError(send("GET", "/api/v1/providers/" + b.provider + "/subscriptions/" + subA.id() + "/attendance",
                b.owner.accessToken(), null), HttpStatus.NOT_FOUND, "SUBSCRIPTION_NOT_FOUND");
        // A customer is not staff: a customer's token on the provider endpoints is just a stranger.
        assertError(send("GET", "/api/v1/providers/" + a.provider + "/attendance", a.customer.accessToken(), null),
                HttpStatus.NOT_FOUND, denied);
        assertError(correct(a.customer, a.provider, subA.id(), today().plusDays(1), "ABSENT", "x"),
                HttpStatus.NOT_FOUND, denied);

        // Customer side: another customer cannot see, declare for or cancel someone else's subscription.
        String hidden = "SUBSCRIPTION_NOT_FOUND";
        assertError(send("GET", "/api/v1/subscriptions/" + subA.id() + "/attendance", b.customer.accessToken(), null),
                HttpStatus.NOT_FOUND, hidden);
        assertError(send("GET", "/api/v1/subscriptions/" + subA.id() + "/absence", b.customer.accessToken(), null),
                HttpStatus.NOT_FOUND, hidden);
        assertError(declareSpec(b.customer, subA.id(), today().plusDays(1), null, null), HttpStatus.NOT_FOUND, hidden);
        assertError(cancelAbsence(b.customer, subA.id(), absenceA), HttpStatus.NOT_FOUND, hidden);
        // Nor can an absence id be used through a subscription it does not belong to.
        SubscriptionView subB = sell(b, today());
        assertError(cancelAbsence(b.customer, subB.id(), absenceA), HttpStatus.NOT_FOUND, "ABSENCE_NOT_FOUND");
        assertThat(declaredAbsences(subA.id())).isEqualTo(1);
    }

    // ------------------------------------------------------------ the database as the last defence

    @Test
    void theLedgerCannotBeRewrittenOrDeletedBehindTheApplicationsBack() {
        Fixture f = fixture();
        SubscriptionView sub = sell(f, today());
        LocalDate day = today().plusDays(2);
        declareOk(f.customer, sub.id(), day, null, "Trip");

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> jdbc.update("update attendance_record set status = 'PRESENT' where subscription_id = ?",
                        sub.id())).isInstanceOf(DataAccessException.class);
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> jdbc.update("delete from attendance_record where subscription_id = ?", sub.id()))
                .isInstanceOf(DataAccessException.class);
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> jdbc.update("delete from absence_record where subscription_id = ?", sub.id()))
                .isInstanceOf(DataAccessException.class);
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> jdbc.update("update absence_record set absence_date = absence_date + 1 where subscription_id = ?",
                        sub.id())).isInstanceOf(DataAccessException.class);
        assertThat(declaredAbsences(sub.id())).isEqualTo(1);
        assertLedgerConsistent(sub.id());
    }

    // ------------------------------------------------------------ concurrency

    @Test
    void concurrentDeclarationsOfTheSameDayCreateExactlyOneAbsence() throws Exception {
        Fixture f = fixture();
        SubscriptionView sub = sell(f, today());
        LocalDate day = today().plusDays(3);

        List<Integer> statuses = runConcurrently(
                () -> statusOf(declareSpec(f.customer, sub.id(), day, null, "a")),
                () -> statusOf(declareSpec(f.customer, sub.id(), day, null, "b")),
                () -> statusOf(declareSpec(f.customer, sub.id(), day, null, "c")),
                () -> statusOf(declareSpec(f.customer, sub.id(), day, null, "d")));

        // One request wins (201); the others find its absence after the lock is released (200).
        assertThat(statuses).containsOnly(201, 200);
        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
        assertThat(declaredAbsences(sub.id())).isEqualTo(1);
        assertThat(absenceRows(sub.id())).isEqualTo(1);
        assertThat(ledgerRows(sub.id(), day)).isEqualTo(1);
        assertLedgerConsistent(sub.id());
    }

    @Test
    void aCustomerAndTheOwnerRecordingTheSameDayAtTheSameTimeLeaveOneAbsence() throws Exception {
        Fixture f = fixture();
        SubscriptionView sub = sell(f, today());
        LocalDate day = today().plusDays(3);

        List<Integer> statuses = runConcurrently(
                () -> statusOf(declareSpec(f.customer, sub.id(), day, null, "mine")),
                () -> statusOf(correct(f.owner, f.provider, sub.id(), day, "ABSENT", "theirs")));

        assertThat(statuses).allMatch(s -> s == 200 || s == 201);
        assertThat(declaredAbsences(sub.id())).isEqualTo(1);
        assertThat(ledgerRows(sub.id(), day)).isEqualTo(1);
        assertLedgerConsistent(sub.id());
    }

    @Test
    void aCancelAndAnOverrideAtTheSameTimeEndInOneConsistentState() throws Exception {
        Fixture f = fixture();
        SubscriptionView sub = sell(f, today());
        LocalDate day = today().plusDays(3);
        MyAbsenceView declared = declareOk(f.customer, sub.id(), day, null, null).absences().get(0);

        List<Integer> statuses = runConcurrently(
                () -> statusOf(cancelAbsence(f.customer, sub.id(), declared.id())),
                () -> statusOf(correct(f.owner, f.provider, sub.id(), day, "PRESENT", "Eating")));

        // Whoever got the lock first decided; the other saw the result: either both 200, or the customer lost (409).
        assertThat(statuses).allMatch(s -> s == 200 || s == 409);
        assertThat(declaredAbsences(sub.id())).isZero();
        assertThat(ledgerRows(sub.id(), day)).isEqualTo(2);
        assertLedgerConsistent(sub.id());
    }

    @Test
    void attendanceChangesRaceACancellationWithoutLeavingAHalfWrittenDay() throws Exception {
        Fixture f = fixture();
        SubscriptionView sub = sell(f, today());
        LocalDate day = today().plusDays(2);

        List<Integer> statuses = runConcurrently(
                () -> statusOf(declareSpec(f.customer, sub.id(), day, null, null)),
                () -> statusOf(send("POST", "/api/v1/providers/" + f.provider + "/subscriptions/" + sub.id() + "/cancel",
                        f.owner.accessToken(), Map.of())));

        // Declare first -> 201 then cancel 200; cancel first -> declare is refused (409). Never a 500.
        assertThat(statuses.get(1)).isEqualTo(200);
        assertThat(statuses.get(0)).isIn(201, 409);
        assertLedgerConsistent(sub.id());
        assertThat(declaredAbsences(sub.id())).isEqualTo(statuses.get(0) == 201 ? 1 : 0);
    }

    // ------------------------------------------------------------ helpers

    private record Account(UUID id, String phone, String accessToken) {
    }

    private record Customer(Account account, UUID membershipId, String fullName) {
    }

    /** An owned provider with one DAY plan and one member (with the member's own login). */
    private record Fixture(Account owner, UUID provider, PlanView plan, Account customer, UUID membership,
            String customerName) {
    }

    private Fixture fixture() {
        return fixture(null);
    }

    /** @param planBody the plan to create; null = the pilot's 30-day plan, same-day absence NOT allowed */
    private Fixture fixture(Map<String, Object> planBody) {
        Account owner = login();
        UUID provider = createProvider(owner);
        PlanView plan = createPlan(owner, provider, planBody != null ? planBody : dayPlanBody("Monthly", policyMap(false, null)));
        Customer c = addCustomer(owner, provider);
        return new Fixture(owner, provider, plan, c.account(), c.membershipId(), c.fullName());
    }

    private Map<String, Object> sameDayPlan(String cutoff) {
        return dayPlanBody("Monthly", sameDayPolicy(cutoff));
    }

    private static Map<String, Object> policyMap(boolean sameDay, String cutoff) {
        Map<String, Object> m = new HashMap<>();
        m.put("extensionAllowed", false);
        m.put("sameDayAbsenceAllowed", sameDay);
        if (cutoff != null) {
            m.put("absenceCutoffTime", cutoff);
        }
        return m;
    }

    private static Map<String, Object> sameDayPolicy(String cutoff) {
        return policyMap(true, cutoff);
    }

    private static Map<String, Object> dayPlanBody(String name, Map<String, Object> policy) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("consumptionType", "DAY");
        m.put("durationDays", 30);
        m.put("price", new BigDecimal("2500"));
        m.put("policy", policy);
        return m;
    }

    private static Map<String, Object> mealPlanBody(String name) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("consumptionType", "MEAL");
        m.put("mealQuantity", 20);
        m.put("price", new BigDecimal("900"));
        m.put("policy", policyMap(false, null));
        return m;
    }

    private Customer addCustomer(Account owner, UUID provider) {
        Account account = login();
        String name = "Customer " + account.phone().substring(account.phone().length() - 4);
        CustomerView view = send("POST", "/api/v1/providers/" + provider + "/customers", owner.accessToken(),
                Map.of("phone", account.phone(), "fullName", name))
                .expectStatus().isCreated().expectBody(CustomerView.class).returnResult().getResponseBody();
        return new Customer(account, view.membershipId(), name);
    }

    private Account login() {
        String phone = String.format("+91987657%04d", PHONE_COUNTER.getAndIncrement());
        restClient.post().uri("/api/v1/auth/otp/request")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new OtpRequestRequest(phone))
                .exchange().expectStatus().isEqualTo(HttpStatus.ACCEPTED);

        AuthTokensResponse tokens = restClient.post().uri("/api/v1/auth/otp/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new OtpVerifyRequest(phone, otpSender.lastOtpFor(phone)))
                .exchange().expectStatus().isOk()
                .expectBody(AuthTokensResponse.class).returnResult().getResponseBody();

        ProfileResponse profile = restClient.get().uri("/api/v1/me")
                .header("Authorization", "Bearer " + tokens.accessToken())
                .exchange().expectStatus().isOk()
                .expectBody(ProfileResponse.class).returnResult().getResponseBody();
        return new Account(profile.accountId(), phone, tokens.accessToken());
    }

    private UUID createProvider(Account owner) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "Attendance Mess");
        body.put("providerType", "MESS");
        body.put("addressLine", "12 MP Nagar Zone 1");
        body.put("locality", "MP Nagar");
        body.put("city", "Bhopal");
        body.put("pincode", "462011");
        return send("POST", "/api/v1/providers", owner.accessToken(), body)
                .expectStatus().isCreated().expectBody(ProviderResponse.class).returnResult()
                .getResponseBody().id();
    }

    private void assignRole(Account owner, UUID providerId, Account target, String role) {
        send("POST", "/api/v1/providers/" + providerId + "/roles", owner.accessToken(),
                Map.of("phone", target.phone(), "role", role)).expectStatus().isCreated();
    }

    private PlanView createPlan(Account owner, UUID provider, Map<String, Object> body) {
        return send("POST", "/api/v1/providers/" + provider + "/plans", owner.accessToken(), body)
                .expectStatus().isCreated().expectBody(PlanView.class).returnResult().getResponseBody();
    }

    /** Sells the fixture's plan to the fixture's customer, starting on {@code start}. */
    private SubscriptionView sell(Fixture f, LocalDate start) {
        return sell(f.owner, f.provider, f.membership, f.plan.id(), start);
    }

    private SubscriptionView sell(Account actor, UUID provider, UUID membership, UUID planId, LocalDate start) {
        Map<String, Object> body = new HashMap<>();
        body.put("membershipId", membership);
        body.put("planId", planId);
        body.put("startDate", start.toString());
        return send("POST", "/api/v1/providers/" + provider + "/subscriptions", actor.accessToken(), body)
                .expectStatus().isCreated().expectBody(SubscriptionView.class).returnResult().getResponseBody();
    }

    // ---- attendance calls

    private static String attendanceUrl(UUID provider, UUID subscription, LocalDate date) {
        return "/api/v1/providers/" + provider + "/subscriptions/" + subscription + "/attendance/" + date;
    }

    private RestTestClient.ResponseSpec declareSpec(Account who, UUID subscription, LocalDate from, LocalDate to,
            String reason) {
        Map<String, Object> body = new HashMap<>();
        body.put("fromDate", from.toString());
        if (to != null) {
            body.put("toDate", to.toString());
        }
        if (reason != null) {
            body.put("reason", reason);
        }
        return send("POST", "/api/v1/subscriptions/" + subscription + "/absence", who.accessToken(), body);
    }

    /** Declares and expects "something was created" (201). */
    private DeclareAbsenceResult declareOk(Account who, UUID subscription, LocalDate from, LocalDate to,
            String reason) {
        return declareSpec(who, subscription, from, to, reason).expectStatus().isCreated()
                .expectBody(DeclareAbsenceResult.class).returnResult().getResponseBody();
    }

    private RestTestClient.ResponseSpec cancelAbsence(Account who, UUID subscription, UUID absenceId) {
        return send("POST", "/api/v1/subscriptions/" + subscription + "/absence/" + absenceId + "/cancel",
                who.accessToken(), Map.of());
    }

    private List<MyAttendanceDayView> myDays(Account who, UUID subscription, LocalDate from, LocalDate to) {
        String query = from == null ? "" : "?from=" + from + "&to=" + to;
        MyAttendanceDayView[] body = send("GET", "/api/v1/subscriptions/" + subscription + "/attendance" + query,
                who.accessToken(), null).expectStatus().isOk().expectBody(MyAttendanceDayView[].class).returnResult()
                .getResponseBody();
        return body == null ? List.of() : List.of(body);
    }

    private List<MyAbsenceView> myAbsences(Account who, UUID subscription) {
        MyAbsenceView[] body = send("GET", "/api/v1/subscriptions/" + subscription + "/absence", who.accessToken(),
                null).expectStatus().isOk().expectBody(MyAbsenceView[].class).returnResult().getResponseBody();
        return body == null ? List.of() : List.of(body);
    }

    private DailySheetView sheet(Account who, UUID provider, LocalDate date) {
        return send("GET", "/api/v1/providers/" + provider + "/attendance?date=" + date, who.accessToken(), null)
                .expectStatus().isOk().expectBody(DailySheetView.class).returnResult().getResponseBody();
    }

    private List<AttendanceDayView> days(Account who, UUID provider, UUID subscription, LocalDate from, LocalDate to) {
        AttendanceDayView[] body = send("GET", "/api/v1/providers/" + provider + "/subscriptions/" + subscription
                + "/attendance?from=" + from + "&to=" + to, who.accessToken(), null)
                .expectStatus().isOk().expectBody(AttendanceDayView[].class).returnResult().getResponseBody();
        return body == null ? List.of() : List.of(body);
    }

    private List<AttendanceHistoryEntry> history(Account who, UUID provider, UUID subscription, LocalDate date) {
        AttendanceHistoryEntry[] body = send("GET", attendanceUrl(provider, subscription, date) + "/history",
                who.accessToken(), null).expectStatus().isOk().expectBody(AttendanceHistoryEntry[].class)
                .returnResult().getResponseBody();
        return body == null ? List.of() : List.of(body);
    }

    private List<AbsenceView> absencesAsStaff(Account who, UUID provider, UUID subscription) {
        AbsenceView[] body = send("GET", "/api/v1/providers/" + provider + "/subscriptions/" + subscription
                + "/absences", who.accessToken(), null).expectStatus().isOk().expectBody(AbsenceView[].class)
                .returnResult().getResponseBody();
        return body == null ? List.of() : List.of(body);
    }

    private RestTestClient.ResponseSpec correct(Account who, UUID provider, UUID subscription, LocalDate date,
            String status, String reason) {
        return send("PUT", attendanceUrl(provider, subscription, date), who.accessToken(),
                Map.of("status", status, "reason", reason));
    }

    // ---- plumbing

    /** token == null sends no Authorization header. */
    private RestTestClient.ResponseSpec send(String method, String uri, String token, Object body) {
        RestTestClient.RequestBodyUriSpec spec = restClient.method(HttpMethod.valueOf(method));
        RestTestClient.RequestBodySpec request = spec.uri(uri);
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON);
            return request.body(body).exchange();
        }
        return request.exchange();
    }

    private void assertError(RestTestClient.ResponseSpec spec, HttpStatus status, String code) {
        Map<?, ?> body = spec.expectStatus().isEqualTo(status).expectBody(Map.class).returnResult()
                .getResponseBody();
        assertThat(body).isNotNull();
        assertThat(body.get("code")).isEqualTo(code);
    }

    private int statusOf(RestTestClient.ResponseSpec spec) {
        return spec.expectBody(String.class).returnResult().getStatus().value();
    }

    // ---- database probes

    private int absenceRows(UUID subscriptionId) {
        return jdbc.queryForObject("select count(*) from absence_record where subscription_id = ?", Integer.class,
                subscriptionId);
    }

    private int declaredAbsences(UUID subscriptionId) {
        return jdbc.queryForObject(
                "select count(*) from absence_record where subscription_id = ? and status = 'DECLARED'",
                Integer.class, subscriptionId);
    }

    private int ledgerRows(UUID subscriptionId) {
        return jdbc.queryForObject("select count(*) from attendance_record where subscription_id = ?", Integer.class,
                subscriptionId);
    }

    private int ledgerRows(UUID subscriptionId, LocalDate date) {
        return jdbc.queryForObject(
                "select count(*) from attendance_record where subscription_id = ? and attendance_date = ?",
                Integer.class, subscriptionId, date);
    }

    /**
     * The two tables tell one story: a DECLARED absence is exactly a current ABSENT row, and no day has two current
     * rows (V11 enforces both; this proves the application never relies on that to "fix" anything).
     */
    private void assertLedgerConsistent(UUID subscriptionId) {
        Integer mismatched = jdbc.queryForObject("""
                select count(*) from absence_record a
                 where a.subscription_id = ?
                   and (a.status = 'DECLARED') <> exists (
                         select 1 from attendance_record r
                          where r.absence_id = a.id and r.is_current and r.status = 'ABSENT')
                """, Integer.class, subscriptionId);
        assertThat(mismatched).as("declared absences without a current ABSENT row (or the reverse)").isZero();
        Integer doubled = jdbc.queryForObject("""
                select count(*) from (
                    select attendance_date from attendance_record
                     where subscription_id = ? and is_current
                     group by attendance_date having count(*) > 1) x
                """, Integer.class, subscriptionId);
        assertThat(doubled).as("days with two current rows").isZero();
    }

    /**
     * Writes a DECLARED absence for today straight into the database (an absence the customer "made before the
     * cutoff"): the API would rightly refuse to create it now. Both rows go in one transaction because the deferred
     * consistency triggers check them together at commit.
     */
    private UUID insertDeclaredAbsenceForToday(SubscriptionView sub, UUID customerAccountId) {
        UUID absenceId = UUID.randomUUID();
        UUID provider = jdbc.queryForObject("select provider_id from subscription where id = ?", UUID.class, sub.id());
        new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.support.JdbcTransactionManager(jdbc.getDataSource()))
                .executeWithoutResult(status -> {
                    jdbc.update("""
                            insert into absence_record (id, subscription_id, provider_id, absence_date, source, declared_by, declared_at)
                            values (?, ?, ?, ?, 'CUSTOMER', ?, now())
                            """, absenceId, sub.id(), provider, today(), customerAccountId);
                    jdbc.update("""
                            insert into attendance_record (id, subscription_id, provider_id, attendance_date, status, source,
                                                           absence_id, recorded_by, recorded_at)
                            values (gen_random_uuid(), ?, ?, ?, 'ABSENT', 'CUSTOMER', ?, ?, now())
                            """, sub.id(), provider, today(), absenceId, customerAccountId);
                });
        return absenceId;
    }

    /**
     * Moves a just-sold DAY subscription {@code days} into the past, keeping its dates consistent with its snapshot
     * (start + purchased days - 1). Needs the identity trigger off for the moment.
     */
    private void backdate(UUID subscriptionId, int days) {
        jdbc.execute("alter table subscription disable trigger subscription_no_identity_change");
        try {
            jdbc.update("""
                    update subscription
                       set start_date = start_date - ?,
                           base_expiry_date = base_expiry_date - ?,
                           effective_expiry_date = effective_expiry_date - ?
                     where id = ?
                    """, days, days, days, subscriptionId);
        } finally {
            jdbc.execute("alter table subscription enable trigger subscription_no_identity_change");
        }
    }

    @SafeVarargs
    private static List<Integer> runConcurrently(Callable<Integer>... tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.length);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
