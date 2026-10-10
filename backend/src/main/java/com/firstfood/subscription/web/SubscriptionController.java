package com.firstfood.subscription.web;

import com.firstfood.identity.security.AuthenticatedAccount;
import com.firstfood.subscription.SubscriptionService;
import com.firstfood.subscription.SubscriptionStatus;
import com.firstfood.subscription.SubscriptionView;
import com.firstfood.subscription.dto.CancelSubscriptionRequest;
import com.firstfood.subscription.dto.CreateSubscriptionRequest;
import com.firstfood.subscription.dto.RenewSubscriptionRequest;
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
 * Thin HTTP layer: the acting account is always the JWT principal; every authorization decision
 * happens in {@link SubscriptionService}. Price and terms are never accepted from the client.
 */
@RestController
@RequestMapping("/api/v1/providers/{providerId}/subscriptions")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    public SubscriptionController(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    @GetMapping
    public List<SubscriptionView> list(
            @AuthenticationPrincipal AuthenticatedAccount principal,
            @PathVariable UUID providerId,
            @RequestParam(required = false) UUID membershipId,
            @RequestParam(required = false) SubscriptionStatus status) {
        return subscriptionService.list(principal.accountId(), providerId, membershipId, status);
    }

    @PostMapping
    public ResponseEntity<SubscriptionView> create(
            @AuthenticationPrincipal AuthenticatedAccount principal,
            @PathVariable UUID providerId,
            @Valid @RequestBody CreateSubscriptionRequest request) {
        SubscriptionView created = subscriptionService.create(
                principal.accountId(), providerId, request.membershipId(), request.planId(), request.startDate());
        return ResponseEntity.created(
                        URI.create("/api/v1/providers/" + providerId + "/subscriptions/" + created.id()))
                .body(created);
    }

    @GetMapping("/{subscriptionId}")
    public SubscriptionView get(
            @AuthenticationPrincipal AuthenticatedAccount principal,
            @PathVariable UUID providerId,
            @PathVariable UUID subscriptionId) {
        return subscriptionService.get(principal.accountId(), providerId, subscriptionId);
    }

    @PostMapping("/{subscriptionId}/cancel")
    public SubscriptionView cancel(
            @AuthenticationPrincipal AuthenticatedAccount principal,
            @PathVariable UUID providerId,
            @PathVariable UUID subscriptionId,
            @Valid @RequestBody(required = false) CancelSubscriptionRequest request) {
        return subscriptionService.cancel(
                principal.accountId(), providerId, subscriptionId, request == null ? null : request.reason());
    }

    @PostMapping("/{subscriptionId}/renew")
    public ResponseEntity<SubscriptionView> renew(
            @AuthenticationPrincipal AuthenticatedAccount principal,
            @PathVariable UUID providerId,
            @PathVariable UUID subscriptionId,
            @Valid @RequestBody(required = false) RenewSubscriptionRequest request) {
        SubscriptionView renewed = subscriptionService.renew(principal.accountId(), providerId, subscriptionId,
                request == null ? null : request.planId(), request == null ? null : request.startDate());
        return ResponseEntity.created(
                        URI.create("/api/v1/providers/" + providerId + "/subscriptions/" + renewed.id()))
                .body(renewed);
    }
}
