package com.firstfood.extension.web;

import com.firstfood.extension.ExtensionService;
import com.firstfood.extension.ExtensionStatusView;
import com.firstfood.extension.MyExtensionEventView;
import com.firstfood.identity.security.AuthenticatedAccount;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The customer's side: read-only. A customer sees how many of their absent days count towards an extension, what has
 * been applied and where their expiry stands; applying it is the provider's decision. Always scoped to the JWT
 * principal's own persons.
 */
@RestController
@RequestMapping("/api/v1/subscriptions/{subscriptionId}/extension")
public class MyExtensionController {

    private final ExtensionService extensionService;

    public MyExtensionController(ExtensionService extensionService) {
        this.extensionService = extensionService;
    }

    @GetMapping
    public ExtensionStatusView status(
            @AuthenticationPrincipal AuthenticatedAccount principal, @PathVariable UUID subscriptionId) {
        return extensionService.myStatus(principal.accountId(), subscriptionId);
    }

    @GetMapping("/events")
    public List<MyExtensionEventView> events(
            @AuthenticationPrincipal AuthenticatedAccount principal, @PathVariable UUID subscriptionId) {
        return extensionService.myEvents(principal.accountId(), subscriptionId);
    }
}
