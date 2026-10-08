-- Provider module schema (phases.md Phase 3; FirstFood_V2_Phase3_Domain_Decision_Freeze.md;
-- FirstFood_V2_Phase3_Execution_Plan.pdf §3).
--
-- FoodProvider is never physically deleted after creation (freeze #7): it is
-- closed via status = 'CLOSED' with closure audit fields. No ON DELETE CASCADE
-- anywhere on purpose - a stray delete must fail loudly, not erase history.

create table food_provider (
    id uuid primary key default gen_random_uuid(),
    name varchar(120) not null,
    provider_type varchar(20) not null,
    description varchar(1000),
    address_line varchar(255) not null,
    locality varchar(120) not null,
    city varchar(100) not null,
    pincode varchar(6),
    contact_phone varchar(20),
    status varchar(30) not null default 'ACTIVE',
    accepting_new_customers boolean not null default true,
    -- NULL = no configured limit. Phase 3 only stores/validates this; the
    -- subscription domain (later phase) owns the actual capacity check.
    max_active_subscriptions integer,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    closed_at timestamptz,
    closed_by_account_id uuid references user_account (id),
    closure_reason varchar(500),

    constraint food_provider_type_check check (
        provider_type in ('MESS', 'TIFFIN', 'PG', 'HOSTEL', 'CORPORATE', 'OTHER')),
    constraint food_provider_status_check check (
        status in ('ACTIVE', 'FULL', 'TEMPORARILY_UNAVAILABLE', 'CLOSED')),
    constraint food_provider_capacity_check check (
        max_active_subscriptions is null or max_active_subscriptions >= 1),
    -- Only an ACTIVE provider may have the customer-intake switch on
    -- (freeze #2): FULL / TEMPORARILY_UNAVAILABLE / CLOSED => false.
    constraint food_provider_intake_check check (
        status = 'ACTIVE' or accepting_new_customers = false),
    -- Closure metadata is present exactly when the provider is CLOSED
    -- (freeze #7). closure_reason stays optional even when closed.
    constraint food_provider_closure_check check (
        (status = 'CLOSED' and closed_at is not null and closed_by_account_id is not null)
        or (status <> 'CLOSED' and closed_at is null and closed_by_account_id is null
            and closure_reason is null))
);

-- Provider-scoped role relationship (freeze #4). MANAGER/WORKER exist in the
-- schema now to avoid a second redesign in Phase 4, but Phase 3 only ever
-- creates OWNER rows.
create table provider_role_assignment (
    id uuid primary key default gen_random_uuid(),
    provider_id uuid not null references food_provider (id),
    account_id uuid not null references user_account (id),
    role varchar(20) not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),

    constraint provider_role_assignment_role_check check (role in ('OWNER', 'MANAGER', 'WORKER')),
    constraint provider_role_assignment_unique unique (provider_id, account_id, role)
);

-- Exactly one OWNER per provider. The unique constraint above only stops the
-- same account holding the same role twice; this partial index is what stops a
-- second, different OWNER. "At least one" is guaranteed by creating the
-- provider and its OWNER row in a single transaction (ProviderServiceImpl).
create unique index uq_provider_role_assignment_one_owner
    on provider_role_assignment (provider_id) where role = 'OWNER';

-- "Which providers can this account access" - the provider list query.
create index idx_provider_role_assignment_account on provider_role_assignment (account_id);
