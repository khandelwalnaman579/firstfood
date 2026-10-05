package com.firstfood.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.firstfood.AbstractIntegrationTest;
import com.firstfood.identity.TestCapturingOtpSender;
import com.firstfood.identity.dto.AuthTokensResponse;
import com.firstfood.identity.dto.OtpRequestRequest;
import com.firstfood.identity.dto.OtpVerifyRequest;
import com.firstfood.identity.dto.ProfileResponse;
import com.firstfood.provider.dto.ProviderResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Phase 3 acceptance tests (FirstFood_V2_Phase3_Domain_Decision_Freeze.md
 * section 15; execution plan section 7) against real Postgres + Redis.
 *
 * Each test logs in its own phone number(s): Postgres and Redis are singleton
 * containers shared across the whole run (see AbstractIntegrationTest), so
 * phones must not be reused across tests. Phones here use the +91987651xxxx
 * range; AuthenticationFlowIntegrationTest uses +91987650xxxx.
 */
class ProviderIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    RestTestClient restClient;

    @Autowired
    TestCapturingOtpSender otpSender;

    @Autowired
    JdbcTemplate jdbc;

    // ---------------------------------------------------------------- creation

    @Test
    void createProviderMakesCreatorTheSingleOwnerAndPersistsProfile() {
        Account owner = login("+919876510001");

        ProviderResponse created = create(owner, validCreateBody("Annapurna Mess"));

        assertThat(created.id()).isNotNull();
        assertThat(created.name()).isEqualTo("Annapurna Mess");
        assertThat(created.providerType()).isEqualTo(ProviderType.MESS);
        assertThat(created.addressLine()).isEqualTo("12 MP Nagar Zone 1");
        assertThat(created.locality()).isEqualTo("MP Nagar");
        assertThat(created.city()).isEqualTo("Bhopal");
        assertThat(created.pincode()).isEqualTo("462011");
        assertThat(created.status()).isEqualTo(ProviderStatus.ACTIVE);
        assertThat(created.acceptingNewCustomers()).isTrue();
        assertThat(created.maxActiveSubscriptions()).isNull();
        assertThat(created.myRole().name()).isEqualTo("OWNER");

        // Exactly one OWNER row, and it is the authenticated creator.
        List<UUID> owners = jdbc.queryForList(
                "select account_id from provider_role_assignment where provider_id = ? and role = 'OWNER'",
                UUID.class, created.id());
        assertThat(owners).containsExactly(owner.id());

        // Round-trips through GET.
        ProviderResponse fetched = get(owner, created.id());
        assertThat(fetched.locality()).isEqualTo("MP Nagar");
        assertThat(fetched.providerType()).isEqualTo(ProviderType.MESS);
    }

    @Test
    void clientCannotChooseOwnerOrRoleOrStatusOnCreate() {
        Account creator = login("+919876510002");
        Account other = login("+919876510003");

        Map<String, Object> body = validCreateBody("Sneaky");
        body.put("ownerAccountId", other.id().toString());
        body.put("accountId", other.id().toString());
        body.put("role", "WORKER");
        body.put("status", "CLOSED");
        body.put("acceptingNewCustomers", false);

        ProviderResponse created = create(creator, body);

        assertThat(created.status()).isEqualTo(ProviderStatus.ACTIVE);
        assertThat(created.acceptingNewCustomers()).isTrue();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select account_id, role from provider_role_assignment where provider_id = ?", created.id());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("account_id")).isEqualTo(creator.id());
        assertThat(rows.get(0).get("role")).isEqualTo("OWNER");
    }

    @Test
    void createRejectsInvalidInput() {
        Account owner = login("+919876510004");

        Map<String, Object> noName = validCreateBody("x");
        noName.remove("name");
        postProvider(owner, noName).expectStatus().isBadRequest();

        Map<String, Object> badType = validCreateBody("x");
        badType.put("providerType", "RESTAURANT");
        postProvider(owner, badType).expectStatus().isBadRequest();

        Map<String, Object> badPincode = validCreateBody("x");
        badPincode.put("pincode", "12");
        postProvider(owner, badPincode).expectStatus().isBadRequest();

        Map<String, Object> zeroCapacity = validCreateBody("x");
        zeroCapacity.put("maxActiveSubscriptions", 0);
        postProvider(owner, zeroCapacity).expectStatus().isBadRequest();

        assertThat(list(owner)).isEmpty();
    }

    @Test
    void endpointsRequireAuthentication() {
        restClient.get().uri("/api/v1/providers").exchange().expectStatus().isUnauthorized();
        restClient.post().uri("/api/v1/providers")
                .contentType(MediaType.APPLICATION_JSON)
                .body(validCreateBody("x"))
                .exchange().expectStatus().isUnauthorized();
        restClient.get().uri("/api/v1/providers/" + UUID.randomUUID()).exchange().expectStatus().isUnauthorized();
    }

    // ------------------------------------------------------ database constraints

    @Test
    void databaseRejectsASecondOwnerOnTheSameProvider() {
        Account owner = login("+919876510005");
        Account other = login("+919876510006");
        ProviderResponse provider = create(owner, validCreateBody("One Owner Only"));

        assertThatThrownBy(() -> insertAssignment(provider.id(), other.id(), "OWNER"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsDuplicateAssignmentAndUnknownRole() {
        Account owner = login("+919876510007");
        Account manager = login("+919876510008");
        ProviderResponse provider = create(owner, validCreateBody("Dup Check"));

        insertAssignment(provider.id(), manager.id(), "MANAGER"); // MANAGER rows are legal in the schema
        assertThatThrownBy(() -> insertAssignment(provider.id(), manager.id(), "MANAGER"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertAssignment(provider.id(), manager.id(), "SUPERUSER"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsImpossibleStateCombinations() {
        Account owner = login("+919876510009");
        UUID id = create(owner, validCreateBody("Constraint Check")).id();

        // FULL while still accepting customers
        assertThatThrownBy(() -> jdbc.update("update food_provider set status = 'FULL' where id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        // CLOSED without closure metadata
        assertThatThrownBy(() -> jdbc.update(
                "update food_provider set status = 'CLOSED', accepting_new_customers = false where id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        // closure metadata on a non-closed provider
        assertThatThrownBy(() -> jdbc.update("update food_provider set closed_at = now() where id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        // capacity below 1
        assertThatThrownBy(() -> jdbc.update("update food_provider set max_active_subscriptions = 0 where id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        // unknown status / type
        assertThatThrownBy(() -> jdbc.update("update food_provider set status = 'PAUSED' where id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update food_provider set provider_type = 'RESTAURANT' where id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void providerRowCannotBePhysicallyDeletedOnceItHasAnOwner() {
        Account owner = login("+919876510010");
        UUID id = create(owner, validCreateBody("No Hard Delete")).id();

        // FK from provider_role_assignment has no cascade: history is never silently erased.
        assertThatThrownBy(() -> jdbc.update("delete from food_provider where id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ------------------------------------------------------------------ capacity

    @Test
    void capacityIsStoredValidatedAndCanBeClearedToUnlimited() {
        Account owner = login("+919876510011");
        Map<String, Object> body = validCreateBody("Capacity Mess");
        body.put("maxActiveSubscriptions", 100);
        ProviderResponse created = create(owner, body);
        assertThat(created.maxActiveSubscriptions()).isEqualTo(100);

        assertThat(patch(owner, created.id(), Map.of("maxActiveSubscriptions", 45))
                .expectStatus().isOk().expectBody(ProviderResponse.class).returnResult().getResponseBody()
                .maxActiveSubscriptions()).isEqualTo(45);

        patch(owner, created.id(), Map.of("maxActiveSubscriptions", 0)).expectStatus().isBadRequest();
        patch(owner, created.id(), Map.of("maxActiveSubscriptions", -3)).expectStatus().isBadRequest();
        patch(owner, created.id(), Map.of("maxActiveSubscriptions", 10, "unlimitedCapacity", true))
                .expectStatus().isBadRequest();
        assertThat(get(owner, created.id()).maxActiveSubscriptions()).isEqualTo(45);

        ProviderResponse cleared = patch(owner, created.id(), Map.of("unlimitedCapacity", true))
                .expectStatus().isOk().expectBody(ProviderResponse.class).returnResult().getResponseBody();
        assertThat(cleared.maxActiveSubscriptions()).isNull();
    }

    // -------------------------------------------------------------------- status

    @Test
    void validStatusTransitionsSucceedAndControlIntake() {
        Account owner = login("+919876510012");
        UUID id = create(owner, validCreateBody("Lifecycle")).id();

        ProviderResponse full = patchOk(owner, id, Map.of("status", "FULL"));
        assertThat(full.status()).isEqualTo(ProviderStatus.FULL);
        assertThat(full.acceptingNewCustomers()).isFalse();

        ProviderResponse temp = patchOk(owner, id, Map.of("status", "TEMPORARILY_UNAVAILABLE"));
        assertThat(temp.status()).isEqualTo(ProviderStatus.TEMPORARILY_UNAVAILABLE);
        assertThat(temp.acceptingNewCustomers()).isFalse();

        ProviderResponse active = patchOk(owner, id, Map.of("status", "ACTIVE"));
        assertThat(active.status()).isEqualTo(ProviderStatus.ACTIVE);
        assertThat(active.acceptingNewCustomers()).isTrue();

        // ACTIVE + intake switched off is a legal combination.
        ProviderResponse paused = patchOk(owner, id, Map.of("acceptingNewCustomers", false));
        assertThat(paused.status()).isEqualTo(ProviderStatus.ACTIVE);
        assertThat(paused.acceptingNewCustomers()).isFalse();

        // Back to ACTIVE can also pick the intake state explicitly.
        patchOk(owner, id, Map.of("status", "FULL"));
        ProviderResponse reopenedClosedIntake = patchOk(owner, id,
                Map.of("status", "ACTIVE", "acceptingNewCustomers", false));
        assertThat(reopenedClosedIntake.acceptingNewCustomers()).isFalse();
    }

    @Test
    void invalidTransitionsAndStateCombinationsAreRejected() {
        Account owner = login("+919876510013");
        UUID id = create(owner, validCreateBody("Invalid Moves")).id();

        // TEMPORARILY_UNAVAILABLE -> FULL is not in the transition graph.
        patchOk(owner, id, Map.of("status", "TEMPORARILY_UNAVAILABLE"));
        patch(owner, id, Map.of("status", "FULL")).expectStatus().isEqualTo(HttpStatus.CONFLICT);
        assertThat(get(owner, id).status()).isEqualTo(ProviderStatus.TEMPORARILY_UNAVAILABLE);

        // Non-ACTIVE statuses can't be combined with accepting = true.
        patch(owner, id, Map.of("acceptingNewCustomers", true)).expectStatus().isBadRequest();
        patchOk(owner, id, Map.of("status", "ACTIVE"));
        patch(owner, id, Map.of("status", "FULL", "acceptingNewCustomers", true)).expectStatus().isBadRequest();
        assertThat(get(owner, id).status()).isEqualTo(ProviderStatus.ACTIVE);

        // Garbage status value.
        patch(owner, id, Map.of("status", "PAUSED")).expectStatus().isBadRequest();
        // closureReason only makes sense when closing.
        patch(owner, id, Map.of("closureReason", "just because")).expectStatus().isBadRequest();
    }

    // ------------------------------------------------------------------- closure

    @Test
    void ownerCanSoftCloseAProviderAndClosureIsTerminal() {
        Account owner = login("+919876510014");
        UUID id = create(owner, validCreateBody("Closing Down")).id();

        ProviderResponse closed = patchOk(owner, id, Map.of("status", "CLOSED", "closureReason", "Owner retired"));
        assertThat(closed.status()).isEqualTo(ProviderStatus.CLOSED);
        assertThat(closed.acceptingNewCustomers()).isFalse();
        assertThat(closed.closedAt()).isNotNull();
        assertThat(closed.closedByAccountId()).isEqualTo(owner.id());
        assertThat(closed.closureReason()).isEqualTo("Owner retired");

        // The historical row (and its owner assignment) is still there.
        assertThat(jdbc.queryForObject("select count(*) from food_provider where id = ?", Integer.class, id))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from provider_role_assignment where provider_id = ?", Integer.class, id))
                .isEqualTo(1);
        // Still readable and still listed.
        assertThat(get(owner, id).status()).isEqualTo(ProviderStatus.CLOSED);
        assertThat(list(owner)).extracting(ProviderResponse::id).contains(id);

        // Terminal: no reopening, no edits.
        patch(owner, id, Map.of("status", "ACTIVE")).expectStatus().isEqualTo(HttpStatus.CONFLICT);
        patch(owner, id, Map.of("name", "Renamed After Close")).expectStatus().isEqualTo(HttpStatus.CONFLICT);
        patch(owner, id, Map.of("status", "CLOSED")).expectStatus().isEqualTo(HttpStatus.CONFLICT);
        ProviderResponse after = get(owner, id);
        assertThat(after.status()).isEqualTo(ProviderStatus.CLOSED);
        assertThat(after.name()).isEqualTo("Closing Down");
    }

    @Test
    void closureReasonIsOptional() {
        Account owner = login("+919876510015");
        UUID id = create(owner, validCreateBody("Quiet Close")).id();

        ProviderResponse closed = patchOk(owner, id, Map.of("status", "CLOSED"));
        assertThat(closed.closedAt()).isNotNull();
        assertThat(closed.closedByAccountId()).isEqualTo(owner.id());
        assertThat(closed.closureReason()).isNull();
    }

    @Test
    void closingWhileAcceptingIsRejected() {
        Account owner = login("+919876510016");
        UUID id = create(owner, validCreateBody("Bad Close")).id();

        patch(owner, id, Map.of("status", "CLOSED", "acceptingNewCustomers", true)).expectStatus().isBadRequest();
        assertThat(get(owner, id).status()).isEqualTo(ProviderStatus.ACTIVE);
    }

    // ------------------------------------------------ profile updates / multi-provider

    @Test
    void ownerCanUpdateProfileFieldsAndLeaveOthersUntouched() {
        Account owner = login("+919876510017");
        UUID id = create(owner, validCreateBody("Before")).id();

        ProviderResponse updated = patchOk(owner, id, Map.of(
                "name", "After", "providerType", "TIFFIN", "locality", "Arera Colony",
                "description", "Home-style food", "contactPhone", "+919999900000"));

        assertThat(updated.name()).isEqualTo("After");
        assertThat(updated.providerType()).isEqualTo(ProviderType.TIFFIN);
        assertThat(updated.locality()).isEqualTo("Arera Colony");
        assertThat(updated.description()).isEqualTo("Home-style food");
        assertThat(updated.contactPhone()).isEqualTo("+919999900000");
        assertThat(updated.city()).isEqualTo("Bhopal");
        assertThat(updated.addressLine()).isEqualTo("12 MP Nagar Zone 1");

        patch(owner, id, Map.of("name", "   ")).expectStatus().isBadRequest();
    }

    @Test
    void ownerCanManageMultipleIndependentProviders() {
        Account owner = login("+919876510018");
        UUID a = create(owner, validCreateBody("Provider A")).id();
        UUID b = create(owner, validCreateBody("Provider B")).id();
        UUID c = create(owner, validCreateBody("Provider C")).id();

        assertThat(list(owner)).extracting(ProviderResponse::id).containsExactlyInAnyOrder(a, b, c);

        patchOk(owner, b, Map.of("status", "FULL"));
        assertThat(get(owner, a).status()).isEqualTo(ProviderStatus.ACTIVE);
        assertThat(get(owner, b).status()).isEqualTo(ProviderStatus.FULL);
        assertThat(get(owner, c).status()).isEqualTo(ProviderStatus.ACTIVE);
    }

    // --------------------------------------------------- cross-provider isolation

    @Test
    void accountCannotReadUpdateOrListAnotherAccountsProvider() {
        Account alice = login("+919876510019");
        Account bob = login("+919876510020");
        ProviderResponse aliceProvider = create(alice, validCreateBody("Alice Kitchen"));
        ProviderResponse bobProvider = create(bob, validCreateBody("Bob Kitchen"));

        // GET: Alice -> Bob's provider is a 404, indistinguishable from a provider that doesn't exist.
        Map<?, ?> foreignBody = getRaw(alice, bobProvider.id()).expectStatus().isNotFound()
                .expectBody(Map.class).returnResult().getResponseBody();
        Map<?, ?> missingBody = getRaw(alice, UUID.randomUUID()).expectStatus().isNotFound()
                .expectBody(Map.class).returnResult().getResponseBody();
        assertThat(foreignBody.get("code")).isEqualTo("PROVIDER_NOT_FOUND");
        assertThat(foreignBody.get("code")).isEqualTo(missingBody.get("code"));
        assertThat(foreignBody.get("message")).isEqualTo(missingBody.get("message"));

        // PATCH (including closing) against someone else's provider: 404, and nothing changes.
        patch(alice, bobProvider.id(), Map.of("name", "Hijacked")).expectStatus().isNotFound();
        patch(alice, bobProvider.id(), Map.of("status", "CLOSED", "closureReason", "hostile"))
                .expectStatus().isNotFound();
        patch(alice, bobProvider.id(), Map.of("maxActiveSubscriptions", 1)).expectStatus().isNotFound();

        ProviderResponse bobAfter = get(bob, bobProvider.id());
        assertThat(bobAfter.name()).isEqualTo("Bob Kitchen");
        assertThat(bobAfter.status()).isEqualTo(ProviderStatus.ACTIVE);
        assertThat(bobAfter.maxActiveSubscriptions()).isNull();
        assertThat(bobAfter.closedAt()).isNull();

        // Lists are scoped to the caller.
        assertThat(list(alice)).extracting(ProviderResponse::id)
                .contains(aliceProvider.id()).doesNotContain(bobProvider.id());
        assertThat(list(bob)).extracting(ProviderResponse::id)
                .contains(bobProvider.id()).doesNotContain(aliceProvider.id());
    }

    @Test
    void changingProviderIdInUrlCannotBypassAuthorization() {
        Account alice = login("+919876510021");
        Account bob = login("+919876510022");
        ProviderResponse aliceProvider = create(alice, validCreateBody("Alice Only"));
        ProviderResponse bobProvider = create(bob, validCreateBody("Bob Only"));

        // Alice legitimately updates her own provider...
        patchOk(alice, aliceProvider.id(), Map.of("name", "Alice Renamed"));
        // ...then reuses the exact same request with only the URL id swapped.
        patch(alice, bobProvider.id(), Map.of("name", "Alice Renamed")).expectStatus().isNotFound();
        assertThat(get(bob, bobProvider.id()).name()).isEqualTo("Bob Only");
    }

    @Test
    void malformedProviderIdIsABadRequestNotAServerError() {
        Account owner = login("+919876510023");
        restClient.get().uri("/api/v1/providers/not-a-uuid")
                .header("Authorization", "Bearer " + owner.accessToken())
                .exchange().expectStatus().isBadRequest();
    }

    // ------------------------------------------------------------------- helpers

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

    private Map<String, Object> validCreateBody(String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("providerType", "MESS");
        body.put("addressLine", "12 MP Nagar Zone 1");
        body.put("locality", "MP Nagar");
        body.put("city", "Bhopal");
        body.put("pincode", "462011");
        return body;
    }

    private RestTestClient.ResponseSpec postProvider(Account account, Map<String, Object> body) {
        return restClient.post().uri("/api/v1/providers")
                .header("Authorization", "Bearer " + account.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange();
    }

    private ProviderResponse create(Account account, Map<String, Object> body) {
        return postProvider(account, body).expectStatus().isCreated()
                .expectBody(ProviderResponse.class).returnResult().getResponseBody();
    }

    private RestTestClient.ResponseSpec getRaw(Account account, UUID providerId) {
        return restClient.get().uri("/api/v1/providers/" + providerId)
                .header("Authorization", "Bearer " + account.accessToken())
                .exchange();
    }

    private ProviderResponse get(Account account, UUID providerId) {
        return getRaw(account, providerId).expectStatus().isOk()
                .expectBody(ProviderResponse.class).returnResult().getResponseBody();
    }

    private List<ProviderResponse> list(Account account) {
        ProviderResponse[] body = restClient.get().uri("/api/v1/providers")
                .header("Authorization", "Bearer " + account.accessToken())
                .exchange().expectStatus().isOk()
                .expectBody(ProviderResponse[].class).returnResult().getResponseBody();
        return body == null ? List.of() : List.of(body);
    }

    private RestTestClient.ResponseSpec patch(Account account, UUID providerId, Map<String, Object> body) {
        return restClient.patch().uri("/api/v1/providers/" + providerId)
                .header("Authorization", "Bearer " + account.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange();
    }

    private ProviderResponse patchOk(Account account, UUID providerId, Map<String, Object> body) {
        return patch(account, providerId, body).expectStatus().isOk()
                .expectBody(ProviderResponse.class).returnResult().getResponseBody();
    }

    private void insertAssignment(UUID providerId, UUID accountId, String role) {
        // assigned_by is NOT NULL since V6. Supplying it keeps the constraint tests below failing for
        // the reason they assert (second OWNER / duplicate / bad role), not for a missing column.
        jdbc.update(
                "insert into provider_role_assignment (id, provider_id, account_id, role, assigned_by) "
                        + "values (?, ?, ?, ?, ?)",
                UUID.randomUUID(), providerId, accountId, role, accountId);
    }
}