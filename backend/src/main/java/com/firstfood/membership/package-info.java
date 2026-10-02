/**
 * membership module (Phase 5): Person + ProviderMembership.
 *
 * Person is the individual who eats; it belongs to a UserAccount but is NOT the
 * account (rules.md Rule 3.1/3.2). ProviderMembership is the long-lived
 * Person-to-FoodProvider relationship and is deliberately separate from
 * Subscription (rules.md Rule 6.1).
 *
 * Other modules may only use the service interfaces in this package, never the
 * entities or repositories.
 */
package com.firstfood.membership;
