package com.firstfood.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstfood.AbstractIntegrationTest;
import com.firstfood.identity.TestCapturingOtpSender;
import com.firstfood.identity.dto.AuthTokensResponse;
import com.firstfood.identity.dto.OtpRequestRequest;
import com.firstfood.identity.dto.OtpVerifyRequest;
import com.firstfood.identity.dto.ProfileResponse;
import com.firstfood.membership.CustomerView;
import com.firstfood.plan.ConsumptionType;
import com.firstfood.plan.PlanView;
import com.firstfood.provider.dto.ProviderResponse;
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
 * Phase 7 HTTP-level tests: Subscription + SubscriptionTermSnapshot. Real Postgres + Redis, real OTP
 * login. Phones are generated as +91987656xxxx (earlier phases use ...650xxxx to ...655xxxx); the
 * database is a shared singleton container, so do not reuse that prefix elsewhere.
 *
 * "Today" is read in the business time zone exactly as the service does (Asia/Kolkata by default).
 * A subscription that has already lapsed cannot be sold (it would end in the past), so the renewal
 * tests backdate a freshly sold one through SQL with the identity trigger switched off - the only
 * way to reach that state before the Phase 9 background job exists.
 */
class SubscriptionApiIntegrationTest extends AbstractIntegrationTest {

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
    void subscriptionEndpointsRequireAuthentication() {
        UUID id = UUID.randomUUID();
        String base = "/api/v1/providers/" + id + "/subscriptions";
        for (String[] call : new String[][] {
                {"GET", base}, {"POST", base}, {"GET", base + "/" + id}, {"POST", base + "/" + id + "/cancel"},
                {"POST", base + "/" + id + "/renew"}, {"GET", "/api/v1/subscriptions"},
                {"GET", "/api/v1/subscriptions/" + id}}) {
            Object body = call[0].equals("GET") ? null : Map.of();
            assertThat(statusOf(send(call[0], call[1], null, body))).as(call[0] + " " + call[1]).isEqualTo(401);
        }
    }

    // ------------------------------------------------------------ selling

    @Test
    void ownerSellsADayPlanAndTheTermsAreFrozenInASnapshot() {
        Fixture f = fixture("Day Mess", null);
        PlanView plan = createPlan(f.owner, f.provider, dayPlan("Monthly", "2500", 30, true, 2, 45));

        SubscriptionView s = sell(f.owner, f.provider, f.membership, plan.id(), null);

        assertThat(s.status()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(s.phase()).isEqualTo(SubscriptionPhase.RUNNING);
        assertThat(s.consumptionType()).isEqualTo(ConsumptionType.DAY);
        assertThat(s.startDate()).isEqualTo(today());
        assertThat(s.baseExpiryDate()).isEqualTo(today().plusDays(29));
        assertThat(s.effectiveExpiryDate()).isEqualTo(today().plusDays(29));
        assertThat(s.maximumExpiryDate()).isEqualTo(today().plusDays(44));
        assertThat(s.remainingDays()).isEqualTo(30);
        assertThat(s.remainingMeals()).isNull();
        assertThat(s.customerName()).isEqualTo(f.customerName);
        assertThat(s.createdBy()).isEqualTo(f.owner.id());
        assertThat(s.cancellable()).isTrue();
        assertThat(s.renewable()).isFalse();
        assertThat(s.renewedFromSubscriptionId()).isNull();
        assertThat(s.terms().planName()).isEqualTo("Monthly");
        assertThat(s.terms().price()).isEqualByComparingTo("2500.00");
        assertThat(s.terms().currency()).isEqualTo("INR");
        assertThat(s.terms().purchasedDays()).isEqualTo(30);
        assertThat(s.terms().policyVersion()).isEqualTo(1);
        assertThat(s.terms().extensionAllowed()).isTrue();
        assertThat(s.terms().minConsecutiveAbsenceDays()).isEqualTo(2);
        assertThat(s.terms().maxCalendarWindowDays()).isEqualTo(45);
        assertThat(snapshotRows(s.id())).isEqualTo(1);
        assertThat(get(f.owner, f.provider, s.id()).id()).isEqualTo(s.id());
        assertThat(list(f.owner, f.provider, "")).extracting(SubscriptionView::id).containsExactly(s.id());
    }

    @Test
    void ownerSellsMealPlansWithAndWithoutACalendarWindow() {
        Fixture f = fixture("Meal Mess", null);
        PlanView windowed = createPlan(f.owner, f.provider, mealPlan("60 meals", "2500", 60, 90));
        SubscriptionView s = sell(f.owner, f.provider, f.membership, windowed.id(), null);
        assertThat(s.consumptionType()).isEqualTo(ConsumptionType.MEAL);
        assertThat(s.remainingMeals()).isEqualTo(60);
        assertThat(s.baseExpiryDate()).isEqualTo(today().plusDays(89));
        assertThat(s.terms().purchasedMeals()).isEqualTo(60);
        assertThat(s.terms().purchasedDays()).isNull();
        cancel(f.owner, f.provider, s.id(), null);

        PlanView open = createPlan(f.owner, f.provider, mealPlan("20 meals", "900", 20, null));
        SubscriptionView unbounded = sell(f.owner, f.provider, f.membership, open.id(), null);
        assertThat(unbounded.baseExpiryDate()).isNull();
        assertThat(unbounded.effectiveExpiryDate()).isNull();
        assertThat(unbounded.remainingDays()).isNull();
        assertThat(unbounded.remainingMeals()).isEqualTo(20);
        assertThat(unbounded.phase()).isEqualTo(SubscriptionPhase.RUNNING);
    }

    @Test
    void aFutureStartIsUpcomingAndKeepsItsFullSpan() {
        Fixture f = fixture("Upcoming Mess", null);
        PlanView plan = createPlan(f.owner, f.provider, dayPlan("Monthly", "2500", 30, false, null, null));

        SubscriptionView s = sell(f.owner, f.provider, f.membership, plan.id(), today().plusDays(5));

        assertThat(s.phase()).isEqualTo(SubscriptionPhase.UPCOMING);
        assertThat(s.remainingDays()).isEqualTo(30);
        assertThat(s.baseExpiryDate()).isEqualTo(today().plusDays(34));
        assertThat(s.cancellable()).isTrue();
    }

    @Test
    void laterPlanEditsNeverChangeASoldSubscription() {
        Fixture f = fixture("Frozen Mess", null);
        PlanView plan = createPlan(f.owner, f.provider, dayPlan("Monthly", "2500", 30, false, null, null));
        SubscriptionView sold = sell(f.owner, f.provider, f.membership, plan.id(), null);

        Map<String, Object> edit = new HashMap<>();
        edit.put("name", "Monthly Deluxe");
        edit.put("durationDays", 30);
        edit.put("price", new BigDecimal("4000"));
        edit.put("policy", Map.of("extensionAllowed", true, "minConsecutiveAbsenceDays", 3,
                "sameDayAbsenceAllowed", true, "maxCalendarWindowDays", 60));
        send("PUT", "/api/v1/providers/" + f.provider + "/plans/" + plan.id(), f.owner.accessToken(), edit)
                .expectStatus().isOk();

        SubscriptionView after = get(f.owner, f.provider, sold.id());
        assertThat(after.terms().planName()).isEqualTo("Monthly");
        assertThat(after.terms().price()).isEqualByComparingTo("2500.00");
        assertThat(after.terms().policyVersion()).isEqualTo(1);
        assertThat(after.terms().extensionAllowed()).isFalse();
        assertThat(after.terms().maxCalendarWindowDays()).isNull();
        assertThat(after.baseExpiryDate()).isEqualTo(sold.baseExpiryDate());
        assertThat(snapshotRows(sold.id())).isEqualTo(1);
    }

    @Test
    void saleRejectsBadDatesAndMalformedRequests() {
        Fixture f = fixture("Dates Mess", null);
        PlanView plan = createPlan(f.owner, f.provider, dayPlan("Monthly", "2500", 30, false, null, null));

        assertError(sendSale(f.owner, f.provider, f.membership, plan.id(), today().minusDays(367)),
                HttpStatus.BAD_REQUEST, "SUBSCRIPTION_START_INVALID");
        assertError(sendSale(f.owner, f.provider, f.membership, plan.id(), today().plusDays(91)),
                HttpStatus.BAD_REQUEST, "SUBSCRIPTION_START_INVALID");
        // A 30-day plan starting 40 days ago would already be over.
        assertError(sendSale(f.owner, f.provider, f.membership, plan.id(), today().minusDays(40)),
                HttpStatus.BAD_REQUEST, "SUBSCRIPTION_PERIOD_IN_PAST");
        // Starting 29 days ago, the last day is today: still sellable.
        assertThat(sell(f.owner, f.provider, f.membership, plan.id(), today().minusDays(29)).remainingDays())
                .isEqualTo(1);

        assertError(send("POST", subsUrl(f.provider), f.owner.accessToken(), Map.of("planId", plan.id())),
                HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        assertError(send("POST", subsUrl(f.provider), f.owner.accessToken(), Map.of("membershipId", f.membership)),
                HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        assertThat(subscriptionRows(f.provider)).isEqualTo(1);
    }

    @Test
    void clientCannotChooseThePriceStatusOrActor() {
        Fixture f = fixture("Ctx Mess", null);
        PlanView plan = createPlan(f.owner, f.provider, dayPlan("Monthly", "2500", 30, false, null, null));
        Map<String, Object> body = new HashMap<>();
        body.put("membershipId", f.membership);
        body.put("planId", plan.id());
        body.put("price", 1);
        body.put("status", "CANCELLED");
        body.put("createdBy", UUID.randomUUID());
        body.put("providerId", UUID.randomUUID());

        SubscriptionView s = send("POST", subsUrl(f.provider), f.owner.accessToken(), body)
                .expectStatus().isCreated().expectBody(SubscriptionView.class).returnResult().getResponseBody();

        assertThat(s.terms().price()).isEqualByComparingTo("2500.00");
        assertThat(s.status()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(s.createdBy()).isEqualTo(f.owner.id());
        assertThat(s.providerId()).isEqualTo(f.provider);
    }

    // ------------------------------------------------------------ refusals

    @Test
    void overlappingActiveSubscriptionsAreRefusedButAdjacentOnesAreAllowed() {
        Fixture f = fixture("Overlap Mess", null);
        PlanView plan = createPlan(f.owner, f.provider, dayPlan("Monthly", "2500", 30, false, null, null));
        SubscriptionView first = sell(f.owner, f.provider, f.membership, plan.id(), null);

        assertError(sendSale(f.owner, f.provider, f.membership, plan.id(), null),
                HttpStatus.CONFLICT, "SUBSCRIPTION_OVERLAP");
        assertError(sendSale(f.owner, f.provider, f.membership, plan.id(), today().plusDays(29)),
                HttpStatus.CONFLICT, "SUBSCRIPTION_OVERLAP");
        SubscriptionView next = sell(f.owner, f.provider, f.membership, plan.id(), today().plusDays(30));
        assertThat(next.phase()).isEqualTo(SubscriptionPhase.UPCOMING);
        assertThat(subscriptionRows(f.provider)).isEqualTo(2);

        // A cancelled subscription stops blocking.
        cancel(f.owner, f.provider, next.id(), null);
        cancel(f.owner, f.provider, first.id(), null);
        sell(f.owner, f.provider, f.membership, plan.id(), null);
    }

    @Test
    void providerCapacityLimitsLiveSubscriptions() {
        Fixture f = fixture("Capacity Mess", 1);
        Customer second = addCustomer(f.owner, f.provider);
        PlanView plan = createPlan(f.owner, f.provider, dayPlan("Monthly", "2500", 30, false, null, null));
        SubscriptionView first = sell(f.owner, f.provider, f.membership, plan.id(), null);

        assertError(sendSale(f.owner, f.provider, second.membershipId, plan.id(), null),
                HttpStatus.CONFLICT, "PROVIDER_AT_CAPACITY");

        cancel(f.owner, f.provider, first.id(), null);
        sell(f.owner, f.provider, second.membershipId, plan.id(), null);
    }

    @Test
    void inactivePlansForeignPlansAndForeignOrEndedMembershipsAreRefused() {
        Fixture f = fixture("Refuse Mess", null);
        Fixture other = fixture("Other Mess", null);
        PlanView plan = createPlan(f.owner, f.provider, dayPlan("Monthly", "2500", 30, false, null, null));
        PlanView foreignPlan = createPlan(other.owner, other.provider, dayPlan("Theirs", "10", 30, false, null, null));

        // Another provider's plan or membership is simply not found, even for an owner of both.
        assertError(sendSale(f.owner, f.provider, f.membership, foreignPlan.id(), null),
                HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND");
        assertError(sendSale(f.owner, f.provider, other.membership, plan.id(), null),
                HttpStatus.NOT_FOUND, "MEMBERSHIP_NOT_FOUND");
        assertError(sendSale(f.owner, f.provider, f.membership, UUID.randomUUID(), null),
                HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND");

        send("POST", "/api/v1/providers/" + f.provider + "/plans/" + plan.id() + "/deactivate",
                f.owner.accessToken(), Map.of()).expectStatus().isOk();
        assertError(sendSale(f.owner, f.provider, f.membership, plan.id(), null),
                HttpStatus.CONFLICT, "PLAN_NOT_ACTIVE");
        send("POST", "/api/v1/providers/" + f.provider + "/plans/" + plan.id() + "/activate",
                f.owner.accessToken(), Map.of()).expectStatus().isOk();

        send("POST", "/api/v1/providers/" + f.provider + "/customers/" + f.membership + "/deactivate",
                f.owner.accessToken(), Map.of()).expectStatus().isOk();
        assertError(sendSale(f.owner, f.provider, f.membership, plan.id(), null),
                HttpStatus.CONFLICT, "MEMBERSHIP_NOT_ACTIVE");
        assertThat(subscriptionRows(f.provider)).isZero();
    }

    // ------------------------------------------------------------ roles and isolation

    @Test
    void rolesDecideWhoMaySeeAndSellSubscriptions() {
        Fixture f = fixture("Roles Mess", null);
        Account manager = login();
        Account worker = login();
        Account outsider = login();
        assignRole(f.owner, f.provider, manager, "MANAGER");
        assignRole(f.owner, f.provider, worker, "WORKER");
        PlanView plan = createPlan(f.owner, f.provider, dayPlan("Monthly", "2500", 30, false, null, null));

        SubscriptionView byManager = sell(manager, f.provider, f.membership, plan.id(), null);
        assertThat(byManager.createdBy()).isEqualTo(manager.id());
        assertThat(list(manager, f.provider, "")).hasSize(1);
        assertThat(get(manager, f.provider, byManager.id()).id()).isEqualTo(byManager.id());
        assertThat(cancel(manager, f.provider, byManager.id(), "moved away").status())
                .isEqualTo(SubscriptionStatus.CANCELLED);

        SubscriptionView live = sell(f.owner, f.provider, f.membership, plan.id(), null);
        String forbidden = "INSUFFICIENT_PERMISSION";
        assertError(send("GET", subsUrl(f.provider), worker.accessToken(), null), HttpStatus.FORBIDDEN, forbidden);
        assertError(send("GET", subsUrl(f.provider) + "/" + live.id(), worker.accessToken(), null),
                HttpStatus.FORBIDDEN, forbidden);
        assertError(sendSale(worker, f.provider, f.membership, plan.id(), null), HttpStatus.FORBIDDEN, forbidden);
        assertError(send("POST", subsUrl(f.provider) + "/" + live.id() + "/cancel", worker.accessToken(), Map.of()),
                HttpStatus.FORBIDDEN, forbidden);
        assertError(send("POST", subsUrl(f.provider) + "/" + live.id() + "/renew", worker.accessToken(), Map.of()),
                HttpStatus.FORBIDDEN, forbidden);

        String hidden = "PROVIDER_NOT_FOUND";
        assertError(send("GET", subsUrl(f.provider), outsider.accessToken(), null), HttpStatus.NOT_FOUND, hidden);
        assertError(send("GET", subsUrl(f.provider) + "/" + live.id(), outsider.accessToken(), null),
                HttpStatus.NOT_FOUND, hidden);
        assertError(sendSale(outsider, f.provider, f.membership, plan.id(), null), HttpStatus.NOT_FOUND, hidden);
        assertError(send("POST", subsUrl(f.provider) + "/" + live.id() + "/cancel", outsider.accessToken(), Map.of()),
                HttpStatus.NOT_FOUND, hidden);
    }

    @Test
    void aSubscriptionIsNotReachableThroughAnotherProvidersPath() {
        Fixture a = fixture("Iso A", null);
        // The same account owns provider B, so authorization passes there: only the scoping can stop it.
        UUID b = createProvider(a.owner, "Iso B", null);
        PlanView plan = createPlan(a.owner, a.provider, dayPlan("Monthly", "2500", 30, false, null, null));
        SubscriptionView s = sell(a.owner, a.provider, a.membership, plan.id(), null);

        assertError(send("GET", subsUrl(b) + "/" + s.id(), a.owner.accessToken(), null),
                HttpStatus.NOT_FOUND, "SUBSCRIPTION_NOT_FOUND");
        assertError(send("POST", subsUrl(b) + "/" + s.id() + "/cancel", a.owner.accessToken(), Map.of()),
                HttpStatus.NOT_FOUND, "SUBSCRIPTION_NOT_FOUND");
        assertError(send("POST", subsUrl(b) + "/" + s.id() + "/renew", a.owner.accessToken(), Map.of()),
                HttpStatus.NOT_FOUND, "SUBSCRIPTION_NOT_FOUND");
        assertThat(list(a.owner, b, "")).isEmpty();
        assertThat(get(a.owner, a.provider, s.id()).status()).isEqualTo(SubscriptionStatus.ACTIVE);
    }

    // ------------------------------------------------------------ cancel

    @Test
    void cancellingRecordsWhoWhenAndWhyAndIsFinal() {
        Fixture f = fixture("Cancel Mess", null);
        PlanView plan = createPlan(f.owner, f.provider, dayPlan("Monthly", "2500", 30, false, null, null));
        SubscriptionView s = sell(f.owner, f.provider, f.membership, plan.id(), null);

        SubscriptionView cancelled = cancel(f.owner, f.provider, s.id(), "  Left the city  ");

        assertThat(cancelled.status()).isEqualTo(SubscriptionStatus.CANCELLED);
        assertThat(cancelled.phase()).isEqualTo(SubscriptionPhase.CANCELLED);
        assertThat(cancelled.cancelledBy()).isEqualTo(f.owner.id());
        assertThat(cancelled.cancelledAt()).isNotNull();
        assertThat(cancelled.cancellationReason()).isEqualTo("Left the city");
        assertThat(cancelled.remainingDays()).isZero();
        assertThat(cancelled.cancellable()).isFalse();
        assertThat(cancelled.renewable()).isFalse();
        assertThat(cancelled.terms().price()).isEqualByComparingTo("2500.00");

        assertError(send("POST", subsUrl(f.provider) + "/" + s.id() + "/cancel", f.owner.accessToken(), Map.of()),
                HttpStatus.CONFLICT, "SUBSCRIPTION_NOT_ACTIVE");
        // History is kept: the row and its snapshot are still there.
        assertThat(subscriptionRows(f.provider)).isEqualTo(1);
        assertThat(snapshotRows(s.id())).isEqualTo(1);
    }

    @Test
    void cancelWorksWithoutABodyAndRejectsAnOverlongReason() {
        Fixture f = fixture("Cancel Body Mess", null);
        PlanView plan = createPlan(f.owner, f.provider, dayPlan("Monthly", "2500", 30, false, null, null));
        SubscriptionView s = sell(f.owner, f.provider, f.membership, plan.id(), null);

        assertError(send("POST", subsUrl(f.provider) + "/" + s.id() + "/cancel", f.owner.accessToken(),
                Map.of("reason", "x".repeat(501))), HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        SubscriptionView cancelled = send("POST", subsUrl(f.provider) + "/" + s.id() + "/cancel",
                f.owner.accessToken(), null).expectStatus().isOk().expectBody(SubscriptionView.class)
                .returnResult().getResponseBody();
        assertThat(cancelled.status()).isEqualTo(SubscriptionStatus.CANCELLED);
        assertThat(cancelled.cancellationReason()).isNull();
    }

    @Test
    void aCustomerWithALiveSubscriptionCannotBeRemovedUntilItIsCancelled() {
        Fixture f = fixture("Guard Mess", null);
        PlanView plan = createPlan(f.owner, f.provider, dayPlan("Monthly", "2500", 30, false, null, null));
        SubscriptionView s = sell(f.owner, f.provider, f.membership, plan.id(), null);
        String deactivate = "/api/v1/providers/" + f.provider + "/customers/" + f.membership + "/deactivate";

        assertError(send("POST", deactivate, f.owner.accessToken(), Map.of()),
                HttpStatus.CONFLICT, "MEMBERSHIP_HAS_ACTIVE_SUBSCRIPTION");

        cancel(f.owner, f.provider, s.id(), null);
        send("POST", deactivate, f.owner.accessToken(), Map.of()).expectStatus().isOk();
    }

    // ------------------------------------------------------------ renewal

    @Test
    void anEndedSubscriptionIsRenewedAsANewSaleAtTodaysTerms() {
        Fixture f = fixture("Renew Mess", null);
        PlanView plan = createPlan(f.owner, f.provider, dayPlan("Monthly", "2500", 30, false, null, null));
        SubscriptionView old = sell(f.owner, f.provider, f.membership, plan.id(), null);
        backdate(old.id(), 40);
        SubscriptionView lapsed = get(f.owner, f.provider, old.id());
        assertThat(lapsed.phase()).isEqualTo(SubscriptionPhase.ENDED);
        assertThat(lapsed.status()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(lapsed.renewable()).isTrue();
        assertThat(lapsed.cancellable()).isFalse();

        // The price rises before the renewal: the renewal is a new commercial event.
        Map<String, Object> edit = new HashMap<>();
        edit.put("name", "Monthly");
        edit.put("durationDays", 30);
        edit.put("price", new BigDecimal("3000"));
        // A changed policy gets version 2; the renewal snapshots it.
        edit.put("policy", Map.of("extensionAllowed", false, "sameDayAbsenceAllowed", true));
        send("PUT", "/api/v1/providers/" + f.provider + "/plans/" + plan.id(), f.owner.accessToken(), edit)
                .expectStatus().isOk();

        SubscriptionView renewed = send("POST", subsUrl(f.provider) + "/" + old.id() + "/renew",
                f.owner.accessToken(), null).expectStatus().isCreated().expectBody(SubscriptionView.class)
                .returnResult().getResponseBody();

        assertThat(renewed.id()).isNotEqualTo(old.id());
        assertThat(renewed.renewedFromSubscriptionId()).isEqualTo(old.id());
        assertThat(renewed.startDate()).isEqualTo(today());
        assertThat(renewed.baseExpiryDate()).isEqualTo(today().plusDays(29));
        assertThat(renewed.terms().price()).isEqualByComparingTo("3000.00");
        assertThat(renewed.terms().policyVersion()).isEqualTo(2);
        assertThat(renewed.phase()).isEqualTo(SubscriptionPhase.RUNNING);

        SubscriptionView oldAfter = get(f.owner, f.provider, old.id());
        assertThat(oldAfter.status()).isEqualTo(SubscriptionStatus.EXPIRED);
        assertThat(oldAfter.renewed()).isTrue();
        assertThat(oldAfter.renewable()).isFalse();
        assertThat(oldAfter.terms().price()).isEqualByComparingTo("2500.00");
        assertThat(snapshotRows(old.id())).isEqualTo(1);
        assertThat(snapshotRows(renewed.id())).isEqualTo(1);

        assertError(send("POST", subsUrl(f.provider) + "/" + old.id() + "/renew", f.owner.accessToken(), Map.of()),
                HttpStatus.CONFLICT, "SUBSCRIPTION_ALREADY_RENEWED");
    }

    @Test
    void renewalCanChangePlanAndRefusesBadStartsAndStates() {
        Fixture f = fixture("Renew Rules Mess", null);
        PlanView monthly = createPlan(f.owner, f.provider, dayPlan("Monthly", "2500", 30, false, null, null));
        PlanView weekly = createPlan(f.owner, f.provider, dayPlan("Weekly", "700", 7, false, null, null));
        SubscriptionView running = sell(f.owner, f.provider, f.membership, monthly.id(), null);

        // Not ended yet, or cancelled: not renewable.
        assertError(send("POST", subsUrl(f.provider) + "/" + running.id() + "/renew", f.owner.accessToken(), Map.of()),
                HttpStatus.CONFLICT, "SUBSCRIPTION_NOT_RENEWABLE");
        cancel(f.owner, f.provider, running.id(), null);
        assertError(send("POST", subsUrl(f.provider) + "/" + running.id() + "/renew", f.owner.accessToken(), Map.of()),
                HttpStatus.CONFLICT, "SUBSCRIPTION_NOT_RENEWABLE");

        SubscriptionView old = sell(f.owner, f.provider, f.membership, monthly.id(), null);
        backdate(old.id(), 40);
        // A renewal may not start inside, or on the last day of, what it follows.
        assertError(send("POST", subsUrl(f.provider) + "/" + old.id() + "/renew", f.owner.accessToken(),
                Map.of("startDate", today().minusDays(11).toString())),
                HttpStatus.BAD_REQUEST, "SUBSCRIPTION_START_INVALID");
        assertError(send("POST", subsUrl(f.provider) + "/" + old.id() + "/renew", f.owner.accessToken(),
                Map.of("planId", UUID.randomUUID().toString())), HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND");

        SubscriptionView renewed = send("POST", subsUrl(f.provider) + "/" + old.id() + "/renew",
                f.owner.accessToken(), Map.of("planId", weekly.id().toString(),
                        "startDate", today().plusDays(2).toString()))
                .expectStatus().isCreated().expectBody(SubscriptionView.class).returnResult().getResponseBody();
        assertThat(renewed.planId()).isEqualTo(weekly.id());
        assertThat(renewed.terms().planName()).isEqualTo("Weekly");
        assertThat(renewed.phase()).isEqualTo(SubscriptionPhase.UPCOMING);
        assertThat(renewed.baseExpiryDate()).isEqualTo(today().plusDays(8));
    }

    @Test
    void aMemberWhoLeftCannotBeRenewed() {
        Fixture f = fixture("Renew Left Mess", null);
        PlanView plan = createPlan(f.owner, f.provider, dayPlan("Monthly", "2500", 30, false, null, null));
        SubscriptionView old = sell(f.owner, f.provider, f.membership, plan.id(), null);
        backdate(old.id(), 40);
        send("POST", "/api/v1/providers/" + f.provider + "/customers/" + f.membership + "/deactivate",
                f.owner.accessToken(), Map.of()).expectStatus().isOk();

        SubscriptionView lapsed = get(f.owner, f.provider, old.id());
        assertThat(lapsed.renewable()).isFalse();
        assertError(send("POST", subsUrl(f.provider) + "/" + old.id() + "/renew", f.owner.accessToken(), Map.of()),
                HttpStatus.CONFLICT, "MEMBERSHIP_NOT_ACTIVE");
    }

    // ------------------------------------------------------------ customer side

    @Test
    void aCustomerSeesOnlyTheirOwnSubscriptionsWithTheTermsTheyBought() {
        Fixture f = fixture("Mine Mess", null);
        PlanView plan = createPlan(f.owner, f.provider, dayPlan("Monthly", "2500", 30, false, null, null));
        SubscriptionView s = sell(f.owner, f.provider, f.membership, plan.id(), null);
        Account stranger = login();

        MySubscriptionView[] mine = send("GET", "/api/v1/subscriptions", f.customer.accessToken(), null)
                .expectStatus().isOk().expectBody(MySubscriptionView[].class).returnResult().getResponseBody();
        assertThat(mine).hasSize(1);
        assertThat(mine[0].id()).isEqualTo(s.id());
        assertThat(mine[0].providerName()).isEqualTo("Mine Mess");
        assertThat(mine[0].phase()).isEqualTo(SubscriptionPhase.RUNNING);
        assertThat(mine[0].remainingDays()).isEqualTo(30);
        assertThat(mine[0].terms().price()).isEqualByComparingTo("2500.00");
        MySubscriptionView one = send("GET", "/api/v1/subscriptions/" + s.id(), f.customer.accessToken(), null)
                .expectStatus().isOk().expectBody(MySubscriptionView.class).returnResult().getResponseBody();
        assertThat(one.id()).isEqualTo(s.id());

        MySubscriptionView[] none = send("GET", "/api/v1/subscriptions", stranger.accessToken(), null)
                .expectStatus().isOk().expectBody(MySubscriptionView[].class).returnResult().getResponseBody();
        assertThat(none).isEmpty();
        assertError(send("GET", "/api/v1/subscriptions/" + s.id(), stranger.accessToken(), null),
                HttpStatus.NOT_FOUND, "SUBSCRIPTION_NOT_FOUND");
    }

    // ------------------------------------------------------------ database guarantees

    @Test
    void theDatabaseItselfRefusesToRewriteHistory() {
        Fixture f = fixture("History Mess", null);
        PlanView plan = createPlan(f.owner, f.provider, dayPlan("Monthly", "2500", 30, false, null, null));
        SubscriptionView s = sell(f.owner, f.provider, f.membership, plan.id(), null);

        assertThatThrownBy(() -> jdbc.update(
                "update subscription_term_snapshot set price = 1 where subscription_id = ?", s.id()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "delete from subscription_term_snapshot where subscription_id = ?", s.id()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("delete from subscription where id = ?", s.id()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("update subscription set plan_id = ? where id = ?",
                UUID.randomUUID(), s.id())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("update subscription set start_date = start_date + 1 where id = ?",
                s.id())).isInstanceOf(DataAccessException.class);
        assertThat(snapshotRows(s.id())).isEqualTo(1);
    }

    // ------------------------------------------------------------ concurrency

    @Test
    void concurrentSalesToTheSameCustomerProduceExactlyOne() throws Exception {
        Fixture f = fixture("Race Mess", null);
        PlanView plan = createPlan(f.owner, f.provider, dayPlan("Monthly", "2500", 30, false, null, null));

        List<Integer> statuses = runConcurrently(
                () -> statusOf(sendSale(f.owner, f.provider, f.membership, plan.id(), null)),
                () -> statusOf(sendSale(f.owner, f.provider, f.membership, plan.id(), null)),
                () -> statusOf(sendSale(f.owner, f.provider, f.membership, plan.id(), null)));

        assertThat(statuses).containsExactlyInAnyOrder(201, 409, 409);
        assertThat(subscriptionRows(f.provider)).isEqualTo(1);
    }

    @Test
    void concurrentSalesNeverExceedProviderCapacity() throws Exception {
        Fixture f = fixture("Race Capacity Mess", 2);
        Customer c2 = addCustomer(f.owner, f.provider);
        Customer c3 = addCustomer(f.owner, f.provider);
        Customer c4 = addCustomer(f.owner, f.provider);
        PlanView plan = createPlan(f.owner, f.provider, dayPlan("Monthly", "2500", 30, false, null, null));

        List<Integer> statuses = runConcurrently(
                () -> statusOf(sendSale(f.owner, f.provider, f.membership, plan.id(), null)),
                () -> statusOf(sendSale(f.owner, f.provider, c2.membershipId, plan.id(), null)),
                () -> statusOf(sendSale(f.owner, f.provider, c3.membershipId, plan.id(), null)),
                () -> statusOf(sendSale(f.owner, f.provider, c4.membershipId, plan.id(), null)));

        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(2);
        assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(2);
        assertThat(subscriptionRows(f.provider)).isEqualTo(2);
    }

    @Test
    void concurrentRenewalsOfTheSameSubscriptionProduceExactlyOne() throws Exception {
        Fixture f = fixture("Race Renew Mess", null);
        PlanView plan = createPlan(f.owner, f.provider, dayPlan("Monthly", "2500", 30, false, null, null));
        SubscriptionView old = sell(f.owner, f.provider, f.membership, plan.id(), null);
        backdate(old.id(), 40);

        String url = subsUrl(f.provider) + "/" + old.id() + "/renew";
        List<Integer> statuses = runConcurrently(
                () -> statusOf(send("POST", url, f.owner.accessToken(), Map.of())),
                () -> statusOf(send("POST", url, f.owner.accessToken(), Map.of())));

        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(subscriptionRows(f.provider)).isEqualTo(2);
    }

    // ------------------------------------------------------------ helpers

    private record Account(UUID id, String phone, String accessToken) {
    }

    private record Customer(Account account, UUID membershipId, String fullName) {
    }

    /** An owned provider with one member (and the member's own login). */
    private record Fixture(Account owner, UUID provider, Account customer, UUID membership, String customerName) {
    }

    private Fixture fixture(String providerName, Integer maxActive) {
        Account owner = login();
        UUID provider = createProvider(owner, providerName, maxActive);
        Customer c = addCustomer(owner, provider);
        return new Fixture(owner, provider, c.account(), c.membershipId(), c.fullName());
    }

    private Customer addCustomer(Account owner, UUID provider) {
        Account account = login();
        String name = "Customer " + account.phone().substring(account.phone().length() - 4);
        CustomerView view = send("POST", "/api/v1/providers/" + provider + "/customers", owner.accessToken(),
                Map.of("phone", account.phone(), "fullName", name))
                .expectStatus().isCreated().expectBody(CustomerView.class).returnResult().getResponseBody();
        return new Customer(account, view.membershipId(), name);
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

    private static Map<String, Object> dayPlan(String name, String price, int days, boolean extension,
            Integer minConsecutive, Integer window) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("consumptionType", "DAY");
        m.put("durationDays", days);
        m.put("price", new BigDecimal(price));
        m.put("policy", policy(extension, minConsecutive, window));
        return m;
    }

    private static Map<String, Object> mealPlan(String name, String price, int meals, Integer window) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("consumptionType", "MEAL");
        m.put("mealQuantity", meals);
        m.put("price", new BigDecimal(price));
        m.put("policy", policy(false, null, window));
        return m;
    }

    private Account login() {
        String phone = String.format("+91987656%04d", PHONE_COUNTER.getAndIncrement());
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

    private UUID createProvider(Account owner, String name, Integer maxActive) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("providerType", "MESS");
        body.put("addressLine", "12 MP Nagar Zone 1");
        body.put("locality", "MP Nagar");
        body.put("city", "Bhopal");
        body.put("pincode", "462011");
        if (maxActive != null) {
            body.put("maxActiveSubscriptions", maxActive);
        }
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

    private static String subsUrl(UUID providerId) {
        return "/api/v1/providers/" + providerId + "/subscriptions";
    }

    private RestTestClient.ResponseSpec sendSale(Account actor, UUID provider, UUID membership, UUID planId,
            LocalDate start) {
        Map<String, Object> body = new HashMap<>();
        body.put("membershipId", membership);
        body.put("planId", planId);
        if (start != null) {
            body.put("startDate", start.toString());
        }
        return send("POST", subsUrl(provider), actor.accessToken(), body);
    }

    private SubscriptionView sell(Account actor, UUID provider, UUID membership, UUID planId, LocalDate start) {
        return sendSale(actor, provider, membership, planId, start).expectStatus().isCreated()
                .expectBody(SubscriptionView.class).returnResult().getResponseBody();
    }

    private SubscriptionView get(Account actor, UUID provider, UUID id) {
        return send("GET", subsUrl(provider) + "/" + id, actor.accessToken(), null)
                .expectStatus().isOk().expectBody(SubscriptionView.class).returnResult().getResponseBody();
    }

    private List<SubscriptionView> list(Account actor, UUID provider, String query) {
        SubscriptionView[] body = send("GET", subsUrl(provider) + query, actor.accessToken(), null)
                .expectStatus().isOk().expectBody(SubscriptionView[].class).returnResult().getResponseBody();
        return body == null ? List.of() : List.of(body);
    }

    private SubscriptionView cancel(Account actor, UUID provider, UUID id, String reason) {
        Object body = reason == null ? Map.of() : Map.of("reason", reason);
        return send("POST", subsUrl(provider) + "/" + id + "/cancel", actor.accessToken(), body)
                .expectStatus().isOk().expectBody(SubscriptionView.class).returnResult().getResponseBody();
    }

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

    private int subscriptionRows(UUID providerId) {
        return jdbc.queryForObject("select count(*) from subscription where provider_id = ?", Integer.class,
                providerId);
    }

    private int snapshotRows(UUID subscriptionId) {
        return jdbc.queryForObject("select count(*) from subscription_term_snapshot where subscription_id = ?",
                Integer.class, subscriptionId);
    }

    /**
     * Moves a just-sold DAY subscription {@code days} into the past, keeping its dates consistent
     * with its snapshot (start + purchased days - 1). Needs the identity trigger off for the moment.
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
