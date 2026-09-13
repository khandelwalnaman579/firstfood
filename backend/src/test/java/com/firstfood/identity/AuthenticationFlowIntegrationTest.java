package com.firstfood.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstfood.AbstractIntegrationTest;
import com.firstfood.identity.dto.AuthTokensResponse;
import com.firstfood.identity.dto.OtpRequestRequest;
import com.firstfood.identity.dto.OtpVerifyRequest;
import com.firstfood.identity.dto.ProfileResponse;
import com.firstfood.identity.dto.RefreshTokenRequest;
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
 */
class AuthenticationFlowIntegrationTest extends AbstractIntegrationTest {

    private static final String PHONE = "+919876500001";

    @Autowired
    RestTestClient restClient;

    @Autowired
    TestCapturingOtpSender otpSender;

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
        ProfileResponse profile = restClient.get()
                .uri("/api/v1/me")
                .header("Authorization", "Bearer " + tokens.accessToken())
                .exchange()
                .expectStatus().isOk()
                .expectBody(ProfileResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(profile).isNotNull();
        assertThat(profile.phone()).isEqualTo(PHONE);

        // 5. refresh the session
        AuthTokensResponse refreshed = restClient.post()
                .uri("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new RefreshTokenRequest(tokens.refreshToken()))
                .exchange()
                .expectStatus().isOk()
                .expectBody(AuthTokensResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(refreshed).isNotNull();
        assertThat(refreshed.refreshToken()).isNotEqualTo(tokens.refreshToken());

        // the old refresh token must no longer work (rotate-on-use)
        restClient.post()
                .uri("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new RefreshTokenRequest(tokens.refreshToken()))
                .exchange()
                .expectStatus().isUnauthorized();

        // 6. logout
        restClient.post()
                .uri("/api/v1/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new RefreshTokenRequest(refreshed.refreshToken()))
                .exchange()
                .expectStatus().isNoContent();

        // the just-logged-out refresh token must no longer work either
        restClient.post()
                .uri("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new RefreshTokenRequest(refreshed.refreshToken()))
                .exchange()
                .expectStatus().isUnauthorized();
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

        restClient.post()
                .uri("/api/v1/auth/otp/request")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new OtpRequestRequest(phone))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.ACCEPTED);

        restClient.post()
                .uri("/api/v1/auth/otp/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new OtpVerifyRequest(phone, "000000"))
                .exchange()
                .expectStatus().isUnauthorized();

        String realOtp = otpSender.lastOtpFor(phone);

        restClient.post()
                .uri("/api/v1/auth/otp/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new OtpVerifyRequest(phone, realOtp))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void secondLoginForSamePhoneReusesTheSameAccount() {
        String phone = "+919876500003";

        UUID firstAccountId = loginAndGetAccountId(phone);
        UUID secondAccountId = loginAndGetAccountId(phone);

        assertThat(secondAccountId).isEqualTo(firstAccountId);
    }

    private UUID loginAndGetAccountId(String phone) {
        restClient.post()
                .uri("/api/v1/auth/otp/request")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new OtpRequestRequest(phone))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.ACCEPTED);

        AuthTokensResponse tokens = restClient.post()
                .uri("/api/v1/auth/otp/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new OtpVerifyRequest(phone, otpSender.lastOtpFor(phone)))
                .exchange()
                .expectStatus().isOk()
                .expectBody(AuthTokensResponse.class)
                .returnResult()
                .getResponseBody();

        ProfileResponse profile = restClient.get()
                .uri("/api/v1/me")
                .header("Authorization", "Bearer " + tokens.accessToken())
                .exchange()
                .expectStatus().isOk()
                .expectBody(ProfileResponse.class)
                .returnResult()
                .getResponseBody();

        return profile.accountId();
    }
}