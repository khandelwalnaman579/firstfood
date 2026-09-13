package com.firstfood.identity;

/**
 * Delivers an OTP code to a phone number. Business logic depends on this
 * interface, never on a vendor SDK directly (architecture.md #24
 * "Business logic should depend on interfaces, not directly on vendor
 * SDKs"), so the delivery channel can be swapped without touching
 * {@link OtpService}.
 */
public interface OtpSender {

    void send(String phone, String otpCode);
}
