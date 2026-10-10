-- Phase 9: Extension Engine (phases.md §12; rules.md Rules 13.1-13.6, 15.4, 20.2, 21.1-21.3, 22.1, 22.3).
--
-- extension_event = the append-only audit of every change to a subscription's effective expiry. A DAY subscription
-- whose terms allow it is extended by the days its customer did not eat (policy-driven: the snapshot says whether
-- extension is allowed, how many CONSECUTIVE absent days make a stretch count, and the calendar window that caps it).
--
--     final expiry = minimum(base expiry + eligible extension, maximum allowed expiry)
--     maximum allowed expiry = start_date + max_calendar_window_days - 1   (counted from the ORIGINAL start, Rule 13.4;
--                              not stored: it follows from two immutable columns, exactly as in V10)
--
-- THE INVARIANT THIS MIGRATION ADDS: for a DAY subscription,
--     effective_expiry_date = base_expiry_date + sum(applied_extension_days of its extension events)
-- so the expiry can never move without an event that explains it ("do not silently change effective_expiry_date",
-- Rule 13.2). It is checked at COMMIT, in both directions (a subscription change without its event, an event without
-- its subscription change).
--
-- IDEMPOTENCY (Rule 13.5). The engine reconciles to a TARGET computed from the absence records, it does not add "+5".
-- Running it again finds nothing left to apply and writes nothing. The database backs that up with two unique keys:
--   (subscription_id, sequence_no)            - events form a gap-free 1..n chain; two racing writers cannot both be #n
--   (subscription_id, eligible_absence_days)  - the absence basis (the cumulative count of eligible days at the time)
--                                               is the business identity of an extension: the same basis cannot be
--                                               applied twice. It strictly grows from one event to the next because
--                                               an event is only written for days beyond everything already applied.
--
-- DAY only, like V11 (a MEAL entitlement is not extended by absence - Phase 6 decision 2). No ON DELETE CASCADE and
-- no delete path (Rule 15.3): the table is append-only.

create table extension_event (
    id uuid primary key default gen_random_uuid(),
    subscription_id uuid not null,
    -- Denormalised on purpose (architecture.md §34): every provider-owned row carries its provider.
    provider_id uuid not null,
    consumption_type varchar(10) not null default 'DAY',
    -- 1, 2, 3 ... per subscription, in the order the events were applied.
    sequence_no integer not null,
    -- STAFF = a provider account asked for it (created_by is that account); SYSTEM = the engine itself, e.g. the
    -- Phase 10 background job (created_by is null). Rule 13.6: trigger/source and actor.
    trigger_source varchar(10) not null,
    created_by uuid references user_account (id),
    -- The business date the evaluation was made on. Only absent days BEFORE this date were counted: a day counts
    -- once it is over, because until then the customer (or the provider) can still change it.
    evaluated_on date not null,
    -- Cumulative eligible absence days at evaluation: every absent day (declared, not cancelled or overridden)
    -- that belongs to a run of at least min_consecutive_absence_days consecutive absent days.
    eligible_absence_days integer not null,
    -- What this event was asked to add: eligible_absence_days minus everything applied by earlier events.
    requested_extension_days integer not null,
    -- What it did add after the cap. Less than requested exactly when the maximum allowed expiry cut it short.
    applied_extension_days integer not null,
    previous_expiry_date date not null,
    new_expiry_date date not null,
    -- The cap in force (null = the policy has no calendar window, so no cap).
    maximum_expiry_date date,
    capped boolean not null,
    created_at timestamptz not null default now(),

    constraint extension_subscription_fk
        foreign key (subscription_id, provider_id, consumption_type)
        references subscription (id, provider_id, consumption_type),

    constraint extension_day_only_check check (consumption_type = 'DAY'),
    constraint extension_source_check check (trigger_source in ('STAFF', 'SYSTEM')),
    -- Who: a staff event names its actor; a system event has none. ("is not null" is explicit: V8 lesson.)
    constraint extension_actor_check check (
        (trigger_source = 'STAFF' and created_by is not null)
        or (trigger_source = 'SYSTEM' and created_by is null)),
    constraint extension_sequence_check check (sequence_no >= 1),
    constraint extension_eligible_check check (eligible_absence_days >= 1),
    constraint extension_requested_check check (requested_extension_days >= 1),
    -- An event that changed nothing is never written (the caller reports "nothing to apply" instead).
    constraint extension_applied_check check (
        applied_extension_days >= 1 and applied_extension_days <= requested_extension_days),
    constraint extension_capped_check check (capped = (applied_extension_days < requested_extension_days)),
    constraint extension_dates_check check (new_expiry_date = previous_expiry_date + applied_extension_days),
    constraint extension_maximum_check check (maximum_expiry_date is null or new_expiry_date <= maximum_expiry_date),

    constraint uq_extension_subscription_sequence unique (subscription_id, sequence_no),
    constraint uq_extension_subscription_basis unique (subscription_id, eligible_absence_days)
);

create index idx_extension_provider_created on extension_event (provider_id, created_at);

-- ------------------------------------------------------------------------------------------------
-- What may be inserted. The application computes the extension; the database refuses the cases that can
-- never be right, so a bug or a second writer cannot corrupt an entitlement.
-- ------------------------------------------------------------------------------------------------
create function extension_guard_insert() returns trigger as $$
declare
    sub subscription%rowtype;
    snap subscription_term_snapshot%rowtype;
    last_sequence integer;
begin
    select * into sub from subscription where id = new.subscription_id;
    select * into snap from subscription_term_snapshot where subscription_id = new.subscription_id;

    -- ACTIVE is the only state that can change (V10); say why in words instead of a generic trigger error.
    if sub.status <> 'ACTIVE' then
        raise exception 'subscription % is %: it can no longer be extended', new.subscription_id, sub.status
            using errcode = 'check_violation';
    end if;
    if not found or not snap.extension_allowed then
        raise exception 'subscription % was not sold with extension allowed', new.subscription_id
            using errcode = 'check_violation';
    end if;
    -- A renewal starts after the previous subscription's expiry; extending the previous one afterwards would
    -- overlap it (and would rewrite what the renewal was sold against).
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
    -- The cap is a policy fact copied onto the snapshot at sale: start + window - 1 (Rule 13.4).
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
    return new;
end;
$$ language plpgsql;

create trigger extension_event_guard_insert
    before insert on extension_event
    for each row execute function extension_guard_insert();

-- An event is a fact (Rule 15.3/15.4): never edited, never deleted. A wrong extension is answered by a decision of
-- its own, not by rewriting history.
create function extension_reject_change() returns trigger as $$
begin
    raise exception 'extension_event rows are append-only: they are never updated or deleted'
        using errcode = 'check_violation';
end;
$$ language plpgsql;

create trigger extension_event_append_only
    before update or delete on extension_event
    for each row execute function extension_reject_change();

-- ------------------------------------------------------------------------------------------------
-- The invariant: a DAY subscription's effective expiry is its base expiry plus what its events applied.
-- Deferred to COMMIT because the event and the subscription change are written one after the other.
-- ------------------------------------------------------------------------------------------------
create function assert_expiry_matches_extensions(subscription_uuid uuid) returns void as $$
declare
    sub subscription%rowtype;
    applied bigint;
begin
    select * into sub from subscription where id = subscription_uuid;
    if not found or sub.consumption_type <> 'DAY' then
        return;
    end if;
    select coalesce(sum(applied_extension_days), 0) into applied
    from extension_event where subscription_id = subscription_uuid;
    if sub.effective_expiry_date is distinct from sub.base_expiry_date + applied::integer then
        raise exception 'subscription % expires on % but its base expiry % plus % applied extension day(s) is %',
            subscription_uuid, sub.effective_expiry_date, sub.base_expiry_date, applied,
            sub.base_expiry_date + applied::integer
            using errcode = 'check_violation';
    end if;
end;
$$ language plpgsql;

create function subscription_expiry_consistent() returns trigger as $$
begin
    perform assert_expiry_matches_extensions(new.id);
    return null;
end;
$$ language plpgsql;

create constraint trigger subscription_expiry_matches_extensions
    after insert or update on subscription
    deferrable initially deferred
    for each row execute function subscription_expiry_consistent();

create function extension_event_expiry_consistent() returns trigger as $$
begin
    perform assert_expiry_matches_extensions(new.subscription_id);
    return null;
end;
$$ language plpgsql;

create constraint trigger extension_event_expiry_matches_subscription
    after insert on extension_event
    deferrable initially deferred
    for each row execute function extension_event_expiry_consistent();

comment on table extension_event is
    'Append-only audit of every extension of a DAY subscription: eligible vs requested vs applied days, previous and new expiry, the cap, who/what triggered it. effective_expiry_date = base_expiry_date + sum(applied_extension_days), checked at commit.';
comment on column extension_event.eligible_absence_days is
    'Cumulative absent days that belonged to a run of at least min_consecutive_absence_days consecutive days, counting only days before evaluated_on. Unique per subscription: the same absence basis is never applied twice.';
comment on column extension_event.requested_extension_days is
    'eligible_absence_days minus the days earlier events already applied.';
comment on column extension_event.applied_extension_days is
    'Days actually added: the request, cut short by the maximum allowed expiry when that is lower (then capped = true).';
comment on column extension_event.maximum_expiry_date is
    'start_date + max_calendar_window_days - 1 of the subscription''s term snapshot; NULL when the policy has no calendar window.';
comment on column extension_event.trigger_source is
    'STAFF = a provider account applied it (created_by); SYSTEM = the engine / a background job (no account).';
comment on constraint uq_extension_subscription_basis on extension_event is
    'Idempotency boundary: one extension per cumulative eligible-absence basis.';
comment on function assert_expiry_matches_extensions(uuid) is
    'Deferred check at COMMIT: a DAY subscription''s effective expiry equals its base expiry plus the days its extension events applied.';
comment on column subscription.effective_expiry_date is
    'Current expiry = base expiry plus applied extensions (Phase 9: extension_event, checked at commit). NULL only for a MEAL subscription without a calendar window.';
