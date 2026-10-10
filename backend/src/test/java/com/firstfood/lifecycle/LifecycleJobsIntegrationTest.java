package com.firstfood.lifecycle;

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
import com.firstfood.subscription.SubscriptionKey;
import com.firstfood.subscription.SubscriptionView;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Phase 10 end-to-end job tests: the four jobs against a real database, through the same {@link JobRunner} the
 * scheduler uses (the scheduler itself is off in tests, {@code app.jobs.enabled=false}). Phones are +91987659xxxx; the
 * database is a shared singleton container, so do not reuse that prefix elsewhere.
 *
 * As in the Phase 8/9 tests, a subscription is sold through the API and then moved into the past through SQL, and past
 * absences are written straight into the tables, exactly as a customer would have declared them back then.
 */
class LifecycleJobsIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger PHONE_COUNTER = new AtomicInteger(1);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");

    @Autowired
    RestTestClient restClient;

    @Autowired
    TestCapturingOtpSender otpSender;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JobRunner runner;

    @Autowired
    SubscriptionExpiryJob expiryJob;

    @Autowired
    ExtensionEvaluationJob extensionJob;

    @Autowired
    OtpCleanupJob otpCleanupJob;

    @Autowired
    SessionCleanupJob sessionCleanupJob;

    @Autowired
    SubscriptionLapseProcessor lapseProcessor;

    private static LocalDate today() {
        return LocalDate.now(BUSINESS_ZONE);
    }

    // ================================================================ expiry

    @Test
    void aLapsedSubscriptionIsExpiredAndARunningOneIsLeftAlone() {
        Fixture f = fixture(noExtensionPlan());
        SubscriptionView lapsed = sell(f, today());
        backdate(lapsed.id(), 31); // base expiry = today - 2
        Fixture g = fixture(noExtensionPlan());
        SubscriptionView running = sell(g, today());

        JobRunResult result = runner.run(expiryJob);

        assertThat(result.outcome()).isEqualTo(JobRunOutcome.SUCCEEDED);
        assertThat(result.changed()).isGreaterThanOrEqualTo(1);
        assertThat(statusOf(lapsed.id())).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("select expired_at is not null from subscription where id = ?", Boolean.class,
                lapsed.id())).isTrue();
        assertThat(statusOf(running.id())).isEqualTo("ACTIVE");
    }

    @Test
    void theLastDayOfTheSubscriptionIsStillActiveAndTheDayAfterIsNot() {
        Fixture f = fixture(noExtensionPlan());
        SubscriptionView endsToday = sell(f, today());
        backdate(endsToday.id(), 29); // base expiry = today
        Fixture g = fixture(noExtensionPlan());
        SubscriptionView endedYesterday = sell(g, today());
        backdate(endedYesterday.id(), 30); // base expiry = yesterday

        runner.run(expiryJob);

        assertThat(statusOf(endsToday.id())).isEqualTo("ACTIVE");
        assertThat(statusOf(endedYesterday.id())).isEqualTo("EXPIRED");
    }

    @Test
    void runningTheExpiryJobAgainChangesNothing() {
        Fixture f = fixture(noExtensionPlan());
        SubscriptionView sub = sell(f, today());
        backdate(sub.id(), 31);

        runner.run(expiryJob);
        Instant expiredAt = expiredAt(sub.id());
        runner.run(expiryJob);
        JobRunResult third = runner.run(expiryJob);

        assertThat(third.outcome()).isEqualTo(JobRunOutcome.SUCCEEDED);
        assertThat(statusOf(sub.id())).isEqualTo("EXPIRED");
        assertThat(expiredAt(sub.id())).isEqualTo(expiredAt);
    }

    @Test
    void anExpiredSubscriptionKeepsItsMembershipAndHistory() {
        Fixture f = fixture(noExtensionPlan());
        SubscriptionView sub = sell(f, today());
        backdate(sub.id(), 31);

        runner.run(expiryJob);

        assertThat(jdbc.queryForObject("select count(*) from subscription where id = ?", Integer.class, sub.id()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from subscription_term_snapshot where subscription_id = ?",
                Integer.class, sub.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from provider_membership where id = ?", String.class,
                f.membership)).isEqualTo("ACTIVE");
    }

    @Test
    void anExtendableSubscriptionWithNothingEarnedStillExpires() {
        Fixture f = fixture(extensionPlan(2, 45));
        SubscriptionView sub = sell(f, today());
        backdate(sub.id(), 31);

        JobRunResult result = runner.run(expiryJob);

        assertThat(result.failed()).isZero();
        assertThat(statusOf(sub.id())).isEqualTo("EXPIRED");
        assertThat(extensionRows(sub.id())).isZero();
    }

    @Test
    void earnedExtensionIsAppliedBeforeExpiryAndCanKeepTheSubscriptionActive() {
        Fixture f = fixture(extensionPlan(2, 45));
        SubscriptionView sub = sell(f, today());
        backdate(sub.id(), 35); // start = today-35, base expiry = today-6
        LocalDate base = today().minusDays(6);
        absent(sub.id(), f.customer.id(), base.minusDays(7), base); // 8 absent days, the last on the expiry day

        runner.run(expiryJob);

        assertThat(statusOf(sub.id())).isEqualTo("ACTIVE");
        assertThat(effectiveExpiry(sub.id())).isEqualTo(base.plusDays(8)); // today + 2
        assertThat(extensionRows(sub.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select trigger_source from extension_event where subscription_id = ?",
                String.class, sub.id())).isEqualTo("SYSTEM");
        assertThat(jdbc.queryForObject("select created_by is null from extension_event where subscription_id = ?",
                Boolean.class, sub.id())).isTrue();

        // and again: nothing more to add, still ACTIVE, still one event
        runner.run(expiryJob);
        assertThat(statusOf(sub.id())).isEqualTo("ACTIVE");
        assertThat(extensionRows(sub.id())).isEqualTo(1);
    }

    @Test
    void anExtensionThatIsNotEnoughIsRecordedAndTheSubscriptionStillExpires() {
        Fixture f = fixture(extensionPlan(2, 45));
        SubscriptionView sub = sell(f, today());
        backdate(sub.id(), 35);
        LocalDate base = today().minusDays(6);
        absent(sub.id(), f.customer.id(), base.minusDays(2), base); // 3 days -> base + 3 = today - 3, still over

        runner.run(expiryJob);

        assertThat(statusOf(sub.id())).isEqualTo("EXPIRED");
        assertThat(effectiveExpiry(sub.id())).isEqualTo(base.plusDays(3));
        assertThat(extensionRows(sub.id())).isEqualTo(1);
        // The database's own invariant: expiry = base + what the events applied.
        assertThat(jdbc.queryForObject("""
                select effective_expiry_date - base_expiry_date = coalesce((select sum(applied_extension_days)
                         from extension_event e where e.subscription_id = s.id), 0)
                from subscription s where s.id = ?
                """, Boolean.class, sub.id())).isTrue();
    }

    @Test
    void twoWorkersProcessingTheSameLapsedSubscriptionAtOnceApplyOneExtensionAndOneExpiry() throws Exception {
        Fixture f = fixture(extensionPlan(2, 45));
        SubscriptionView sub = sell(f, today());
        backdate(sub.id(), 35);
        LocalDate base = today().minusDays(6);
        absent(sub.id(), f.customer.id(), base.minusDays(2), base);
        SubscriptionKey key = new SubscriptionKey(f.provider, sub.id());

        List<SubscriptionLapseProcessor.Result> results = concurrently(6, () -> lapseProcessor.process(key, today()));

        assertThat(results).filteredOn(r -> r == SubscriptionLapseProcessor.Result.EXPIRED).hasSize(1);
        assertThat(results).filteredOn(r -> r == SubscriptionLapseProcessor.Result.SKIPPED).hasSize(5);
        assertThat(extensionRows(sub.id())).isEqualTo(1);
        assertThat(statusOf(sub.id())).isEqualTo("EXPIRED");
    }

    @Test
    void twoWorkersOnAKeptAliveSubscriptionExtendItOnce() throws Exception {
        Fixture f = fixture(extensionPlan(2, 45));
        SubscriptionView sub = sell(f, today());
        backdate(sub.id(), 35);
        LocalDate base = today().minusDays(6);
        absent(sub.id(), f.customer.id(), base.minusDays(7), base);
        SubscriptionKey key = new SubscriptionKey(f.provider, sub.id());

        List<SubscriptionLapseProcessor.Result> results = concurrently(6, () -> lapseProcessor.process(key, today()));

        assertThat(results).filteredOn(r -> r == SubscriptionLapseProcessor.Result.EXTENDED).hasSize(1);
        assertThat(extensionRows(sub.id())).isEqualTo(1);
        assertThat(statusOf(sub.id())).isEqualTo("ACTIVE");
        assertThat(effectiveExpiry(sub.id())).isEqualTo(base.plusDays(8));
    }

    @Test
    void twoJobRunsAtOnceLeaveOneExpiry() throws Exception {
        Fixture f = fixture(extensionPlan(2, 45));
        SubscriptionView sub = sell(f, today());
        backdate(sub.id(), 35);
        LocalDate base = today().minusDays(6);
        absent(sub.id(), f.customer.id(), base.minusDays(2), base);

        List<JobRunOutcome> outcomes = concurrently(4, () -> runner.run(expiryJob).outcome());

        assertThat(outcomes).allMatch(o -> o == JobRunOutcome.SUCCEEDED || o == JobRunOutcome.SKIPPED);
        assertThat(statusOf(sub.id())).isEqualTo("EXPIRED");
        assertThat(extensionRows(sub.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from job_run where job_name = 'subscription-expiry' "
                + "and status = 'RUNNING'", Integer.class)).isZero();
    }

    @Test
    void theRunLeavesAnObservableRecord() {
        Fixture f = fixture(noExtensionPlan());
        SubscriptionView sub = sell(f, today());
        backdate(sub.id(), 31);

        JobRunResult result = runner.run(expiryJob);

        JobRunRecord latest = runner.recentRuns(SubscriptionExpiryJob.NAME, 1).get(0);
        assertThat(latest.id()).isEqualTo(result.runId());
        assertThat(latest.status()).isEqualTo(JobRunStatus.SUCCEEDED);
        assertThat(latest.changedCount()).isEqualTo(result.changed()).isGreaterThanOrEqualTo(1);
        assertThat(latest.finishedAt()).isNotNull();
    }

    // ================================================================ extension evaluation

    @Test
    void theExtensionJobAppliesEarnedDaysAsTheSystemAndIsIdempotent() {
        Fixture f = fixture(extensionPlan(2, 45));
        SubscriptionView sub = sell(f, today());
        backdate(sub.id(), 12);
        LocalDate start = today().minusDays(12);
        absent(sub.id(), f.customer.id(), start.plusDays(1), start.plusDays(3)); // 3 days, all over

        JobRunResult first = runner.run(extensionJob);
        assertThat(first.changed()).isGreaterThanOrEqualTo(1);
        assertThat(extensionRows(sub.id())).isEqualTo(1);
        assertThat(effectiveExpiry(sub.id())).isEqualTo(start.plusDays(29 + 3));
        assertThat(jdbc.queryForObject("select trigger_source from extension_event where subscription_id = ?",
                String.class, sub.id())).isEqualTo("SYSTEM");

        runner.run(extensionJob);
        runner.run(extensionJob);
        assertThat(extensionRows(sub.id())).isEqualTo(1);
        assertThat(effectiveExpiry(sub.id())).isEqualTo(start.plusDays(29 + 3));
    }

    @Test
    void theExtensionJobLeavesNonExtendablePlansAndUnearnedAbsencesAlone() {
        Fixture noExt = fixture(noExtensionPlan());
        SubscriptionView a = sell(noExt, today());
        backdate(a.id(), 12);
        absent(a.id(), noExt.customer.id(), today().minusDays(8), today().minusDays(6));

        Fixture ext = fixture(extensionPlan(3, 45));
        SubscriptionView b = sell(ext, today());
        backdate(b.id(), 12);
        absent(b.id(), ext.customer.id(), today().minusDays(8), today().minusDays(7)); // 2 < minimum 3
        absent(b.id(), ext.customer.id(), today(), today()); // today is not over yet

        JobRunResult result = runner.run(extensionJob);

        assertThat(result.failed()).isZero();
        assertThat(extensionRows(a.id())).isZero();
        assertThat(extensionRows(b.id())).isZero();
        assertThat(effectiveExpiry(a.id())).isEqualTo(baseExpiry(a.id()));
        assertThat(effectiveExpiry(b.id())).isEqualTo(baseExpiry(b.id()));
    }

    @Test
    void aLapsedSubscriptionBelongsToTheExpiryJobAndCanBeRenewedAfterwards() {
        // The extension job only looks at running subscriptions; the expiry job extends a lapsed one first (B8) and
        // expires it, after which no earned day is pending (B6) and the owner can renew.
        Fixture f = fixture(extensionPlan(2, 45));
        SubscriptionView sub = sell(f, today());
        backdate(sub.id(), 35); // base expiry today - 6
        absent(sub.id(), f.customer.id(), today().minusDays(14), today().minusDays(12)); // earned 3, new expiry today-3

        runner.run(extensionJob); // not a candidate (lapsed): the expiry job owns it
        assertThat(extensionRows(sub.id())).isZero();

        runner.run(expiryJob); // extends (B8) then expires
        assertThat(statusOf(sub.id())).isEqualTo("EXPIRED");
        send("POST", "/api/v1/providers/" + f.provider + "/subscriptions/" + sub.id() + "/renew", f.owner.accessToken(),
                Map.of()).expectStatus().isCreated();
    }

    // ================================================================ cleanup

    @Test
    void otpCleanupDeletesOnlyChallengesThatExpiredBeyondTheRetention() {
        String phone = "+91987659" + String.format("%04d", 9000 + PHONE_COUNTER.getAndIncrement());
        Instant now = Instant.now();
        int old = 250; // more than two batches of 100
        for (int i = 0; i < old; i++) {
            insertOtp(phone, i % 2 == 0 ? "PENDING" : "CONSUMED", now.minus(Duration.ofDays(2)).minusSeconds(i));
        }
        UUID recentlyExpired = insertOtp(phone, "EXPIRED", now.minus(Duration.ofHours(1)));
        UUID stillValid = insertOtp(phone, "PENDING", now.plus(Duration.ofMinutes(4)));

        JobRunResult result = runner.run(otpCleanupJob);

        assertThat(result.outcome()).isEqualTo(JobRunOutcome.SUCCEEDED);
        assertThat(result.changed()).isGreaterThanOrEqualTo(old);
        assertThat(jdbc.queryForList("select id from otp_verification where phone = ?", UUID.class, phone))
                .containsExactlyInAnyOrder(recentlyExpired, stillValid);

        JobRunResult again = runner.run(otpCleanupJob);
        assertThat(again.changed()).isZero();
    }

    @Test
    void sessionCleanupDeletesOnlySessionsThatAreDeadBeyondTheRetention() {
        Account owner = login();
        Instant now = Instant.now();
        UUID expiredLongAgo = insertSession(owner.id(), now.minus(Duration.ofDays(40)), now.minus(Duration.ofDays(10)), null);
        UUID revokedLongAgo = insertSession(owner.id(), now.minus(Duration.ofDays(20)), now.plus(Duration.ofDays(10)),
                now.minus(Duration.ofDays(10)));
        UUID revokedRecently = insertSession(owner.id(), now.minus(Duration.ofDays(2)), now.plus(Duration.ofDays(28)),
                now.minus(Duration.ofDays(1)));
        UUID expiredRecently = insertSession(owner.id(), now.minus(Duration.ofDays(31)), now.minus(Duration.ofDays(1)), null);
        UUID live = insertSession(owner.id(), now.minus(Duration.ofDays(20)), now.plus(Duration.ofDays(10)), null);

        JobRunResult result = runner.run(sessionCleanupJob);

        assertThat(result.outcome()).isEqualTo(JobRunOutcome.SUCCEEDED);
        assertThat(result.changed()).isGreaterThanOrEqualTo(2);
        List<UUID> remaining = jdbc.queryForList("select id from refresh_session where user_account_id = ?", UUID.class,
                owner.id());
        assertThat(remaining).doesNotContain(expiredLongAgo, revokedLongAgo);
        assertThat(remaining).contains(revokedRecently, expiredRecently, live);
    }

    @Test
    void sessionCleanupNeverBreaksALiveLogin() {
        Account owner = login();
        runner.run(sessionCleanupJob);
        send("GET", "/api/v1/me", owner.accessToken(), null).expectStatus().isOk();
    }

    // ================================================================ helpers: data

    private UUID insertOtp(String phone, String status, Instant expiresAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into otp_verification (id, phone, purpose, otp_hash, attempt_count, status, expires_at)
                values (?, ?, 'LOGIN', 'x', 0, ?, ?)
                """, id, phone, status, Timestamp.from(expiresAt));
        return id;
    }

    private UUID insertSession(UUID accountId, Instant issuedAt, Instant expiresAt, Instant revokedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into refresh_session (id, user_account_id, refresh_token_hash, issued_at, expires_at, revoked_at)
                values (?, ?, ?, ?, ?, ?)
                """, id, accountId, "test-hash-" + id, Timestamp.from(issuedAt), Timestamp.from(expiresAt),
                revokedAt == null ? null : Timestamp.from(revokedAt));
        return id;
    }

    private String statusOf(UUID subscriptionId) {
        return jdbc.queryForObject("select status from subscription where id = ?", String.class, subscriptionId);
    }

    private Instant expiredAt(UUID subscriptionId) {
        return jdbc.queryForObject("select expired_at from subscription where id = ?", Timestamp.class, subscriptionId)
                .toInstant();
    }

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

    private void absent(UUID subscriptionId, UUID customerAccountId, LocalDate from, LocalDate to) {
        UUID provider = jdbc.queryForObject("select provider_id from subscription where id = ?", UUID.class,
                subscriptionId);
        new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.support.JdbcTransactionManager(jdbc.getDataSource()))
                .executeWithoutResult(tx -> {
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

    private static <T> List<T> concurrently(int threads, java.util.concurrent.Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return task.call();
                }));
            }
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    // ================================================================ helpers: API fixtures

    private record Account(UUID id, String phone, String accessToken) {
    }

    private record Fixture(Account owner, UUID provider, PlanView plan, Account customer, UUID membership) {
    }

    private Fixture fixture(Map<String, Object> planBody) {
        Account owner = login();
        UUID provider = createProvider(owner);
        PlanView plan = send("POST", "/api/v1/providers/" + provider + "/plans", owner.accessToken(), planBody)
                .expectStatus().isCreated().expectBody(PlanView.class).returnResult().getResponseBody();
        Account customer = login();
        CustomerView view = send("POST", "/api/v1/providers/" + provider + "/customers", owner.accessToken(),
                Map.of("phone", customer.phone(), "fullName", "Customer " + customer.phone().substring(10)))
                .expectStatus().isCreated().expectBody(CustomerView.class).returnResult().getResponseBody();
        return new Fixture(owner, provider, plan, customer, view.membershipId());
    }

    private SubscriptionView sell(Fixture f, LocalDate start) {
        Map<String, Object> body = new HashMap<>();
        body.put("membershipId", f.membership);
        body.put("planId", f.plan.id());
        body.put("startDate", start.toString());
        return send("POST", "/api/v1/providers/" + f.provider + "/subscriptions", f.owner.accessToken(), body)
                .expectStatus().isCreated().expectBody(SubscriptionView.class).returnResult().getResponseBody();
    }

    private static Map<String, Object> noExtensionPlan() {
        return dayPlanBody(policy(false, null, null));
    }

    private static Map<String, Object> extensionPlan(int min, int window) {
        return dayPlanBody(policy(true, min, window));
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

    private static Map<String, Object> dayPlanBody(Map<String, Object> policy) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", "Monthly");
        m.put("consumptionType", "DAY");
        m.put("durationDays", 30);
        m.put("price", new BigDecimal("2500"));
        m.put("policy", policy);
        return m;
    }

    private UUID createProvider(Account owner) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "Lifecycle Mess");
        body.put("providerType", "MESS");
        body.put("addressLine", "12 MP Nagar Zone 1");
        body.put("locality", "MP Nagar");
        body.put("city", "Bhopal");
        body.put("pincode", "462011");
        return send("POST", "/api/v1/providers", owner.accessToken(), body)
                .expectStatus().isCreated().expectBody(ProviderResponse.class).returnResult().getResponseBody().id();
    }

    private Account login() {
        String phone = String.format("+91987659%04d", PHONE_COUNTER.getAndIncrement());
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
}
