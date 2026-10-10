-- Phase 7: Subscription Core (phases.md §10; rules.md Rules 8.1-8.7, 9.3, 10.3, 15.2, 15.3, 20.1, 21.3).
--
-- subscription               = the customer's purchased entitlement: ONE Person, at ONE provider, under ONE
--                              membership stint, bought on ONE plan.
-- subscription_term_snapshot = the immutable record of the terms it was SOLD under: commercial terms
--                              (price, currency, purchased quantity) AND the policy terms in force
--                              (frozen decision 1: there is no separate policy-snapshot table).
--                              Exactly one per subscription (frozen decision 2).
--
-- After creation a subscription never reads plan or subscription_policy again to explain itself
-- (rules.md Rules 8.6, 15.2): every number needed to explain a past sale is on the snapshot.
--
-- No ON DELETE CASCADE and no delete path (same rule as V3/V7/V8): a subscription ends by being
-- EXPIRED or CANCELLED, never deleted (rules.md Rule 15.3).
--
-- Several invariants below are enforced by a trigger or an EXCLUDE constraint because they span rows
-- or tables, so a plain CHECK cannot see them. The application (SubscriptionServiceImpl /
-- SubscriptionRules) stays the primary, user-facing enforcement and gives the friendly errors.

-- Needed so the exclusion constraint below can mix "membership_id WITH =" with a range overlap.
create extension if not exists btree_gist;

-- Composite keys so a subscription can be tied to ITS provider's membership and plan by foreign key,
-- not just by convention. Both are supersets of the primary key, so they can never fail on existing data.
alter table provider_membership
    add constraint uq_provider_membership_id_provider_person unique (id, provider_id, person_id);
alter table plan
    add constraint uq_plan_id_provider unique (id, provider_id);

create table subscription (
    id uuid primary key default gen_random_uuid(),
    -- Denormalised on purpose (architecture.md §34): every provider-owned row carries its provider.
    provider_id uuid not null references food_provider (id),
    -- The membership STINT this was bought during (Phase 5 decision 1). person_id is repeated so the
    -- customer-side query needs no join; the composite FK below makes it impossible to disagree.
    membership_id uuid not null,
    person_id uuid not null,
    -- Which plan it was bought on. Kept for traceability only - never used to explain the terms.
    plan_id uuid not null,
    consumption_type varchar(10) not null,
    status varchar(20) not null default 'ACTIVE',
    start_date date not null,
    -- DAY: start + purchased_days - 1 (counting the start day as day 1). MEAL: start + max calendar window - 1,
    -- or NULL when the policy sets no window.
    base_expiry_date date,
    -- base_expiry_date plus whatever extensions have been applied (Phase 9). Equal to the base until then.
    effective_expiry_date date,
    -- MEAL only: purchased meals minus consumed meals. A counter that Phase 8 changes in the same transaction
    -- as the consumption record it belongs to. Remaining DAYS are derived from the dates, never stored.
    remaining_meals integer,
    -- Set when this subscription is a renewal of an earlier one (rules.md Rule 8.7).
    renewed_from_subscription_id uuid references subscription (id),
    created_by uuid not null references user_account (id),
    expired_at timestamptz,
    cancelled_at timestamptz,
    cancelled_by uuid references user_account (id),
    cancellation_reason varchar(500),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),

    constraint subscription_membership_fk
        foreign key (membership_id, provider_id, person_id)
        references provider_membership (id, provider_id, person_id),
    constraint subscription_plan_fk
        foreign key (plan_id, provider_id) references plan (id, provider_id),
    -- Lets the snapshot repeat consumption_type and be forced to agree with it.
    constraint uq_subscription_id_consumption_type unique (id, consumption_type),

    constraint subscription_consumption_type_check check (consumption_type in ('DAY', 'MEAL')),
    constraint subscription_status_check check (status in ('ACTIVE', 'EXPIRED', 'CANCELLED')),
    -- Exactly the entitlement fields that belong to the consumption type (rules.md Rule 7.4).
    -- "is not null" is explicit on purpose: a bare comparison yields NULL for a NULL and a CHECK
    -- treats NULL as passing (the V8 lesson).
    constraint subscription_entitlement_shape_check check (
        (consumption_type = 'DAY'
            and base_expiry_date is not null and effective_expiry_date is not null
            and remaining_meals is null)
        or (consumption_type = 'MEAL'
            and remaining_meals is not null and remaining_meals >= 0
            and ((base_expiry_date is null and effective_expiry_date is null)
                or (base_expiry_date is not null and effective_expiry_date is not null)))),
    constraint subscription_dates_check check (
        (base_expiry_date is null or base_expiry_date >= start_date)
        and (effective_expiry_date is null or effective_expiry_date >= base_expiry_date)),
    -- Lifecycle metadata is present exactly for the state it describes (rules.md Rule 15.4).
    constraint subscription_lifecycle_check check (
        (status = 'ACTIVE'
            and expired_at is null and cancelled_at is null and cancelled_by is null
            and cancellation_reason is null)
        or (status = 'EXPIRED'
            and expired_at is not null and cancelled_at is null and cancelled_by is null
            and cancellation_reason is null)
        or (status = 'CANCELLED'
            and cancelled_at is not null and cancelled_by is not null and expired_at is null)),
    constraint subscription_not_own_renewal check (renewed_from_subscription_id <> id),

    -- One person cannot be entitled twice for the same day at the same membership. Only ACTIVE rows
    -- compete: an EXPIRED or CANCELLED subscription has handed its days back. A MEAL subscription with
    -- no calendar window has an open-ended range and therefore holds the membership until it ends.
    -- Independent of when (or whether) a background job flips a lapsed row to EXPIRED: a lapsed row's
    -- range is already in the past, so it cannot collide with a new one.
    constraint subscription_no_overlapping_active
        exclude using gist (
            membership_id with =,
            daterange(start_date, effective_expiry_date, '[]') with &&
        ) where (status = 'ACTIVE')
);

-- A subscription can be renewed at most once: a retried or duplicated renewal request hits this index
-- instead of creating a second successor (idempotency boundary for renewal, design.md §22).
create unique index uq_subscription_one_renewal
    on subscription (renewed_from_subscription_id) where renewed_from_subscription_id is not null;

-- Provider list / capacity count ("how many ACTIVE subscriptions does this provider have").
create index idx_subscription_provider_status on subscription (provider_id, status);
-- A customer's history at one membership stint.
create index idx_subscription_membership on subscription (membership_id);
-- Customer-side "my subscriptions" across providers.
create index idx_subscription_person on subscription (person_id);

create table subscription_term_snapshot (
    id uuid primary key default gen_random_uuid(),
    -- 1 : 1 with subscription (frozen decision 2).
    subscription_id uuid not null unique,
    consumption_type varchar(10) not null,
    -- Commercial terms at the moment of sale.
    plan_name varchar(120) not null,
    price numeric(10, 2) not null,
    currency varchar(3) not null,
    purchased_days integer,
    purchased_meals integer,
    -- Policy terms at the moment of sale. policy_version is the subscription_policy.version of the
    -- plan these were copied from - provenance only, the values below are the truth.
    -- The maximum allowed expiry date is not stored: it is always start_date + max_calendar_window_days - 1.
    policy_version integer not null,
    extension_allowed boolean not null,
    min_consecutive_absence_days integer,
    same_day_absence_allowed boolean not null,
    absence_cutoff_time time,
    max_calendar_window_days integer,
    captured_at timestamptz not null,
    created_at timestamptz not null default now(),

    constraint subscription_term_snapshot_subscription_fk
        foreign key (subscription_id, consumption_type)
        references subscription (id, consumption_type),

    constraint snapshot_consumption_type_check check (consumption_type in ('DAY', 'MEAL')),
    constraint snapshot_plan_name_not_blank check (length(btrim(plan_name)) > 0),
    constraint snapshot_price_positive check (price > 0),
    constraint snapshot_currency_format check (currency ~ '^[A-Z]{3}$'),
    constraint snapshot_quantity_matches_type_check check (
        (consumption_type = 'DAY' and purchased_days is not null and purchased_meals is null)
        or (consumption_type = 'MEAL' and purchased_meals is not null and purchased_days is null)),
    constraint snapshot_purchased_days_range check (purchased_days is null or purchased_days between 1 and 366),
    constraint snapshot_purchased_meals_range check (purchased_meals is null or purchased_meals between 1 and 1000),
    constraint snapshot_policy_version_check check (policy_version >= 1),
    -- The same policy rules as V8/V9, now checkable in one table because the type is on the row.
    constraint snapshot_extension_day_only_check check (not extension_allowed or consumption_type = 'DAY'),
    constraint snapshot_min_absence_check check (
        (extension_allowed and min_consecutive_absence_days is not null
            and min_consecutive_absence_days between 1 and 1000)
        or (not extension_allowed and min_consecutive_absence_days is null)),
    constraint snapshot_cutoff_check check (same_day_absence_allowed or absence_cutoff_time is null),
    constraint snapshot_window_check check (
        max_calendar_window_days is null or max_calendar_window_days between 1 and 1000)
);

-- ------------------------------------------------------------------------------------------------
-- Snapshot: immutable history (rules.md Rules 8.6, 15.3). Same pattern as subscription_policy in V8.
-- ------------------------------------------------------------------------------------------------
create function subscription_term_snapshot_immutable() returns trigger as $$
begin
    raise exception 'subscription_term_snapshot is immutable: a changed term is a new subscription (renewal)'
        using errcode = 'check_violation';
end;
$$ language plpgsql;

create trigger subscription_term_snapshot_no_update_delete
    before update or delete on subscription_term_snapshot
    for each row execute function subscription_term_snapshot_immutable();

-- ------------------------------------------------------------------------------------------------
-- Subscription: what may and may not change after insert.
-- ------------------------------------------------------------------------------------------------
create function subscription_guard_update() returns trigger as $$
declare
    purchased integer;
begin
    -- Who, where, what and when it was sold never move (rules.md Rules 8.2-8.4, 8.6).
    if new.provider_id is distinct from old.provider_id
        or new.membership_id is distinct from old.membership_id
        or new.person_id is distinct from old.person_id
        or new.plan_id is distinct from old.plan_id
        or new.consumption_type is distinct from old.consumption_type
        or new.start_date is distinct from old.start_date
        or new.base_expiry_date is distinct from old.base_expiry_date
        or new.renewed_from_subscription_id is distinct from old.renewed_from_subscription_id
        or new.created_by is distinct from old.created_by
        or new.created_at is distinct from old.created_at then
        raise exception 'subscription identity and sold terms are immutable'
            using errcode = 'check_violation';
    end if;

    -- State transition matrix: ACTIVE -> ACTIVE | EXPIRED | CANCELLED. EXPIRED and CANCELLED are final;
    -- a renewal is a NEW row, never a revival.
    if old.status <> 'ACTIVE' then
        raise exception 'subscription is % and can no longer change', old.status
            using errcode = 'check_violation';
    end if;

    -- Remaining meals can never exceed what was bought. (They may go up again: an owner's correction
    -- in Phase 8 can hand a meal back.)
    if new.remaining_meals is distinct from old.remaining_meals and new.remaining_meals is not null then
        select purchased_meals into purchased
        from subscription_term_snapshot where subscription_id = new.id;
        if new.remaining_meals > purchased then
            raise exception 'remaining_meals % exceeds purchased_meals %', new.remaining_meals, purchased
                using errcode = 'check_violation';
        end if;
    end if;
    return new;
end;
$$ language plpgsql;

create trigger subscription_no_identity_change
    before update on subscription
    for each row execute function subscription_guard_update();

create function subscription_reject_delete() returns trigger as $$
begin
    raise exception 'subscription rows are never deleted: expire or cancel instead'
        using errcode = 'check_violation';
end;
$$ language plpgsql;

create trigger subscription_no_delete
    before delete on subscription
    for each row execute function subscription_reject_delete();

-- ------------------------------------------------------------------------------------------------
-- Every subscription has exactly one snapshot, and the two agree (frozen decision 2).
-- Deferred to COMMIT because the snapshot can only be inserted after its subscription (the composite
-- foreign key), so the check has to run once both rows exist.
-- ------------------------------------------------------------------------------------------------
create function subscription_requires_matching_snapshot() returns trigger as $$
declare
    snap subscription_term_snapshot%rowtype;
begin
    select * into snap from subscription_term_snapshot where subscription_id = new.id;
    if not found then
        raise exception 'subscription % has no term snapshot', new.id
            using errcode = 'check_violation';
    end if;
    if new.consumption_type = 'DAY' and new.base_expiry_date <> new.start_date + (snap.purchased_days - 1) then
        raise exception 'subscription % base expiry does not match its purchased days', new.id
            using errcode = 'check_violation';
    end if;
    if new.consumption_type = 'MEAL' then
        if new.remaining_meals > snap.purchased_meals then
            raise exception 'subscription % remaining meals exceed its purchased meals', new.id
                using errcode = 'check_violation';
        end if;
        -- A MEAL subscription has a calendar limit exactly when its policy defines a window.
        if (snap.max_calendar_window_days is null) <> (new.base_expiry_date is null) then
            raise exception 'subscription % calendar limit does not match its policy window', new.id
                using errcode = 'check_violation';
        end if;
        if snap.max_calendar_window_days is not null
            and new.base_expiry_date <> new.start_date + (snap.max_calendar_window_days - 1) then
            raise exception 'subscription % base expiry does not match its policy window', new.id
                using errcode = 'check_violation';
        end if;
    end if;
    return null;
end;
$$ language plpgsql;

create constraint trigger subscription_snapshot_required
    after insert on subscription
    deferrable initially deferred
    for each row execute function subscription_requires_matching_snapshot();

comment on table subscription is
    'A customer''s purchased entitlement. Never deleted; ends as EXPIRED or CANCELLED (both final). Terms live on subscription_term_snapshot, not on the plan.';
comment on column subscription.membership_id is
    'The membership stint (Phase 5) this subscription was bought during. Rejoining creates a new stint, so history stays attached to the stint it belongs to.';
comment on column subscription.base_expiry_date is
    'Expiry before any extension. DAY: start + purchased days - 1. MEAL: start + policy window - 1, NULL when the policy has no window.';
comment on column subscription.effective_expiry_date is
    'Current expiry = base expiry plus applied extensions (Phase 9). NULL only for a MEAL subscription without a calendar window.';
comment on column subscription.remaining_meals is
    'MEAL only: meals still available. A counter maintained by Phase 8 together with its consumption record. Remaining days are derived, never stored.';
comment on column subscription.renewed_from_subscription_id is
    'The subscription this one renews. Unique: a subscription is renewed at most once.';
comment on constraint subscription_no_overlapping_active on subscription is
    'No two ACTIVE subscriptions of one membership may cover the same day.';
comment on table subscription_term_snapshot is
    'The commercial AND policy terms a subscription was sold under. Exactly one per subscription, immutable. Never reconstruct a past sale from plan or subscription_policy.';
comment on column subscription_term_snapshot.policy_version is
    'subscription_policy.version of the plan the policy terms were copied from (provenance). The columns beside it are the authoritative values.';
comment on function subscription_guard_update() is
    'Subscription update guard: identity/sold terms immutable; ACTIVE is the only state that can change; remaining_meals never above purchased.';
comment on function subscription_requires_matching_snapshot() is
    'Deferred check at COMMIT: exactly one snapshot exists and the dates/meals on the subscription follow from it.';
