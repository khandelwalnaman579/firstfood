-- Phase 6 frozen decisions, enforced one level deeper than the application.
--
-- V8 already carries the per-row CHECKs (price > 0, quantity matches type, cutoff only with
-- same-day absence). Two of the five frozen decisions span more than one row or one UPDATE
-- and cannot be a plain CHECK, so they get triggers here as a last line of defence. The
-- application (PlanRules / PlanServiceImpl) remains the primary, user-facing enforcement.
--
--   Decision 2: extension_allowed = true is valid only for DAY plans. consumption_type lives
--               on plan and extension_allowed on subscription_policy, so a CHECK cannot see both.
--   Decision 3: a plan's consumption type is immutable once created. Changing the type means
--               creating a new plan. The same guard covers the other identity columns that
--               must never move: provider (Rule 7.1), currency (snapshot source), creation.

create function plan_identity_immutable() returns trigger as $$
begin
    if new.consumption_type is distinct from old.consumption_type then
        raise exception 'plan.consumption_type is immutable: create a new plan instead'
            using errcode = 'check_violation';
    end if;
    if new.provider_id is distinct from old.provider_id then
        raise exception 'plan.provider_id is immutable: a plan belongs to exactly one provider'
            using errcode = 'check_violation';
    end if;
    if new.currency is distinct from old.currency then
        raise exception 'plan.currency is immutable'
            using errcode = 'check_violation';
    end if;
    return new;
end;
$$ language plpgsql;

create trigger plan_identity_no_change
    before update on plan
    for each row execute function plan_identity_immutable();

create function subscription_policy_extension_day_only() returns trigger as $$
declare
    plan_type varchar(10);
begin
    if new.extension_allowed then
        select consumption_type into plan_type from plan where id = new.plan_id;
        if plan_type is distinct from 'DAY' then
            raise exception 'extension_allowed is valid only for DAY plans (plan % is %)', new.plan_id, plan_type
                using errcode = 'check_violation';
        end if;
    end if;
    return new;
end;
$$ language plpgsql;

create trigger subscription_policy_extension_day_only_check
    before insert on subscription_policy
    for each row execute function subscription_policy_extension_day_only();

comment on function plan_identity_immutable() is
    'Phase 6 frozen decision 3: consumption_type (and provider_id, currency) never change after creation.';
comment on function subscription_policy_extension_day_only() is
    'Phase 6 frozen decision 2: extension_allowed = true only for DAY plans. Policy rows are insert-only, so insert is the only moment to check.';
