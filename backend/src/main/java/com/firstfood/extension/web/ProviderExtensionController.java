package com.firstfood.extension.web;

import com.firstfood.extension.ApplyExtensionResult;
import com.firstfood.extension.ExtensionEventView;
import com.firstfood.extension.ExtensionService;
import com.firstfood.extension.ExtensionStatusView;
import com.firstfood.identity.security.AuthenticatedAccount;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Provider staff's side of extension. Thin HTTP layer - the acting account is always the JWT principal and every
 * authorization decision is in {@link ExtensionService}. The request carries no body: what is extended, and by how
 * much, is decided entirely by the backend from the subscription's terms and absences.
 */
@RestController
@RequestMapping("/api/v1/providers/{providerId}/subscriptions/{subscriptionId}/extension")
public class ProviderExtensionController {

    private final ExtensionService extensionService;

    public ProviderExtensionController(ExtensionService extensionService) {
        this.extensionService = extensionService;
    }

    @GetMapping
    public ExtensionStatusView status(
            @AuthenticationPrincipal AuthenticatedAccount principal,
            @PathVariable UUID providerId,
            @PathVariable UUID subscriptionId) {
        return extensionService.status(principal.accountId(), providerId, subscriptionId);
    }

    @GetMapping("/events")
    public List<ExtensionEventView> events(
            @AuthenticationPrincipal AuthenticatedAccount principal,
            @PathVariable UUID providerId,
            @PathVariable UUID subscriptionId) {
        return extensionService.events(principal.accountId(), providerId, subscriptionId);
    }

    /** 201 when an extension was applied; 200 when there was nothing to add (a retry, or no eligible days yet). */
    @PostMapping("/apply")
    public ResponseEntity<ApplyExtensionResult> apply(
            @AuthenticationPrincipal AuthenticatedAccount principal,
            @PathVariable UUID providerId,
            @PathVariable UUID subscriptionId) {
        ApplyExtensionResult result = extensionService.apply(principal.accountId(), providerId, subscriptionId);
        return ResponseEntity.status(result.applied() ? HttpStatus.CREATED : HttpStatus.OK).body(result);
    }
}
