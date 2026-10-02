package com.firstfood.provideraccess;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstfood.AbstractIntegrationTest;
import com.firstfood.identity.TestCapturingOtpSender;
import com.firstfood.identity.dto.AuthTokensResponse;
import com.firstfood.identity.dto.OtpRequestRequest;
import com.firstfood.identity.dto.OtpVerifyRequest;
import com.firstfood.identity.dto.ProfileResponse;
import com.firstfood.provider.dto.ProviderResponse;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Phase 4 HTTP-level tests: role APIs, authorization matrix, provider isolation,
 * ownership transfer, audit, concurrency and the end-to-end flow. Runs against
 * real Postgres + Redis with real OTP login. Phones use +91987653xxxx (others
 * use ...650xxxx, ...651xxxx, ...652xxxx); the database is a shared singleton
 * container, so do not reuse.
 */
class ProviderRoleApiIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    RestTestClient restClient;

    @Autowired
    TestCapturingOtpSender otpSender;

    @Autowired
    JdbcTemplate jdbc;

    @Value("${app.jwt.secret}")
    String jwtSecret;

    // ------------------------------------------------------------ authentication

    @Test
    void unauthenticatedInvalidAndExpiredTokensAreRejectedOnEveryRoleEndpoint() {
        Account owner = login("+919876530001");
        UUID provider = createProvider(owner);
        String expired = Jwts.builder()
                .subject(owner.id().toString())
                .issuedAt(Date.from(Instant.now().minusSeconds(7200)))
                .expiration(Date.from(Instant.now().minusSeconds(3600)))
                .signWith(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8)))
                .compact();

        for (String token : new String[] {null, "garbage.token.value", expired}) {
            String base = "/api/v1/providers/" + provider;
            send("GET", base + "/roles", token, null).expectStatus().isUnauthorized();
            send("POST", base + "/roles", token, Map.of("phone", "+919876530002", "role", "WORKER"))
                    .expectStatus().isUnauthorized();
            send("DELETE", base + "/roles/" + UUID.randomUUID(), token, null).expectStatus().isUnauthorized();
            send("POST", base + "/ownership/transfer", token, Map.of("phone", "+919876530002"))
                    .expectStatus().isUnauthorized();
        }
        assertThat(activeRoleCount(provider)).isEqualTo(1);
    }

    // ------------------------------------------------------- end-to-end Phase 4 flow

    @Test
    void endToEndAssignAuthorizeRevokeFlow() {
        Account owner = login("+919876530003");
        Account member = login("+919876530004");
        UUID provider = createProvider(owner);

        // Before assignment the member can't even see the provider (existence hidden).
        getProvider(member, provider).expectStatus().isNotFound();

        RoleAssignmentView assigned = assign(owner, provider, "+919876530004", "MANAGER");
        assertThat(assigned.role()).isEqualTo(ProviderRole.MANAGER);
        assertThat(assigned.accountId()).isEqualTo(member.id());
        assertThat(assigned.assignedBy()).isEqualTo(owner.id());

        // The target now receives the approved MANAGER permissions.
        ProviderResponse asManager = getProvider(member, provider).expectStatus().isOk()
                .expectBody(ProviderResponse.class).returnResult().getResponseBody();
        assertThat(asManager.myRole()).isEqualTo(ProviderRole.MANAGER);
        assertThat(asManager.myPermissions()).containsExactlyInAnyOrder(
                ProviderPermission.PROVIDER_VIEW, ProviderPermission.PROVIDER_EDIT, ProviderPermission.ROLE_VIEW,
                ProviderPermission.MEMBERSHIP_VIEW, ProviderPermission.MEMBERSHIP_MANAGE,
                ProviderPermission.PLAN_VIEW);
        assertThat(listProviders(member)).extracting(ProviderResponse::id).contains(provider);
        send("PATCH", "/api/v1/providers/" + provider, member.accessToken(), Map.of("name", "Manager Renamed"))
                .expectStatus().isOk();
        send("GET", "/api/v1/providers/" + provider + "/roles", member.accessToken(), null)
                .expectStatus().isOk();

        // Unauthorized operations are rejected (403: the member knows the provider exists).
        assertError(send("PATCH", "/api/v1/providers/" + provider, member.accessToken(),
                Map.of("status", "CLOSED")), HttpStatus.FORBIDDEN, "INSUFFICIENT_PERMISSION");
        assertError(send("POST", "/api/v1/providers/" + provider + "/roles", member.accessToken(),
                Map.of("phone", "+919876530003", "role", "WORKER")), HttpStatus.FORBIDDEN, "INSUFFICIENT_PERMISSION");
        assertError(send("DELETE", "/api/v1/providers/" + provider + "/roles/" + assigned.id(),
                member.accessToken(), null), HttpStatus.FORBIDDEN, "INSUFFICIENT_PERMISSION");
        assertError(send("POST", "/api/v1/providers/" + provider + "/ownership/transfer", member.accessToken(),
                Map.of("phone", "+919876530004")), HttpStatus.FORBIDDEN, "INSUFFICIENT_PERMISSION");

        // Team listing shows both, with phones for identification.
        List<RoleAssignmentView> team = listRoles(owner, provider);
        assertThat(team).extracting(RoleAssignmentView::role)
                .containsExactly(ProviderRole.OWNER, ProviderRole.MANAGER);
        assertThat(team).extracting(RoleAssignmentView::accountPhone)
                .containsExactly("+919876530003", "+919876530004");

        // Revocation removes every permission immediately.
        send("DELETE", "/api/v1/providers/" + provider + "/roles/" + assigned.id(), owner.accessToken(), null)
                .expectStatus().isOk();
        getProvider(member, provider).expectStatus().isNotFound();
        assertThat(listProviders(member)).extracting(ProviderResponse::id).doesNotContain(provider);
        send("PATCH", "/api/v1/providers/" + provider, member.accessToken(), Map.of("name", "Nope"))
                .expectStatus().isNotFound();

        // Audit trail: assigned then revoked, ids only.
        List<Map<String, Object>> audit = jdbc.queryForList(
                "select action, actor_account_id, target_account_id, old_role, new_role "
                        + "from provider_role_audit where provider_id = ? order by created_at", provider);
        assertThat(audit).extracting(r -> r.get("action")).containsExactly("ROLE_ASSIGNED", "ROLE_REVOKED");
        assertThat(audit.get(0).get("new_role")).isEqualTo("MANAGER");
        assertThat(audit.get(1).get("old_role")).isEqualTo("MANAGER");
        assertThat(audit.get(1).get("actor_account_id")).isEqualTo(owner.id());
        assertThat(audit.get(1).get("target_account_id")).isEqualTo(member.id());
    }

    @Test
    void workerIsReadOnly() {
        Account owner = login("+919876530005");
        Account worker = login("+919876530006");
        UUID provider = createProvider(owner);
        assign(owner, provider, "+919876530006", "WORKER");

        ProviderResponse view = getProvider(worker, provider).expectStatus().isOk()
                .expectBody(ProviderResponse.class).returnResult().getResponseBody();
        assertThat(view.myRole()).isEqualTo(ProviderRole.WORKER);
        assertThat(view.myPermissions()).containsExactlyInAnyOrder(
                ProviderPermission.PROVIDER_VIEW, ProviderPermission.MEMBERSHIP_VIEW);
        assertError(send("PATCH", "/api/v1/providers/" + provider, worker.accessToken(), Map.of("name", "x")),
                HttpStatus.FORBIDDEN, "INSUFFICIENT_PERMISSION");
        assertError(send("GET", "/api/v1/providers/" + provider + "/roles", worker.accessToken(), null),
                HttpStatus.FORBIDDEN, "INSUFFICIENT_PERMISSION");
        assertError(send("POST", "/api/v1/providers/" + provider + "/roles", worker.accessToken(),
                Map.of("phone", "+919876530005", "role", "WORKER")), HttpStatus.FORBIDDEN, "INSUFFICIENT_PERMISSION");
    }

    // ---------------------------------------------------------------- validation

    @Test
    void assignmentValidationIsStandardized() {
        Account owner = login("+919876530007");
        login("+919876530008");
        UUID provider = createProvider(owner);
        String url = "/api/v1/providers/" + provider + "/roles";

        assertError(send("POST", url, owner.accessToken(), Map.of("phone", "+919876539999", "role", "WORKER")),
                HttpStatus.NOT_FOUND, "TARGET_ACCOUNT_NOT_FOUND");
        assertError(send("POST", url, owner.accessToken(), Map.of("phone", "+919876530008", "role", "OWNER")),
                HttpStatus.BAD_REQUEST, "OWNER_ASSIGNMENT_NOT_ALLOWED");
        send("POST", url, owner.accessToken(), Map.of("phone", "+919876530008", "role", "SUPERUSER"))
                .expectStatus().isBadRequest();
        send("POST", url, owner.accessToken(), Map.of("phone", "+919876530008")).expectStatus().isBadRequest();
        send("POST", url, owner.accessToken(), Map.of("role", "WORKER")).expectStatus().isBadRequest();

        assign(owner, provider, "+919876530008", "WORKER");
        assertError(send("POST", url, owner.accessToken(), Map.of("phone", "+919876530008", "role", "MANAGER")),
                HttpStatus.CONFLICT, "ROLE_ALREADY_ASSIGNED");
    }

    // ---------------------------------------------------------- provider isolation

    @Test
    void roleOnProviderADoesNotGrantAnythingOnProviderB() {
        Account alice = login("+919876530009");
        Account bob = login("+919876530010");
        Account carol = login("+919876530011");
        UUID providerA = createProvider(alice);
        UUID providerB = createProvider(bob);
        RoleAssignmentView carolOnA = assign(alice, providerA, "+919876530011", "MANAGER");

        // Alice (OWNER of A) has no standing on B - changing only the URL must not help.
        send("POST", "/api/v1/providers/" + providerB + "/roles", alice.accessToken(),
                Map.of("phone", "+919876530011", "role", "WORKER")).expectStatus().isNotFound();
        send("GET", "/api/v1/providers/" + providerB + "/roles", alice.accessToken(), null)
                .expectStatus().isNotFound();
        send("POST", "/api/v1/providers/" + providerB + "/ownership/transfer", alice.accessToken(),
                Map.of("phone", "+919876530011")).expectStatus().isNotFound();
        // Carol (MANAGER of A) is nobody on B.
        getProvider(carol, providerB).expectStatus().isNotFound();
        // Bob cannot reach A's assignment through B's URL, nor through A's URL.
        assertError(send("DELETE", "/api/v1/providers/" + providerB + "/roles/" + carolOnA.id(),
                bob.accessToken(), null), HttpStatus.NOT_FOUND, "ROLE_ASSIGNMENT_NOT_FOUND");
        send("DELETE", "/api/v1/providers/" + providerA + "/roles/" + carolOnA.id(), bob.accessToken(), null)
                .expectStatus().isNotFound();
        assertThat(activeRoleCount(providerB)).isEqualTo(1);
        assertThat(activeRoleCount(providerA)).isEqualTo(2);
    }

    @Test
    void clientSuppliedActorOrRoleCannotGrantAuthority() {
        Account owner = login("+919876530012");
        Account attacker = login("+919876530013");
        login("+919876530014");
        UUID provider = createProvider(owner);

        Map<String, Object> body = new HashMap<>();
        body.put("phone", "+919876530014");
        body.put("role", "MANAGER");
        body.put("actorAccountId", owner.id().toString());   // must be ignored
        body.put("actor", owner.id().toString());
        body.put("callerRole", "OWNER");
        send("POST", "/api/v1/providers/" + provider + "/roles", attacker.accessToken(), body)
                .expectStatus().isNotFound();
        assertThat(activeRoleCount(provider)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ ownership

    @Test
    void ownershipTransferIsAtomicAndPreservesExactlyOneOwner() {
        Account alice = login("+919876530015");
        Account bob = login("+919876530016");
        UUID provider = createProvider(alice);
        // Bob already holds a WORKER role: the transfer supersedes it.
        assign(alice, provider, "+919876530016", "WORKER");

        RoleAssignmentView[] result = send("POST", "/api/v1/providers/" + provider + "/ownership/transfer",
                alice.accessToken(), Map.of("phone", "+919876530016"))
                .expectStatus().isOk().expectBody(RoleAssignmentView[].class).returnResult().getResponseBody();
        assertThat(result).extracting(RoleAssignmentView::role)
                .containsExactly(ProviderRole.OWNER, ProviderRole.MANAGER);
        assertThat(result).extracting(RoleAssignmentView::accountId).containsExactly(bob.id(), alice.id());

        assertThat(roleOf(provider, bob.id())).isEqualTo("OWNER");
        assertThat(roleOf(provider, alice.id())).isEqualTo("MANAGER");
        assertThat(jdbc.queryForObject(
                "select count(*) from provider_role_assignment where provider_id = ? and role = 'OWNER' "
                        + "and status = 'ACTIVE'", Integer.class, provider)).isEqualTo(1);
        assertThat(activeRoleCount(provider)).isEqualTo(2);
        // Bob's superseded WORKER row is kept as REVOKED history.
        assertThat(jdbc.queryForObject(
                "select count(*) from provider_role_assignment where provider_id = ? and account_id = ? "
                        + "and role = 'WORKER' and status = 'REVOKED'", Integer.class, provider, bob.id()))
                .isEqualTo(1);

        // Old owner lost owner powers immediately; new owner has them.
        assertError(send("POST", "/api/v1/providers/" + provider + "/ownership/transfer", alice.accessToken(),
                Map.of("phone", "+919876530015")), HttpStatus.FORBIDDEN, "INSUFFICIENT_PERMISSION");
        assertError(send("PATCH", "/api/v1/providers/" + provider, alice.accessToken(),
                Map.of("status", "CLOSED")), HttpStatus.FORBIDDEN, "INSUFFICIENT_PERMISSION");
        send("PATCH", "/api/v1/providers/" + provider, bob.accessToken(), Map.of("name", "Bob's Now"))
                .expectStatus().isOk();

        // Audit: one row = one account's role change, so a transfer writes two rows
        // (created_at can tie inside one transaction, so rows are looked up by target, not order).
        assertThat(jdbc.queryForObject("select count(*) from provider_role_audit where provider_id = ? "
                + "and action = 'OWNER_TRANSFERRED'", Integer.class, provider)).isEqualTo(2);
        Map<String, Object> newOwnerRow = jdbc.queryForMap(
                "select actor_account_id, old_role, new_role from provider_role_audit "
                        + "where provider_id = ? and action = 'OWNER_TRANSFERRED' and target_account_id = ?",
                provider, bob.id());
        assertThat(newOwnerRow.get("actor_account_id")).isEqualTo(alice.id());
        assertThat(newOwnerRow.get("old_role")).isEqualTo("WORKER");   // Bob's superseded role
        assertThat(newOwnerRow.get("new_role")).isEqualTo("OWNER");
        Map<String, Object> formerOwnerRow = jdbc.queryForMap(
                "select actor_account_id, old_role, new_role from provider_role_audit "
                        + "where provider_id = ? and action = 'OWNER_TRANSFERRED' and target_account_id = ?",
                provider, alice.id());
        assertThat(formerOwnerRow.get("actor_account_id")).isEqualTo(alice.id());
        assertThat(formerOwnerRow.get("old_role")).isEqualTo("OWNER");
        assertThat(formerOwnerRow.get("new_role")).isEqualTo("MANAGER");
    }

    @Test
    void managerCanOperateTheProviderButNotCloseOrAdministerRoles() {
        Account owner = login("+919876530030");
        Account manager = login("+919876530031");
        login("+919876530032");
        UUID provider = createProvider(owner);
        RoleAssignmentView managerRow = assign(owner, provider, "+919876530031", "MANAGER");
        String url = "/api/v1/providers/" + provider;
        String token = manager.accessToken();

        // Allowed: edit profile fields.
        ProviderResponse edited = send("PATCH", url, token, Map.of(
                "name", "Manager Edited Mess", "description", "Edited by a manager",
                "locality", "Arera Colony", "contactPhone", "+919800000001"))
                .expectStatus().isOk().expectBody(ProviderResponse.class).returnResult().getResponseBody();
        assertThat(edited.name()).isEqualTo("Manager Edited Mess");
        assertThat(edited.locality()).isEqualTo("Arera Colony");

        // Allowed: edit capacity (set, then clear).
        ProviderResponse capped = send("PATCH", url, token, Map.of("maxActiveSubscriptions", 40))
                .expectStatus().isOk().expectBody(ProviderResponse.class).returnResult().getResponseBody();
        assertThat(capped.maxActiveSubscriptions()).isEqualTo(40);
        ProviderResponse uncapped = send("PATCH", url, token, Map.of("unlimitedCapacity", true))
                .expectStatus().isOk().expectBody(ProviderResponse.class).returnResult().getResponseBody();
        assertThat(uncapped.maxActiveSubscriptions()).isNull();

        // Allowed: non-close status changes and the intake toggle.
        ProviderResponse paused = send("PATCH", url, token, Map.of("status", "TEMPORARILY_UNAVAILABLE"))
                .expectStatus().isOk().expectBody(ProviderResponse.class).returnResult().getResponseBody();
        assertThat(paused.status().name()).isEqualTo("TEMPORARILY_UNAVAILABLE");
        ProviderResponse reactivated = send("PATCH", url, token, Map.of("status", "ACTIVE"))
                .expectStatus().isOk().expectBody(ProviderResponse.class).returnResult().getResponseBody();
        assertThat(reactivated.status().name()).isEqualTo("ACTIVE");
        ProviderResponse notAccepting = send("PATCH", url, token, Map.of("acceptingNewCustomers", false))
                .expectStatus().isOk().expectBody(ProviderResponse.class).returnResult().getResponseBody();
        assertThat(notAccepting.acceptingNewCustomers()).isFalse();

        // Allowed: view the provider and the team.
        send("GET", url, token, null).expectStatus().isOk();
        assertThat(listRoles(manager, provider)).hasSize(2);

        // Denied: closing (even bundled with an otherwise allowed edit), role administration, transfer.
        assertError(send("PATCH", url, token, Map.of("status", "CLOSED", "closureReason", "no")),
                HttpStatus.FORBIDDEN, "INSUFFICIENT_PERMISSION");
        assertError(send("PATCH", url, token, Map.of("name", "Sneaky", "status", "CLOSED")),
                HttpStatus.FORBIDDEN, "INSUFFICIENT_PERMISSION");
        assertError(send("POST", url + "/roles", token, Map.of("phone", "+919876530032", "role", "WORKER")),
                HttpStatus.FORBIDDEN, "INSUFFICIENT_PERMISSION");
        assertError(send("DELETE", url + "/roles/" + managerRow.id(), token, null),
                HttpStatus.FORBIDDEN, "INSUFFICIENT_PERMISSION");
        assertError(send("POST", url + "/ownership/transfer", token, Map.of("phone", "+919876530032")),
                HttpStatus.FORBIDDEN, "INSUFFICIENT_PERMISSION");

        // Nothing denied took effect: provider still open, still one owner, manager still a manager.
        ProviderResponse after = getProvider(owner, provider).expectStatus().isOk()
                .expectBody(ProviderResponse.class).returnResult().getResponseBody();
        assertThat(after.status().name()).isEqualTo("ACTIVE");
        assertThat(after.name()).isEqualTo("Manager Edited Mess"); // the "Sneaky" rename was rejected
        assertThat(roleOf(provider, owner.id())).isEqualTo("OWNER");
        assertThat(roleOf(provider, manager.id())).isEqualTo("MANAGER");
        assertThat(activeRoleCount(provider)).isEqualTo(2);
    }

    @Test
    void invalidTransfersAreRejected() {
        Account owner = login("+919876530017");
        Account manager = login("+919876530018");
        Account outsider = login("+919876530019");
        login("+919876530020");
        UUID provider = createProvider(owner);
        assign(owner, provider, "+919876530018", "MANAGER");
        String url = "/api/v1/providers/" + provider + "/ownership/transfer";

        assertError(send("POST", url, owner.accessToken(), Map.of("phone", "+919876530017")),
                HttpStatus.BAD_REQUEST, "OWNERSHIP_TRANSFER_TO_SELF");
        assertError(send("POST", url, owner.accessToken(), Map.of("phone", "+919876539998")),
                HttpStatus.NOT_FOUND, "TARGET_ACCOUNT_NOT_FOUND");
        send("POST", url, owner.accessToken(), Map.of()).expectStatus().isBadRequest();
        assertError(send("POST", url, manager.accessToken(), Map.of("phone", "+919876530020")),
                HttpStatus.FORBIDDEN, "INSUFFICIENT_PERMISSION");
        send("POST", url, outsider.accessToken(), Map.of("phone", "+919876530020")).expectStatus().isNotFound();
        send("POST", "/api/v1/providers/" + UUID.randomUUID() + "/ownership/transfer", owner.accessToken(),
                Map.of("phone", "+919876530020")).expectStatus().isNotFound();

        assertThat(roleOf(provider, owner.id())).isEqualTo("OWNER");
        assertThat(jdbc.queryForObject("select count(*) from provider_role_audit where provider_id = ? "
                + "and action = 'OWNER_TRANSFERRED'", Integer.class, provider)).isZero();
    }

    @Test
    void finalOwnerCannotBeRevokedOrRemovedAndClosedProvidersAreReadOnly() {
        Account owner = login("+919876530021");
        Account member = login("+919876530022");
        UUID provider = createProvider(owner);
        UUID ownerRow = jdbc.queryForObject(
                "select id from provider_role_assignment where provider_id = ? and role = 'OWNER'",
                UUID.class, provider);

        assertError(send("DELETE", "/api/v1/providers/" + provider + "/roles/" + ownerRow, owner.accessToken(), null),
                HttpStatus.CONFLICT, "OWNER_REVOCATION_NOT_ALLOWED");
        assertThat(roleOf(provider, owner.id())).isEqualTo("OWNER");

        send("PATCH", "/api/v1/providers/" + provider, owner.accessToken(), Map.of("status", "CLOSED"))
                .expectStatus().isOk();
        assertError(send("POST", "/api/v1/providers/" + provider + "/roles", owner.accessToken(),
                Map.of("phone", "+919876530022", "role", "WORKER")), HttpStatus.CONFLICT, "PROVIDER_CLOSED");
        assertError(send("POST", "/api/v1/providers/" + provider + "/ownership/transfer", owner.accessToken(),
                Map.of("phone", "+919876530022")), HttpStatus.CONFLICT, "PROVIDER_CLOSED");
        // Ownership stays available for audit.
        assertThat(listRoles(owner, provider)).hasSize(1);
        assertThat(member).isNotNull();
    }

    // ---------------------------------------------------------------- concurrency

    @Test
    void concurrentTransfersCannotCreateTwoOwners() throws Exception {
        Account alice = login("+919876530023");
        login("+919876530024");
        login("+919876530025");
        UUID provider = createProvider(alice);
        String url = "/api/v1/providers/" + provider + "/ownership/transfer";

        List<Integer> statuses = runConcurrently(
                () -> statusOf(send("POST", url, alice.accessToken(), Map.of("phone", "+919876530024"))),
                () -> statusOf(send("POST", url, alice.accessToken(), Map.of("phone", "+919876530025"))));

        // Exactly one wins; the other is rejected because Alice is no longer OWNER once the lock frees.
        assertThat(statuses).containsExactlyInAnyOrder(200, 403);
        assertThat(jdbc.queryForObject(
                "select count(*) from provider_role_assignment where provider_id = ? and role = 'OWNER' "
                        + "and status = 'ACTIVE'", Integer.class, provider)).isEqualTo(1);
        assertThat(roleOf(provider, alice.id())).isEqualTo("MANAGER");
    }

    @Test
    void concurrentDuplicateAssignmentsCreateOnlyOneActiveRole() throws Exception {
        Account owner = login("+919876530026");
        login("+919876530027");
        UUID provider = createProvider(owner);
        String url = "/api/v1/providers/" + provider + "/roles";

        List<Integer> statuses = runConcurrently(
                () -> statusOf(send("POST", url, owner.accessToken(), Map.of("phone", "+919876530027", "role", "MANAGER"))),
                () -> statusOf(send("POST", url, owner.accessToken(), Map.of("phone", "+919876530027", "role", "WORKER"))));

        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(activeRoleCount(provider)).isEqualTo(2);
    }

    @Test
    void concurrentTransferAndRevocationStayConsistent() throws Exception {
        Account alice = login("+919876530028");
        Account bob = login("+919876530029");
        UUID provider = createProvider(alice);
        RoleAssignmentView bobRow = assign(alice, provider, "+919876530029", "MANAGER");

        List<Integer> statuses = runConcurrently(
                () -> statusOf(send("POST", "/api/v1/providers/" + provider + "/ownership/transfer",
                        alice.accessToken(), Map.of("phone", "+919876530029"))),
                () -> statusOf(send("DELETE", "/api/v1/providers/" + provider + "/roles/" + bobRow.id(),
                        alice.accessToken(), null)));

        // Either order is valid, but the provider must always end with exactly one active OWNER.
        assertThat(jdbc.queryForObject(
                "select count(*) from provider_role_assignment where provider_id = ? and role = 'OWNER' "
                        + "and status = 'ACTIVE'", Integer.class, provider)).isEqualTo(1);
        assertThat(statuses).doesNotContain(500);
        assertThat(bob).isNotNull();
    }

    // -------------------------------------------------------------------- helpers

    private record Account(UUID id, String accessToken) {
    }

    private Account login(String phone) {
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
        return new Account(profile.accountId(), tokens.accessToken());
    }

    private UUID createProvider(Account owner) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "Role API Mess");
        body.put("providerType", "MESS");
        body.put("addressLine", "12 MP Nagar Zone 1");
        body.put("locality", "MP Nagar");
        body.put("city", "Bhopal");
        body.put("pincode", "462011");
        return send("POST", "/api/v1/providers", owner.accessToken(), body)
                .expectStatus().isCreated().expectBody(ProviderResponse.class).returnResult()
                .getResponseBody().id();
    }

    /** token == null sends no Authorization header. */
    private RestTestClient.ResponseSpec send(String method, String uri, String token, Object body) {
        RestTestClient.RequestBodyUriSpec spec = switch (method) {
            case "GET" -> restClient.method(org.springframework.http.HttpMethod.GET);
            case "POST" -> restClient.method(org.springframework.http.HttpMethod.POST);
            case "PATCH" -> restClient.method(org.springframework.http.HttpMethod.PATCH);
            case "DELETE" -> restClient.method(org.springframework.http.HttpMethod.DELETE);
            default -> throw new IllegalArgumentException(method);
        };
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

    private RestTestClient.ResponseSpec getProvider(Account account, UUID providerId) {
        return send("GET", "/api/v1/providers/" + providerId, account.accessToken(), null);
    }

    private List<ProviderResponse> listProviders(Account account) {
        ProviderResponse[] body = send("GET", "/api/v1/providers", account.accessToken(), null)
                .expectStatus().isOk().expectBody(ProviderResponse[].class).returnResult().getResponseBody();
        return body == null ? List.of() : List.of(body);
    }

    private List<RoleAssignmentView> listRoles(Account account, UUID providerId) {
        RoleAssignmentView[] body = send("GET", "/api/v1/providers/" + providerId + "/roles",
                account.accessToken(), null)
                .expectStatus().isOk().expectBody(RoleAssignmentView[].class).returnResult().getResponseBody();
        return body == null ? List.of() : List.of(body);
    }

    private RoleAssignmentView assign(Account owner, UUID providerId, String phone, String role) {
        return send("POST", "/api/v1/providers/" + providerId + "/roles", owner.accessToken(),
                Map.of("phone", phone, "role", role))
                .expectStatus().isCreated().expectBody(RoleAssignmentView.class).returnResult().getResponseBody();
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

    private String roleOf(UUID providerId, UUID accountId) {
        return jdbc.queryForObject(
                "select role from provider_role_assignment where provider_id = ? and account_id = ? "
                        + "and status = 'ACTIVE'", String.class, providerId, accountId);
    }

    private int activeRoleCount(UUID providerId) {
        return jdbc.queryForObject(
                "select count(*) from provider_role_assignment where provider_id = ? and status = 'ACTIVE'",
                Integer.class, providerId);
    }

    @SafeVarargs
    private static List<Integer> runConcurrently(java.util.concurrent.Callable<Integer>... tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.length);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> futures = new java.util.ArrayList<>();
            for (java.util.concurrent.Callable<Integer> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<Integer> results = new java.util.ArrayList<>();
            for (Future<Integer> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}