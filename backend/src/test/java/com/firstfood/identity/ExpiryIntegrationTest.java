package com.firstfood.identity;

import com.firstfood.AbstractIntegrationTest;
import com.firstfood.identity.dto.AuthTokensResponse;
import com.firstfood.identity.dto.OtpRequestRequest;
import com.firstfood.identity.dto.OtpVerifyRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Expiry has to be exercised with a near-zero TTL to be deterministic in
 * a test (waiting out the real 5-minute OTP / 15-minute JWT TTLs isn't
 * practical) - kept in its own class so these property overrides don't
 * affect {@link AuthenticationFlowIntegrationTest}'s normal-TTL
 * assertions. Spring spins up a separate application context for the
 * different property set; the Postgres/Redis containers themselves are
 * still the shared singletons from {@link AbstractIntegrationTest}.
 */
class ExpiryIntegrationTest extends AbstractIntegrationTest {

    @DynamicPropertySource
    static void tinyTtls(DynamicPropertyRegistry registry) {
        registry.add("app.otp.ttl-minutes", () -> 0);
        registry.add("app.jwt.access-token-ttl-minutes", () -> 0);
    }

    @Autowired
    RestTestClient restClient;

    @Autowired
    TestCapturingOtpSender otpSender;

    @Test
    void expiredOtpIsRejected() throws InterruptedException {
        String phone = "+919876500020";

        restClient.post()
                .uri("/api/v1/auth/otp/request")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new OtpRequestRequest(phone))
                .exchange();

        String code = otpSender.lastOtpFor(phone);

        // ttl-minutes=0 means expiresAt == the instant the OTP was
        // created; any measurable delay afterwards is already "expired".
        // A short sleep makes that timing-independent instead of relying
        // on it happening to already be true by the time this line runs.
        Thread.sleep(50);

        restClient.post()
                .uri("/api/v1/auth/otp/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new OtpVerifyRequest(phone, code))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void expiredAccessTokenIsRejected() throws InterruptedException {
        String phone = "+919876500021";

        restClient.post()
                .uri("/api/v1/auth/otp/request")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new OtpRequestRequest(phone))
                .exchange();

        AuthTokensResponse tokens = restClient.post()
                .uri("/api/v1/auth/otp/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new OtpVerifyRequest(phone, otpSender.lastOtpFor(phone)))
                .exchange()
                .expectBody(AuthTokensResponse.class)
                .returnResult()
                .getResponseBody();

        // JWT exp/iat are second-resolution - sleep past the second
        // boundary so this isn't a coin-flip on how the clock rounds.
        Thread.sleep(1_500);

        restClient.get()
                .uri("/api/v1/me")
                .header("Authorization", "Bearer " + tokens.accessToken())
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
