package com.firstfood.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstfood.AbstractIntegrationTest;
import com.firstfood.identity.TestCapturingOtpSender;
import com.firstfood.identity.dto.AuthTokensResponse;
import com.firstfood.identity.dto.OtpRequestRequest;
import com.firstfood.identity.dto.OtpVerifyRequest;
import com.firstfood.identity.dto.ProfileResponse;
import com.firstfood.provider.dto.ProviderResponse;
import com.firstfood.provideraccess.RoleAssignmentView;
import java.math.BigDecimal;
import java.time.LocalTime;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Phase 6 HTTP-level tests: Plan + SubscriptionPolicy. Real Postgres + Redis, real OTP
 * login. Phones are generated as +91987655xxxx (earlier phases use ...650xxxx to ...654xxxx);
 * the database is a shared singleton container, so do not reuse that prefix elsewhere.
 */
class PlanApiIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger PHONE_COUNTER = new AtomicInteger(1);

    @Autowired
    RestTestClient restClient;

    @Autowired
    TestCapturingOtpSender otpSender;

    @Autowired
    JdbcTemplate jdbc;

    // ------------------------------------------------------------ authentication

    @Test
    void planEndpointsRequireAuthentication() {
        UUID id = UUID.randomUUID();
        String base = "/api/v1/providers/" + id + "/plans";
        for (String[] call : new String[][] {
                {"GET", base}, {"POST", base}, {"GET", base + "/" + id}, {"PUT", base + "/" + id},
                {"POST", base + "/" + id + "/activate"}, {"POST", base + "/" + id + "/deactivate"}}) {
            Object body = call[0].equals("GET") ? null : Map.of();
            assertThat(statusOf(send(call[0], call[1], null, body))).as(call[0] + " " + call[1]).isEqualTo(401);
        }
    }

    // ------------------------------------------------------------ create / read

    @Test
    void ownerCreatesThePilotDayPlanWithItsPolicy() {
        Account owner = login();
        UUID provider = createProvider(owner, "Pilot Mess");

        PlanView plan = create(owner, provider, dayPlan("Monthly", "2500", 30,
                policy(true, 2, false, null, 45)));

        assertThat(plan.consumptionType()).isEqualTo(ConsumptionType.DAY);
        assertThat(plan.durationDays()).isEqualTo(30);
        assertThat(plan.mealQuantity()).isNull();
        assertThat(plan.price()).isEqualByComparingTo("2500.00");
        assertThat(plan.currency()).isEqualTo("INR");
        assertThat(plan.status()).isEqualTo(PlanStatus.ACTIVE);
        assertThat(plan.providerId()).isEqualTo(provider);
        assertThat(plan.createdBy()).isEqualTo(owner.id());
        assertThat(plan.policy().version()).isEqualTo(1);
        assertThat(plan.policy().extensionAllowed()).isTrue();
        assertThat(plan.policy().minConsecutiveAbsenceDays()).isEqualTo(2);
        assertThat(plan.policy().sameDayAbsenceAllowed()).isFalse();
        assertThat(plan.policy().absenceCutoffTime()).isNull();
        assertThat(plan.policy().maxCalendarWindowDays()).isEqualTo(45);

        assertThat(list(owner, provider, false)).extracting(PlanView::id).containsExactly(plan.id());
        PlanView fetched = get(owner, provider, plan.id());
        assertThat(fetched.name()).isEqualTo("Monthly");
        assertThat(fetched.policy().version()).isEqualTo(1);
    }

    @Test
    void ownerCreatesAMealPlanWithSameDayCutoffAndCalendarWindow() {
        Account owner = login();
        UUID provider = createProvider(owner, "Meal Mess");

        PlanView plan = create(owner, provider, mealPlan("60 meals", "2500", 60,
                policy(false, null, true, "09:30", 90)));

        assertThat(plan.consumptionType()).isEqualTo(ConsumptionType.MEAL);
        assertThat(plan.mealQuantity()).isEqualTo(60);
        assertThat(plan.durationDays()).isNull();
        assertThat(plan.policy().sameDayAbsenceAllowed()).isTrue();
        assertThat(plan.policy().absenceCutoffTime()).isEqualTo(LocalTime.of(9, 30));
        assertThat(plan.policy().maxCalendarWindowDays()).isEqualTo(90);
    }

    @Test
    void nameIsTrimmedAndCreatorAndProviderComeFromTheRequestContextNotTheBody() {
        Account owner = login();
        UUID provider = createProvider(owner, "Ctx Mess");
        UUID otherProvider = createProvider(owner, "Ctx Mess Two");

        Map<String, Object> body = dayPlan("  Padded  ", "100", 7, policy(false, null, false, null, null));
        // Client-supplied scope/actor/status are ignored, never trusted.
        body.put("providerId", otherProvider.toString());
        body.put("createdBy", UUID.randomUUID().toString());
        body.put("status", "INACTIVE");
        body.put("currency", "USD");

        PlanView plan = create(owner, provider, body);
        assertThat(plan.name()).isEqualTo("Padded");
        assertThat(plan.providerId()).isEqualTo(provider);
        assertThat(plan.createdBy()).isEqualTo(owner.id());
        assertThat(plan.status()).isEqualTo(PlanStatus.ACTIVE);
        assertThat(plan.currency()).isEqualTo("INR");
        assertThat(planRows(otherProvider)).isZero();
    }

    @Test
    void oneOwnerMayUseTheSamePlanNameAtTwoProviders() {
        Account owner = login();
        UUID a = createProvider(owner, "Twin A");
        UUID b = createProvider(owner, "Twin B");
        create(owner, a, dayPlan("Monthly", "2500", 30, policy(false, null, false, null, null)));
        create(owner, b, dayPlan("Monthly", "3000", 30, policy(false, null, false, null, null)));

        assertThat(list(owner, a, false)).hasSize(1);
        assertThat(list(owner, b, false)).hasSize(1);
        assertThat(list(owner, b, false).get(0).price()).isEqualByComparingTo("3000");
    }

    // ------------------------------------------------------------ validation

    @Test
    void invalidPlanTermsAreRejectedAndNothingIsStored() {
        Account owner = login();
        UUID provider = createProvider(owner, "Invalid Mess");
        PolicyBody none = policy(false, null, false, null, null);

        // consumption-specific fields
        assertError(sendCreate(owner, provider, with(dayPlan("a", "10", 30, none), "mealQuantity", 60)),
                HttpStatus.BAD_REQUEST, "PLAN_TERMS_INVALID");
        assertError(sendCreate(owner, provider, without(dayPlan("a", "10", 30, none), "durationDays")),
                HttpStatus.BAD_REQUEST, "PLAN_TERMS_INVALID");
        assertError(sendCreate(owner, provider, with(mealPlan("a", "10", 60, none), "durationDays", 30)),
                HttpStatus.BAD_REQUEST, "PLAN_TERMS_INVALID");
        assertError(sendCreate(owner, provider, without(mealPlan("a", "10", 60, none), "mealQuantity")),
                HttpStatus.BAD_REQUEST, "PLAN_TERMS_INVALID");

        // policy consistency
        assertError(sendCreate(owner, provider, mealPlan("a", "10", 60, policy(true, 2, false, null, null))),
                HttpStatus.BAD_REQUEST, "PLAN_POLICY_INVALID");
        assertError(sendCreate(owner, provider, dayPlan("a", "10", 30, policy(true, null, false, null, null))),
                HttpStatus.BAD_REQUEST, "PLAN_POLICY_INVALID");
        assertError(sendCreate(owner, provider, dayPlan("a", "10", 30, policy(false, 2, false, null, null))),
                HttpStatus.BAD_REQUEST, "PLAN_POLICY_INVALID");
        assertError(sendCreate(owner, provider, dayPlan("a", "10", 30, policy(false, null, false, "09:00", null))),
                HttpStatus.BAD_REQUEST, "PLAN_POLICY_INVALID");
        assertError(sendCreate(owner, provider, dayPlan("a", "10", 30, policy(true, 2, false, null, 29))),
                HttpStatus.BAD_REQUEST, "PLAN_POLICY_INVALID");
        assertError(sendCreate(owner, provider, dayPlan("a", "10", 30, policy(false, null, false, null, 45))),
                HttpStatus.BAD_REQUEST, "PLAN_POLICY_INVALID");

        // field-level validation
        assertError(sendCreate(owner, provider, dayPlan("a", "0", 30, none)), HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        assertError(sendCreate(owner, provider, dayPlan("a", "-5", 30, none)), HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        assertError(sendCreate(owner, provider, dayPlan("a", "10.999", 30, none)), HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR");
        assertError(sendCreate(owner, provider, dayPlan("a", "10", 0, none)), HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        assertError(sendCreate(owner, provider, dayPlan("a", "10", 367, none)), HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR");
        assertError(sendCreate(owner, provider, dayPlan("   ", "10", 30, none)), HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR");
        assertError(sendCreate(owner, provider, without(dayPlan("a", "10", 30, none), "policy")),
                HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        assertError(sendCreate(owner, provider, without(dayPlan("a", "10", 30, none), "consumptionType")),
                HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        assertError(sendCreate(owner, provider, with(dayPlan("a", "10", 30, none), "consumptionType", "WEEK")),
                HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        assertError(sendCreate(owner, provider, dayPlan("a", "10", 30, policy(false, null, true, "25:99", null))),
                HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");

        assertThat(planRows(provider)).isZero();
    }

    // ------------------------------------------------------------ editing / policy versioning

    @Test
    void changingThePriceKeepsOnePolicyVersionAndMovesUpdatedAt() {
        Account owner = login();
        UUID provider = createProvider(owner, "Price Mess");
        PlanView plan = create(owner, provider, dayPlan("Monthly", "2500", 30, policy(true, 2, false, null, 45)));

        PlanView edited = update(owner, provider, plan.id(),
                dayPlan("Monthly", "2800", 30, policy(true, 2, false, null, 45)));

        assertThat(edited.price()).isEqualByComparingTo("2800");
        assertThat(edited.policy().version()).isEqualTo(1);
        assertThat(edited.updatedAt()).isAfter(plan.updatedAt());
        assertThat(edited.updatedBy()).isEqualTo(owner.id());
        assertThat(policyVersions(plan.id())).containsExactly(1);
    }

    @Test
    void changingThePolicyInsertsANewVersionAndLeavesTheOldRowUntouched() {
        Account owner = login();
        UUID provider = createProvider(owner, "Policy Mess");
        PlanView plan = create(owner, provider, dayPlan("Monthly", "2500", 30, policy(true, 2, false, null, 45)));

        PlanView edited = update(owner, provider, plan.id(),
                dayPlan("Monthly", "2500", 30, policy(true, 3, true, "10:00", 50)));

        assertThat(edited.policy().version()).isEqualTo(2);
        assertThat(edited.policy().minConsecutiveAbsenceDays()).isEqualTo(3);
        assertThat(edited.policy().sameDayAbsenceAllowed()).isTrue();
        assertThat(edited.policy().absenceCutoffTime()).isEqualTo(LocalTime.of(10, 0));
        assertThat(edited.policy().maxCalendarWindowDays()).isEqualTo(50);
        assertThat(get(owner, provider, plan.id()).policy().version()).isEqualTo(2);

        // History: version 1 still holds exactly what it held when it was current (Rule 14.3).
        Map<String, Object> v1 = jdbc.queryForMap(
                "select * from subscription_policy where plan_id = ? and version = 1", plan.id());
        assertThat(v1.get("min_consecutive_absence_days")).isEqualTo(2);
        assertThat(v1.get("same_day_absence_allowed")).isEqualTo(false);
        assertThat(v1.get("absence_cutoff_time")).isNull();
        assertThat(v1.get("max_calendar_window_days")).isEqualTo(45);
        assertThat(policyVersions(plan.id())).containsExactly(1, 2);
    }

    @Test
    void resubmittingIdenticalTermsChangesNothing() {
        Account owner = login();
        UUID provider = createProvider(owner, "Noop Mess");
        Map<String, Object> body = dayPlan("Monthly", "2500", 30, policy(true, 2, true, "09:30:45", 45));
        PlanView plan = create(owner, provider, body);

        PlanView again = update(owner, provider, plan.id(), body);

        assertThat(again.policy().version()).isEqualTo(1);
        assertThat(again.updatedAt()).isEqualTo(plan.updatedAt());
        assertThat(policyVersions(plan.id())).containsExactly(1);
    }

    @Test
    void theConsumptionTypeIsFixedAndTheQuantityMustMatchIt() {
        Account owner = login();
        UUID provider = createProvider(owner, "Fixed Mess");
        PlanView plan = create(owner, provider, dayPlan("Monthly", "2500", 30, policy(false, null, false, null, null)));

        // A consumptionType in an update body is ignored: the plan stays DAY.
        Map<String, Object> body = dayPlan("Monthly", "2500", 30, policy(false, null, false, null, null));
        body.put("consumptionType", "MEAL");
        assertThat(update(owner, provider, plan.id(), body).consumptionType()).isEqualTo(ConsumptionType.DAY);

        // And a DAY plan cannot be turned into a meal count.
        assertError(sendUpdate(owner, provider, plan.id(),
                with(without(dayPlan("Monthly", "2500", 30, policy(false, null, false, null, null)), "durationDays"),
                        "mealQuantity", 60)), HttpStatus.BAD_REQUEST, "PLAN_TERMS_INVALID");
        assertThat(get(owner, provider, plan.id()).durationDays()).isEqualTo(30);
    }

    @Test
    void policyRowsAreImmutableInTheDatabase() {
        Account owner = login();
        UUID provider = createProvider(owner, "Immutable Mess");
        PlanView plan = create(owner, provider, dayPlan("Monthly", "2500", 30, policy(true, 2, false, null, 45)));

        assertThatThrownBy(() -> jdbc.update(
                "update subscription_policy set min_consecutive_absence_days = 5 where plan_id = ?", plan.id()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("delete from subscription_policy where plan_id = ?", plan.id()))
                .isInstanceOf(DataAccessException.class);
        assertThat(get(owner, provider, plan.id()).policy().minConsecutiveAbsenceDays()).isEqualTo(2);
    }

    @Test
    void databaseConstraintsRejectInvalidRowsEvenWithoutTheApplicationRules() {
        Account owner = login();
        UUID provider = createProvider(owner, "Constraint Mess");

        // plan columns: (consumption_type, duration_days, meal_quantity, price)
        for (String values : List.of(
                "'DAY', 30, 60, 100",    // DAY must not carry a meal quantity
                "'MEAL', 30, 60, 100",   // MEAL must not carry a duration
                "'DAY', null, null, 100", // DAY needs a duration
                "'MEAL', null, null, 100", // MEAL needs a quantity
                "'DAY', 30, null, 0",    // price must be positive
                "'DAY', 0, null, 100",   // duration range
                "'DAY', 400, null, 100",
                "'MEAL', null, 0, 100",  // quantity range
                "'WEEK', 30, null, 100")) { // unknown consumption type
            assertThatThrownBy(() -> jdbc.update(
                    "insert into plan (provider_id, name, consumption_type, duration_days, meal_quantity, price,"
                            + " created_by, updated_by) values (?, 'x', " + values + ", ?, ?)",
                    provider, owner.id(), owner.id()))
                    .as(values).isInstanceOf(DataIntegrityViolationException.class);
        }

        PlanView plan = create(owner, provider, dayPlan("Real", "100", 30, policy(false, null, false, null, null)));

        // policy columns: (version, extension_allowed, min_consecutive, same_day, cutoff, window)
        for (String values : List.of(
                "2, false, 3, false, null, null",   // extension off but a minimum given
                "2, true, null, false, null, null", // extension on without a minimum
                "2, true, 0, false, null, null",    // minimum below 1
                "2, false, null, false, '09:00', null", // cutoff without same-day absence
                "0, false, null, false, null, null", // version below 1
                "2, false, null, false, null, 0")) { // window below 1
            assertThatThrownBy(() -> jdbc.update(
                    "insert into subscription_policy (plan_id, version, extension_allowed, min_consecutive_absence_days,"
                            + " same_day_absence_allowed, absence_cutoff_time, max_calendar_window_days, created_by)"
                            + " values (?, " + values + ", ?)",
                    plan.id(), owner.id()))
                    .as(values).isInstanceOf(DataIntegrityViolationException.class);
        }
        // (plan_id, version) is unique.
        assertThatThrownBy(() -> jdbc.update(
                "insert into subscription_policy (plan_id, version, extension_allowed, same_day_absence_allowed,"
                        + " created_by) values (?, 1, false, false, ?)", plan.id(), owner.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(policyVersions(plan.id())).containsExactly(1);
    }

    /** Frozen Phase 6 decision 3, enforced by V9 even for a caller that bypasses the service. */
    @Test
    void planIdentityColumnsCannotChangeInTheDatabase() {
        Account owner = login();
        UUID provider = createProvider(owner, "Identity Mess");
        UUID otherProvider = createProvider(owner, "Other Identity Mess");
        PlanView plan = create(owner, provider, dayPlan("Monthly", "2500", 30, policy(false, null, false, null, null)));

        assertThatThrownBy(() -> jdbc.update(
                "update plan set consumption_type = 'MEAL', duration_days = null, meal_quantity = 60 where id = ?",
                plan.id())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("update plan set provider_id = ? where id = ?", otherProvider, plan.id()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("update plan set currency = 'USD' where id = ?", plan.id()))
                .isInstanceOf(DataAccessException.class);

        PlanView unchanged = get(owner, provider, plan.id());
        assertThat(unchanged.consumptionType()).isEqualTo(ConsumptionType.DAY);
        assertThat(unchanged.durationDays()).isEqualTo(30);
        // Ordinary edits still work.
        assertThat(jdbc.update("update plan set price = 2600, updated_at = now() where id = ?", plan.id())).isEqualTo(1);
    }

    /** Frozen Phase 6 decision 2, enforced by V9 even for a caller that bypasses the service. */
    @Test
    void extensionOnAMealPlanIsRejectedByTheDatabase() {
        Account owner = login();
        UUID provider = createProvider(owner, "Meal Extension Mess");
        PlanView meal = create(owner, provider, mealPlan("60 meals", "2500", 60, policy(false, null, false, null, null)));

        assertThatThrownBy(() -> jdbc.update(
                "insert into subscription_policy (plan_id, version, extension_allowed, min_consecutive_absence_days,"
                        + " same_day_absence_allowed, created_by) values (?, 2, true, 2, false, ?)",
                meal.id(), owner.id())).isInstanceOf(DataAccessException.class);
        assertThat(policyVersions(meal.id())).containsExactly(1);
    }

    // ------------------------------------------------------------ lifecycle

    @Test
    void planCanBeDeactivatedAndReactivatedAndNeverDisappears() {
        Account owner = login();
        UUID provider = createProvider(owner, "Lifecycle Mess");
        PlanView plan = create(owner, provider, dayPlan("Monthly", "2500", 30, policy(true, 2, false, null, 45)));

        PlanView inactive = post(owner, provider, plan.id(), "deactivate");
        assertThat(inactive.status()).isEqualTo(PlanStatus.INACTIVE);
        assertThat(inactive.updatedAt()).isAfter(plan.updatedAt());
        assertThat(inactive.policy().version()).isEqualTo(1);
        assertError(send("POST", planUrl(provider, plan.id()) + "/deactivate", owner.accessToken(), Map.of()),
                HttpStatus.CONFLICT, "PLAN_ALREADY_INACTIVE");

        // Hidden from the default (active) list but still listed with includeInactive and still readable.
        assertThat(list(owner, provider, false)).isEmpty();
        assertThat(list(owner, provider, true)).extracting(PlanView::id).containsExactly(plan.id());
        assertThat(get(owner, provider, plan.id()).status()).isEqualTo(PlanStatus.INACTIVE);

        PlanView active = post(owner, provider, plan.id(), "activate");
        assertThat(active.status()).isEqualTo(PlanStatus.ACTIVE);
        assertError(send("POST", planUrl(provider, plan.id()) + "/activate", owner.accessToken(), Map.of()),
                HttpStatus.CONFLICT, "PLAN_ALREADY_ACTIVE");
        assertThat(planRows(provider)).isEqualTo(1);
    }

    @Test
    void activePlanNamesAreUniquePerProviderCaseInsensitivelyButInactiveOnesDoNotBlockReuse() {
        Account owner = login();
        UUID provider = createProvider(owner, "Names Mess");
        PolicyBody none = policy(false, null, false, null, null);
        PlanView first = create(owner, provider, dayPlan("Monthly", "2500", 30, none));

        assertError(sendCreate(owner, provider, dayPlan("  monthly ", "2600", 30, none)), HttpStatus.CONFLICT,
                "PLAN_NAME_IN_USE");
        assertThat(planRows(provider)).isEqualTo(1);

        // Retire the first, then the name is free again.
        post(owner, provider, first.id(), "deactivate");
        PlanView second = create(owner, provider, dayPlan("Monthly", "2600", 30, none));

        // The retired plan cannot come back while the name is taken ...
        assertError(send("POST", planUrl(provider, first.id()) + "/activate", owner.accessToken(), Map.of()),
                HttpStatus.CONFLICT, "PLAN_NAME_IN_USE");
        // ... but an inactive plan may be edited (even into a taken name; it is checked on reactivation).
        update(owner, provider, first.id(), dayPlan("MONTHLY", "2500", 30, none));

        // Renaming an ACTIVE plan into another active plan's name is rejected.
        PlanView third = create(owner, provider, dayPlan("Quarterly", "7000", 90, none));
        assertError(sendUpdate(owner, provider, third.id(), dayPlan("monthly", "7000", 90, none)),
                HttpStatus.CONFLICT, "PLAN_NAME_IN_USE");
        assertThat(get(owner, provider, third.id()).name()).isEqualTo("Quarterly");
        // Keeping its own name on an edit is not a conflict with itself.
        assertThat(update(owner, provider, second.id(), dayPlan("Monthly", "2700", 30, none)).price())
                .isEqualByComparingTo("2700");
    }

    // ------------------------------------------------------------ authorization

    @Test
    void managerMayViewPlansButNotManageThemAndWorkerMayDoNeither() {
        Account owner = login();
        Account manager = login();
        Account worker = login();
        UUID provider = createProvider(owner, "Roles Mess");
        assignRole(owner, provider, manager, "MANAGER");
        assignRole(owner, provider, worker, "WORKER");
        PlanView plan = create(owner, provider, dayPlan("Monthly", "2500", 30, policy(false, null, false, null, null)));
        Map<String, Object> body = dayPlan("Hacked", "1", 30, policy(false, null, false, null, null));

        // MANAGER: read yes ...
        assertThat(list(manager, provider, false)).extracting(PlanView::id).containsExactly(plan.id());
        assertThat(get(manager, provider, plan.id()).price()).isEqualByComparingTo("2500");
        // ... every mutation 403.
        assertError(sendCreate(manager, provider, body), HttpStatus.FORBIDDEN, "INSUFFICIENT_PERMISSION");
        assertError(sendUpdate(manager, provider, plan.id(), body), HttpStatus.FORBIDDEN, "INSUFFICIENT_PERMISSION");
        assertError(send("POST", planUrl(provider, plan.id()) + "/deactivate", manager.accessToken(), Map.of()),
                HttpStatus.FORBIDDEN, "INSUFFICIENT_PERMISSION");
        assertError(send("POST", planUrl(provider, plan.id()) + "/activate", manager.accessToken(), Map.of()),
                HttpStatus.FORBIDDEN, "INSUFFICIENT_PERMISSION");

        // WORKER: nothing at all (a member, so 403 - not 404).
        assertError(send("GET", plansUrl(provider), worker.accessToken(), null), HttpStatus.FORBIDDEN,
                "INSUFFICIENT_PERMISSION");
        assertError(send("GET", planUrl(provider, plan.id()), worker.accessToken(), null), HttpStatus.FORBIDDEN,
                "INSUFFICIENT_PERMISSION");
        assertError(sendCreate(worker, provider, body), HttpStatus.FORBIDDEN, "INSUFFICIENT_PERMISSION");

        // Nothing denied took effect.
        PlanView after = get(owner, provider, plan.id());
        assertThat(after.name()).isEqualTo("Monthly");
        assertThat(after.status()).isEqualTo(PlanStatus.ACTIVE);
        assertThat(planRows(provider)).isEqualTo(1);
    }

    @Test
    void outsidersAndOtherProvidersOwnersCannotSeeOrTouchPlansAndGetTheSame404() {
        Account owner = login();
        Account outsider = login();
        UUID provider = createProvider(owner, "Private Mess");
        UUID outsiderProvider = createProvider(outsider, "Other Mess");
        PlanView plan = create(owner, provider, dayPlan("Monthly", "2500", 30, policy(false, null, false, null, null)));
        Map<String, Object> body = dayPlan("Hacked", "1", 30, policy(false, null, false, null, null));

        // The outsider owns a different provider; that grants nothing here (Rule 5.3).
        assertError(send("GET", plansUrl(provider), outsider.accessToken(), null), HttpStatus.NOT_FOUND,
                "PROVIDER_NOT_FOUND");
        assertError(send("GET", planUrl(provider, plan.id()), outsider.accessToken(), null), HttpStatus.NOT_FOUND,
                "PROVIDER_NOT_FOUND");
        assertError(sendCreate(outsider, provider, body), HttpStatus.NOT_FOUND, "PROVIDER_NOT_FOUND");
        assertError(sendUpdate(outsider, provider, plan.id(), body), HttpStatus.NOT_FOUND, "PROVIDER_NOT_FOUND");
        assertError(send("POST", planUrl(provider, plan.id()) + "/deactivate", outsider.accessToken(), Map.of()),
                HttpStatus.NOT_FOUND, "PROVIDER_NOT_FOUND");
        // A provider that does not exist looks identical.
        assertError(send("GET", plansUrl(UUID.randomUUID()), owner.accessToken(), null), HttpStatus.NOT_FOUND,
                "PROVIDER_NOT_FOUND");

        // Changing the provider id in the path to one the caller does own does not expose the plan (no IDOR).
        assertError(send("GET", planUrl(outsiderProvider, plan.id()), outsider.accessToken(), null),
                HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND");
        assertError(sendUpdate(outsider, outsiderProvider, plan.id(), body), HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND");
        assertError(send("POST", planUrl(outsiderProvider, plan.id()) + "/deactivate", outsider.accessToken(),
                Map.of()), HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND");
        assertError(send("POST", planUrl(outsiderProvider, plan.id()) + "/activate", outsider.accessToken(),
                Map.of()), HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND");
        assertThat(list(outsider, outsiderProvider, true)).isEmpty();

        PlanView untouched = get(owner, provider, plan.id());
        assertThat(untouched.name()).isEqualTo("Monthly");
        assertThat(untouched.status()).isEqualTo(PlanStatus.ACTIVE);
        assertThat(planRows(provider)).isEqualTo(1);
        assertThat(planRows(outsiderProvider)).isZero();
    }

    @Test
    void aRevokedManagerLosesPlanAccessImmediately() {
        Account owner = login();
        Account manager = login();
        UUID provider = createProvider(owner, "Revoke Mess");
        RoleAssignmentView assignment = assignRole(owner, provider, manager, "MANAGER");
        create(owner, provider, dayPlan("Monthly", "2500", 30, policy(false, null, false, null, null)));
        assertThat(list(manager, provider, false)).hasSize(1);

        send("DELETE", "/api/v1/providers/" + provider + "/roles/" + assignment.id(), owner.accessToken(), null)
                .expectStatus().isOk();

        assertError(send("GET", plansUrl(provider), manager.accessToken(), null), HttpStatus.NOT_FOUND,
                "PROVIDER_NOT_FOUND");
    }

    // ------------------------------------------------------------ provider state

    @Test
    void aClosedProviderKeepsItsPlansReadableButNoLongerChangeable() {
        Account owner = login();
        UUID provider = createProvider(owner, "Closing Mess");
        PolicyBody none = policy(false, null, false, null, null);
        PlanView plan = create(owner, provider, dayPlan("Monthly", "2500", 30, none));
        PlanView retired = create(owner, provider, dayPlan("Old", "2000", 30, none));
        post(owner, provider, retired.id(), "deactivate");

        send("PATCH", "/api/v1/providers/" + provider, owner.accessToken(), Map.of("status", "CLOSED"))
                .expectStatus().isOk();

        assertError(sendCreate(owner, provider, dayPlan("New", "1", 30, none)), HttpStatus.CONFLICT, "PROVIDER_CLOSED");
        assertError(sendUpdate(owner, provider, plan.id(), dayPlan("Monthly", "9999", 30, none)),
                HttpStatus.CONFLICT, "PROVIDER_CLOSED");
        assertError(send("POST", planUrl(provider, plan.id()) + "/deactivate", owner.accessToken(), Map.of()),
                HttpStatus.CONFLICT, "PROVIDER_CLOSED");
        assertError(send("POST", planUrl(provider, retired.id()) + "/activate", owner.accessToken(), Map.of()),
                HttpStatus.CONFLICT, "PROVIDER_CLOSED");

        // History is intact and readable.
        assertThat(list(owner, provider, true)).hasSize(2);
        assertThat(get(owner, provider, plan.id()).price()).isEqualByComparingTo("2500");
        assertThat(get(owner, provider, retired.id()).status()).isEqualTo(PlanStatus.INACTIVE);
    }

    // ------------------------------------------------------------ concurrency

    @Test
    void twoConcurrentCreatesWithTheSameNameYieldOneWinner() throws Exception {
        Account owner = login();
        UUID provider = createProvider(owner, "Race Mess");
        Map<String, Object> body = dayPlan("Monthly", "2500", 30, policy(false, null, false, null, null));

        List<Integer> statuses = runConcurrently(
                () -> statusOf(sendCreate(owner, provider, body)),
                () -> statusOf(sendCreate(owner, provider, body)));

        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(planRows(provider)).isEqualTo(1);
    }

    @Test
    void twoConcurrentPolicyEditsBothSucceedWithDistinctVersions() throws Exception {
        Account owner = login();
        UUID provider = createProvider(owner, "Version Race Mess");
        PlanView plan = create(owner, provider, dayPlan("Monthly", "2500", 30, policy(true, 2, false, null, 45)));

        List<Integer> statuses = runConcurrently(
                () -> statusOf(sendUpdate(owner, provider, plan.id(),
                        dayPlan("Monthly", "2500", 30, policy(true, 3, false, null, 45)))),
                () -> statusOf(sendUpdate(owner, provider, plan.id(),
                        dayPlan("Monthly", "2500", 30, policy(true, 4, false, null, 45)))));

        // The provider row lock serializes the edits: no 500, no duplicate version number.
        assertThat(statuses).containsExactly(200, 200);
        assertThat(policyVersions(plan.id())).containsExactly(1, 2, 3);
        assertThat(get(owner, provider, plan.id()).policy().version()).isEqualTo(3);
    }

    // ------------------------------------------------------------ helpers

    private record Account(UUID id, String phone, String accessToken) {
    }

    /** Request-side policy: kept as a plain bag so tests can send exactly what a client would. */
    private record PolicyBody(Map<String, Object> fields) {
    }

    private static PolicyBody policy(boolean extensionAllowed, Integer minConsecutive, boolean sameDay,
            String cutoff, Integer window) {
        Map<String, Object> m = new HashMap<>();
        m.put("extensionAllowed", extensionAllowed);
        m.put("sameDayAbsenceAllowed", sameDay);
        if (minConsecutive != null) {
            m.put("minConsecutiveAbsenceDays", minConsecutive);
        }
        if (cutoff != null) {
            m.put("absenceCutoffTime", cutoff);
        }
        if (window != null) {
            m.put("maxCalendarWindowDays", window);
        }
        return new PolicyBody(m);
    }

    private static Map<String, Object> dayPlan(String name, String price, int days, PolicyBody policy) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("consumptionType", "DAY");
        m.put("durationDays", days);
        m.put("price", new BigDecimal(price));
        m.put("policy", policy.fields());
        return m;
    }

    private static Map<String, Object> mealPlan(String name, String price, int meals, PolicyBody policy) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("consumptionType", "MEAL");
        m.put("mealQuantity", meals);
        m.put("price", new BigDecimal(price));
        m.put("policy", policy.fields());
        return m;
    }

    private static Map<String, Object> with(Map<String, Object> body, String key, Object value) {
        body.put(key, value);
        return body;
    }

    private static Map<String, Object> without(Map<String, Object> body, String key) {
        body.remove(key);
        return body;
    }

    private Account login() {
        String phone = String.format("+91987655%04d", PHONE_COUNTER.getAndIncrement());
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

    private UUID createProvider(Account owner, String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("providerType", "MESS");
        body.put("addressLine", "12 MP Nagar Zone 1");
        body.put("locality", "MP Nagar");
        body.put("city", "Bhopal");
        body.put("pincode", "462011");
        return send("POST", "/api/v1/providers", owner.accessToken(), body)
                .expectStatus().isCreated().expectBody(ProviderResponse.class).returnResult()
                .getResponseBody().id();
    }

    private RoleAssignmentView assignRole(Account owner, UUID providerId, Account target, String role) {
        return send("POST", "/api/v1/providers/" + providerId + "/roles", owner.accessToken(),
                Map.of("phone", target.phone(), "role", role))
                .expectStatus().isCreated().expectBody(RoleAssignmentView.class).returnResult().getResponseBody();
    }

    private static String plansUrl(UUID providerId) {
        return "/api/v1/providers/" + providerId + "/plans";
    }

    private static String planUrl(UUID providerId, UUID planId) {
        return plansUrl(providerId) + "/" + planId;
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

    private RestTestClient.ResponseSpec sendCreate(Account actor, UUID providerId, Map<String, Object> body) {
        return send("POST", plansUrl(providerId), actor.accessToken(), body);
    }

    private RestTestClient.ResponseSpec sendUpdate(Account actor, UUID providerId, UUID planId,
            Map<String, Object> body) {
        return send("PUT", planUrl(providerId, planId), actor.accessToken(), body);
    }

    private PlanView create(Account actor, UUID providerId, Map<String, Object> body) {
        return sendCreate(actor, providerId, body).expectStatus().isCreated()
                .expectBody(PlanView.class).returnResult().getResponseBody();
    }

    private PlanView update(Account actor, UUID providerId, UUID planId, Map<String, Object> body) {
        return sendUpdate(actor, providerId, planId, body).expectStatus().isOk()
                .expectBody(PlanView.class).returnResult().getResponseBody();
    }

    private PlanView get(Account actor, UUID providerId, UUID planId) {
        return send("GET", planUrl(providerId, planId), actor.accessToken(), null)
                .expectStatus().isOk().expectBody(PlanView.class).returnResult().getResponseBody();
    }

    private PlanView post(Account actor, UUID providerId, UUID planId, String action) {
        return send("POST", planUrl(providerId, planId) + "/" + action, actor.accessToken(), Map.of())
                .expectStatus().isOk().expectBody(PlanView.class).returnResult().getResponseBody();
    }

    private List<PlanView> list(Account actor, UUID providerId, boolean includeInactive) {
        PlanView[] body = send("GET", plansUrl(providerId) + "?includeInactive=" + includeInactive,
                actor.accessToken(), null).expectStatus().isOk().expectBody(PlanView[].class).returnResult()
                .getResponseBody();
        return body == null ? List.of() : List.of(body);
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

    private int planRows(UUID providerId) {
        return jdbc.queryForObject("select count(*) from plan where provider_id = ?", Integer.class, providerId);
    }

    private List<Integer> policyVersions(UUID planId) {
        return jdbc.queryForList("select version from subscription_policy where plan_id = ? order by version",
                Integer.class, planId);
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
