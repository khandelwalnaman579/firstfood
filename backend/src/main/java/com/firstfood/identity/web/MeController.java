package com.firstfood.identity.web;

import com.firstfood.identity.AuthenticationService;
import com.firstfood.identity.UserAccount;
import com.firstfood.identity.UserAccountRepository;
import com.firstfood.identity.dto.PhoneChangeRequest;
import com.firstfood.identity.dto.PhoneChangeVerifyRequest;
import com.firstfood.identity.dto.ProfileResponse;
import com.firstfood.identity.dto.UpdateEmailRequest;
import com.firstfood.identity.security.AuthenticatedAccount;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Protected profile endpoints - everything here requires a valid Bearer
 * access token (SecurityConfig's anyRequest().authenticated() default).
 *
 * Email is a non-sensitive field: PATCH /me/email just needs a valid
 * token. Phone number is the authentication-critical identity (rules.md
 * Rule 3.3), so changing it goes through its own OTP-gated flow instead
 * of a plain PATCH.
 */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final UserAccountRepository userAccountRepository;
    private final AuthenticationService authenticationService;

    public MeController(UserAccountRepository userAccountRepository, AuthenticationService authenticationService) {
        this.userAccountRepository = userAccountRepository;
        this.authenticationService = authenticationService;
    }

    @GetMapping
    public ProfileResponse getProfile(@AuthenticationPrincipal AuthenticatedAccount principal) {
        UserAccount account = requireAccount(principal);
        return toResponse(account);
    }

    @PatchMapping("/email")
    public ProfileResponse updateEmail(
            @AuthenticationPrincipal AuthenticatedAccount principal, @Valid @RequestBody UpdateEmailRequest request) {
        UserAccount account = requireAccount(principal);
        account.setEmail(request.email());
        userAccountRepository.save(account);
        return toResponse(account);
    }

    @PostMapping("/phone/otp/request")
    public ResponseEntity<Void> requestPhoneChange(
            @AuthenticationPrincipal AuthenticatedAccount principal, @Valid @RequestBody PhoneChangeRequest request) {
        authenticationService.requestPhoneChangeOtp(principal.accountId(), request.newPhone());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/phone/otp/verify")
    public ProfileResponse verifyPhoneChange(
            @AuthenticationPrincipal AuthenticatedAccount principal,
            @Valid @RequestBody PhoneChangeVerifyRequest request) {
        authenticationService.verifyPhoneChangeOtp(principal.accountId(), request.newPhone(), request.code());
        return toResponse(requireAccount(principal));
    }

    private UserAccount requireAccount(AuthenticatedAccount principal) {
        return userAccountRepository.findById(principal.accountId())
                .orElseThrow(() -> new IllegalStateException(
                        "Authenticated account not found: " + principal.accountId()));
    }

    private ProfileResponse toResponse(UserAccount account) {
        return new ProfileResponse(
                account.getId(),
                account.getPhone(),
                account.getEmail(),
                account.getStatus().name(),
                account.getCreatedAt());
    }
}
