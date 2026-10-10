-- Identity module schema (phases.md Phase 2, memory.md #2/#3).
--
-- UserAccount is the authentication identity, deliberately separate from
-- Person (rules.md Rule 3.1/3.2) - Person doesn't exist yet (Phase 5), so
-- there is no FK from user_account to a person table here. Do not add a
-- unique constraint or structure that would prevent one UserAccount from
-- eventually owning multiple Persons.

create table user_account (
    id uuid primary key default gen_random_uuid(),
    phone varchar(20) not null,
    email varchar(255),
    status varchar(20) not null default 'ACTIVE',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint user_account_phone_unique unique (phone),
    constraint user_account_status_check check (status in ('ACTIVE', 'SUSPENDED'))
);

-- OTP codes are never stored in plaintext (architecture.md #12 "Never
-- store plaintext OTPs where avoidable") - only a hash. purpose/new_phone
-- exist so the same table backs both login OTPs and the sensitive
-- phone-number-change flow required by rules.md Rule 3.3.
create table otp_verification (
    id uuid primary key default gen_random_uuid(),
    phone varchar(20) not null,
    new_phone varchar(20),
    purpose varchar(20) not null,
    otp_hash varchar(255) not null,
    attempt_count integer not null default 0,
    status varchar(20) not null default 'PENDING',
    expires_at timestamptz not null,
    created_at timestamptz not null default now(),
    constraint otp_verification_purpose_check check (purpose in ('LOGIN', 'PHONE_CHANGE')),
    constraint otp_verification_status_check check (status in ('PENDING', 'VERIFIED', 'EXPIRED', 'CONSUMED'))
);

-- Looked up by phone on every verify attempt - keep this fast.
create index idx_otp_verification_phone_status on otp_verification (phone, status);

-- Refresh tokens are opaque random strings on the wire; only their hash
-- is persisted (architecture.md #12 "Store refresh-session state
-- securely"). Revocation is a soft delete (revoked_at) so logout/rotation
-- history is auditable rather than silently disappearing.
create table refresh_session (
    id uuid primary key default gen_random_uuid(),
    user_account_id uuid not null references user_account (id),
    refresh_token_hash varchar(255) not null,
    issued_at timestamptz not null default now(),
    expires_at timestamptz not null,
    revoked_at timestamptz,
    constraint refresh_session_token_hash_unique unique (refresh_token_hash)
);

create index idx_refresh_session_user_account on refresh_session (user_account_id);
