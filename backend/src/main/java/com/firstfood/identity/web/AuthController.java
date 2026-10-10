package com.firstfood.identity.web;

import com.firstfood.identity.AuthTokens;
import com.firstfood.identity.AuthenticationService;
import com.firstfood.identity.dto.AuthTokensResponse;
import com.firstfood.identity.dto.OtpRequestRequest;
import com.firstfood.identity.dto.OtpVerifyRequest;
import com.firstfood.identity.dto.RefreshTokenRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public (unauthenticated) auth endpoints - see SecurityConfig for the
 * /api/v1/auth/** permitAll rule. Implements phases.md Phase 2's exit
 * criteria: request OTP -> verify OTP -> authenticate -> refresh ->
 * logout.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationService authenticationService;

    public AuthController(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    @PostMapping("/otp/request")
    public ResponseEntity<Void> requestOtp(@Valid @RequestBody OtpRequestRequest request) {
        authenticationService.requestLoginOtp(request.phone());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/otp/verify")
    public AuthTokensResponse verifyOtp(@Valid @RequestBody OtpVerifyRequest request) {
        AuthTokens tokens = authenticationService.verifyLoginOtp(request.phone(), request.code());
        return toResponse(tokens);
    }

    @PostMapping("/refresh")
    public AuthTokensResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        AuthTokens tokens = authenticationService.refresh(request.refreshToken());
        return toResponse(tokens);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshTokenRequest request) {
        authenticationService.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    private AuthTokensResponse toResponse(AuthTokens tokens) {
        return new AuthTokensResponse(tokens.accessToken(), tokens.refreshToken());
    }
}
