-- Phase 9 review: OVER_APPLIED reconciliation events (decision B4/B12).
--
-- An applied extension is never reversed. When an absence that was counted is later corrected (overridden), the days
-- the customer is eligible for can fall below the days already applied. The expiry stays as it is; the engine records
-- the gap as an OVER_APPLIED event so the history says so, without editing any earlier row.
--
--   EXTENSION_APPLIED  applied_extension_days >= 1, over_applied_days = 0          (everything V12 wrote)
--   OVER_APPLIED       applied_extension_days  = 0, over_applied_days  >= 1, expiry unchanged
--
-- Both kinds share the gap-free per-subscription sequence. The V12 invariant is untouched: an OVER_APPLIED event adds 0
-- to sum(applied_extension_days), so effective_expiry_date = base_expiry_date + sum(applied) still holds.

alter table extension_event
    add column event_kind varchar(20) not null default 'EXTENSION_APPLIED',
    add column over_applied_days integer not null default 0,
    -- Days already applied when the gap was measured (= previous expiry - base expiry); only set for OVER_APPLIED.
    add column already_applied_extension_days integer;

alter table extension_event
    add constraint extension_kind_check check (event_kind in ('EXTENSION_APPLIED', 'OVER_APPLIED'));

-- The V12 checks assumed every row adds days; re-state them per kind.
alter table extension_event
    drop constraint extension_eligible_check,
    drop constraint extension_requested_check,
    drop constraint extension_applied_check;

alter table extension_event
    add constraint extension_kind_shape_check check (
        (event_kind = 'EXTENSION_APPLIED'
            and eligible_absence_days >= 1
            and requested_extension_days >= 1
            and applied_extension_days >= 1
            and applied_extension_days <= requested_extension_days
            and over_applied_days = 0
            and already_applied_extension_days is null)
        or
        (event_kind = 'OVER_APPLIED'
            and eligible_absence_days >= 0
            and requested_extension_days = 0
            and applied_extension_days = 0
            and over_applied_days >= 1
            and already_applied_extension_days is not null
            and already_applied_extension_days = eligible_absence_days + over_applied_days
            and capped = false
            and new_expiry_date = previous_expiry_date));

-- "The same absence basis is never applied twice" is about extensions only; OVER_APPLIED events may legitimately
-- repeat a count after the situation changed and changed back.
alter table extension_event drop constraint uq_extension_subscription_basis;
create unique index uq_extension_subscription_basis on extension_event (subscription_id, eligible_absence_days)
    where event_kind = 'EXTENSION_APPLIED';

create or replace function extension_guard_insert() returns trigger as $$
declare
    sub subscription%rowtype;
    snap subscription_term_snapshot%rowtype;
    last_sequence integer;
begin
    select * into sub from subscription where id = new.subscription_id;
    select * into snap from subscription_term_snapshot where subscription_id = new.subscription_id;

    if sub.status <> 'ACTIVE' then
        raise exception 'subscription % is %: it can no longer be extended', new.subscription_id, sub.status
            using errcode = 'check_violation';
    end if;
    if not found or not snap.extension_allowed then
        raise exception 'subscription % was not sold with extension allowed', new.subscription_id
            using errcode = 'check_violation';
    end if;
    if exists (select 1 from subscription r where r.renewed_from_subscription_id = new.subscription_id) then
        raise exception 'subscription % has been renewed: it can no longer be extended', new.subscription_id
            using errcode = 'check_violation';
    end if;
    if new.previous_expiry_date is distinct from sub.effective_expiry_date then
        raise exception 'extension starts from % but the subscription currently expires on %',
            new.previous_expiry_date, sub.effective_expiry_date
            using errcode = 'check_violation';
    end if;
    select coalesce(max(sequence_no), 0) into last_sequence
    from extension_event where subscription_id = new.subscription_id;
    if new.sequence_no <> last_sequence + 1 then
        raise exception 'extension sequence % is not the next one (%)', new.sequence_no, last_sequence + 1
            using errcode = 'check_violation';
    end if;
    if snap.max_calendar_window_days is not null
        and new.new_expiry_date > sub.start_date + (snap.max_calendar_window_days - 1) then
        raise exception 'extension to % goes past the maximum allowed expiry %',
            new.new_expiry_date, sub.start_date + (snap.max_calendar_window_days - 1)
            using errcode = 'check_violation';
    end if;
    if new.maximum_expiry_date is distinct from
        (case when snap.max_calendar_window_days is null then null
              else sub.start_date + (snap.max_calendar_window_days - 1) end) then
        raise exception 'extension records a maximum expiry that does not match the subscription''s policy'
            using errcode = 'check_violation';
    end if;
    -- An OVER_APPLIED event reports a real gap: what is already applied is exactly what the subscription carries.
    if new.event_kind = 'OVER_APPLIED'
        and new.already_applied_extension_days is distinct from (sub.effective_expiry_date - sub.base_expiry_date) then
        raise exception 'over-applied report says % day(s) are applied but the subscription carries %',
            new.already_applied_extension_days, sub.effective_expiry_date - sub.base_expiry_date
            using errcode = 'check_violation';
    end if;
    return new;
end;
$$ language plpgsql;

comment on column extension_event.event_kind is
    'EXTENSION_APPLIED = days were added; OVER_APPLIED = reconciliation report that more days are applied than are now eligible (expiry never reduced).';
comment on column extension_event.over_applied_days is
    'OVER_APPLIED only: already-applied days minus currently eligible days. Never taken back.';
comment on column extension_event.already_applied_extension_days is
    'OVER_APPLIED only: extension days the subscription carried when the gap was measured.';
comment on index uq_extension_subscription_basis is
    'Idempotency guard for EXTENSION_APPLIED events under the monotonic entitlement model. NOT the identity of an absence set: different absence histories can have the same eligible count.';
