-- Phase 5: Person & Provider Membership (phases.md §8; rules.md Rules 3.1-3.2, 6.1-6.4).
--
-- person = the individual who actually eats (NOT the login identity).
-- provider_membership = a Person's long-lived relationship with ONE FoodProvider,
-- deliberately separate from Subscription (Phase 7), which will reference it.
--
-- No ON DELETE CASCADE anywhere (same rule as V3): history must never vanish by accident.

create table person (
    id uuid primary key default gen_random_uuid(),
    -- NOT unique on purpose (rules.md Rule 3.2): one UserAccount must be able to own
    -- several Persons later (self, flatmate, family member...). The MVP "one account =
    -- one person" behaviour is the partial index below, which only constrains the
    -- single *primary* (self) person and does NOT limit how many persons exist.
    user_account_id uuid not null references user_account (id),
    full_name varchar(120) not null,
    is_primary boolean not null default false,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),

    constraint person_full_name_not_blank check (length(btrim(full_name)) > 0)
);

create unique index uq_person_one_primary_per_account
    on person (user_account_id) where is_primary;

create index idx_person_user_account on person (user_account_id);

-- One row per membership *stint*. Leaving ends the row (INACTIVE + left_at); rejoining
-- inserts a NEW row, so every earlier joined/left period is retained exactly as it was
-- (rules.md Rule 6.3, 15.3) and later subscriptions stay attached to the stint they
-- belong to. Rows are never deleted.
create table provider_membership (
    id uuid primary key default gen_random_uuid(),
    provider_id uuid not null references food_provider (id),
    person_id uuid not null references person (id),
    status varchar(20) not null default 'ACTIVE',
    joined_at timestamptz not null default now(),
    left_at timestamptz,
    -- who/when for the lifecycle changes (rules.md Rule 15.4)
    added_by uuid not null references user_account (id),
    left_by uuid references user_account (id),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),

    constraint provider_membership_status_check check (status in ('ACTIVE', 'INACTIVE')),
    -- Departure metadata is present exactly when the membership is INACTIVE.
    constraint provider_membership_lifecycle_check check (
        (status = 'ACTIVE' and left_at is null and left_by is null)
        or (status = 'INACTIVE' and left_at is not null and left_by is not null
            and left_at >= joined_at))
);

-- A Person has at most ONE active membership per provider, but may have any number of
-- historical (INACTIVE) ones - and any number of ACTIVE ones at *different* providers
-- (rules.md Rule 6.4: no global one-provider-per-customer rule).
create unique index uq_provider_membership_one_active
    on provider_membership (provider_id, person_id) where status = 'ACTIVE';

-- Customer list for a provider (the owner's "notebook" view).
create index idx_provider_membership_provider_status on provider_membership (provider_id, status);
-- "Which providers does this person belong to" (customer-side view).
create index idx_provider_membership_person on provider_membership (person_id);

comment on table person is
    'The individual who receives food. Separate from user_account (login identity); one account may own many persons.';
comment on column person.is_primary is
    'The account holder''s own ("self") person. At most one per account; says nothing about how many other persons the account may own.';
comment on table provider_membership is
    'One row per membership stint between a person and a provider. Rejoining inserts a new row; history is never overwritten or deleted.';
