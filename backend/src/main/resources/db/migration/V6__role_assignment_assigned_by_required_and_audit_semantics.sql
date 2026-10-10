-- Phase 4 closure pass.
--
-- 1. assigned_by becomes mandatory. V4 left it nullable only so the Phase 3 test
--    helper could keep inserting rows without it; every row written by the
--    service (and the V4 backfill) already has it, and the Phase 3 helper now
--    supplies it too.
-- 2. Audit semantics are documented on the table itself. V4/V5 are not edited
--    (Flyway checksums); this migration is the single place the final meaning lives.

update provider_role_assignment set assigned_by = account_id where assigned_by is null;

alter table provider_role_assignment alter column assigned_by set not null;

comment on table provider_role_audit is
    'Append-only. ONE ROW = ONE ACCOUNT''S ROLE CHANGE: target_account_id went from old_role to new_role, '
    'performed by actor_account_id. An ownership transfer therefore writes TWO OWNER_TRANSFERRED rows in the '
    'same transaction: (target = new owner, old_role = its previous role or NULL, new_role = OWNER) and '
    '(target = former owner, old_role = OWNER, new_role = MANAGER).';
comment on column provider_role_audit.actor_account_id is 'Account that performed the action (from the JWT).';
comment on column provider_role_audit.target_account_id is 'Account whose role changed in this row.';
comment on column provider_role_audit.old_role is 'target''s role before the change; NULL when it had none.';
comment on column provider_role_audit.new_role is 'target''s role after the change; NULL when it was revoked.';