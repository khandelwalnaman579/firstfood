/**
 * Subscription lifecycle and background jobs (Phase 10, phases.md §13).
 *
 * <p>A coordination module that sits ABOVE the domain modules: it depends on {@code subscription}, {@code extension}
 * and {@code identity} through their system-only interfaces ({@code SubscriptionLifecycleService},
 * {@code SystemExtensionService}, {@code IdentityCleanupService}), and nothing depends on it. It owns no business
 * rules and no business tables - expiry, extension and cleanup rules stay in the modules that own the data
 * (rules.md Rule 22.2/22.3); a job only decides WHEN to ask. The one table it owns, {@code job_run}, is an operational
 * log plus a lease, never business history.
 *
 * <p>Structure: {@link com.firstfood.lifecycle.JobRunner} (lease + run record + counts) runs any
 * {@link com.firstfood.lifecycle.BackgroundJob}; {@link com.firstfood.lifecycle.JobScheduler} triggers the jobs on a
 * cron (switchable with {@code app.jobs.enabled}); tests call the runner directly.
 */
package com.firstfood.lifecycle;
