package com.firstfood.identity;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Test-only {@link OtpSender}: instead of sending anything, it records
 * the most recent code per phone number in memory so integration tests
 * can complete a real request -> verify flow without needing to
 * intercept an SMS provider. Never active outside the "test" profile.
 */
@Component
@Profile("test")
public class TestCapturingOtpSender implements OtpSender {

    private final Map<String, String> lastOtpByPhone = new ConcurrentHashMap<>();

    @Override
    public void send(String phone, String otpCode) {
        lastOtpByPhone.put(phone, otpCode);
    }

    public String lastOtpFor(String phone) {
        String code = lastOtpByPhone.get(phone);
        if (code == null) {
            throw new IllegalStateException("No OTP was sent to " + phone + " in this test");
        }
        return code;
    }
}
