package com.firstfood.extension;

import java.util.List;
import java.util.UUID;

/**
 * The extension module's application boundary. Every method takes the acting account from the authenticated
 * principal; a client-supplied actor is never accepted (rules.md Rule 18.4).
 *
 * Provider side: authorized per provider through EXTENSION_VIEW / EXTENSION_MANAGE (404 for a non-member, 403 for a
 * member without the permission). Customer side: scoped to the caller's own persons - someone else's subscription is
 * simply "not found".
 */
public interface ExtensionService {

    // ---- provider staff ----

    /** The subscription's extension standing today, including what applying would add. Read-only. */
    ExtensionStatusView status(UUID actorAccountId, UUID providerId, UUID subscriptionId);

    /** Every extension applied to the subscription, oldest first. */
    List<ExtensionEventView> events(UUID actorAccountId, UUID providerId, UUID subscriptionId);

    /**
     * Applies whatever extension the subscription's terms entitle it to right now. Idempotent: when nothing is left to
     * add it changes nothing and says so. A subscription that cannot be extended at all is refused (409).
     */
    ApplyExtensionResult apply(UUID actorAccountId, UUID providerId, UUID subscriptionId);

    // ---- customer ----

    ExtensionStatusView myStatus(UUID accountId, UUID subscriptionId);

    List<MyExtensionEventView> myEvents(UUID accountId, UUID subscriptionId);
}
