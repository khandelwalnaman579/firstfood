package com.firstfood.identity;

import java.time.Instant;

/**
 * SYSTEM ONLY (Phase 10): housekeeping for the identity module's short-lived rows, for the scheduled cleanup jobs.
 * Performs no authorization and deletes only rows that can never matter again; it never touches a live OTP challenge
 * or a usable refresh session. Not business history (Rule 15.3 / 22.3): these are authentication artifacts.
 *
 * Each call is ONE batch in its own transaction, so a job loops until a batch comes back short and stays resumable
 * and retry-safe: running it twice, or two instances at once, only ever deletes rows that are already dead.
 */
public interface IdentityCleanupService {

    /** Deletes up to {@code batchSize} OTP challenges that expired before {@code cutoff}. Returns how many. */
    int deleteExpiredOtpChallenges(Instant cutoff, int batchSize);

    /** Deletes up to {@code batchSize} refresh sessions that expired or were revoked before {@code cutoff}. Returns how many. */
    int deleteDeadRefreshSessions(Instant cutoff, int batchSize);
}
