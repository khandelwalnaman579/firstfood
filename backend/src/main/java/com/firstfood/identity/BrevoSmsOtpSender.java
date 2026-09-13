package com.firstfood.identity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Sends OTPs via Brevo's Transactional SMS API
 * (https://developers.brevo.com/docs/transactional-sms-endpoints).
 *
 * Uses {@code type: "transactional"} deliberately, not "marketing" -
 * marketing SMS in Brevo is subject to time-of-day/day-of-week sending
 * restrictions (and expects a [STOP CODE]), which is wrong for a
 * time-sensitive login code.
 *
 * NOT wired up against a real Brevo account - this has been written
 * against Brevo's published API contract but never actually exercised
 * against a live account/phone number from this environment. Verify with
 * a real API key and a real phone number before relying on it in
 * production (memory.md #7).
 */
@Component
@Profile("production")
public class BrevoSmsOtpSender implements OtpSender {

    private static final String SEND_SMS_URL = "https://api.brevo.com/v3/transactionalSMS/send";

    private final RestClient restClient;
    private final String apiKey;
    private final String senderName;

    public BrevoSmsOtpSender(
            RestClient.Builder restClientBuilder,
            @Value("${app.notifications.brevo.api-key}") String apiKey,
            @Value("${app.notifications.brevo.sender-name}") String senderName) {
        this.restClient = restClientBuilder.build();
        this.apiKey = apiKey;
        this.senderName = senderName;
    }

    @Override
    public void send(String phone, String otpCode) {
        restClient.post()
                .uri(SEND_SMS_URL)
                .header("api-key", apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .body(new BrevoSmsRequest(
                        senderName,
                        phone,
                        "Your FirstFood verification code is " + otpCode + ". It expires shortly.",
                        "transactional"))
                .retrieve()
                .toBodilessEntity();
    }

    private record BrevoSmsRequest(String sender, String recipient, String content, String type) {
    }
}
