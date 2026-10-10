-- Phase 4 Checkpoint 6: append-only audit trail for security-sensitive role changes.
-- Stores ids only: no phone numbers, tokens or other secrets.
-- For OWNER_TRANSFERRED: target_account_id is the new OWNER, old_role is that
-- account's role before the transfer (NULL if it had none), new_role is OWNER.

create table provider_role_audit (
    id uuid primary key default gen_random_uuid(),
    provider_id uuid not null references food_provider (id),
    actor_account_id uuid not null references user_account (id),
    target_account_id uuid not null references user_account (id),
    action varchar(30) not null,
    old_role varchar(20),
    new_role varchar(20),
    created_at timestamptz not null default now(),

    constraint provider_role_audit_action_check check (
        action in ('ROLE_ASSIGNED', 'ROLE_REVOKED', 'OWNER_TRANSFERRED')),
    constraint provider_role_audit_old_role_check check (
        old_role is null or old_role in ('OWNER', 'MANAGER', 'WORKER')),
    constraint provider_role_audit_new_role_check check (
        new_role is null or new_role in ('OWNER', 'MANAGER', 'WORKER'))
);

create index idx_provider_role_audit_provider on provider_role_audit (provider_id, created_at);

-- Append-only: audit rows can be neither changed nor deleted.
create function provider_role_audit_immutable() returns trigger as $$
begin
    raise exception 'provider_role_audit is append-only';
end;
$$ language plpgsql;

create trigger provider_role_audit_no_update_delete
    before update or delete on provider_role_audit
    for each row execute function provider_role_audit_immutable();
