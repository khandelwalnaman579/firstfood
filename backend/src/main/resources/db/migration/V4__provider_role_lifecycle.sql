-- Phase 4 Checkpoint 1: provider role lifecycle (Phase 4 Decision Record).
--
-- * One effective (ACTIVE) role per account per provider.
-- * Exactly one ACTIVE OWNER per provider.
-- * Assignments are never hard-deleted: revocation is a status change with
--   who/when recorded.

alter table provider_role_assignment
    add column status varchar(20) not null default 'ACTIVE',
    -- Nullable on purpose: NULL never occurs for rows written by the service;
    -- Phase 3 rows are backfilled below.
    add column assigned_by uuid references user_account (id),
    add column revoked_at timestamptz,
    add column revoked_by uuid references user_account (id);

-- Phase 3 only ever created OWNER rows, and the creator was the assigner.
update provider_role_assignment set assigned_by = account_id where assigned_by is null;

alter table provider_role_assignment
    add constraint provider_role_assignment_status_check check (status in ('ACTIVE', 'REVOKED')),
    add constraint provider_role_assignment_revocation_check check (
        (status = 'ACTIVE' and revoked_at is null and revoked_by is null)
        or (status = 'REVOKED' and revoked_at is not null and revoked_by is not null));

-- Replaces unique (provider_id, account_id, role), which allowed one account to
-- hold several roles.
alter table provider_role_assignment drop constraint provider_role_assignment_unique;

create unique index uq_provider_role_assignment_one_active_role
    on provider_role_assignment (provider_id, account_id) where status = 'ACTIVE';

-- The one-OWNER index must ignore revoked history.
drop index uq_provider_role_assignment_one_owner;
create unique index uq_provider_role_assignment_one_owner
    on provider_role_assignment (provider_id) where role = 'OWNER' and status = 'ACTIVE';

create index idx_provider_role_assignment_provider_status
    on provider_role_assignment (provider_id, status);
