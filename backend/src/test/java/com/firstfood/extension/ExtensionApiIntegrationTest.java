package com.firstfood.extension;

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
 * Phase 9 HTTP-level tests: the extension engine. Real Postgres + Redis, real OTP login.
 * Phones are generated as +91987658xxxx (earlier phases use ...650xxxx to ...657xxxx); the database is a shared
 * singleton container, so do not reuse that prefix elsewhere.
 *
 * The API refuses to declare an absence for a day that is already over, so the tests sell a subscription, move it
 * into the past through SQL (identity trigger off for the moment, the same technique as the Phase 8 tests) and write
 * the past absences straight into the database, exactly as a customer would have declared them back then.
 */
class ExtensionApiIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger PHONE_COUNTER = new AtomicInteger(1);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");

    @Autowired
    RestTestClient restClient;

    @Autowired
    TestCapturingOtpSender otpSender;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SystemExtensionService systemExtension;

    private static LocalDate today() {
        return LocalDate.now(BUSINESS_ZONE);
    }

    // ------------------------------------------------------------ authentication

    @Test
    void extensionEndpointsRequireAuthentication() {
        UUID id = UUID.randomUUID();
        String provider = "/api/v1/providers/" + id + "/subscriptions/" + id + "/extension";
        String mine = "/api/v1/subscriptions/" + id + "/extension";
        for (String[] call : new String[][] {{"GET", provider}, {"GET", provider + "/events"},
                {"POST", provider + "/apply"}, {"GET", mine}, {"GET", mine + "/events"}}) {
            assertThat(statusOf(send(call[0], call[1], null, null))).as(call[0] + " " + call[1]).isEqualTo(401);
        }
    }

    // ------------------------------------------------------------ the happy path and the formula

    @Test
    void ownerAppliesTheExtensionAndTheExpiryMovesByTheEligibleDays() {
        Fixture f = fixture(extensionPlan(2, 45));
        SubscriptionView sub = sell(f, today());
        backdate(sub.id(), 10);
        LocalDate start = today().minusDays(10);
        absent(sub.id(), f.customer.id(), start.plusDays(2), start.plusDays(6)); // 5 consecutive days, all over

        ExtensionStatusView before = status(f.owner, f.provider, sub.id());
        assertThat(before.canApply()).isTrue();
        assertThat(before.eligibleAbsenceDays()).isEqualTo(5);
        assertThat(before.pendingExtensionDays()).isEqualTo(5);
        assertThat(before.projectedExpiryDate()).isEqualTo(start.plusDays(29 + 5));
        assertThat(before.maximumExpiryDate()).isEqualTo(start.plusDays(44));
        assertThat(before.qualifyingRuns()).hasSize(1);

        ApplyExtensionResult result = apply(f.owner, f.provider, sub.id()).expectStatus().isCreated()
                .expectBody(ApplyExtensionResult.class).returnResult().getResponseBody();
        assertThat(result.applied()).isTrue();
        assertThat(result.event().sequenceNo()).isEqualTo(1);
        assertThat(result.event().triggerSource()).isEqualTo(ExtensionTrigger.STAFF);
        assertThat(result.event().createdBy()).isEqualTo(f.owner.id());
        assertThat(result.event().requestedExtensionDays()).isEqualTo(5);
        assertThat(result.event().appliedExtensionDays()).isEqualTo(5);
        assertThat(result.event().capped()).isFalse();
        assertThat(result.event().previousExpiryDate()).isEqualTo(start.plusDays(29));
        assertThat(result.event().newExpiryDate()).isEqualTo(start.plusDays(34));
        assertThat(result.status().canApply()).isFalse();
        assertThat(result.status().effectiveExpiryDate()).isEqualTo(start.plusDays(34));

        assertThat(effectiveExpiry(sub.id())).isEqualTo(start.plusDays(34));
        assertThat(baseExpiry(sub.id())).isEqualTo(start.plusDays(29));
        assertThat(events(f.owner, f.provider, sub.id())).hasSize(1);
    }

    @Test
    void nothingEligibleYetMeansNothingIsWrittenAndTheAnswerIsOk() {
        Fixture f = fixture(extensionPlan(3, 45));
        SubscriptionView sub = sell(f, today());
        backdate(sub.id(), 10);
        absent(sub.id(), f.customer.id(), today().minusDays(5), today().minusDays(4)); // only 2, minimum is 3

        ApplyExtensionResult result = apply(f.owner, f.provider, sub.id()).expectStatus().isOk()
                .expectBody(ApplyExtensionResult.class).returnResult().getResponseBody();
        assertThat(result.applied()).isFalse();
        assertThat(result.event()).isNull();
        assertThat(extensionRows(sub.id())).isZero();
        assertThat(effectiveExpiry(sub.id())).isEqualTo(baseExpiry(sub.id()));
    }

    @Test
    void anAbsenceThatIsNotOverYetIsNotCounted() {
        Fixture f = fixture(extensionPlan(1, 45));
        SubscriptionView sub = sell(f, today());
        backdate(sub.id(), 3);
        absent(sub.id(), f.customer.id(), today().minusDays(2), today().minusDays(2));
        absent(sub.id(), f.customer.id(), today(), today()); // today: still changeable, not counted

        ExtensionStatusView s = status(f.owner, f.provider, sub.id());
        assertThat(s.countedAbsentDays()).isEqualTo(1);
        assertThat(s.pendingExtensionDays()).isEqualTo(1);
    }

    // ------------------------------------------------------------ idempotency

    @Test
    void applyingTwiceWritesOneEventAndTheSecondCallChangesNothing() {
        Fixture f = fixture(extensionPlan(2, 45));
        SubscriptionView sub = sell(f, today());
        backdate(sub.id(), 10);
        absent(sub.id(), f.customer.id(), today().minusDays(8), today().minusDays(6));

        apply(f.owner, f.provider, sub.id()).expectStatus().isCreated();
        LocalDate afterFirst = effectiveExpiry(sub.id());
        ApplyExtensionResult second = apply(f.owner, f.provider, sub.id()).expectStatus().isOk()
                .expectBody(ApplyExtensionResult.class).returnResult().getResponseBody();

        assertThat(second.applied()).isFalse();
        assertThat(extensionRows(sub.id())).isEqualTo(1);
        assertThat(effectiveExpiry(sub.id())).isEqualTo(afterFirst);
    }

    @Test
    void theSystemJobRunTwiceAfterAStaffApplyAddsNothing() {
        Fixture f = fixture(extensionPlan(2, 45));
        SubscriptionView sub = sell(f, today());
        backdate(sub.id(), 10);
        absent(sub.id(), f.customer.id(), today().minusDays(8), today().minusDays(7));

        ApplyExtensionResult first = systemExtension.applyAutomatically(f.provider, sub.id());
        ApplyExtensionResult again = systemExtension.applyAutomatically(f.provider, sub.id());
        assertThat(first.applied()).isTrue();
        assertThat(first.event().triggerSource()).isEqualTo(ExtensionTrigger.SYSTEM);
        assertThat(first.event().createdBy()).isNull();
        assertThat(again.applied()).isFalse();
        apply(f.owner, f.provider, sub.id()).expectStatus().isOk();
        assertThat(extensionRows(sub.id())).isEqualTo(1);
    }

    @Test
    void laterAbsencesAddAnotherEventForTheDifferenceOnly() {
        Fixture f = fixture(extensionPlan(2, 45));
        SubscriptionView sub = sell(f, today());
        backdate(sub.id(), 12);
        LocalDate start = today().minusDays(12);
        absent(sub.id(), f.customer.id(), start.plusDays(1), start.plusDays(3)); // 3 days
        apply(f.owner, f.provider, sub.id()).expectStatus().isCreated();

        absent(sub.id(), f.customer.id(), start.plusDays(6), start.plusDays(7)); // 2 more
        ApplyExtensionResult second = apply(f.owner, f.provider, sub.id()).expectStatus().isCreated()
                .expectBody(ApplyExtensionResult.class).returnResult().getResponseBody();

        assertThat(second.event().sequenceNo()).isEqualTo(2);
        assertThat(second.event().eligibleAbsenceDays()).isEqualTo(5);
        assertThat(second.event().requestedExtensionDays()).isEqualTo(2);
        assertThat(second.event().appliedExtensionDays()).isEqualTo(2);
        assertThat(effectiveExpiry(sub.id())).isEqualTo(start.plusDays(29 + 5));
        assertThat(events(f.owner, f.provider, sub.id())).extracting(ExtensionEventView::sequenceNo)
                .containsExactly(1, 2);
    }

    @Test
    void concurrentAppliesWriteExactlyOneEvent() throws Exception {
        Fixture f = fixture(extensionPlan(2, 45));
        SubscriptionView sub = sell(f, today());
        backdate(sub.id(), 10);
        absent(sub.id(), f.customer.id(), today().minusDays(8), today().minusDays(4));

        List<Integer> statuses = runConcurrently(
                () -> statusOf(apply(f.owner, f.provider, sub.id())),
                () -> statusOf(apply(f.owner, f.provider, sub.id())),
                () -> statusOf(apply(f.owner, f.provider, sub.id())));

        assertThat(statuses).containsOnly(200, 201);
        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
        assertThat(extensionRows(sub.id())).isEqualTo(1);
        assertThat(effectiveExpiry(sub.id())).isEqualTo(baseExpiry(sub.id()).plusDays(5));
    }

    // ------------------------------------------------------------ the cap

    @Test
    void theExtensionIsCutAtTheMaximumCountedFromTheOriginalStart() {
        Fixture f = fixture(extensionPlan(2, 35)); // 30 days + at most 5 more
        SubscriptionView sub = sell(f, today());
        backdate(sub.id(), 12);
        LocalDate start = today().minusDays(12);
        absent(sub.id(), f.customer.id(), start.plusDays(1), start.plusDays(8)); // 8 eligible, only 5 fit

        ApplyExtensionResult r = apply(f.owner, f.provider, sub.id()).expectStatus().isCreated()
                .expectBody(ApplyExtensionResult.class).returnResult().getResponseBody();
        assertThat(r.event().requestedExtensionDays()).isEqualTo(8);
        assertThat(r.event().appliedExtensionDays()).isEqualTo(5);
        assertThat(r.event().capped()).isTrue();
        assertThat(r.event().newExpiryDate()).isEqualTo(start.plusDays(34));
        assertThat(r.event().maximumExpiryDate()).isEqualTo(start.plusDays(34));

        // At the maximum: asking again changes nothing and is not an error.
        apply(f.owner, f.provider, sub.id()).expectStatus().isOk();
        assertThat(extensionRows(sub.id())).isEqualTo(1);
    }

    // ------------------------------------------------------------ refusals

    @Test
    void aSubscriptionSoldWithoutExtensionCannotBeExtended() {
        Fixture f = fixture(null); // the default plan: extension not allowed
        SubscriptionView sub = sell(f, today());
        assertError(apply(f.owner, f.provider, sub.id()), HttpStatus.CONFLICT, "EXTENSION_NOT_ALLOWED");
        ExtensionStatusView s = status(f.owner, f.provider, sub.id());
        assertThat(s.canApply()).isFalse();
        assertThat(s.blockedReason()).isEqualTo(ExtensionBlock.NOT_ALLOWED_BY_POLICY);
    }

    @Test
    void aMealSubscriptionCannotBeExtended() {
        Fixture f = fixture(mealPlanBody("Meals"));
        SubscriptionView sub = sell(f, today());
        assertError(apply(f.owner, f.provider, sub.id()), HttpStatus.CONFLICT, "EXTENSION_NOT_SUPPORTED");
    }

    @Test
    void aCancelledSubscriptionCannotBeExtended() {
        Fixture f = fixture(extensionPlan(2, 45));
        SubscriptionView sub = sell(f, today());
        send("POST", subsUrl(f.provider) + "/" + sub.id() + "/cancel", f.owner.accessToken(), Map.of())
                .expectStatus().isOk();
        assertError(apply(f.owner, f.provider, sub.id()), HttpStatus.CONFLICT, "SUBSCRIPTION_NOT_ACTIVE");
    }

    @Test
    void aRenewedSubscriptionCannotBeExtended() {
        Fixture f = fixture(extensionPlan(2, 45));
        SubscriptionView sub = sell(f, today());
        backdate(sub.id(), 40); // a subscription can only be renewed once it has ended (start today-40, ended today-11)
        send("POST", subsUrl(f.provider) + "/" + sub.id() + "/renew", f.owner.accessToken(), null)
                .expectStatus().isCreated();
        assertError(apply(f.owner, f.provider, sub.id()), HttpStatus.CONFLICT, "SUBSCRIPTION_ALREADY_RENEWED");
    }

    @Test
    void aSubscriptionThatIsStillRunningCannotBeRenewedYet() {
        Fixture f = fixture(extensionPlan(2, 45));
        SubscriptionView sub = sell(f, today());
        assertError(send("POST", subsUrl(f.provider) + "/" + sub.id() + "/renew", f.owner.accessToken(), null),
                HttpStatus.CONFLICT, "SUBSCRIPTION_NOT_RENEWABLE");
    }

    @Test
    void renewalWaitsUntilPendingExtensionDaysAreApplied() {
        Fixture f = fixture(extensionPlan(2, 45));
        SubscriptionView sub = sell(f, today());
        backdate(sub.id(), 40); // ended on today-11, so renewal is possible by date
        LocalDate expiry = baseExpiry(sub.id());
        absent(sub.id(), f.customer.id(), expiry.minusDays(5), expiry.minusDays(3)); // 3 earned, not yet applied

        // Renewal now would start a successor and lose the three days: refused until they are applied.
        assertError(send("POST", subsUrl(f.provider) + "/" + sub.id() + "/renew", f.owner.accessToken(), null),
                HttpStatus.CONFLICT, "EXTENSION_PENDING");

        apply(f.owner, f.provider, sub.id()).expectStatus().isCreated();
        send("POST", subsUrl(f.provider) + "/" + sub.id() + "/renew", f.owner.accessToken(), null)
                .expectStatus().isCreated();
    }

    // ------------------------------------------------------------ authorization and isolation

    @Test
    void ownerAndManagerMayApplyAWorkerMayNotAndAnOutsiderSeesNothing() {
        Fixture f = fixture(extensionPlan(2, 45));
        SubscriptionView sub = sell(f, today());
        backdate(sub.id(), 10);
        absent(sub.id(), f.customer.id(), today().minusDays(8), today().minusDays(6));

        Account manager = login();
        Account worker = login();
        Account outsider = login();
        assignRole(f.owner, f.provider, manager, "MANAGER");
        assignRole(f.owner, f.provider, worker, "WORKER");

        assertError(status(worker, f.provider, sub.id(), true), HttpStatus.FORBIDDEN, "INSUFFICIENT_PERMISSION");
        assertError(apply(worker, f.provider, sub.id()), HttpStatus.FORBIDDEN, "INSUFFICIENT_PERMISSION");
        assertError(send("GET", extUrl(f.provider, sub.id()) + "/events", worker.accessToken(), null),
                HttpStatus.FORBIDDEN, "INSUFFICIENT_PERMISSION");
        assertError(status(outsider, f.provider, sub.id(), true), HttpStatus.NOT_FOUND, "PROVIDER_NOT_FOUND");
        assertError(apply(outsider, f.provider, sub.id()), HttpStatus.NOT_FOUND, "PROVIDER_NOT_FOUND");
        assertThat(extensionRows(sub.id())).isZero();

        status(manager, f.provider, sub.id());
        apply(manager, f.provider, sub.id()).expectStatus().isCreated();
        assertThat(events(f.owner, f.provider, sub.id()).get(0).createdBy()).isEqualTo(manager.id());
    }

    @Test
    void aSubscriptionOfAnotherProviderIsNotFound() {
        Fixture a = fixture(extensionPlan(2, 45));
        Fixture b = fixture(extensionPlan(2, 45));
        SubscriptionView subOfA = sell(a, today());
        assertError(status(b.owner, b.provider, subOfA.id(), true), HttpStatus.NOT_FOUND, "SUBSCRIPTION_NOT_FOUND");
        assertError(apply(b.owner, b.provider, subOfA.id()), HttpStatus.NOT_FOUND, "SUBSCRIPTION_NOT_FOUND");
        assertError(send("GET", extUrl(b.provider, subOfA.id()) + "/events", b.owner.accessToken(), null),
                HttpStatus.NOT_FOUND, "SUBSCRIPTION_NOT_FOUND");
    }

    // ------------------------------------------------------------ the customer's view

    @Test
    void theCustomerSeesTheirOwnExtensionButNeverWhoAppliedItAndCannotApply() {
        Fixture f = fixture(extensionPlan(2, 45));
        SubscriptionView sub = sell(f, today());
        backdate(sub.id(), 10);
        absent(sub.id(), f.customer.id(), today().minusDays(8), today().minusDays(6));
        apply(f.owner, f.provider, sub.id()).expectStatus().isCreated();

        String mine = "/api/v1/subscriptions/" + sub.id() + "/extension";
        ExtensionStatusView s = send("GET", mine, f.customer.accessToken(), null).expectStatus().isOk()
                .expectBody(ExtensionStatusView.class).returnResult().getResponseBody();
        assertThat(s.appliedExtensionDays()).isEqualTo(3);
        assertThat(s.eventCount()).isEqualTo(1);

        String raw = send("GET", mine + "/events", f.customer.accessToken(), null).expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        MyExtensionEventView[] events = send("GET", mine + "/events", f.customer.accessToken(), null)
                .expectStatus().isOk().expectBody(MyExtensionEventView[].class).returnResult().getResponseBody();
        assertThat(events).hasSize(1);
        assertThat(events[0].appliedExtensionDays()).isEqualTo(3);
        assertThat(raw).doesNotContain("createdBy").doesNotContain(f.owner.id().toString());

        // Read-only: no apply on the customer's route, and another customer's subscription is simply not found.
        assertThat(statusOf(send("POST", mine + "/apply", f.customer.accessToken(), Map.of())))
                .isIn(404, 405);
        Account stranger = login();
        assertError(send("GET", mine, stranger.accessToken(), null), HttpStatus.NOT_FOUND, "SUBSCRIPTION_NOT_FOUND");
        assertError(send("GET", mine + "/events", stranger.accessToken(), null), HttpStatus.NOT_FOUND,
                "SUBSCRIPTION_NOT_FOUND");
    }


    // ------------------------------------------------------------ Phase 9 review hardening

    @Test
    void aClosedProviderBlocksStaffApplyButNotTheSystemReconciliation() {
        Fixture f = fixture(extensionPlan(2, 45));
        SubscriptionView sub = sell(f, today());
        backdate(sub.id(), 10);
        absent(sub.id(), f.customer.id(), today().minusDays(8), today().minusDays(6));
        // OPEN + STAFF: normal path (checked first so the closed case is the only difference).
        assertThat(status(f.owner, f.provider, sub.id()).canApply()).isTrue();

        send("PATCH", "/api/v1/providers/" + f.provider, f.owner.accessToken(),
                Map.of("status", "CLOSED", "closureReason", "Shut down")).expectStatus().isOk();

        assertError(apply(f.owner, f.provider, sub.id()), HttpStatus.CONFLICT, "PROVIDER_CLOSED");
        assertThat(extensionRows(sub.id())).isZero();

        // The earned days are not lost because the provider closed: the system still applies them.
        ApplyExtensionResult result = systemExtension.applyAutomatically(f.provider, sub.id());
        assertThat(result.applied()).isTrue();
        assertThat(result.event().triggerSource()).isEqualTo(ExtensionTrigger.SYSTEM);
        assertThat(result.event().createdBy()).isNull();
        assertThat(effectiveExpiry(sub.id())).isEqualTo(baseExpiry(sub.id()).plusDays(3));
    }

    @Test
    void openProviderStaffApplyIsAuthorizedAndApplied() {
        Fixture f = fixture(extensionPlan(2, 45));
        SubscriptionView sub = sell(f, today());
        backdate(sub.id(), 10);
        absent(sub.id(), f.customer.id(), today().minusDays(8), today().minusDays(6));
        apply(f.owner, f.provider, sub.id()).expectStatus().isCreated();
        assertThat(events(f.owner, f.provider, sub.id()).get(0).triggerSource()).isEqualTo(ExtensionTrigger.STAFF);
    }

    @Test
    void anAbsenceCorrectionNeverReversesAnExtensionAndLaterEntitlementAddsOnlyTheDifference() {
        Fixture f = fixture(extensionPlan(2, 60));
        SubscriptionView sub = sell(f, today());
        backdate(sub.id(), 25);
        LocalDate start = today().minusDays(25);
        // Days 10, 11, 12 of the subscription: one run of 3 -> +3.
        absent(sub.id(), f.customer.id(), start.plusDays(9), start.plusDays(11));
        ApplyExtensionResult first = apply(f.owner, f.provider, sub.id()).expectStatus().isCreated()
                .expectBody(ApplyExtensionResult.class).returnResult().getResponseBody();
        assertThat(first.event().appliedExtensionDays()).isEqualTo(3);
        LocalDate afterFirst = effectiveExpiry(sub.id());
        assertThat(afterFirst).isEqualTo(baseExpiry(sub.id()).plusDays(3));

        // Staff correct day 12 to PRESENT: the absence is overridden, eligibility drops to 2.
        correct(f.owner, f.provider, sub.id(), start.plusDays(11), "PRESENT", "Actually ate")
                .expectStatus().isOk();

        ExtensionStatusView over = status(f.owner, f.provider, sub.id());
        assertThat(over.eligibleAbsenceDays()).isEqualTo(2);
        assertThat(over.appliedExtensionDays()).isEqualTo(3);
        assertThat(over.overApplied()).isTrue();
        assertThat(over.pendingExtensionDays()).isZero();
        // Nothing is reversed. The gap is reported ONCE as an OVER_APPLIED event (no days, expiry unchanged); asking
        // again writes nothing more.
        assertThat(effectiveExpiry(sub.id())).isEqualTo(afterFirst);
        ApplyExtensionResult report = apply(f.owner, f.provider, sub.id()).expectStatus().isOk()
                .expectBody(ApplyExtensionResult.class).returnResult().getResponseBody();
        assertThat(report.applied()).isFalse();
        assertThat(report.event().kind()).isEqualTo(ExtensionKind.OVER_APPLIED);
        assertThat(report.event().sequenceNo()).isEqualTo(2);
        assertThat(report.event().overAppliedDays()).isEqualTo(1);
        assertThat(report.event().appliedExtensionDays()).isZero();
        assertThat(report.event().eligibleAbsenceDays()).isEqualTo(2);
        assertThat(report.event().previousExpiryDate()).isEqualTo(report.event().newExpiryDate());
        assertThat(effectiveExpiry(sub.id())).isEqualTo(afterFirst);
        ApplyExtensionResult again = apply(f.owner, f.provider, sub.id()).expectStatus().isOk()
                .expectBody(ApplyExtensionResult.class).returnResult().getResponseBody();
        assertThat(again.event()).isNull();
        assertThat(extensionRows(sub.id())).isEqualTo(2);

        // New absences on days 20 and 21: total eligibility 4, only the difference (+1) is added.
        absent(sub.id(), f.customer.id(), start.plusDays(19), start.plusDays(20));
        ExtensionStatusView more = status(f.owner, f.provider, sub.id());
        assertThat(more.eligibleAbsenceDays()).isEqualTo(4);
        assertThat(more.pendingExtensionDays()).isEqualTo(1);
        ApplyExtensionResult second = apply(f.owner, f.provider, sub.id()).expectStatus().isCreated()
                .expectBody(ApplyExtensionResult.class).returnResult().getResponseBody();
        assertThat(second.event().kind()).isEqualTo(ExtensionKind.EXTENSION_APPLIED);
        assertThat(second.event().sequenceNo()).isEqualTo(3);
        assertThat(second.event().appliedExtensionDays()).isEqualTo(1);
        assertThat(second.event().eligibleAbsenceDays()).isEqualTo(4);

        assertThat(effectiveExpiry(sub.id())).isEqualTo(baseExpiry(sub.id()).plusDays(4));
        List<ExtensionEventView> history = events(f.owner, f.provider, sub.id());
        assertThat(history).extracting(ExtensionEventView::kind).containsExactly(
                ExtensionKind.EXTENSION_APPLIED, ExtensionKind.OVER_APPLIED, ExtensionKind.EXTENSION_APPLIED);
        // The first event is untouched.
        assertThat(history.get(0).id()).isEqualTo(first.event().id());
        assertThat(history.get(0).appliedExtensionDays()).isEqualTo(3);
        assertThat(history.get(0).eligibleAbsenceDays()).isEqualTo(3);
        assertThat(history.get(0).newExpiryDate()).isEqualTo(first.event().newExpiryDate());
        apply(f.owner, f.provider, sub.id()).expectStatus().isOk();
        assertThat(extensionRows(sub.id())).isEqualTo(3);
        assertExpiryInvariant(sub.id());
    }

    @Test
    void anAbsenceEpisodeThatCrossesTheExpiryIsExtendedInFull() {
        Fixture f = fixture(extensionPlan(2, 60));
        SubscriptionView sub = sell(f, today());
        backdate(sub.id(), 40); // start = today-40, base expiry = today-11
        LocalDate expiry = baseExpiry(sub.id());
        // Absent from 3 days before the expiry to 5 days after it: one episode of 9 days. (Written straight into the
        // table: today the declare API stops at the expiry - the Phase 8 rule is a separate decision.)
        absent(sub.id(), f.customer.id(), expiry.minusDays(3), expiry.plusDays(5));

        ExtensionStatusView s = status(f.owner, f.provider, sub.id());
        assertThat(s.eligibleAbsenceDays()).isEqualTo(9);
        assertThat(s.projectedExpiryDate()).isEqualTo(expiry.plusDays(9));
        apply(f.owner, f.provider, sub.id()).expectStatus().isCreated();
        assertThat(effectiveExpiry(sub.id())).isEqualTo(expiry.plusDays(9));
        apply(f.owner, f.provider, sub.id()).expectStatus().isOk();
        assertThat(extensionRows(sub.id())).isEqualTo(1);
        assertExpiryInvariant(sub.id());
    }

    @Test
    void anAbsenceEpisodeThatStartsAfterTheExpiryEarnsNothing() {
        Fixture f = fixture(extensionPlan(2, 60));
        SubscriptionView sub = sell(f, today());
        backdate(sub.id(), 40);
        LocalDate expiry = baseExpiry(sub.id());
        absent(sub.id(), f.customer.id(), expiry.plusDays(1), expiry.plusDays(6));

        assertThat(status(f.owner, f.provider, sub.id()).canApply()).isFalse();
        apply(f.owner, f.provider, sub.id()).expectStatus().isOk();
        assertThat(extensionRows(sub.id())).isZero();
        assertThat(effectiveExpiry(sub.id())).isEqualTo(expiry);
    }

    @Test
    void theClientCannotChooseTheDaysOrTheExpiry() {
        Fixture f = fixture(extensionPlan(2, 45));
        SubscriptionView sub = sell(f, today());
        backdate(sub.id(), 10);
        absent(sub.id(), f.customer.id(), today().minusDays(8), today().minusDays(7)); // 2 eligible days

        Map<String, Object> hostile = new HashMap<>();
        hostile.put("days", 30);
        hostile.put("extensionDays", 30);
        hostile.put("appliedExtensionDays", 30);
        hostile.put("newExpiryDate", today().plusDays(400).toString());
        hostile.put("effectiveExpiryDate", today().plusDays(400).toString());
        ApplyExtensionResult r = send("POST", extUrl(f.provider, sub.id()) + "/apply", f.owner.accessToken(), hostile)
                .expectStatus().isCreated().expectBody(ApplyExtensionResult.class).returnResult().getResponseBody();
        assertThat(r.event().appliedExtensionDays()).isEqualTo(2);
        assertThat(effectiveExpiry(sub.id())).isEqualTo(baseExpiry(sub.id()).plusDays(2));
    }

    @Test
    void staffAndCustomersCannotReachAnotherProvidersSubscriptionByMixingIds() {
        Fixture a = fixture(extensionPlan(2, 45));
        Fixture b = fixture(extensionPlan(2, 45));
        SubscriptionView subOfA = sell(a, today());
        SubscriptionView subOfB = sell(b, today());

        // A's owner, A's provider path, B's subscription: not found, nothing changes.
        assertError(status(a.owner, a.provider, subOfB.id(), true), HttpStatus.NOT_FOUND, "SUBSCRIPTION_NOT_FOUND");
        assertError(apply(a.owner, a.provider, subOfB.id()), HttpStatus.NOT_FOUND, "SUBSCRIPTION_NOT_FOUND");
        // A's owner, B's provider path: not a member there.
        assertError(apply(a.owner, b.provider, subOfB.id()), HttpStatus.NOT_FOUND, "PROVIDER_NOT_FOUND");
        // A's customer on B's subscription (customer routes resolve through the caller's own persons).
        String theirs = "/api/v1/subscriptions/" + subOfB.id() + "/extension";
        assertError(send("GET", theirs, a.customer.accessToken(), null), HttpStatus.NOT_FOUND,
                "SUBSCRIPTION_NOT_FOUND");
        assertError(send("GET", theirs + "/events", a.customer.accessToken(), null), HttpStatus.NOT_FOUND,
                "SUBSCRIPTION_NOT_FOUND");
        assertThat(extensionRows(subOfA.id()) + extensionRows(subOfB.id())).isZero();
    }

    @Test
    void concurrentSystemRunsAreIdempotent() throws Exception {
        Fixture f = fixture(extensionPlan(2, 45));
        SubscriptionView sub = sell(f, today());
        backdate(sub.id(), 10);
        absent(sub.id(), f.customer.id(), today().minusDays(8), today().minusDays(4));

        List<Integer> applied = runConcurrently(
                () -> systemExtension.applyAutomatically(f.provider, sub.id()).applied() ? 1 : 0,
                () -> systemExtension.applyAutomatically(f.provider, sub.id()).applied() ? 1 : 0,
                () -> systemExtension.applyAutomatically(f.provider, sub.id()).applied() ? 1 : 0);

        assertThat(applied.stream().mapToInt(Integer::intValue).sum()).isEqualTo(1);
        assertThat(extensionRows(sub.id())).isEqualTo(1);
        assertThat(effectiveExpiry(sub.id())).isEqualTo(baseExpiry(sub.id()).plusDays(5));
        assertExpiryInvariant(sub.id());
    }

    @Test
    void extensionAndRenewalRacingOnOneSubscriptionNeverLoseEarnedDays() throws Exception {
        Fixture f = fixture(extensionPlan(2, 45));
        SubscriptionView sub = sell(f, today());
        backdate(sub.id(), 40); // ended: renewable by date
        LocalDate expiry = baseExpiry(sub.id());
        absent(sub.id(), f.customer.id(), expiry.minusDays(5), expiry.minusDays(3)); // +3

        List<Integer> statuses = runConcurrently(
                () -> statusOf(apply(f.owner, f.provider, sub.id())),
                () -> statusOf(send("POST", subsUrl(f.provider) + "/" + sub.id() + "/renew",
                        f.owner.accessToken(), null)));

        // Order A: apply first (201), then the renewal passes (201). Order B: the renewal ran first and was refused
        // (409 EXTENSION_PENDING) - or it won and the extension was then refused (409 ALREADY_RENEWED). Either way the
        // earned days are never lost silently: if the renewal exists, the extension was applied before it.
        int renewalRows = jdbc.queryForObject(
                "select count(*) from subscription where renewed_from_subscription_id = ?", Integer.class, sub.id());
        if (statuses.get(1) == 201) {
            assertThat(statuses.get(0)).isEqualTo(201);
            assertThat(extensionRows(sub.id())).isEqualTo(1);
            assertThat(effectiveExpiry(sub.id())).isEqualTo(expiry.plusDays(3));
            LocalDate renewedStart = jdbc.queryForObject(
                    "select start_date from subscription where renewed_from_subscription_id = ?", LocalDate.class,
                    sub.id());
            assertThat(renewedStart.isAfter(effectiveExpiry(sub.id()))).isTrue();
            assertThat(renewalRows).isEqualTo(1);
        } else {
            assertThat(statuses.get(1)).isEqualTo(409);
            assertThat(statuses.get(0)).isEqualTo(201);
            assertThat(renewalRows).isZero();
        }
        assertExpiryInvariant(sub.id());
    }

    @Test
    void extensionRacingAnAbsenceCorrectionKeepsTheSequenceAndInvariantIntact() throws Exception {
        Fixture f = fixture(extensionPlan(2, 60));
        SubscriptionView sub = sell(f, today());
        backdate(sub.id(), 20);
        LocalDate start = today().minusDays(20);
        absent(sub.id(), f.customer.id(), start.plusDays(5), start.plusDays(8));

        List<Integer> statuses = runConcurrently(
                () -> statusOf(apply(f.owner, f.provider, sub.id())),
                () -> statusOf(correct(f.owner, f.provider, sub.id(), start.plusDays(8), "PRESENT", "Ate")),
                () -> statusOf(apply(f.owner, f.provider, sub.id())));

        assertThat(statuses.get(1)).isEqualTo(200);
        assertThat(statuses.get(0)).isIn(200, 201);
        assertThat(statuses.get(2)).isIn(200, 201);
        assertThat(extensionRows(sub.id())).isLessThanOrEqualTo(2);
        assertExpiryInvariant(sub.id());
        // Sequence is gap-free 1..n.
        Integer gaps = jdbc.queryForObject(
                "select count(*) from extension_event where subscription_id = ? and sequence_no > ?", Integer.class,
                sub.id(), extensionRows(sub.id()));
        assertThat(gaps).isZero();
    }


    // ------------------------------------------------------------ absences that continue past the expiry (Phase 8 + 9)

    @Test
    void aCustomerCanDeclareAnEpisodeThatContinuesPastTheExpiry() {
        Fixture f = fixture(extensionPlan(2, 60));
        SubscriptionView sub = sell(f, today());
        LocalDate expiry = effectiveExpiry(sub.id());

        // Two days before the expiry through three days after it: one episode of 6 days.
        declare(f.customer, sub.id(), expiry.minusDays(2), expiry.plusDays(3)).expectStatus().isCreated();
        assertThat(absenceRows(sub.id())).isEqualTo(6);
        // Retrying the same request creates nothing (idempotent), and the episode can grow by continuing it.
        declare(f.customer, sub.id(), expiry.minusDays(2), expiry.plusDays(3)).expectStatus().isOk();
        declare(f.customer, sub.id(), expiry.plusDays(4), expiry.plusDays(5)).expectStatus().isCreated();
        assertThat(absenceRows(sub.id())).isEqualTo(8);
        // Nothing earned yet: the days are in the future.
        assertThat(status(f.owner, f.provider, sub.id()).canApply()).isFalse();
    }

    @Test
    void anArbitraryAbsenceAfterTheExpiryIsRefused() {
        Fixture f = fixture(extensionPlan(2, 60));
        SubscriptionView sub = sell(f, today());
        LocalDate expiry = effectiveExpiry(sub.id());

        // Entirely after the expiry: no episode reaches the last entitled day.
        assertError(declare(f.customer, sub.id(), expiry.plusDays(1), expiry.plusDays(3)), HttpStatus.BAD_REQUEST,
                "DATE_OUTSIDE_SUBSCRIPTION");
        declare(f.customer, sub.id(), expiry.minusDays(1), expiry).expectStatus().isCreated();
        // Skips a day (expiry+1 is not absent): a gap, so expiry+2.. is not a continuation. Nothing is recorded.
        assertError(declare(f.customer, sub.id(), expiry.plusDays(2), expiry.plusDays(3)), HttpStatus.BAD_REQUEST,
                "DATE_OUTSIDE_SUBSCRIPTION");
        assertThat(absenceRows(sub.id())).isEqualTo(2);
        // Continuing day by day is fine.
        declare(f.customer, sub.id(), expiry.plusDays(1), expiry.plusDays(1)).expectStatus().isCreated();
        declare(f.customer, sub.id(), expiry.plusDays(2), expiry.plusDays(3)).expectStatus().isCreated();
        assertThat(absenceRows(sub.id())).isEqualTo(5);
    }

    @Test
    void postExpiryDaysNeedAnEpisodeThatQualifiesAndStaysUnderTheMaximum() {
        Fixture f = fixture(extensionPlan(3, 35)); // minimum 3 days; maximum = start + 34 = expiry + 5
        SubscriptionView sub = sell(f, today());
        LocalDate expiry = effectiveExpiry(sub.id());

        // The whole episode (this request included) is only 2 days: too short for a minimum of 3.
        assertError(declare(f.customer, sub.id(), expiry, expiry.plusDays(1)), HttpStatus.BAD_REQUEST,
                "DATE_OUTSIDE_SUBSCRIPTION");
        assertThat(absenceRows(sub.id())).isZero(); // all or nothing: even the in-window day was not recorded
        // Long enough but running past the maximum allowed expiry is refused.
        assertError(declare(f.customer, sub.id(), expiry.minusDays(2), expiry.plusDays(6)), HttpStatus.BAD_REQUEST,
                "DATE_OUTSIDE_SUBSCRIPTION");
        assertThat(absenceRows(sub.id())).isZero();
        // 3 days is exactly the minimum: expiry-1, expiry, expiry+1 is accepted; so is going on up to the maximum.
        declare(f.customer, sub.id(), expiry.minusDays(1), expiry.plusDays(1)).expectStatus().isCreated();
        declare(f.customer, sub.id(), expiry.plusDays(2), expiry.plusDays(5)).expectStatus().isCreated();
        assertThat(absenceRows(sub.id())).isEqualTo(7);
    }

    @Test
    void aSubscriptionSoldWithoutExtensionStillStopsAtItsExpiry() {
        Fixture f = fixture(null);
        SubscriptionView sub = sell(f, today());
        LocalDate expiry = effectiveExpiry(sub.id());
        assertError(declare(f.customer, sub.id(), expiry.minusDays(1), expiry.plusDays(1)), HttpStatus.BAD_REQUEST,
                "DATE_OUTSIDE_SUBSCRIPTION");
        assertThat(absenceRows(sub.id())).isZero();
    }

    @Test
    void concurrentIdenticalCrossingDeclarationsRecordEachDayOnce() throws Exception {
        Fixture f = fixture(extensionPlan(2, 60));
        SubscriptionView sub = sell(f, today());
        LocalDate expiry = effectiveExpiry(sub.id());

        List<Integer> statuses = runConcurrently(
                () -> statusOf(declare(f.customer, sub.id(), expiry.minusDays(1), expiry.plusDays(2))),
                () -> statusOf(declare(f.customer, sub.id(), expiry.minusDays(1), expiry.plusDays(2))),
                () -> statusOf(declare(f.customer, sub.id(), expiry.minusDays(1), expiry.plusDays(2))));

        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(2);
        assertThat(absenceRows(sub.id())).isEqualTo(4);
        Integer duplicates = jdbc.queryForObject(
                "select count(*) from (select absence_date from absence_record where subscription_id = ? "
                        + "group by absence_date having count(*) > 1) d", Integer.class, sub.id());
        assertThat(duplicates).isZero();
    }

    @Test
    void staffContinueAnEpisodePastTheExpiryAndTheEngineExtendsItInFull() {
        Fixture f = fixture(extensionPlan(2, 60));
        SubscriptionView sub = sell(f, today());
        backdate(sub.id(), 40); // start = today-40, base expiry = today-11
        LocalDate expiry = baseExpiry(sub.id());

        // Staff mark the last three entitled days absent, then continue the episode day by day beyond the expiry.
        for (int offset = -2; offset <= 3; offset++) {
            correct(f.owner, f.provider, sub.id(), expiry.plusDays(offset), "ABSENT", "Away, told us by phone")
                    .expectStatus().isOk();
        }
        // An arbitrary day after the episode (a gap at expiry+4) is refused for staff too.
        assertError(correct(f.owner, f.provider, sub.id(), expiry.plusDays(5), "ABSENT", "x"),
                HttpStatus.BAD_REQUEST, "DATE_OUTSIDE_SUBSCRIPTION");
        // Provider authorization is unchanged: outsiders still see nothing.
        Account outsider = login();
        assertError(correct(outsider, f.provider, sub.id(), expiry.plusDays(4), "ABSENT", "x"),
                HttpStatus.NOT_FOUND, "PROVIDER_NOT_FOUND");

        ExtensionStatusView s = status(f.owner, f.provider, sub.id());
        assertThat(s.eligibleAbsenceDays()).isEqualTo(6); // 3 in the entitlement + 3 after it
        assertThat(s.pendingExtensionDays()).isEqualTo(6);
        apply(f.owner, f.provider, sub.id()).expectStatus().isCreated();
        assertThat(effectiveExpiry(sub.id())).isEqualTo(expiry.plusDays(6));
        assertExpiryInvariant(sub.id());

        // The last absent day is corrected to present: nothing is taken back, the gap is reported once.
        correct(f.owner, f.provider, sub.id(), expiry.plusDays(3), "PRESENT", "Actually ate").expectStatus().isOk();
        ApplyExtensionResult report = apply(f.owner, f.provider, sub.id()).expectStatus().isOk()
                .expectBody(ApplyExtensionResult.class).returnResult().getResponseBody();
        assertThat(report.event().kind()).isEqualTo(ExtensionKind.OVER_APPLIED);
        assertThat(report.event().overAppliedDays()).isEqualTo(1);
        assertThat(effectiveExpiry(sub.id())).isEqualTo(expiry.plusDays(6));
        assertExpiryInvariant(sub.id());
    }

    @Test
    void anAbsenceDeclaredPastTheExpiryCanBeUndoneByStaffAndDoesNotCountOnItsOwn() {
        Fixture f = fixture(extensionPlan(2, 60));
        SubscriptionView sub = sell(f, today());
        LocalDate expiry = effectiveExpiry(sub.id());
        declare(f.customer, sub.id(), expiry.minusDays(1), expiry.plusDays(2)).expectStatus().isCreated();

        // Staff undo a post-expiry day (a PRESENT correction is allowed where an absence was declared).
        correct(f.owner, f.provider, sub.id(), expiry.plusDays(2), "PRESENT", "Customer changed plans")
                .expectStatus().isOk();
        assertThat(declaredAbsencesOf(sub.id())).isEqualTo(3);
        // Staff cannot add one that is not a continuation.
        assertError(correct(f.owner, f.provider, sub.id(), expiry.plusDays(4), "ABSENT", "x"), HttpStatus.BAD_REQUEST,
                "DATE_OUTSIDE_SUBSCRIPTION");
    }

    // ------------------------------------------------------------ the database as the last line of defence

    @Test
    void extensionEventsAreAppendOnly() {
        Fixture f = fixture(extensionPlan(2, 45));
        SubscriptionView sub = sell(f, today());
        backdate(sub.id(), 10);
        absent(sub.id(), f.customer.id(), today().minusDays(8), today().minusDays(6));
        apply(f.owner, f.provider, sub.id()).expectStatus().isCreated();

        assertRejected(() -> jdbc.update("update extension_event set applied_extension_days = 1 where subscription_id = ?",
                sub.id()));
        assertRejected(() -> jdbc.update("delete from extension_event where subscription_id = ?", sub.id()));
        assertThat(extensionRows(sub.id())).isEqualTo(1);
    }

    @Test
    void theExpiryCannotMoveWithoutAnEventThatExplainsIt() {
        Fixture f = fixture(extensionPlan(2, 45));
        SubscriptionView sub = sell(f, today());
        // Straight into the table, bypassing the service: the deferred check at commit refuses it.
        assertRejected(() -> jdbc.update(
                "update subscription set effective_expiry_date = effective_expiry_date + 3 where id = ?", sub.id()));
        assertThat(effectiveExpiry(sub.id())).isEqualTo(baseExpiry(sub.id()));
    }

    // ------------------------------------------------------------ helpers

    private record Account(UUID id, String phone, String accessToken) {
    }

    private record Customer(Account account, UUID membershipId) {
    }

    /** An owned provider with one plan and one customer (with the customer's own login). */
    private record Fixture(Account owner, UUID provider, PlanView plan, Account customer, UUID membership) {
    }

    private Fixture fixture(Map<String, Object> planBody) {
        Account owner = login();
        UUID provider = createProvider(owner);
        PlanView plan = createPlan(owner, provider, planBody != null ? planBody : dayPlanBody(policy(false, null, null)));
        Customer c = addCustomer(owner, provider);
        return new Fixture(owner, provider, plan, c.account(), c.membershipId());
    }

    private static Map<String, Object> policy(boolean extension, Integer minConsecutive, Integer window) {
        Map<String, Object> m = new HashMap<>();
        m.put("extensionAllowed", extension);
        m.put("sameDayAbsenceAllowed", false);
        if (minConsecutive != null) {
            m.put("minConsecutiveAbsenceDays", minConsecutive);
        }
        if (window != null) {
            m.put("maxCalendarWindowDays", window);
        }
        return m;
    }

    /** The pilot plan (30 days) sold with extension: {@code min} consecutive days, at most {@code window} calendar days. */
    private static Map<String, Object> extensionPlan(int min, int window) {
        return dayPlanBody(policy(true, min, window));
    }

    private static Map<String, Object> dayPlanBody(Map<String, Object> policy) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", "Monthly");
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
        m.put("policy", policy(false, null, null));
        return m;
    }

    private Customer addCustomer(Account owner, UUID provider) {
        Account account = login();
        String name = "Customer " + account.phone().substring(account.phone().length() - 4);
        CustomerView view = send("POST", "/api/v1/providers/" + provider + "/customers", owner.accessToken(),
                Map.of("phone", account.phone(), "fullName", name))
                .expectStatus().isCreated().expectBody(CustomerView.class).returnResult().getResponseBody();
        return new Customer(account, view.membershipId());
    }

    private Account login() {
        String phone = String.format("+91987658%04d", PHONE_COUNTER.getAndIncrement());
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
        body.put("name", "Extension Mess");
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

    private SubscriptionView sell(Fixture f, LocalDate start) {
        Map<String, Object> body = new HashMap<>();
        body.put("membershipId", f.membership);
        body.put("planId", f.plan.id());
        body.put("startDate", start.toString());
        return send("POST", subsUrl(f.provider), f.owner.accessToken(), body)
                .expectStatus().isCreated().expectBody(SubscriptionView.class).returnResult().getResponseBody();
    }

    private static String subsUrl(UUID provider) {
        return "/api/v1/providers/" + provider + "/subscriptions";
    }

    private static String extUrl(UUID provider, UUID subscription) {
        return subsUrl(provider) + "/" + subscription + "/extension";
    }

    // ---- extension calls

    private RestTestClient.ResponseSpec apply(Account who, UUID provider, UUID subscription) {
        return send("POST", extUrl(provider, subscription) + "/apply", who.accessToken(), null);
    }

    private ExtensionStatusView status(Account who, UUID provider, UUID subscription) {
        return status(who, provider, subscription, false).expectStatus().isOk().expectBody(ExtensionStatusView.class)
                .returnResult().getResponseBody();
    }

    /** The raw response, for the error cases (the flag only selects this overload). */
    private RestTestClient.ResponseSpec status(Account who, UUID provider, UUID subscription, boolean raw) {
        return send("GET", extUrl(provider, subscription), who.accessToken(), null);
    }

    private List<ExtensionEventView> events(Account who, UUID provider, UUID subscription) {
        ExtensionEventView[] body = send("GET", extUrl(provider, subscription) + "/events", who.accessToken(), null)
                .expectStatus().isOk().expectBody(ExtensionEventView[].class).returnResult().getResponseBody();
        return body == null ? List.of() : List.of(body);
    }


    private RestTestClient.ResponseSpec correct(Account who, UUID provider, UUID subscription, LocalDate date,
            String status, String reason) {
        return send("PUT", subsUrl(provider) + "/" + subscription + "/attendance/" + date, who.accessToken(),
                Map.of("status", status, "reason", reason));
    }


    private RestTestClient.ResponseSpec declare(Account who, UUID subscription, LocalDate from, LocalDate to) {
        return send("POST", "/api/v1/subscriptions/" + subscription + "/absence", who.accessToken(),
                Map.of("fromDate", from.toString(), "toDate", to.toString()));
    }

    private int absenceRows(UUID subscriptionId) {
        return jdbc.queryForObject("select count(*) from absence_record where subscription_id = ?", Integer.class,
                subscriptionId);
    }

    private int declaredAbsencesOf(UUID subscriptionId) {
        return jdbc.queryForObject(
                "select count(*) from absence_record where subscription_id = ? and status = 'DECLARED'",
                Integer.class, subscriptionId);
    }

    /** V12's invariant, read back: effective expiry = base expiry + the days all events applied. */
    private void assertExpiryInvariant(UUID subscriptionId) {
        Integer applied = jdbc.queryForObject(
                "select coalesce(sum(applied_extension_days), 0) from extension_event where subscription_id = ?",
                Integer.class, subscriptionId);
        assertThat(effectiveExpiry(subscriptionId)).isEqualTo(baseExpiry(subscriptionId).plusDays(applied));
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

    private static void assertRejected(Runnable statement) {
        try {
            statement.run();
        } catch (DataAccessException expected) {
            return;
        }
        throw new AssertionError("the database should have refused this statement");
    }

    // ---- database probes and setup

    private int extensionRows(UUID subscriptionId) {
        return jdbc.queryForObject("select count(*) from extension_event where subscription_id = ?", Integer.class,
                subscriptionId);
    }

    private LocalDate effectiveExpiry(UUID subscriptionId) {
        return jdbc.queryForObject("select effective_expiry_date from subscription where id = ?", LocalDate.class,
                subscriptionId);
    }

    private LocalDate baseExpiry(UUID subscriptionId) {
        return jdbc.queryForObject("select base_expiry_date from subscription where id = ?", LocalDate.class,
                subscriptionId);
    }

    /**
     * Writes DECLARED absences for every day from..to straight into the database (days the customer declared back
     * then; the API would rightly refuse past days now). Each day's absence row and its ledger row go in one
     * transaction because the deferred consistency triggers check them together at commit.
     */
    private void absent(UUID subscriptionId, UUID customerAccountId, LocalDate from, LocalDate to) {
        UUID provider = jdbc.queryForObject("select provider_id from subscription where id = ?", UUID.class,
                subscriptionId);
        new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.support.JdbcTransactionManager(jdbc.getDataSource()))
                .executeWithoutResult(txStatus -> {
                    for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                        UUID absenceId = UUID.randomUUID();
                        jdbc.update("""
                                insert into absence_record (id, subscription_id, provider_id, absence_date, source, declared_by, declared_at)
                                values (?, ?, ?, ?, 'CUSTOMER', ?, now())
                                """, absenceId, subscriptionId, provider, d, customerAccountId);
                        jdbc.update("""
                                insert into attendance_record (id, subscription_id, provider_id, attendance_date, status, source,
                                                               absence_id, recorded_by, recorded_at)
                                values (gen_random_uuid(), ?, ?, ?, 'ABSENT', 'CUSTOMER', ?, ?, now())
                                """, subscriptionId, provider, d, absenceId, customerAccountId);
                    }
                });
    }

    /**
     * Moves a just-sold DAY subscription {@code days} into the past, keeping its dates consistent with its snapshot.
     * Needs the identity trigger off for the moment. The V12 invariant (expiry = base + applied) is untouched because
     * all three dates move by the same amount.
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
