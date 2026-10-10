package com.firstfood.subscription.web;

import com.firstfood.identity.security.AuthenticatedAccount;
import com.firstfood.subscription.MySubscriptionView;
import com.firstfood.subscription.SubscriptionService;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The caller's own subscriptions, across providers. Always scoped to the JWT principal. */
@RestController
@RequestMapping("/api/v1/subscriptions")
public class MySubscriptionController {

    private final SubscriptionService subscriptionService;

    public MySubscriptionController(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    @GetMapping
    public List<MySubscriptionView> mine(@AuthenticationPrincipal AuthenticatedAccount principal) {
        return subscriptionService.listMine(principal.accountId());
    }

    @GetMapping("/{subscriptionId}")
    public MySubscriptionView get(
            @AuthenticationPrincipal AuthenticatedAccount principal, @PathVariable UUID subscriptionId) {
        return subscriptionService.getMine(principal.accountId(), subscriptionId);
    }
}
