package com.firstfood.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstfood.AbstractIntegrationTest;
import com.firstfood.identity.dto.AuthTokensResponse;
import com.firstfood.identity.dto.OtpRequestRequest;
import com.firstfood.identity.dto.OtpVerifyRequest;
import com.firstfood.identity.dto.PhoneChangeRequest;
import com.firstfood.identity.dto.PhoneChangeVerifyRequest;
import com.firstfood.identity.dto.ProfileResponse;
import com.firstfood.identity.dto.RefreshTokenRequest;
import com.firstfood.identity.dto.UpdateEmailRequest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Exercises phases.md Phase 2's full exit-criteria flow end to end:
 * request OTP -> verify OTP -> authenticate -> access protected API ->
 * refresh session -> logout - against a real Postgres + Redis, using
 * {@link TestCapturingOtpSender} to read back the code that would
 * otherwise have gone out over SMS.
 *
 * Also covers the security gaps found in
 * FirstFood_V2_Phase2_Final_Review.md: suspended-account rejection (both
 * JWT and refresh paths), OTP max-attempt exhaustion, OTP request rate
 * limiting, email update, and the phone-change flow including the
 * already-in-use rejection.
 *
 * Every test uses its own phone number - Redis (rate-limit counters) and
 * Postgres are real, shared singleton containers across the whole test
 * run (see AbstractIntegrationTest), so reusing a phone across tests
 * would leak state between them.
 */
class AuthenticationFlowIntegrationTest extends AbstractIntegrationTest {

    private static final String PHONE = "+919876500001";

    @Autowired
    RestTestClient restClient;

    @Autowired
    TestCapturingOtpSender otpSender;

    @Autowired
    UserAccountRepository userAccountRepository;

    @Test
    void fullLoginRefreshLogoutFlow() {
        // 1. request OTP
        restClient.post()
                .uri("/api/v1/auth/otp/request")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new OtpRequestRequest(PHONE))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.ACCEPTED);

        String otp = otpSender.lastOtpFor(PHONE);

        // 2. verify OTP -> 3. authenticate (tokens issued)
        AuthTokensResponse tokens = restClient.post()
                .uri("/api/v1/auth/otp/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new OtpVerifyRequest(PHONE, otp))
                .exchange()
                .expectStatus().isOk()
                .expectBody(AuthTokensResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(tokens).isNotNull();
        assertThat(tokens.accessToken()).isNotBlank();
        assertThat(tokens.refreshToken()).isNotBlank();

        // 4. access a protected API with the access token
        ProfileResponse profile = getProfile(tokens.accessToken());
        assertThat(profile).isNotNull();
        assertThat(profile.phone()).isEqualTo(PHONE);

        // 5. refresh the session
        AuthTokensResponse refreshed = refresh(tokens.refreshToken())
                .expectStatus().isOk()
                .expectBody(AuthTokensResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(refreshed).isNotNull();
        assertThat(refreshed.refreshToken()).isNotEqualTo(tokens.refreshToken());

        // the old refresh token must no longer work (rotate-on-use)
        refresh(tokens.refreshToken()).expectStatus().isUnauthorized();

        // 6. logout
        restClient.post()
                .uri("/api/v1/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new RefreshTokenRequest(refreshed.refreshToken()))
                .exchange()
                .expectStatus().isNoContent();

        // the just-logged-out refresh token must no longer work either
        refresh(refreshed.refreshToken()).expectStatus().isUnauthorized();
    }

    @Test
    void protectedEndpointRejectsMissingToken() {
        restClient.get()
                .uri("/api/v1/me")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void protectedEndpointRejectsGarbageToken() {
        restClient.get()
                .uri("/api/v1/me")
                .header("Authorization", "Bearer not-a-real-token")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void wrongOtpIsRejectedWithoutConsumingTheRealOne() {
        String phone = "+919876500002";

        requestOtp(phone).expectStatus().isEqualTo(HttpStatus.ACCEPTED);

        verifyOtp(phone, "000000").expectStatus().isUnauthorized();

        String realOtp = otpSender.lastOtpFor(phone);
        verifyOtp(phone, realOtp).expectStatus().isOk();
    }

    @Test
    void secondLoginForSamePhoneReusesTheSameAccount() {
        String phone = "+919876500003";

        UUID firstAccountId = loginAndGetProfile(phone).accountId();
        UUID secondAccountId = loginAndGetProfile(phone).accountId();

        assertThat(secondAccountId).isEqualTo(firstAccountId);
    }

    @Test
    void maxOtpAttemptsExhaustsTheChallengeEvenForTheRightCode() {
        // app.otp.max-attempts is 5 (application.yml) - 5 wrong guesses
        // must exhaust the challenge, so a 6th attempt with the *correct*
        // code afterwards must still be rejected as expired, not accepted.
        String phone = "+919876500004";

        requestOtp(phone).expectStatus().isEqualTo(HttpStatus.ACCEPTED);
        String realOtp = otpSender.lastOtpFor(phone);

        for (int attempt = 0; attempt < 5; attempt++) {
            verifyOtp(phone, "000000").expectStatus().isUnauthorized();
        }

        verifyOtp(phone, realOtp).expectStatus().isUnauthorized();
    }

    @Test
    void otpRequestRateLimitIsEnforced() {
        // app.otp.request-rate-limit-per-hour is 5 (application.yml).
        String phone = "+919876500005";

        for (int i = 0; i < 5; i++) {
            requestOtp(phone).expectStatus().isEqualTo(HttpStatus.ACCEPTED);
        }

        requestOtp(phone).expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void suspendedAccountCannotAccessProtectedEndpoints() {
        String phone = "+919876500006";
        AuthTokensResponse tokens = login(phone);

        suspendAccount(phone);

        // the already-issued access token must stop working immediately,
        // not just after it naturally expires (FirstFood_V2_Phase2_Final_
        // Review.md #12).
        restClient.get()
                .uri("/api/v1/me")
                .header("Authorization", "Bearer " + tokens.accessToken())
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void suspendedAccountCannotRefresh() {
        String phone = "+919876500007";
        AuthTokensResponse tokens = login(phone);

        suspendAccount(phone);

        refresh(tokens.refreshToken()).expectStatus().isForbidden();

        // and the refresh token must have been consumed/revoked in the
        // process, not left usable if the account is later reactivated
        // mid-session - a second attempt must also fail.
        refresh(tokens.refreshToken()).expectStatus().isUnauthorized();
    }

    @Test
    void emailUpdateWorks() {
        String phone = "+919876500008";
        AuthTokensResponse tokens = login(phone);

        ProfileResponse updated = restClient.patch()
                .uri("/api/v1/me/email")
                .header("Authorization", "Bearer " + tokens.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new UpdateEmailRequest("person@example.com"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(ProfileResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(updated).isNotNull();
        assertThat(updated.email()).isEqualTo("person@example.com");
    }

    @Test
    void phoneChangeFlowWorks() {
        String oldPhone = "+919876500009";
        String newPhone = "+919876500010";
        AuthTokensResponse tokens = login(oldPhone);

        restClient.post()
                .uri("/api/v1/me/phone/otp/request")
                .header("Authorization", "Bearer " + tokens.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new PhoneChangeRequest(newPhone))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.ACCEPTED);

        String code = otpSender.lastOtpFor(newPhone);

        ProfileResponse updated = restClient.post()
                .uri("/api/v1/me/phone/otp/verify")
                .header("Authorization", "Bearer " + tokens.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new PhoneChangeVerifyRequest(newPhone, code))
                .exchange()
                .expectStatus().isOk()
                .expectBody(ProfileResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(updated).isNotNull();
        assertThat(updated.phone()).isEqualTo(newPhone);
    }

    @Test
    void phoneChangeRejectsAlreadyInUseNumber() {
        String phoneA = "+919876500011";
        String phoneB = "+919876500012";

        login(phoneA);
        AuthTokensResponse tokensB = login(phoneB);

        // phoneB's account tries to change to phoneA's number, which is
        // already taken.
        restClient.post()
                .uri("/api/v1/me/phone/otp/request")
                .header("Authorization", "Bearer " + tokensB.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new PhoneChangeRequest(phoneA))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT);
    }

    private AuthTokensResponse login(String phone) {
        requestOtp(phone).expectStatus().isEqualTo(HttpStatus.ACCEPTED);

        AuthTokensResponse tokens = verifyOtp(phone, otpSender.lastOtpFor(phone))
                .expectStatus().isOk()
                .expectBody(AuthTokensResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(tokens).isNotNull();
        return tokens;
    }

    private ProfileResponse loginAndGetProfile(String phone) {
        AuthTokensResponse tokens = login(phone);
        return getProfile(tokens.accessToken());
    }

    private ProfileResponse getProfile(String accessToken) {
        return restClient.get()
                .uri("/api/v1/me")
                .header("Authorization", "Bearer " + accessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody(ProfileResponse.class)
                .returnResult()
                .getResponseBody();
    }

    private void suspendAccount(String phone) {
        UserAccount account = userAccountRepository.findByPhone(phone).orElseThrow();
        account.setStatus(AccountStatus.SUSPENDED);
        userAccountRepository.save(account);
    }

    private RestTestClient.ResponseSpec requestOtp(String phone) {
        return restClient.post()
                .uri("/api/v1/auth/otp/request")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new OtpRequestRequest(phone))
                .exchange();
    }

    private RestTestClient.ResponseSpec verifyOtp(String phone, String code) {
        return restClient.post()
                .uri("/api/v1/auth/otp/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new OtpVerifyRequest(phone, code))
                .exchange();
    }

    private RestTestClient.ResponseSpec refresh(String refreshToken) {
        return restClient.post()
                .uri("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new RefreshTokenRequest(refreshToken))
                .exchange();
    }
}
