/**
 * extension module (Phase 9): the provider-specific absence extension engine.
 *
 * A DAY subscription whose snapshot allows it is extended by the days its customer did not eat, counted only from
 * stretches of at least {@code minConsecutiveAbsenceDays} consecutive absent days and capped by the maximum
 * calendar window counted from the ORIGINAL start. Every extension is an append-only {@code ExtensionEvent}; the
 * engine is idempotent (it reconciles to a target, so running it again changes nothing). See memory.md §17.
 *
 * Depends on: subscription (lookup + the single expiry-moving method), attendance (declared absence dates),
 * provider/provideraccess/membership (authorization and scoping). Nothing depends on it except through
 * {@code subscription.RenewalGuard}.
 */
package com.firstfood.extension;
