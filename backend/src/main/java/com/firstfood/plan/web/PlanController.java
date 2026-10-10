package com.firstfood.plan.web;

import com.firstfood.identity.security.AuthenticatedAccount;
import com.firstfood.plan.PlanService;
import com.firstfood.plan.PlanView;
import com.firstfood.plan.dto.CreatePlanRequest;
import com.firstfood.plan.dto.UpdatePlanRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin HTTP layer: the acting account is always the JWT principal; every authorization and
 * business decision happens in {@link PlanService}. There is intentionally no DELETE: plans
 * are deactivated, never deleted (rules.md Rule 15.3).
 */
@RestController
@RequestMapping("/api/v1/providers/{providerId}/plans")
public class PlanController {

    private final PlanService planService;

    public PlanController(PlanService planService) {
        this.planService = planService;
    }

    @GetMapping
    public List<PlanView> list(
            @AuthenticationPrincipal AuthenticatedAccount principal,
            @PathVariable UUID providerId,
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return planService.list(principal.accountId(), providerId, includeInactive);
    }

    @PostMapping
    public ResponseEntity<PlanView> create(
            @AuthenticationPrincipal AuthenticatedAccount principal,
            @PathVariable UUID providerId,
            @Valid @RequestBody CreatePlanRequest request) {
        PlanView created = planService.create(principal.accountId(), providerId, request);
        return ResponseEntity.created(URI.create("/api/v1/providers/" + providerId + "/plans/" + created.id()))
                .body(created);
    }

    @GetMapping("/{planId}")
    public PlanView get(
            @AuthenticationPrincipal AuthenticatedAccount principal,
            @PathVariable UUID providerId,
            @PathVariable UUID planId) {
        return planService.get(principal.accountId(), providerId, planId);
    }

    @PutMapping("/{planId}")
    public PlanView update(
            @AuthenticationPrincipal AuthenticatedAccount principal,
            @PathVariable UUID providerId,
            @PathVariable UUID planId,
            @Valid @RequestBody UpdatePlanRequest request) {
        return planService.update(principal.accountId(), providerId, planId, request);
    }

    @PostMapping("/{planId}/activate")
    public PlanView activate(
            @AuthenticationPrincipal AuthenticatedAccount principal,
            @PathVariable UUID providerId,
            @PathVariable UUID planId) {
        return planService.activate(principal.accountId(), providerId, planId);
    }

    @PostMapping("/{planId}/deactivate")
    public PlanView deactivate(
            @AuthenticationPrincipal AuthenticatedAccount principal,
            @PathVariable UUID providerId,
            @PathVariable UUID planId) {
        return planService.deactivate(principal.accountId(), providerId, planId);
    }
}
