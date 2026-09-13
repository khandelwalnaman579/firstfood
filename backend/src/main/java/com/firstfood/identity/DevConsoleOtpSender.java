package com.firstfood.identity;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Prints the OTP directly to the console instead of sending a real SMS,
 * so Phase 2 can be exercised manually on a local machine without a
 * Brevo account. The "test" profile uses a separate in-memory-capturing
 * sender instead (backend/src/test/java/.../TestCapturingOtpSender), so
 * automated tests can assert against the actual code.
 *
 * Deliberately uses {@code System.out}, NOT the application logger
 * ({@code org.slf4j.Logger}): rules.md Rule 17.4 says never log OTP
 * values, and that rule exists specifically to keep OTPs out of log
 * files/log aggregation/ops tooling. Printing straight to the console in
 * local only - never wired in production, see {@link BrevoSmsOtpSender}
 * - is a narrow, contained exception to make manual local testing
 * possible, not a loophole in the logging rule.
 */
@Component
@Profile("local")
public class DevConsoleOtpSender implements OtpSender {

    @Override
    public void send(String phone, String otpCode) {
        System.out.println(
                "[DEV OTP - local/test only, never shown in production] phone=" + phone + " otp=" + otpCode);
    }
}
