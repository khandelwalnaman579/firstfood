-- Phase 10: Subscription Lifecycle & Background Jobs (phases.md §13; rules.md Rules 21.2, 22.1-22.3, 20.1).
--
-- job_run = the operational record of every background job execution AND the lease that keeps one job from running
-- twice at the same time across application instances.
--
-- It is NOT business history and NOT the source of truth for anything a customer or provider can see: subscriptions,
-- attendance and extensions stay authoritative in their own tables (Rule 22.2). Jobs are retry-safe by themselves
-- (every item is processed under its own row lock and re-checked), so this lease is an efficiency and observability
-- measure, not the correctness mechanism: if it ever failed to exclude a second runner, the second would simply find
-- nothing left to do.
--
-- Why a table and not Redis or a PostgreSQL advisory lock:
--   * Redis is cache/ephemeral only (Rule 19.2) and Upstash may be unavailable; a lease must survive that.
--   * Session-level advisory locks do not survive a pooled connection (Neon's pooler), transaction-level ones would
--     hold a transaction open for the whole job.
--   * A row also leaves the run history the job needs to be "observable" (architecture.md §28) for free.

create table job_run (
    id uuid primary key default gen_random_uuid(),
    -- Stable job identifier, e.g. 'subscription-expiry'. Lower-case, digits and hyphens.
    job_name varchar(60) not null,
    -- RUNNING   : holds the lease.
    -- SUCCEEDED : finished, every item handled.
    -- PARTIAL   : finished, but at least one item failed (the others were processed; failures are retried next run).
    -- FAILED    : the job itself failed (e.g. database unreachable); counts reflect progress before the failure.
    -- ABANDONED : a RUNNING row whose lease expired (the instance died); closed by whoever next wanted to run.
    status varchar(20) not null,
    -- Which application instance ran it (host/pid + random suffix) - for operators reading the log.
    instance_id varchar(120) not null,
    started_at timestamptz not null,
    -- The lease: refreshed (heartbeat) while the job makes progress; a RUNNING row past this instant is dead.
    lease_expires_at timestamptz not null,
    finished_at timestamptz,
    -- processed = items examined; changed = items whose business state was changed; failed = items that errored.
    -- Cleanup jobs count each deleted row as processed AND changed.
    processed_count integer not null default 0,
    changed_count integer not null default 0,
    failed_count integer not null default 0,
    -- Short, secret-free: exception class names and entity ids only (Rule 17.4). Never a stack trace, never a token.
    error_summary varchar(1000),

    constraint job_run_name_format check (job_name ~ '^[a-z][a-z0-9-]*$'),
    constraint job_run_status_check check (status in ('RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED', 'ABANDONED')),
    constraint job_run_counts_check check (
        processed_count >= 0 and changed_count >= 0 and failed_count >= 0
        and changed_count + failed_count <= processed_count),
    -- A run is finished exactly when it is no longer RUNNING.
    constraint job_run_lifecycle_check check (
        (status = 'RUNNING' and finished_at is null)
        or (status <> 'RUNNING' and finished_at is not null and finished_at >= started_at)),
    constraint job_run_lease_check check (lease_expires_at >= started_at)
);

-- THE lease: at most one RUNNING row per job. A second runner's insert is refused (ON CONFLICT DO NOTHING in the
-- application) and it skips the run.
create unique index uq_job_run_one_running on job_run (job_name) where status = 'RUNNING';

-- "What did this job do lately?"
create index idx_job_run_name_started on job_run (job_name, started_at desc);

comment on table job_run is
    'Operational log + lease of background job executions (Phase 10). Not business history; business state lives in its own tables.';
comment on column job_run.lease_expires_at is
    'A RUNNING row past this instant belongs to a dead instance and is closed as ABANDONED by the next runner.';
comment on index uq_job_run_one_running is
    'The lease: one RUNNING run per job at a time.';

-- ------------------------------------------------------------------------------------------------
-- Indexes the jobs' scans need (Rule 19.4: reviewed, not speculative - each backs one named query).
-- ------------------------------------------------------------------------------------------------

-- Expiry job: "ACTIVE subscriptions whose effective expiry is before today", keyset-paged by id.
create index idx_subscription_active_expiry on subscription (effective_expiry_date) where status = 'ACTIVE';

-- OTP cleanup: "challenges that expired before the cutoff".
create index idx_otp_verification_expires_at on otp_verification (expires_at);

-- Session cleanup: "sessions that expired / were revoked before the cutoff".
create index idx_refresh_session_expires_at on refresh_session (expires_at);
create index idx_refresh_session_revoked_at on refresh_session (revoked_at) where revoked_at is not null;
