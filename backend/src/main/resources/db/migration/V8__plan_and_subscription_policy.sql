-- Phase 6: Plans (phases.md §9; rules.md Rules 7.1-7.5, 14.1-14.3, 15.3).
--
-- plan = a provider's CURRENT commercial offering. It is deliberately NOT the historical
-- source of truth for any subscription: Phase 7 copies the terms (and the policy version)
-- into subscription_term_snapshot at creation time (rules.md Rules 8.5, 8.6, 15.2).
-- subscription_policy = the absence/extension terms of a plan, stored as immutable
-- VERSIONS: editing the policy inserts version N+1, it never rewrites version N
-- (rules.md Rule 14.3). The "current" policy of a plan is simply its highest version.
--
-- No ON DELETE CASCADE and no delete path (same rule as V3/V7): plans are deactivated,
-- never deleted, so a plan referenced by old subscriptions can always be explained.

create table plan (
    id uuid primary key default gen_random_uuid(),
    -- Every plan belongs to exactly ONE provider (rules.md Rule 7.1).
    provider_id uuid not null references food_provider (id),
    name varchar(120) not null,
    description varchar(500),
    -- DAY and MEAL are distinct consumption models (rules.md Rule 7.3).
    consumption_type varchar(10) not null,
    -- Meaningful only for DAY / only for MEAL (rules.md Rule 7.4) - see the CHECK below.
    duration_days integer,
    meal_quantity integer,
    price numeric(10, 2) not null,
    currency varchar(3) not null default 'INR',
    status varchar(20) not null default 'ACTIVE',
    created_by uuid not null references user_account (id),
    updated_by uuid not null references user_account (id),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),

    constraint plan_name_not_blank check (length(btrim(name)) > 0),
    constraint plan_consumption_type_check check (consumption_type in ('DAY', 'MEAL')),
    constraint plan_status_check check (status in ('ACTIVE', 'INACTIVE')),
    -- Exactly the quantity that belongs to the consumption type, and nothing else.
    constraint plan_quantity_matches_type_check check (
        (consumption_type = 'DAY' and duration_days is not null and meal_quantity is null)
        or (consumption_type = 'MEAL' and meal_quantity is not null and duration_days is null)),
    constraint plan_duration_days_range check (duration_days is null or duration_days between 1 and 366),
    constraint plan_meal_quantity_range check (meal_quantity is null or meal_quantity between 1 and 1000),
    -- A plan is a priced offering. Loosening this later is a trivial migration; tightening
    -- it once zero-price rows exist is not - so the stricter rule is the safe default.
    constraint plan_price_positive check (price > 0),
    constraint plan_currency_format check (currency ~ '^[A-Z]{3}$')
);

-- Customer-facing choice list ("which plans can I subscribe to") and the owner's list.
create index idx_plan_provider_status on plan (provider_id, status);

-- Two ACTIVE plans of one provider may not share a name (it would be ambiguous in the
-- plan picker). INACTIVE plans keep their name without blocking reuse.
create unique index uq_plan_active_name_per_provider
    on plan (provider_id, lower(btrim(name))) where status = 'ACTIVE';

create table subscription_policy (
    id uuid primary key default gen_random_uuid(),
    plan_id uuid not null references plan (id),
    -- 1, 2, 3 ... per plan. Phase 7 stores this number in the term snapshot.
    version integer not null,
    extension_allowed boolean not null,
    -- Minimum consecutive absent days before an extension is considered (policy-driven,
    -- never a global constant - rules.md Rule 12.5). Only meaningful when extensions are on.
    min_consecutive_absence_days integer,
    same_day_absence_allowed boolean not null,
    -- Local (provider) time of day after which a same-day absence can no longer be
    -- declared. NULL = no cutoff. Only meaningful when same-day absence is allowed.
    -- Wall-clock time on purpose: the timezone convention is a later, explicit decision.
    absence_cutoff_time time,
    -- Maximum calendar window counted from the ORIGINAL subscription start (rules.md Rule
    -- 13.4). NULL = no window.
    max_calendar_window_days integer,
    created_by uuid not null references user_account (id),
    created_at timestamptz not null default now(),

    constraint uq_subscription_policy_plan_version unique (plan_id, version),
    constraint subscription_policy_version_check check (version >= 1),
    -- Fields that have no meaning under the chosen switches must be absent (Rule 7.4 spirit).
    -- "is not null" is explicit on purpose: a bare BETWEEN yields NULL for a NULL value, and a
    -- CHECK treats NULL as passing - which would silently allow extension without a minimum.
    constraint subscription_policy_min_absence_check check (
        (extension_allowed and min_consecutive_absence_days is not null
            and min_consecutive_absence_days between 1 and 1000)
        or (not extension_allowed and min_consecutive_absence_days is null)),
    constraint subscription_policy_cutoff_check check (
        same_day_absence_allowed or absence_cutoff_time is null),
    constraint subscription_policy_window_check check (
        max_calendar_window_days is null or max_calendar_window_days between 1 and 1000)
);

-- Policy rows are immutable history: a changed policy is a NEW version, never an edit
-- (rules.md Rules 8.6, 14.3, 15.3).
create function subscription_policy_immutable() returns trigger as $$
begin
    raise exception 'subscription_policy is immutable: insert a new version instead';
end;
$$ language plpgsql;

create trigger subscription_policy_no_update_delete
    before update or delete on subscription_policy
    for each row execute function subscription_policy_immutable();

comment on table plan is
    'A provider''s CURRENT commercial offering. Never the historical source of truth for a subscription - Phase 7 snapshots terms at creation.';
comment on column plan.duration_days is 'DAY plans only (1..366). NULL for MEAL plans.';
comment on column plan.meal_quantity is 'MEAL plans only (1..1000). NULL for DAY plans.';
comment on table subscription_policy is
    'Immutable, versioned absence/extension terms of a plan. The current policy is the highest version; older versions stay for history and snapshots.';
comment on column subscription_policy.absence_cutoff_time is
    'Provider-local wall-clock time; same-day absences after it are rejected (Phase 8). NULL = no cutoff.';
comment on column subscription_policy.max_calendar_window_days is
    'Cap on the calendar span from the original subscription start (Phase 9 extension cap). NULL = no cap.';
