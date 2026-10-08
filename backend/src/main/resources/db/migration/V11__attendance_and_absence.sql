-- Phase 8: Attendance & Absence (phases.md §11; rules.md Rules 11.1-11.4, 12.1-12.5, 15.3, 15.4, 20.3, 21.1, 21.3).
--
-- FirstFood attendance is OPT-OUT (rules.md Rule 11.1): an entitled day is assumed PRESENT and nothing is
-- stored for it. Rows exist only for the exceptions:
--
--   absence_record     = a declaration "this person will not eat on this date" (the customer's, or the provider
--                        staff's on the customer's behalf - the pilot owner takes these over WhatsApp today).
--                        One row per declaration; a declaration is never rewritten, it ends as CANCELLED (the
--                        customer took it back) or OVERRIDDEN (provider staff decided otherwise).
--                        DECLARED rows are what the Phase 9 extension engine will count.
--   attendance_record  = an append-only LEDGER of the outcome for a (subscription, date). Exactly one row per
--                        (subscription, date) is CURRENT; a change never edits it, it supersedes it with a new
--                        row, so "what was recorded, when it was corrected, who corrected it, why" is always
--                        answerable (rules.md Rule 11.3, 15.4). Not a TEXT/JSON history blob (Rule 11.2).
--
-- DAY ONLY, ON PURPOSE. MEAL attendance granularity (lunch + dinner = 2 meals) is an open decision recorded in
-- memory.md ("do not let the first migration accidentally decide this"). Day-level rows cannot represent it, and
-- a MEAL entitlement is not extended by absence (Phase 6 decision 2). So both tables carry consumption_type and
-- a CHECK pins it to 'DAY' through the composite foreign key to subscription. Opening MEAL up later is a
-- deliberate migration (drop the check, add the meal slot) - not something this table decides by accident.
--
-- No ON DELETE CASCADE and no delete path (rules.md Rule 15.3).

-- A composite key so absence/attendance can be tied to the subscription's OWN provider and consumption type by
-- foreign key. A superset of the primary key, so it can never fail on existing data.
alter table subscription
    add constraint uq_subscription_id_provider_type unique (id, provider_id, consumption_type);

create table absence_record (
    id uuid primary key default gen_random_uuid(),
    subscription_id uuid not null,
    -- Denormalised on purpose (architecture.md §34): every provider-owned row carries its provider.
    provider_id uuid not null,
    consumption_type varchar(10) not null default 'DAY',
    absence_date date not null,
    status varchar(12) not null default 'DECLARED',
    -- Who made the declaration: CUSTOMER, or OWNER_CORRECTION when provider staff recorded it for them.
    source varchar(20) not null,
    declared_by uuid not null references user_account (id),
    declared_at timestamptz not null,
    reason varchar(500),
    cancelled_at timestamptz,
    cancelled_by uuid references user_account (id),
    overridden_at timestamptz,
    overridden_by uuid references user_account (id),
    override_reason varchar(500),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),

    constraint absence_subscription_fk
        foreign key (subscription_id, provider_id, consumption_type)
        references subscription (id, provider_id, consumption_type),
    -- Lets attendance_record point at "the absence for THIS subscription and THIS date" and nothing else.
    constraint uq_absence_id_subscription_date unique (id, subscription_id, absence_date),

    constraint absence_day_only_check check (consumption_type = 'DAY'),
    constraint absence_status_check check (status in ('DECLARED', 'CANCELLED', 'OVERRIDDEN')),
    constraint absence_source_check check (source in ('CUSTOMER', 'OWNER_CORRECTION')),
    constraint absence_reason_not_blank check (reason is null or length(btrim(reason)) > 0),
    -- Lifecycle metadata is present exactly for the state it describes (rules.md Rule 15.4). "is not null" is
    -- explicit on purpose: a bare comparison yields NULL for a NULL and a CHECK treats NULL as passing (V8).
    constraint absence_lifecycle_check check (
        (status = 'DECLARED'
            and cancelled_at is null and cancelled_by is null
            and overridden_at is null and overridden_by is null and override_reason is null)
        or (status = 'CANCELLED'
            and cancelled_at is not null and cancelled_by is not null
            and overridden_at is null and overridden_by is null and override_reason is null)
        or (status = 'OVERRIDDEN'
            and overridden_at is not null and overridden_by is not null
            and override_reason is not null and length(btrim(override_reason)) > 0
            and cancelled_at is null and cancelled_by is null))
);

-- One person cannot be absent twice for the same day. Only DECLARED rows compete: a CANCELLED or OVERRIDDEN
-- declaration has handed the day back, and the person may declare again (a new row, history kept).
-- This index, not application timing, is what makes concurrent / retried declarations safe (Rule 21.3).
create unique index uq_absence_one_declared_per_day
    on absence_record (subscription_id, absence_date) where status = 'DECLARED';
create index idx_absence_subscription_date on absence_record (subscription_id, absence_date);

create table attendance_record (
    id uuid primary key default gen_random_uuid(),
    subscription_id uuid not null,
    provider_id uuid not null,
    consumption_type varchar(10) not null default 'DAY',
    attendance_date date not null,
    status varchar(10) not null,
    -- SYSTEM = the default (assumed present) restored; CUSTOMER = the customer's own declaration;
    -- OWNER_CORRECTION = provider staff decided (owner, manager or worker - the name is the PRD's).
    source varchar(20) not null,
    -- The absence this outcome belongs to (the declared one, the cancelled one, the overridden one).
    -- Null only for a plain SYSTEM/PRESENT row, which no absence explains.
    absence_id uuid,
    -- The account that caused this row. Null only for SYSTEM rows written by the system itself.
    recorded_by uuid references user_account (id),
    recorded_at timestamptz not null,
    -- Why: the customer's reason on a declaration, the staff member's reason on a correction.
    reason varchar(500),
    supersedes_id uuid references attendance_record (id),
    is_current boolean not null default true,
    superseded_at timestamptz,
    created_at timestamptz not null default now(),

    constraint attendance_subscription_fk
        foreign key (subscription_id, provider_id, consumption_type)
        references subscription (id, provider_id, consumption_type),
    -- Same subscription AND same date as the absence it cites (MATCH SIMPLE: skipped while absence_id is null).
    constraint attendance_absence_fk
        foreign key (absence_id, subscription_id, attendance_date)
        references absence_record (id, subscription_id, absence_date),

    constraint attendance_day_only_check check (consumption_type = 'DAY'),
    constraint attendance_status_check check (status in ('PRESENT', 'ABSENT')),
    constraint attendance_source_check check (source in ('SYSTEM', 'CUSTOMER', 'OWNER_CORRECTION')),
    -- A customer can only ever say "I am not eating"; they cannot declare themselves present (opt-out).
    constraint attendance_customer_means_absent check (source <> 'CUSTOMER' or status = 'ABSENT'),
    constraint attendance_actor_required check (source = 'SYSTEM' or recorded_by is not null),
    -- A correction must say why (rules.md Rule 15.4: who, what, when, why).
    constraint attendance_correction_needs_reason check (
        source <> 'OWNER_CORRECTION' or (reason is not null and length(btrim(reason)) > 0)),
    constraint attendance_reason_not_blank check (reason is null or length(btrim(reason)) > 0),
    -- Every absence is backed by an absence declaration; only the default PRESENT can stand alone.
    constraint attendance_absence_required check (absence_id is not null or (source = 'SYSTEM' and status = 'PRESENT')),
    constraint attendance_supersedes_other check (supersedes_id <> id),
    constraint attendance_current_check check (
        (is_current and superseded_at is null) or (not is_current and superseded_at is not null))
);

-- Exactly one CURRENT outcome per subscription and day. The application must supersede the old row (and flush)
-- before inserting the new one; this index is the last defence if two requests ever race past the row lock.
create unique index uq_attendance_one_current_per_day
    on attendance_record (subscription_id, attendance_date) where is_current;
-- The provider's daily sheet ("who is not eating on this date").
create index idx_attendance_provider_date_current
    on attendance_record (provider_id, attendance_date) where is_current;
create index idx_attendance_subscription_date on attendance_record (subscription_id, attendance_date);
create index idx_attendance_absence on attendance_record (absence_id) where absence_id is not null;

-- ------------------------------------------------------------------------------------------------
-- What may be inserted: a day on or after the subscription's start, never for a CANCELLED subscription
-- (final, like every cancelled subscription). The upper bound is NOT checked here: Phase 9 may move the
-- effective expiry, so only the application knows it.
-- ------------------------------------------------------------------------------------------------
create function absence_guard_insert() returns trigger as $$
declare
    sub subscription%rowtype;
begin
    select * into sub from subscription where id = new.subscription_id;
    if sub.status = 'CANCELLED' then
        raise exception 'subscription % is cancelled: no absence can be recorded', new.subscription_id
            using errcode = 'check_violation';
    end if;
    if new.absence_date < sub.start_date then
        raise exception 'absence date % is before the subscription start %', new.absence_date, sub.start_date
            using errcode = 'check_violation';
    end if;
    return new;
end;
$$ language plpgsql;

create trigger absence_record_guard_insert
    before insert on absence_record
    for each row execute function absence_guard_insert();

create function attendance_guard_insert() returns trigger as $$
declare
    sub subscription%rowtype;
begin
    select * into sub from subscription where id = new.subscription_id;
    if sub.status = 'CANCELLED' then
        raise exception 'subscription % is cancelled: no attendance can be recorded', new.subscription_id
            using errcode = 'check_violation';
    end if;
    if new.attendance_date < sub.start_date then
        raise exception 'attendance date % is before the subscription start %', new.attendance_date, sub.start_date
            using errcode = 'check_violation';
    end if;
    return new;
end;
$$ language plpgsql;

create trigger attendance_record_guard_insert
    before insert on attendance_record
    for each row execute function attendance_guard_insert();

-- ------------------------------------------------------------------------------------------------
-- What may change after insert.
-- ------------------------------------------------------------------------------------------------
-- A declaration is a fact; the only thing that can happen to it is that it ends: DECLARED -> CANCELLED or
-- OVERRIDDEN, both final. Everything about who/what/when it was declared never moves.
create function absence_guard_update() returns trigger as $$
begin
    if new.subscription_id is distinct from old.subscription_id
        or new.provider_id is distinct from old.provider_id
        or new.consumption_type is distinct from old.consumption_type
        or new.absence_date is distinct from old.absence_date
        or new.source is distinct from old.source
        or new.declared_by is distinct from old.declared_by
        or new.declared_at is distinct from old.declared_at
        or new.reason is distinct from old.reason
        or new.created_at is distinct from old.created_at then
        raise exception 'an absence declaration is immutable: cancel or override it instead'
            using errcode = 'check_violation';
    end if;
    if old.status <> 'DECLARED' then
        raise exception 'absence is % and can no longer change', old.status
            using errcode = 'check_violation';
    end if;
    if new.status = 'DECLARED' then
        raise exception 'an absence can only be changed by cancelling or overriding it'
            using errcode = 'check_violation';
    end if;
    return new;
end;
$$ language plpgsql;

create trigger absence_record_guard_update
    before update on absence_record
    for each row execute function absence_guard_update();

-- A ledger row is a fact. The ONLY permitted update is "no longer current": is_current true -> false together
-- with superseded_at. Corrections are new rows.
create function attendance_guard_update() returns trigger as $$
begin
    if old.is_current is not true then
        raise exception 'attendance record is superseded and can no longer change'
            using errcode = 'check_violation';
    end if;
    if new.is_current is not false or new.superseded_at is null then
        raise exception 'an attendance record can only be superseded'
            using errcode = 'check_violation';
    end if;
    if (to_jsonb(new) - 'is_current' - 'superseded_at') is distinct from (to_jsonb(old) - 'is_current' - 'superseded_at') then
        raise exception 'attendance records are immutable: record a correction instead'
            using errcode = 'check_violation';
    end if;
    return new;
end;
$$ language plpgsql;

create trigger attendance_record_guard_update
    before update on attendance_record
    for each row execute function attendance_guard_update();

create function attendance_reject_delete() returns trigger as $$
begin
    raise exception '% rows are never deleted: record a correction instead', tg_table_name
        using errcode = 'check_violation';
end;
$$ language plpgsql;

create trigger absence_record_no_delete
    before delete on absence_record
    for each row execute function attendance_reject_delete();
create trigger attendance_record_no_delete
    before delete on attendance_record
    for each row execute function attendance_reject_delete();

-- ------------------------------------------------------------------------------------------------
-- The two tables must tell the same story: a DECLARED absence is exactly a CURRENT ABSENT attendance row.
-- Phase 9 counts DECLARED absences; the owner's daily sheet reads current attendance rows; they must never
-- disagree. Deferred to COMMIT because the two rows are written one after the other in one transaction.
-- ------------------------------------------------------------------------------------------------
create function absence_requires_matching_attendance() returns trigger as $$
begin
    if new.status = 'DECLARED' then
        if not exists (
            select 1 from attendance_record a
            where a.absence_id = new.id and a.is_current and a.status = 'ABSENT'
              and a.subscription_id = new.subscription_id and a.attendance_date = new.absence_date) then
            raise exception 'declared absence % has no current ABSENT attendance record', new.id
                using errcode = 'check_violation';
        end if;
    elsif exists (
        select 1 from attendance_record a
        where a.absence_id = new.id and a.is_current and a.status = 'ABSENT') then
        raise exception '% absence % still has a current ABSENT attendance record', new.status, new.id
            using errcode = 'check_violation';
    end if;
    return null;
end;
$$ language plpgsql;

create constraint trigger absence_attendance_consistent
    after insert or update on absence_record
    deferrable initially deferred
    for each row execute function absence_requires_matching_attendance();

create function attendance_requires_declared_absence() returns trigger as $$
begin
    if new.is_current and new.status = 'ABSENT' then
        if not exists (select 1 from absence_record r where r.id = new.absence_id and r.status = 'DECLARED') then
            raise exception 'current ABSENT attendance record % is not backed by a DECLARED absence', new.id
                using errcode = 'check_violation';
        end if;
    end if;
    return null;
end;
$$ language plpgsql;

create constraint trigger attendance_absence_consistent
    after insert or update on attendance_record
    deferrable initially deferred
    for each row execute function attendance_requires_declared_absence();

comment on table absence_record is
    'A declaration that a person will not eat on a date (customer, or provider staff on their behalf). Never edited or deleted: ends as CANCELLED or OVERRIDDEN. DAY subscriptions only.';
comment on column absence_record.source is
    'Who declared it: CUSTOMER, or OWNER_CORRECTION when provider staff recorded it for the customer.';
comment on table attendance_record is
    'Append-only ledger of the outcome per (subscription, date). Opt-out: no row = assumed present. One CURRENT row per day; a change supersedes it with a new row. DAY subscriptions only (MEAL granularity is an open decision).';
comment on column attendance_record.source is
    'SYSTEM = default restored, CUSTOMER = customer declaration, OWNER_CORRECTION = provider staff decision.';
comment on column attendance_record.is_current is
    'True for the one row that states the day''s outcome; false once a later row supersedes it (superseded_at set).';
comment on index uq_absence_one_declared_per_day is
    'At most one DECLARED absence per subscription and day; the concurrency guard for duplicate/retried declarations.';
comment on index uq_attendance_one_current_per_day is
    'Exactly one current attendance row per subscription and day.';
comment on function absence_requires_matching_attendance() is
    'Deferred check at COMMIT: a DECLARED absence has a current ABSENT attendance row; a CANCELLED/OVERRIDDEN one has none.';
comment on function attendance_requires_declared_absence() is
    'Deferred check at COMMIT: a current ABSENT attendance row is backed by a DECLARED absence.';
