package com.firstfood.membership.web;

import com.firstfood.identity.security.AuthenticatedAccount;
import com.firstfood.membership.CustomerView;
import com.firstfood.membership.MembershipService;
import com.firstfood.membership.dto.AddCustomerRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin HTTP layer: the acting account is always the JWT principal; every
 * authorization decision happens in {@link MembershipService}.
 */
@RestController
@RequestMapping("/api/v1/providers/{providerId}/customers")
public class CustomerController {

    private final MembershipService membershipService;

    public CustomerController(MembershipService membershipService) {
        this.membershipService = membershipService;
    }

    @GetMapping
    public List<CustomerView> list(
            @AuthenticationPrincipal AuthenticatedAccount principal,
            @PathVariable UUID providerId,
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return membershipService.listCustomers(principal.accountId(), providerId, includeInactive);
    }

    @PostMapping
    public ResponseEntity<CustomerView> add(
            @AuthenticationPrincipal AuthenticatedAccount principal,
            @PathVariable UUID providerId,
            @Valid @RequestBody AddCustomerRequest request) {
        CustomerView created =
                membershipService.addCustomer(principal.accountId(), providerId, request.phone(), request.fullName());
        return ResponseEntity.created(
                        URI.create("/api/v1/providers/" + providerId + "/customers/" + created.membershipId()))
                .body(created);
    }

    @GetMapping("/{membershipId}")
    public CustomerView get(
            @AuthenticationPrincipal AuthenticatedAccount principal,
            @PathVariable UUID providerId,
            @PathVariable UUID membershipId) {
        return membershipService.getCustomer(principal.accountId(), providerId, membershipId);
    }

    @PostMapping("/{membershipId}/deactivate")
    public CustomerView deactivate(
            @AuthenticationPrincipal AuthenticatedAccount principal,
            @PathVariable UUID providerId,
            @PathVariable UUID membershipId) {
        return membershipService.deactivate(principal.accountId(), providerId, membershipId);
    }
}
