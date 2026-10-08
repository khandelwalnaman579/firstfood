package com.firstfood.provider.web;

import com.firstfood.identity.security.AuthenticatedAccount;
import com.firstfood.provider.ProviderService;
import com.firstfood.provider.dto.CreateProviderRequest;
import com.firstfood.provider.dto.ProviderResponse;
import com.firstfood.provider.dto.UpdateProviderRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin HTTP layer only: resolves the authenticated account from the JWT
 * principal and delegates. All provider-scoped authorization lives in
 * {@link ProviderService} via ProviderAccessService - not here (rules.md
 * Rule 18.2; freeze #5). No role-administration endpoints: those are Phase 4.
 */
@RestController
@RequestMapping("/api/v1/providers")
public class ProviderController {

    private final ProviderService providerService;

    public ProviderController(ProviderService providerService) {
        this.providerService = providerService;
    }

    @PostMapping
    public ResponseEntity<ProviderResponse> create(
            @AuthenticationPrincipal AuthenticatedAccount principal,
            @Valid @RequestBody CreateProviderRequest request) {
        ProviderResponse created = providerService.create(principal.accountId(), request);
        return ResponseEntity.created(URI.create("/api/v1/providers/" + created.id())).body(created);
    }

    @GetMapping
    public List<ProviderResponse> list(@AuthenticationPrincipal AuthenticatedAccount principal) {
        return providerService.list(principal.accountId());
    }

    @GetMapping("/{providerId}")
    public ProviderResponse get(
            @AuthenticationPrincipal AuthenticatedAccount principal, @PathVariable UUID providerId) {
        return providerService.get(principal.accountId(), providerId);
    }

    @PatchMapping("/{providerId}")
    public ProviderResponse update(
            @AuthenticationPrincipal AuthenticatedAccount principal,
            @PathVariable UUID providerId,
            @Valid @RequestBody UpdateProviderRequest request) {
        return providerService.update(principal.accountId(), providerId, request);
    }
}
