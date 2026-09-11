-- Baseline migration for FirstFood V2.
--
-- Intentionally minimal. The real domain schema (user_account, person,
-- food_provider, provider_role_assignment, provider_membership, plan,
-- subscription, subscription_term_snapshot, absence_record,
-- attendance_record, extension_event, review, review_reply) is NOT
-- created here.
--
-- Per memory.md #3 "Still open", the following must be decided before
-- writing that schema:
--   - MEAL attendance granularity (meal_type vs meal_slot vs consumption
--     event model)
--   - review reply cardinality (0..1 vs 0..N)
--   - subscription policy version ownership/uniqueness
--   - persisted vs derived subscription fields
--   - enums, indexes, FK actions, unique constraints, timezone/date
--     conventions
--
-- This file exists only to prove the Flyway migration mechanism works
-- end-to-end (Phase 1 exit criteria) before real domain migrations land.

create table if not exists schema_healthcheck (
    id integer primary key default 1,
    checked_at timestamptz not null default now(),
    constraint schema_healthcheck_singleton check (id = 1)
);

insert into schema_healthcheck (id) values (1)
    on conflict (id) do nothing;
