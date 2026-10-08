# FirstFood V2 — Project Memory

Living reference for anyone (human or Claude) picking this project up in a
new session. Do not re-litigate the decisions below without a deliberate
review — they were frozen after an explicit documentation reconciliation
pass. Update this file whenever a phase completes or a frozen decision
changes.

---

## 1. What FirstFood Is

Two-sided platform for India's recurring/local food-service ecosystem
(messes, tiffin services, PG/hostel food providers). V2 is a **greenfield
rebuild** — the old implementation is reference-only, not a migration
source.

First real pilot: **one mess, ~40–50 customers**, currently run on
WhatsApp group + notebook. Pilot plan is **DAY-based, 30 days, ₹2,500**.

Product vision: *"Food whenever you need it."*

---

## 2. Canonical Domain Vocabulary

| Concept | Canonical name |
|---|---|
| Food business | `FoodProvider` (never "Restaurant") |
| Login identity | `UserAccount` |
| Food recipient | `Person` |
| Provider role relationship | `ProviderRoleAssignment` / DB: `PROVIDER_ROLE_ASSIGNMENT` |
| Provider/customer relationship | `ProviderMembership` |
| Commercial offering | `Plan` |
| Central entitlement aggregate | `Subscription` |
| Historical terms | `SubscriptionTermSnapshot` (commercial **and** policy terms — see §3) |
| Absence | `AbsenceRecord` |
| Attendance | `AttendanceRecord` |
| Extension | `ExtensionEvent` |
| Feedback | `Review` → `ReviewReply` |

Relationship spine:
`UserAccount → Person → ProviderMembership → Subscription → Plan → FoodProvider`

---

## 3. Frozen Decisions (from Documentation Reconciliation, do not reopen casually)

1. **No separate policy-snapshot entity.** `SubscriptionTermSnapshot` is
   the *only* snapshot table/entity, and it carries both commercial terms
   (price, currency, consumption_type, purchased_days/meals) **and**
   applicable policy terms (extension_allowed, absence cutoff, min
   consecutive absence, max extension window, max expiry rule,
   policy_version) captured at subscription creation.
2. **Cardinality:** `SUBSCRIPTION 1 : 1 SUBSCRIPTION_TERM_SNAPSHOT` — every
   subscription has exactly one immutable snapshot.
3. **Provider role naming:** `ProviderRoleAssignment` / `PROVIDER_ROLE_ASSIGNMENT`
   (not `PROVIDER_ROLE`).
4. **Plan edits never rewrite existing subscriptions.** They affect only
   new subscriptions/renewals. Renewal = new commercial event = new
   snapshot.
5. **Extension formula:**
   `Final Expiry = min(Calculated Expiry + Eligible Extension, Maximum Allowed Expiry Date)`.
   Extension processing must be idempotent and recorded as an auditable
   `ExtensionEvent`.
6. **Attendance/absence are structured records, not a TEXT/JSON blob.**
   Attendance model is **opt-out**: active subscription defaults to
   present; customer acts only when declaring absence.
7. **Historical data is never casually deleted.** Providers/customers go
   inactive, not deleted. `ProviderMembership` survives subscription
   expiry.
8. **PostgreSQL (Neon) is the sole source of truth.** Redis (Upstash) is
   cache/rate-limit/ephemeral only — never authoritative business history.
9. **Architecture: modular monolith**, not microservices, for MVP.
10. **Backend authorization is authoritative** — frontend route
    protection is not a security boundary. Provider-scoped access must be
    enforced server-side.
11. **Database scalability strategy:** single PostgreSQL primary until a
    measured need justifies more — full record in §14.

### Still open (must be resolved before DB migrations, not before)

- **MEAL attendance granularity** — lunch+dinner on the same day = 2
  meals, but current attendance shape (`subscription_id`,
  `attendance_date`, `status`) can't represent that. Options on the
  table: explicit `meal_type` enum, generalized provider-defined
  `meal_slot`, or a dedicated `MealConsumption` event model. **Decide
  intentionally — do not let the first migration accidentally decide
  this.**
- **Review reply cardinality** — one review → one reply (`0..1`) or many
  (`0..N`)? Not yet confirmed.
- **SubscriptionPolicy version ownership/uniqueness** — not yet finalized.
- Persisted vs. derived subscription fields (remaining_days,
  remaining_meals, effective_expiry_date) — decide storage strategy
  during schema design.
- Exact enums, indexes, FK actions, unique constraints, timezone/date-vs-
  timestamp conventions — deferred to schema design (see `architecture.md`
  §13 and ERD "Items to finalize").

### Explicitly not going back into V2

Restaurant-centric terminology, ordering as an MVP dependency, payment
gateway dependency in MVP, generic `usage_history TEXT`/`days_eaten`
fields, old MySQL/Razorpay assumptions, live-headcount-as-core-concept,
menu-locking assumptions, AI features without validated demand.

---

## 4. Tech Stack (locked)

| Layer | Choice | Pinned version (Sept 2026) |
|---|---|---|
| Frontend | Next.js + React + TypeScript, deployed on Vercel | Next.js 16.3.x (Active LTS), React 19.3.x |
| Backend | Spring Boot + Java + Spring Security + JWT, deployed on AWS (Docker) | Spring Boot 4.1.1, Java 25 |
| Primary DB | Neon PostgreSQL | Postgres 17 locally/on Neon (18 still preview-flagged on Neon) |
| Cache/ephemeral | Upstash Redis in production; Valkey 8 locally | — |
| Object storage/CDN | Cloudflare R2 | — |
| Email/SMS (OTP) | Brevo / Brevo SMS | — |
| Containerization | Docker | — |
| Backend base package | `com.firstfood` | — |

### Version audit (done Sept 10, 2026 - re-verify before Phase 2)

- **Java 25** — current LTS (GA Sept 16, 2025, ≥8 years Oracle Premier
  support). Java 21 remains LTS but 25 is the fresher window for a
  greenfield project.
- **Spring Boot 4.1.0** — Spring Boot has no official LTS track (every
  minor gets ~12 months OSS support), but 3.5 reached OSS EOL June 30,
  2026. Only the 4.0/4.1 lines are currently supported; 4.1.0 is latest
  stable (GA ~June 2026). Supports Java 17 through Java 26.
- **Node.js 24** — current Active LTS (Active LTS since Oct 2025,
  Maintenance from Oct 2026). Node 20 (originally scaffolded) reached EOL
  April 30, 2026 — do not use it. Node 22 is Maintenance LTS if 24 turns
  out to be too new for some dependency.
- **Next.js 16.3.x** — Active LTS per Next.js's own LTS policy (16.x
  Active LTS since Oct 21, 2025; 15.x is Maintenance LTS). Requires React
  19. `next lint` was removed in v16 — linting now runs via a flat
  `eslint.config.mjs` and a plain `eslint` script.
- **PostgreSQL 17** (not 18) — 18 is current upstream but Neon still runs
  it with `io_method=sync` during its preview period; 17 is the newest
  fully-GA choice on Neon. Revisit once Neon lifts the PG18 preview flag.
- **TypeScript 6.0.3, deliberately not 7.0.x** — TypeScript 7 (native Go
  compiler, GA July 2026) is the newest stable release, but it ships
  without a stable programmatic API, so `typescript-eslint` /
  `eslint-config-next` can't run on it yet. 6.0.3 is the last
  JS-compiler-based release and is what the lint toolchain needs. Revisit
  this pin once typescript-eslint supports TS7.
- **Redis/Valkey** — architecture.md's frozen choice is Upstash Redis in
  production; unchanged. Locally, `infra/docker-compose.yml` uses
  `valkey/valkey:8-alpine` instead of the official `redis` image only to
  sidestep Redis Ltd.'s SSPL/RSALv2 relicensing (March 2024) for local
  tooling. This is a local-only substitution, not a production stack
  change - flag it if that ever needs to be reconsidered.
- **Spring Boot 4 test breaking change (fixed)** — Spring Boot 4
  modularized `spring-boot-starter-test`; `TestRestTemplate` moved from
  `org.springframework.boot.test.web.client` to
  `org.springframework.boot.resttestclient`, and `@SpringBootTest` no
  longer auto-configures it. Rather than chase the old class with an
  extra annotation, `FirstFoodApplicationTests` was migrated to
  `RestTestClient` (Spring's own recommended replacement -
  `TestRestTemplate` is headed toward deprecation). Needs the
  `spring-boot-resttestclient` and `spring-boot-restclient` test-scoped
  dependencies in `pom.xml` (not pulled in by `spring-boot-starter-test`
  alone anymore) plus `@AutoConfigureRestTestClient` on the test class.
  If a future module needs `TestRestTemplate` specifically for some
  reason, the same two dependencies plus `@AutoConfigureTestRestTemplate`
  are what's needed instead.
- **Two more Spring Boot 4/Security 7 API surprises (fixed, Phase 2)** -
  (1) `UsernamePasswordAuthenticationFilter` lives in
  `org.springframework.security.web.authentication`, not
  `org.springframework.security.authentication` - easy typo, breaks
  `addFilterBefore(...)` with a confusing cascade of unrelated-looking
  errors in the same file. (2) `RestTestClient`'s request-body method is
  `.body(Object)`, not WebTestClient's `.bodyValue(Object)` - they look
  like the same fluent API family but aren't quite. If either of these
  reappears in a new module, it's the same two mistakes, not a new bug.
- **Testcontainers killed mid-CI-run between test classes (fixed)** -
  `AbstractIntegrationTest` originally used `@Testcontainers` +
  `@Container` on `static` Postgres/Redis fields shared by multiple test
  classes. That's a documented Testcontainers anti-pattern: `@Testcontainers`
  ties a field's start/stop to *that specific test class's* JUnit
  lifecycle, so when the first test class (`FirstFoodApplicationTests`)
  finished, its `afterAll` stopped the (shared, static) containers right
  as `AuthenticationFlowIntegrationTest` was about to reuse them -
  producing "connection refused" mid-suite in CI, which looked like
  flakiness but was 100% reproducible. Fixed by switching to the
  documented "singleton containers pattern": a static initializer
  (`Startables.deepStart(...).join()`), no `@Testcontainers`/`@Container`
  annotations, containers never explicitly stopped (Testcontainers'
  Ryuk sidecar cleans them up when the JVM exits). If a third test class
  gets added later extending `AbstractIntegrationTest`, do NOT re-add
  `@Testcontainers` to "simplify" it - that's exactly this bug again.
- **IDE noise fixed, not bugs**: added `spring-boot-configuration-processor`
  (optional, compile-time only) so `app.otp.*`/`app.jwt.*` stop showing as
  "unknown property" in editors - it generates metadata from
  `OtpProperties`/`JwtProperties` automatically. Also quoted the dotted
  logger key using Spring's own bracket-escape convention -
  `"[com.firstfood]"`, not just plain quotes - in all `application-*.yml`
  files (cosmetic, never a functional issue).
- **CI action versions (Sept 2026)**: `actions/checkout` bumped to `v7`,
  `actions/setup-java` to `v6`, `actions/setup-node` to `v5` - all now
  run on Node 24 (GitHub deprecated the Node 20 action runtime). If this
  warning resurfaces later, check for a newer major again rather than
  assuming v7/v6/v5 are still current - these actions get bumped often.

Modular monolith module boundaries (Spring Boot internal packages, not
services): `identity`, `provider`, `provider-access` (RBAC),
`membership`, `plan`, `subscription`, `attendance`, `review`,
`notification`.

---

## 5. MVP Scope Guardrails

**In:** mobile OTP auth, UserAccount/Person split (even if 1:1 in MVP),
FoodProvider CRUD + isolation, ProviderRoleAssignment (OWNER-focused),
ProviderMembership, Plan (DAY + MEAL types even if MEAL tracking is
deferred operationally), DAY subscription lifecycle, provider-specific
absence/extension policy, attendance default + absence declaration,
extension calculation, auto expiry, historical snapshots, basic
dashboards.

**Out (future, don't build early):** payments/payment gateway, food
ordering, WhatsApp API integration, MANAGER/WORKER operational modules,
multi-member accounts UI, GPS/radius discovery, hyperlocal ads,
analytics/automation, AI features, generic rules engine.

---

## 6. Source-of-Truth Documents

- `prd.md` — product requirements (business rules, scope, personas)
- `architecture.md` — system architecture, modules, data flow
- `rules.md` — enumerated business/engineering rules
- `phases.md` — phased delivery plan (this file tracks progress against it)
- `design.md` — detailed domain/aggregate design notes
- `FirstFood_V2_ERD_Final.pdf`, `FirstFood_V2_ERD_Spec.pdf`,
  `FirstFood_V2_Domain_Model.pdf` — entity/relationship reference
- `FirstFood_V2_Phase3_Domain_Decision_Freeze.md` — Phase 3 domain decisions
  (provider status model, roles, closure, authorization contract)
- `FirstFood_V2_Phase3_Execution_Plan.pdf` — Phase 3 build order, required
  tests, Definition of Done, and the decision to defer the reverse proxy
  (supersedes the freeze doc's reverse-proxy section)
- `FirstFood_V2_Phase4_Checkpoint_Implementation_Plan.md` — Phase 4 checkpoint
  order (0-7), exit gates and test lists
- `FirstFood_V2_Phase4_Decision_Record.pdf` — **frozen Phase 4 decisions**
  (roles, permission boundary, ownership transfer, 403/404 behaviour); change
  only via an explicit, reviewed Phase 4 decision change
- `FirstFood_V2_Documentation_Reconciliation.md` — frozen-decision record
  (§3 above is a condensed version of this)

A **documentation reconciliation pass** was completed to align
`prd.md`/`architecture.md`/`phases.md`/`design.md` with the frozen
decisions in §3 (removing the stray "Policy Snapshot" as a separate
entity wherever it appeared as a conceptual diagram node).

---

## 7. Phase Progress

Per `phases.md`:

- [x] **Phase 0 — Product & Architecture Freeze**: PRD, domain model,
      ERD, architecture.md, rules.md, phases.md exist; the four
      documentation conflicts above have been identified and frozen.
- [~] **Phase 1 — Project Foundation**: scaffold built, then hardened
      against an external Phase 1 review (`FirstFood_V2_Phase1_Review.md`,
      Sept 10 2026). Fixed: explicit Spring Security config (public
      `/api/v1/version` + `/actuator/health/**`, everything else denied
      pending Phase 2 auth), a real Redis integration test (Testcontainers
      Valkey + read/write, not just config), JWT secret now fails startup
      if unset outside local/test profiles, `npm ci` in Docker/CI (needs
      the committed `package-lock.json`), `.dockerignore` for both apps,
      frontend error boundary no longer renders raw `error.message`.
      Postgres 17 was already consistent between Testcontainers/Compose/
      prod. Maven Wrapper has since been added and documented; the
      Next.js API URL strategy is documented but still an open decision - see below.
- [x] **Phase 2 — Identity & Authentication**: implemented in
      `com.firstfood.identity`. Mobile OTP login (auto-provisions
      `UserAccount` on first successful login - no separate signup step),
      OTP requests rate-limited via Redis (fixed window,
      `app.otp.request-rate-limit-per-hour`), OTPs hashed with BCrypt and
      never logged/stored in plaintext, JWT access tokens (short-lived,
      `app.jwt.access-token-ttl-minutes`) + opaque revocable refresh
      sessions (rotate-on-use, hashed with SHA-256 at rest - see
      `RefreshTokenHasher` javadoc for why that's a deliberately different
      hash strategy than OTPs), `GET/PATCH /api/v1/me`, and a full
      OTP-gated phone-number-change flow (rules.md Rule 3.3). Explicit
      Spring Security `AuthenticationEntryPoint` added so unauthenticated
      requests return 401, not Spring Security's 403-by-default (a common
      gotcha caused by `AnonymousAuthenticationFilter`). New Flyway
      migration `V2__identity_schema.sql`. `AuthenticationFlowIntegrationTest`
      exercises the full phases.md exit-criteria flow (request → verify →
      authenticate → protected API → refresh → logout) plus rejection
      cases, using a `TestCapturingOtpSender` so tests can read the code
      that would otherwise go out over SMS. `BrevoSmsOtpSender` is written
      against Brevo's published Transactional SMS API contract but has
      **not** been exercised against a real account - verify with a real
      API key before trusting it in production.
- [x] **Phase 2 hardening pass** (post `FirstFood_V2_Phase2_Final_Review.md`,
      13 Sept 2026 - see that review for full detail on each finding):
      - **Suspended-account enforcement (was 🔴 MUST FIX)**: `JwtAuthenticationFilter`
        now loads the account per request and requires `AccountStatus.ACTIVE`,
        not just a valid/unexpired JWT signature. `AuthenticationServiceImpl.refresh()`
        checks the same thing before issuing a new token pair - a suspended
        account's refresh token still gets consumed/revoked in the attempt
        (can't be replayed), it just never receives a working pair back.
      - **CORS (was 🔴 blocking any real browser frontend)**: explicit
        `CorsConfigurationSource` in `SecurityConfig`. Origins come from
        `app.cors.allowed-origins` - defaults to `http://localhost:3000`
        locally/test, **required with no default in production** (same
        fail-fast pattern as `JWT_SECRET`) via `CORS_ALLOWED_ORIGINS`.
      - **Refresh rotation concurrency (was 🟠 HIGH)**: added
        `RefreshSessionRepository.findByRefreshTokenHashForUpdate` (`@Lock(PESSIMISTIC_WRITE)`,
        i.e. `SELECT ... FOR UPDATE`); `rotate()`/`revoke()` now use it
        instead of the plain lookup, closing the race where two concurrent
        refresh calls on the same token could both see it as "still active"
        before either commit.
      - **Stale/missing account (medium)**: `MeController`/`AuthenticationServiceImpl`
        no longer throw a raw `IllegalStateException` (→ 500) when a JWT
        names an account that's vanished; new `AuthenticatedAccountNotFoundException`
        maps it to 401 instead.
      - **Maven Wrapper actually restored**: earlier "generate it yourself"
        guidance is moot now - `mvnw`/`mvnw.cmd`/`.mvn/wrapper/` are for
        real this time, fetched from Apache's own GitHub repo (this
        sandbox has GitHub access even without Maven Central access),
        version placeholders substituted, `distributionUrl`/`wrapperUrl`
        point at verified-current real releases (Maven 3.9.16, wrapper
        3.3.4). CI now runs `./mvnw` instead of a bare `mvn`. **Executable
        bit gotcha**: zip extraction and a fresh clone before the bit is
        committed can both lose it - `chmod +x backend/mvnw`, and if
        committing for the first time confirm it stuck
        (`git update-index --chmod=+x backend/mvnw` if needed).
      - **New tests**: suspended+JWT, suspended+refresh, OTP max-attempts
        exhaustion (5 wrong guesses expires the challenge, even the
        correct code fails after), OTP request rate limit, email update,
        full phone-change flow, phone-already-in-use rejection - all
        added to `AuthenticationFlowIntegrationTest`. OTP expiry and JWT
        expiry needed their own `ExpiryIntegrationTest` class instead,
        since they need near-zero TTL overrides (`app.otp.ttl-minutes=0`,
        `app.jwt.access-token-ttl-minutes=0`) that would otherwise affect
        every other test sharing that Spring context.
      - **Frontend Phase 2 UI (was 🔴 entirely missing - just the Phase 1
        placeholder)**: built the actual phone → OTP → authenticated-
        screen flow (`app/page.tsx` + `components/auth/*`), covering
        every endpoint (login, refresh, logout, email update, phone
        change). Visual language is a "ledger/attendance register" -
        grounded in the actual domain (paper attendance registers, see
        prd.md) rather than a generic template. `lib/auth-api.ts` adds
        typed wrappers per identity endpoint. **Tokens are kept in React
        state only, never localStorage** - lost on page refresh by
        design for Phase 2 (review #23's explicit recommendation); the
        production hardening plan (move the refresh token to a Secure +
        HttpOnly + SameSite cookie) is noted on-screen in the UI itself
        and belongs on the Phase 3+ backlog, not deferred silently (Phase 3's provider
        pages reuse the same in-memory token; still open).
      - **Real bug found while wiring the frontend up**: `apiFetch` only
        treated HTTP 204 as "empty body" - but the OTP-request endpoints
        return **202 Accepted with an empty body** too, and `.json()` on
        an empty body throws. Fixed to check actual response content
        instead of hardcoding one status code as the only "no body" case.
      - **Still not done**: real Brevo SMS verification (still just
        written-against-the-docs, never called with a live account/key -
        same caveat as before, unchanged), and the CORS local default
        (`http://localhost:3000`) assumes the frontend stays on that port.
- [~] **Phase 3 — FoodProvider Management**: implemented (V3 schema,
      `provider` + `provideraccess` modules, 4 endpoints, provider pages,
      `ProviderIntegrationTest`). NOT yet closed: the backend was written
      without Maven Central access, so it still needs its first
      `./mvnw clean verify` (new tests + all Phase 1/2 tests green) before
      this can be ticked. Details in §9.
- [~] **Phase 4 — Provider Roles & Access Control**: all checkpoints 0-7
      implemented in one pass (V4/V5 migrations, permission model, central
      `requirePermission`, role APIs, audit, `TeamRoles` UI, 3 new test
      classes). NOT yet closed: the backend was written without Maven Central
      or Docker, so it still needs its first `./mvnw clean verify` (new tests +
      Phase 1-3 tests green) before this can be ticked. Closure pass done (V6:
      `assigned_by` NOT NULL, final transfer-audit semantics, MANAGER operations
      test); only the green CI run remains. Details in §10.
- [~] **Phase 5 — Person & Provider Membership**: implemented (§11); pending the
      first `./mvnw clean verify` (same status as Phase 4).
- [~] **Phase 6 — Plans**: implemented, decisions FROZEN by the owner (§12, 4 Oct 2026); V9 adds DB guards for decisions 2-3. Pending the first `mvnw clean verify`.
- [~] **Phase 7 — Subscription Core**: implemented (§15): V10, `subscription` module, renewal, DAY/MEAL tracking fields, UI. V10 SQL verified on a real PostgreSQL 16; **Java not compiled or run** (no Maven Central/Docker). Pending the first `mvnw clean verify`.
- [~] **Phase 8 — Attendance & Absence**: implemented (§16): V11, `attendance` module, daily sheet, owner correction, customer declare/cancel, UI. V11 verified on PostgreSQL 16 (34 checks); Java not compiled or run. Pending the first `mvnw clean verify`.
- [ ] Phase 9 — Extension Engine
- [ ] Phase 10 — Subscription Lifecycle & Background Jobs
- [ ] Phase 11 — Pilot Hardening
- [ ] Phases 12+ — Reviews, Notifications, Production Stabilization, and
      future (Discovery, Payments, Ordering, Advertising, Analytics,
      Multi-Member) — not started, intentionally deferred.

### Resolved after the Phase 1 review

- **Maven Wrapper** — added via `mvn wrapper:wrapper` (self-generated
  rather than hand-authored here - see the reasoning that used to live in
  this section, now moot). `./mvnw` / `mvnw.cmd` are committed; README
  "Running locally" uses them instead of a bare `mvn`. Still optional in
  practice since the primary workflow is Docker (Maven runs inside the
  build container either way), but it's there now for anyone running the
  backend natively.

### Still open

- **Expired/consumed OTP row cleanup** — architecture.md #22 mentions
  "Cleanup of expired OTPs" as a background job; Phase 2 doesn't add a
  scheduler, so `otp_verification` rows accumulate indefinitely. Low risk
  short-term (the table is small, nothing reads old rows), but add a
  scheduled cleanup (or a Postgres partial index /TTL-style approach)
  before this sees real production traffic.

- **Next.js `NEXT_PUBLIC_*` build-time API URL** — now a live gap, not a
  hypothetical one: the Phase 2 frontend UI actually calls the API via
  `NEXT_PUBLIC_API_BASE_URL`, which gets baked into the browser bundle at
  `npm run build` time. Changing it at container runtime (as
  `infra/docker-compose.yml` currently does via an `environment:` entry)
  does NOT change already-built client code. Works fine for local dev
  (build and run happen together), but will silently serve a stale API
  URL the moment a built image gets deployed somewhere else. Full write-
  up and the three candidate strategies (build-time injection per
  environment, a runtime config endpoint, or same-origin `/api` +
  reverse proxy) live in README "Frontend API URL: build-time vs.
  runtime" - decide there before this goes anywhere beyond local Docker
  Compose, and update both README and this section with the outcome.
  **Phase 3 update:** the proxy/URL decision is *deliberately deferred*
  (Phase 3 Execution Plan) - keep the current setup and the central
  `api-client` boundary; revisit when there is a concrete HTTPS, routing,
  multi-instance or single-entry-point reason.
  Same applies to the CORS default: `application.yml`'s
  `app.cors.allowed-origins` assumes the frontend stays on
  `http://localhost:3000` locally - fine today, but the two decisions
  (API base URL strategy, CORS origin) should be made together since
  same-origin routing would make the CORS config moot entirely.

---

## 8. Environment Notes

- This sandbox's outbound network allowlist covers npm/PyPI/crates/GitHub
  domains but **not Maven Central** (`repo.maven.apache.org`), so the
  Spring Boot backend scaffold can be authored here but not
  `mvn install`-verified in this environment. Verify the build locally or
  in CI where Maven Central is reachable.
- Frontend (`npm`) dependencies *can* be resolved in this sandbox.

---

## 9. Phase 3 - FoodProvider (implemented, pending first CI run)

Source of truth: `FirstFood_V2_Phase3_Domain_Decision_Freeze.md` +
`FirstFood_V2_Phase3_Execution_Plan.pdf` (the plan supersedes the freeze doc's
reverse-proxy section: **proxy deferred**, current CORS/`NEXT_PUBLIC_API_BASE_URL`
setup stays until a real deployment reason appears).

- `V3__provider_schema.sql`: `food_provider` (type, address/locality/city,
  pincode, contact, status, `accepting_new_customers`, nullable
  `max_active_subscriptions`, closure audit fields) and
  `provider_role_assignment`. CHECKs: status/type/role enums, capacity >= 1,
  intake only when ACTIVE, closure metadata iff CLOSED. Unique
  `(provider_id, account_id, role)` + partial unique index for exactly one
  OWNER per provider (both reworked in V4, see §10). No cascades.
- Modules: `provideraccess` (IDs only, no dependency on the provider entity)
  owns role assignments + `ProviderAccessService`; `provider` owns FoodProvider
  and calls provideraccess. Direction is provider -> provideraccess only.
- Authorization (Phase 3 as built): no sufficient role and nonexistent provider
  both returned the same 404 `PROVIDER_NOT_FOUND`; all four endpoints required
  OWNER. **Superseded by Phase 4 (§10):** the rank-based `assertProviderRole` /
  `ProviderRole.satisfies` are gone, replaced by a permission matrix; members
  lacking a permission now get 403 (non-members still 404).
- PATCH quirks: null = unchanged, so `unlimitedCapacity: true` clears the
  limit; moving to ACTIVE defaults intake to true unless
  `acceptingNewCustomers` is sent; any PATCH on a CLOSED provider is 409.
- Definition of Done checklist (execution plan §10): [x] V3 schema, [x]
  provider lifecycle, [x] provider access control, [x] frontend provider flow
  (typecheck/lint/build pass), [x] docs, [ ] integration tests green, [ ] no
  Phase 1/2 regressions confirmed. The last two need a real Maven run.
- Not done on purpose (Phase 3): role-management endpoints (done in Phase 4), subscription
  capacity enforcement (later), reopening a CLOSED provider, reverse proxy.
- Added generic 400 handlers in `GlobalExceptionHandler` for malformed JSON /
  bad enum values and non-UUID path variables (previously would have been 500).
- **Verification status:** frontend typecheck/lint/build pass (run in this
  sandbox). Backend was authored without Maven Central access - it has NOT
  been compiled or run here. `ProviderIntegrationTest` (Testcontainers) and the
  existing Phase 1/2 tests must be run in CI/locally before Phase 3 is called
  done.

---

## 10. Phase 4 - Provider Roles & Access Control (implemented, pending first `mvnw clean verify`)

Source of truth: `FirstFood_V2_Phase4_Decision_Record.pdf` (frozen) +
`FirstFood_V2_Phase4_Checkpoint_Implementation_Plan.md`. Do not invent
authorization behaviour that is not in the record.

**Frozen decisions (as implemented)**
- Roles: `OWNER`, `MANAGER`, `WORKER`. One effective (ACTIVE) role per account
  per provider; exactly one ACTIVE OWNER per provider.
- Ownership moves only through an explicit transfer; the old OWNER becomes
  MANAGER in the same transaction. OWNER cannot self-revoke or be assigned via
  the role endpoint.
- Owners choose *who* holds a role; the permission set is fixed in code (owners
  never define permissions at runtime).
- Role assignment targets a **registered phone number** (lookup key only,
  never an authorization credential). Unregistered phone -> rejected.
- HTTP: 401 unauthenticated; 404 provider/resource missing **or caller is not a
  member** (existence hidden); 403 member lacking the permission.
- Actor always comes from the JWT; client-supplied actor/role is ignored.
  Provider A's roles never grant anything on provider B.

**Permission matrix** (`ProviderRole` is the only place it lives; asserted in
full by `ProviderRoleMatrixTest`)

| Permission | OWNER | MANAGER | WORKER |
|---|:-:|:-:|:-:|
| PROVIDER_VIEW | yes | yes | yes |
| PROVIDER_EDIT (profile, capacity, non-close status) | yes | yes | no |
| PROVIDER_CLOSE | yes | no | no |
| ROLE_VIEW | yes | yes | no |
| ROLE_ASSIGN / ROLE_REVOKE / OWNER_TRANSFER | yes | no | no |

WORKER is read-only; the decision record lists WORKER's "View provider" as
"Reserved / as later defined" - the implementation grants `PROVIDER_VIEW` only
(one-line change in `ProviderRole` + matrix test if that should be removed).
Operational permissions for MANAGER/WORKER are reserved for the phases that
build meals/attendance/subscriptions/etc.

**Database**
- `V4__provider_role_lifecycle.sql`: `provider_role_assignment` gains `status`
  (ACTIVE/REVOKED), `assigned_by`, `revoked_at`, `revoked_by` (+ CHECK tying
  revocation fields to status). `unique(provider_id, account_id, role)` was
  replaced by a partial unique index `(provider_id, account_id) WHERE
  status='ACTIVE'`; the one-OWNER index now also requires `status='ACTIVE'` so
  revoked history never blocks a new owner. Phase 3 rows backfilled
  (`assigned_by = account_id`). `assigned_by` was left nullable in V4 only for
  the Phase 3 test helper; **closed in V6:** it is now NOT NULL (V6 backfills
  any NULL with `account_id` first), the Phase 3 helper supplies it, and the
  entity constructor rejects null. Assignments are never deleted; a role
  change = revoke + new row.
- `V5__provider_role_audit.sql`: append-only `provider_role_audit` (trigger
  rejects UPDATE/DELETE). Actions: `ROLE_ASSIGNED`, `ROLE_REVOKED`,
  `OWNER_TRANSFERRED`. Ids only - no phone numbers/tokens.
  **Audit semantics (final; documented as table comments in V6): one row = one
  account's role change** - `target_account_id` went from `old_role` to
  `new_role`, performed by `actor_account_id`. A transfer changes two accounts,
  so it writes TWO `OWNER_TRANSFERRED` rows in one transaction: (target = new
  owner, old = its previous role or NULL, new = OWNER) and (target = former
  owner, old = OWNER, new = MANAGER). `created_at` can tie inside one
  transaction - look rows up by target, never by ordering.
- `V6__role_assignment_assigned_by_required_and_audit_semantics.sql`: the two
  closure changes. V4/V5 were deliberately not edited (Flyway checksums).

**Backend code**
- `provideraccess`: `ProviderPermission`, `ProviderRole` (matrix),
  `ProviderAccessService.requirePermission(accountId, providerId, permission)`
  (single authorization entry point; returns the caller's role),
  `ProviderRoleService` (`assignRole`, `revokeRole`, `transferOwnership`,
  `findRoles`, `hasRole`), `RoleAssignmentView`, `InsufficientPermissionException`
  (403), `RoleManagementException` (factories for the 400/404/409 cases),
  audit entity/repository, `web/ProviderRoleController` + `dto/*`.
- `identity.AccountLookupService`: narrow read-only lookup used by other
  modules (phone -> ACTIVE account id with normalization; id -> phone for team
  listings). Other modules must not use `UserAccountRepository` directly.
- `provider.ProviderServiceImpl` now uses permissions: view = PROVIDER_VIEW;
  edit = PROVIDER_EDIT; setting `status: CLOSED` additionally needs
  PROVIDER_CLOSE; list filters by PROVIDER_VIEW. `ProviderResponse` gained
  `myPermissions`.
- Endpoints (`/api/v1/providers/{id}`): `GET /roles`, `POST /roles {phone, role}`
  (201), `DELETE /roles/{assignmentId}` (returns the revoked view),
  `POST /ownership/transfer {phone}` (returns [new owner, former owner]).
- Error codes added: `INSUFFICIENT_PERMISSION`, `ROLE_INVALID`,
  `OWNER_ASSIGNMENT_NOT_ALLOWED`, `TARGET_ACCOUNT_NOT_FOUND` (same for unknown,
  malformed and suspended numbers), `ROLE_ALREADY_ASSIGNED`,
  `ROLE_ASSIGNMENT_NOT_FOUND`, `ROLE_ALREADY_REVOKED`,
  `OWNER_REVOCATION_NOT_ALLOWED`, `OWNERSHIP_TRANSFER_TO_SELF`; closed
  providers reuse `PROVIDER_CLOSED` (409).

**Gotchas worth remembering**
- **Transfer order matters.** The partial unique indexes are checked per
  statement and cannot be deferred, and Hibernate runs INSERTs before UPDATEs
  at flush. `transferOwnership` therefore flushes each step explicitly: revoke
  old OWNER -> revoke target's prior role -> insert new OWNER -> insert old
  owner as MANAGER. "Zero owners" exists only inside the uncommitted transaction.
- **Concurrency.** Every role mutation first runs `select status from
  food_provider where id=? for update` (native query in
  `ProviderRoleAssignmentRepository`, so `provideraccess` stays free of the
  provider entity). The lock serializes mutations; after waiting, READ
  COMMITTED re-reads fresh state (e.g. the loser of two concurrent transfers
  sees itself as MANAGER -> 403). The partial indexes remain the last defence.
- Role mutations and transfers on CLOSED providers -> 409 `PROVIDER_CLOSED`;
  listing the team is still allowed.
- Revocation takes effect immediately because JWTs carry identity only; roles
  are read from the database on every request.
- `JwtAuthenticationFilter` + SUSPENDED accounts holding a role was not
  re-verified for Phase 4 (Phase 2 hardening covers suspended accounts at the
  filter); worth one explicit test.

**Frontend**: `lib/role-api.ts` (one typed function per endpoint),
`components/provider/TeamRoles.tsx` (team list with phones, add member,
remove, transfer with confirmation), wired into `ProviderDetail`; edit/close
sections are shown from `provider.myPermissions` (a usability hint only - the
backend re-checks everything). After a transfer the provider list is reloaded
so the caller's new role/permissions show.

**Tests**: `ProviderRoleMatrixTest` (unit, every role x permission),
`ProviderRoleServiceIntegrationTest` (16, service-level lifecycle incl. the
`assigned_by` NOT NULL constraint),
`ProviderRoleApiIntegrationTest` (HTTP: 401 incl. expired JWT, end-to-end flow,
WORKER read-only, **MANAGER operations** - may edit profile, capacity,
non-close status and intake, and view roles; may NOT close (also when bundled
with an allowed edit), assign, revoke or transfer, and nothing denied takes
effect -, validation, provider isolation, ignored client-supplied actor/role,
transfer atomicity + two-row audit, invalid transfers, final-owner/closed
rules, 3 concurrency cases). Phase 4 tests use phones `+91987652xxxx` and
`+91987653xxxx` (shared singleton DB - do not reuse; Phase 3 uses
`...650xxxx`/`...651xxxx`).

**CI**: `.github/workflows/ci.yml` previously ran pushes only for the Phase 3
branch (plus all PRs); it now runs on pushes to every branch, so the Phase 4
branch gets `./mvnw -B clean verify` plus frontend typecheck/lint/build.

**Phase 4 closure checklist**
- [x] `assigned_by` NOT NULL (V6, entity, Phase 3 helper, DB test)
- [x] Transfer audit semantics clarified and tested (two rows, V6 comments)
- [x] MANAGER operation test added
- [ ] Clean CI run: `./mvnw -B clean verify` green, including the unchanged
      Phase 3 `ProviderIntegrationTest`. Needs a real Maven/Docker environment
      (not available where this was written). Phase 4 is CLOSED when it passes.

**Checkpoint status vs. the plan**: 0 decision freeze done; 1-7 implemented in
a single pass (the plan recommends one commit per checkpoint - not done, the
upload had no git repository). Not covered: expiry of access tokens mid-flow in
the UI beyond the existing "refresh your session" message; pending-invite flow
for unregistered phones (rejected by decision, not built).

**Verification status:** frontend `tsc`/`eslint`/`next build` pass (run in this
sandbox). Backend was authored without Maven Central or Docker: a plain
`javac` pass over all sources (re-run after the closure pass) showed no syntax errors and no unresolved symbols
in project code, but nothing was compiled against real dependencies and **no
test has been run**. Definition of Done still open: [ ] `./mvnw clean verify`
green (new tests + Phase 1-3 regressions).

---

## 11. Phase 5 - Person & Provider Membership (implemented, pending first `mvnw clean verify`)

Scope = `phases.md` §8: `Person`, `ProviderMembership`, add / list / deactivate
customers, rejoin with history kept, and the owner/manager/worker access rules.
**Not** in this phase (and not built): subscriptions, plans, attendance, billing,
invites for unregistered phones, customer self-join.

**New code**: `com.firstfood.membership` (entities `Person`, `ProviderMembership`;
services `PersonService`, `MembershipService`; package-private `PersonProvisioner`;
`CustomerController` at `/api/v1/providers/{pid}/customers`, `MePersonController` at
`/api/v1/me/{person,memberships}`), migration `V7__person_and_provider_membership.sql`,
and in the provider module a narrow `ProviderLookupService` (`lockForIntake`,
`findNames`) so membership never touches `FoodProvider` or its repository.
Dependency direction: `membership -> provider, provideraccess, identity` (no cycles).

**Decisions taken in this phase (review these - the docs did not fix them):**

1. **One membership row per stint, not one row per (provider, person).**
   design.md lists a `MembershipReactivated` event and `joinedAt/leftAt`, which
   suggests flipping one row back to ACTIVE - but that overwrites `leftAt` and
   loses the earlier period, contradicting "historical membership is retained"
   and rules.md 15.3. So leaving ends the row (INACTIVE + `left_at`/`left_by`) and
   **rejoining inserts a new row**. Uniqueness is a partial index: one ACTIVE row per
   (provider, person). If Subscription (Phase 7) later prefers a stable membership id
   across rejoins, change this *before* Phase 7 - it is cheap now, expensive later.
2. **Person is not unique per account.** `person.user_account_id` is NOT unique
   (rules.md 3.2). The MVP "one account = one person" behaviour is only a partial
   unique index on `is_primary`, which does not limit how many persons exist.
   A test inserts a second non-primary person to prove the schema allows it.
3. **Customers are added by registered phone** (same stance as role assignment in
   Phase 4: lookup only, unknown/malformed/suspended all return the identical 404
   `TARGET_ACCOUNT_NOT_FOUND`). A first-time customer's person is created with the
   name the provider types; an **existing person is never renamed by a provider**
   (name is ignored if a person exists; 400 `PERSON_NAME_REQUIRED` if one is needed
   and missing). The customer can rename themselves via `PUT /me/person`.
4. **Permissions**: new `MEMBERSHIP_VIEW` (OWNER, MANAGER, WORKER) and
   `MEMBERSHIP_MANAGE` (OWNER, MANAGER). WORKER gets view because attendance
   (a later phase) needs the customer list; this follows PRD §9 but the PRD does
   not spell the WORKER grant out - frozen phase 5 decision. Matrix test updated deliberately.
5. **Provider state**: adding requires provider not CLOSED and
   `accepting_new_customers = true` (409 `PROVIDER_CLOSED` /
   `PROVIDER_NOT_ACCEPTING_CUSTOMERS`). Deactivating is allowed while not accepting
   but blocked on CLOSED (read-only, like roles). Listing/reading always works.
   Capacity (`max_active_subscriptions`) is a *subscription* rule (Phase 7), not
   enforced here.
6. **Authorize before lock**: `requirePermission` runs before the provider row lock
   so an outsider can never hold or contend for a provider's lock. (Phase 4 locks
   first; that is harmless but this ordering is stricter.)
7. **Race-safe person provisioning**: `insert ... on conflict (user_account_id)
   where is_primary do nothing`, then re-read. A plain INSERT + catch would abort
   the PostgreSQL transaction when two providers add the same new customer at once.
8. **Person lives in the `membership` module** (architecture.md lists no `person`
   module). Move it if a profile module is ever introduced.
9. **No customer consent/notification yet**: an owner can add any registered phone
   as a customer. That mirrors Phase 4 role assignment and the registered-phone
   disclosure it already accepted, but a customer-visible accept/notify step belongs
   with the notifications work - flagged, not built.

**API summary**
- `GET  /providers/{pid}/customers[?includeInactive=true]` - MEMBERSHIP_VIEW
- `POST /providers/{pid}/customers {phone, fullName?}` - MEMBERSHIP_MANAGE, 201
- `GET  /providers/{pid}/customers/{membershipId}` - MEMBERSHIP_VIEW
- `POST /providers/{pid}/customers/{membershipId}/deactivate` - MEMBERSHIP_MANAGE
- `GET|PUT /me/person`, `GET /me/memberships` - the caller's own, JWT-scoped
Membership ids are always resolved *within* the path provider (cross-provider id =
404 `MEMBERSHIP_NOT_FOUND`, no IDOR). The actor is always the JWT principal.

**Frontend**: `lib/membership-api.ts`; `CustomersPanel` inside `ProviderDetail`
(list, show former, add, remove - controls from `myPermissions`, server re-checks);
new "As a customer" tab (`MyMemberships`: own name + own memberships).

**Tests**: `MembershipApiIntegrationTest` (17 cases: 401s, add/list/get, duplicate,
unregistered/malformed phone, name rules, leave + rejoin history, multi-provider,
manager/worker/outsider matrix, cross-provider isolation, not-accepting, closed,
`/me/person`, person reuse, DB-level multi-person + constraint checks, 2 concurrency
cases). Uses phones `+91987654xxxx` (do not reuse). Updated on purpose:
`ProviderRoleMatrixTest` and two permission-set assertions in
`ProviderRoleApiIntegrationTest` (Phase 4 file; new permissions change the exact sets).
`ProviderIntegrationTest` (Phase 3) is untouched.

**Verification status (be honest about this)**
- [x] All 7 migrations V1-V7 applied in order on a real PostgreSQL engine (PGlite/WASM)
      and the new constraints exercised directly: single primary per account while
      allowing more persons, one ACTIVE membership per (provider, person), lifecycle
      CHECK, rejoin as a new row, same person ACTIVE at two providers, no-cascade FKs,
      and the exact `ON CONFLICT` upsert the repository issues.
- [x] Frontend `tsc --noEmit`, `eslint .` and `next build` pass.
- [x] Backend: `javac` over all sources with no dependency jars showed no unresolved
      project classes or methods across modules (only external-jar noise). This is a
      wiring check, **not** a real compile.
- [x] V9 (4 Oct 2026) applied after V1-V8 on a real PostgreSQL 16 server: price 0 and
      negative rejected; MEAL + `extension_allowed` rejected; DAY + extension, and MEAL
      without extension, accepted; cutoff without same-day rejected; `consumption_type`,
      `provider_id` and `currency` updates rejected; ordinary price/name/status edits still
      work. `PlanApiIntegrationTest` gained `planIdentityColumnsCannotChangeInTheDatabase`
      and `extensionOnAMealPlanIsRejectedByTheDatabase` (written, not executed).
- [ ] **No Java test has been run** (no Maven Central / Docker here). The Spring
      wiring, JPA mapping (`primaryPerson` <-> `is_primary`, derived queries), native
      query execution and all 17 integration tests are unverified until
      `./mvnw -B clean verify` runs. Phase 5 is CLOSED when it is green, including the
      unchanged Phase 3 `ProviderIntegrationTest`.

---

## 12. Phase 6 - Plans (implemented, decisions frozen, pending first `mvnw clean verify`)

**Scope (phases.md §9):** `Plan` + `SubscriptionPolicy`, DAY/MEAL consumption types,
plan activation/deactivation, per-provider isolation. Nothing in Phase 7+ (no
subscription, snapshot, attendance, extension logic) was built.

**Delivered**
- `V8__plan_and_subscription_policy.sql`: `plan`, `subscription_policy`, CHECKs mirroring
  the domain rules, partial unique index (active name per provider), immutability trigger.
- Module `com.firstfood.plan` (flat layout like the other modules): `Plan`,
  `SubscriptionPolicy`, `PolicyTerms` (value object), `PlanRules` (pure cross-field
  validation), `PlanService(Impl)`, `PlanException`, `PlanView`, `dto/*`, `web/PlanController`.
- Permissions `PLAN_VIEW`, `PLAN_MANAGE` added to `ProviderPermission`/`ProviderRole`.
- Frontend: `lib/plan-api.ts`, `components/provider/PlansPanel.tsx` (wired into
  `ProviderDetail`), `PLAN_*` in the permission type, small CSS additions.
- Tests: `PlanRulesTest` (unit), `PlanApiIntegrationTest` (HTTP + DB + concurrency);
  `ProviderRoleMatrixTest` and `ProviderRoleApiIntegrationTest` updated for the new matrix.

**API** (`/api/v1/providers/{providerId}/plans`): `GET` (`?includeInactive=`), `POST`,
`GET /{planId}`, `PUT /{planId}` (full replacement), `POST /{planId}/activate`,
`POST /{planId}/deactivate`. **No DELETE** (Rule 15.3). Error codes: `PLAN_NOT_FOUND` 404,
`PLAN_TERMS_INVALID` / `PLAN_POLICY_INVALID` 400, `PLAN_NAME_IN_USE` /
`PLAN_ALREADY_ACTIVE` / `PLAN_ALREADY_INACTIVE` / `PROVIDER_CLOSED` 409.

**FROZEN Phase 6 decisions (owner-confirmed 4 Oct 2026 - implement, do not reopen)**

| # | Decision | Enforced by |
|---|---|---|
| 1 | `OWNER` = `PLAN_VIEW`+`PLAN_MANAGE`; `MANAGER` = `PLAN_VIEW` only; `WORKER` = none. Only OWNER creates/updates/activates/deactivates plans (pricing + commercial terms). | `ProviderRole`, `ProviderRoleMatrixTest`, `requirePermission` in every `PlanServiceImpl` method |
| 2 | `extensionAllowed=true` only for `DAY` plans; a `MEAL` plan with it is rejected. DAY = time entitlement (absence extends time); MEAL = fixed quantity (absence does not extend it). | `PlanRules`, **V9 trigger** `subscription_policy_extension_day_only` |
| 3 | Consumption type is immutable after creation; changing it = new plan. | `Plan` (`updatable=false`), no `consumptionType` on `UpdatePlanRequest`, **V9 trigger** `plan_identity_no_change` |
| 4 | `price > 0` (zero/negative rejected). Relaxing needs a deliberate migration with a strategy once real subscription data exists. | `@DecimalMin("0.01")`, `plan_price_positive` CHECK |
| 5 | `absenceCutoffTime` only when `sameDayAbsenceAllowed=true`; stored as a plain `LocalTime` (no timezone). The provider timezone convention is decided before the phase that uses the cutoff in date/time calculations (Phase 8). **No timezone infrastructure in Phase 6.** | `PlanRules`, `subscription_policy_cutoff_check` CHECK, `time` column |

The rationale notes below are kept for context; items 2-4, 6 and 7 are the frozen
decisions above, the rest are design choices made while implementing.

**Design choices made while implementing**
1. **Policy versioning (resolves the "SubscriptionPolicy version ownership/uniqueness"
   open item).** A policy row belongs to one plan, is immutable (DB trigger rejects
   UPDATE/DELETE), and is identified by `(plan_id, version)`, version 1,2,3... The
   *current* policy is the highest version. Editing a policy inserts version N+1; an
   unchanged policy creates no version. Phase 7 copies the terms plus this
   `policy_version` into `subscription_term_snapshot` and never reads the plan later.
2. **Permissions:** `PLAN_MANAGE` is OWNER-only (plans carry pricing). MANAGER gets
   `PLAN_VIEW` only (needed to choose a plan in Phase 7). WORKER gets nothing. PRD §9 only
   said MANAGER "possibly plans"; widening is a one-line change in `ProviderRole` + the
   matrix test.
3. **Consumption type is immutable** after creation (a different type is a different
   plan). `PUT` has no `consumptionType`.
4. **Extension applies to DAY plans only** (`extensionAllowed=true` on MEAL is rejected):
   absence does not consume a MEAL quantity, so there is nothing to extend. Loosening
   later is easy; tightening once data exists is not.
5. **Field meaning rules (Rule 7.4):** extension on => `minConsecutiveAbsenceDays`
   required, off => must be empty; same-day absence off => no cutoff; DAY
   `maxCalendarWindowDays` needs extension and must be >= `durationDays` (equal is
   allowed); MEAL window is an independent optional limit.
6. **Same-day cutoff** is only meaningful when same-day absence is allowed; null cutoff =
   no time limit. Stored as provider-local wall-clock `time` (no timezone) - the timezone
   convention is still a later, explicit decision (Phase 8 must define it).
7. **Price must be > 0**, `numeric(10,2)`, currency fixed to `INR` (column exists so the
   snapshot can copy it). Zero/free plans can be allowed later with a trivial migration.
8. **Plan names are unique per provider among ACTIVE plans** (case-insensitive, trimmed);
   INACTIVE plans keep their name without blocking reuse; reactivating into a taken name
   is `PLAN_NAME_IN_USE`.
9. **Closed provider = plans read-only** (same as roles/customers). All plan mutations
   take the provider row lock (`lockForIntake`), which also serializes policy version
   numbering. Authorization happens before the lock.
10. **Not built on purpose:** plan price history (past prices live in subscription
    snapshots), a `PlanLookupService` for Phase 7 (add when Phase 7 needs it), domain
    events for plan changes, listing plans to customers.

**Bug caught by verification:** the first draft of the `min_consecutive_absence_days`
CHECK let "extension on, minimum NULL" through, because `NULL between 1 and 1000` is NULL
and a CHECK treats NULL as passing. Fixed with an explicit `is not null`. Lesson: in
CHECKs, never rely on a comparison to reject a NULL.

**Verification status (be honest about this)**
- [x] All 8 migrations V1-V8 applied in order on a real PostgreSQL engine (PGlite/WASM);
      36 direct checks on the V8 constraints, unique index, immutability trigger and the
      "current policy" query shape pass.
- [x] Frontend `tsc --noEmit`, `eslint .` and `next build` pass.
- [x] Backend: `javac` over all sources without dependency jars shows no unresolved
      project classes or methods (only external-jar noise). A wiring check, **not** a
      real compile.
- [ ] **No Java test has been run** (no Maven Central / Docker here). Spring wiring, JPA
      mapping (`LocalTime`<->`time`, `BigDecimal`, the `findCurrentForPlans` JPQL), Jackson
      handling of `LocalTime`, and all new integration tests are unverified until
      `./mvnw -B clean verify` runs. Phase 6 is CLOSED when it is green, including the
      unchanged Phase 3 `ProviderIntegrationTest`.
- New tests use phones `+91987655xxxx`; do not reuse that prefix.

---

## 13. Next Action

1. Run `./mvnw -B clean verify` locally/CI and fix whatever the backend compile or the
   new/old integration tests surface (Phases 3-7 have never run on a JVM build).
   Phase 7 priority: `SubscriptionApiIntegrationTest` (concurrency, lapsed-subscription
   backdating via JdbcTemplate) and the two edited role tests.
2. Confirm the Phase 7 decisions in §15 (esp. D2 overlap rule, D5 renewal start, D9 who may sell).
3. Commit/split the Phase 4-7 work; tick Phases 3-7 in §7 once green.
4. Confirm the Phase 8 decisions in §16 (esp. A3 provider-set days, A5 staff not bound by cutoff, A7 WORKER permissions).
5. Then Phase 9 (Extension Engine). Reverse proxy stays a later step.

---

## 14. Database Scalability Strategy (frozen 4 Oct 2026)

**Decision: start with PostgreSQL. Introduce hybrid/polyglot persistence only when
concrete scalability or workload requirements justify the extra operational complexity.**

- FirstFood uses ONE primary relational database (Neon PostgreSQL) as the source of
  truth. No additional databases are added prematurely, and none is added merely because
  the project is expected to scale.
- PostgreSQL stays the authoritative store of transactional business data unless a
  future architectural decision explicitly changes that.
- A new database or specialised store needs a **demonstrated need** backed by measured
  bottlenecks, not premature optimisation. Qualifying triggers:
  - read/write load the primary cannot handle efficiently
  - caching needs that justify Redis or another in-memory store
  - search needs that justify a dedicated search engine
  - analytics/reporting that should be separated from transactional queries
  - very large or high-volume datasets needing specialised storage
  - workload isolation between transactional and non-transactional operations
  - availability/scaling needs the primary cannot reasonably meet
  - an access pattern where another technology gives a substantial advantage
- Evolution is incremental and workload-specific:

```
Current                         Future, when justified
PostgreSQL (source of truth)    PostgreSQL (transactional source of truth)
                                   |-- Redis (cache)
                                   |-- Search store
                                   |-- Analytics store
```

- Consistent with, and does not change: Rule 19.1 (PostgreSQL is the source of truth),
  Rule 19.2 / architecture §22 (Redis is cache/ephemeral only, never business history —
  Upstash Redis is already in the stack for exactly that role), Rule 27.1 and Phase 21
  (Scaling Review: keep the modular monolith unless a concrete bottleneck is measured).
- Practical consequence today: **no code or schema change.** Any future store is fed from
  PostgreSQL (e.g. via the outbox in design.md §24), so history is never split across
  stores.

---

## 15. Phase 7 - Subscription Core (implemented, pending first `mvnw clean verify`)

**Scope (phases.md §10):** `Subscription` + `SubscriptionTermSnapshot` (policy terms merged
into the snapshot, frozen decision 1; exactly one per subscription, decision 2), creation
Person -> Membership -> Plan -> Subscription -> Snapshot, lifecycle, renewal as a new
commercial event, DAY/MEAL tracking fields. Excluded: attendance, absence, extension,
background jobs, payments.

**Schema (V10):** `subscription`, `subscription_term_snapshot`; composite FKs keep
provider/person/plan consistent; exclusion constraint forbids overlapping ACTIVE date
ranges per membership; unique `renewed_from_subscription_id` makes renewal idempotent;
immutability triggers (snapshot fully, subscription identity columns, no deletes);
deferred constraint trigger requires a matching snapshot (base expiry = start + days - 1,
meals match). Needs `btree_gist`.

**API:** `/api/v1/providers/{id}/subscriptions` (list, create, get, `/cancel`, `/renew`),
`/api/v1/subscriptions` (customer's own, read-only). New permissions SUBSCRIPTION_VIEW and
SUBSCRIPTION_MANAGE: OWNER and MANAGER get both, WORKER neither. Price/terms are never
client input. Authorization precedes the provider row lock.

**Decisions to confirm:**
- D1 Days are counted inclusively (30 days from 1 Sep ends 30 Sep).
- D2 One ACTIVE subscription per membership per date range (overlap -> 409).
- D3 Phase (UPCOMING/RUNNING/ENDED/CANCELLED) is derived from dates in `app.business.time-zone` (default Asia/Kolkata); no job exists, so storage may say ACTIVE for a lapsed subscription.
- D4 Renewal requires phase ENDED, no existing successor, an ACTIVE membership, a sellable plan; it snapshots today's price/policy. A lapsed stored-ACTIVE row is expired first.
- D5 DAY renewal must start after the previous effective expiry; default start is today.
- D6 Start date window: up to 366 days back, 90 ahead; must not already be over.
- D7 MEAL without a calendar window has null expiry (open-ended until meals are used).
- D8 Capacity (`max_active_subscriptions`) counts non-ended ACTIVE subscriptions.
- D9 MANAGER may sell and cancel.
- D10 Cancel only RUNNING/UPCOMING; final, with optional reason.
- D11 Deactivating a membership with a live subscription is refused (409) via `MembershipDeactivationGuard`.
- D12 Customers see their own subscriptions across providers, without other customers' data.
- D13 Cross-module access only through `PlanLookupService` and `MembershipLookupService`.
- D14 `ProviderIntake` now carries `maxActiveSubscriptions`.

**Phase 9 caveat:** `effective_expiry_date` and `remaining_meals` are mutable only while
ACTIVE; extension must respect `maximumExpiryDate` counted from the original start.

**Verification status (honest):** V10 exercised on PostgreSQL 16 (~30 constraint
scenarios, incl. deferred trigger inside transactions). `SubscriptionRules` run against a
stubbed `HttpStatus` (17 checks). Frontend `tsc`, `eslint`, `next build` pass. The Java
backend was **not compiled** and no integration test was run. Test phones: `+91987656xxxx`.

---

## 16. Phase 8 - Attendance & Absence (implemented, pending first `mvnw clean verify`)

**Scope (phases.md §11):** `AttendanceRecord`, `AbsenceRecord`, declare/cancel absence, owner correction, policy-based
same-day behaviour, structured sources (SYSTEM / CUSTOMER / OWNER_CORRECTION). No extension logic (Phase 9), no jobs.

**Model:** opt-out. `absence_record` = declaration (immutable; ends CANCELLED or OVERRIDDEN, never edited/deleted).
`attendance_record` = append-only ledger, exactly one `is_current` row per (subscription, date); a change supersedes it
with a new row (`supersedes_id`). A DECLARED absence == a current ABSENT row (deferred constraint triggers at commit).
**DAY only:** the tables are pinned to `consumption_type='DAY'` by composite FK - MEAL granularity stays the open
decision (§3); MEAL subscriptions get 409 `ATTENDANCE_NOT_SUPPORTED`.

**Decisions to confirm:**
- A1 Timezone convention (Phase 6 deferral resolved): cutoff is a wall-clock `LocalTime` read in `app.business.time-zone`
  (`BusinessCalendar.timeOfDay()`); per-provider zone is a later change.
- A2 Customer rules: past days never changeable; future days always; today only if the snapshot's `sameDayAbsenceAllowed`
  and now <= cutoff (cutoff instant still in time). Same rule for declaring and cancelling.
- A3 A day whose current row is OWNER_CORRECTION is locked to staff (customer cancel/re-declare -> 409
  `ATTENDANCE_LOCKED_BY_PROVIDER`), including after an OVERRIDE. Declaring an already-absent day is an idempotent 200.
- A4 Declare ranges are all-or-nothing, max 31 days; retried/overlapping requests return existing absences.
- A5 Staff (OWNER/MANAGER/WORKER) are not bound by cutoff or past dates but must give a reason, only within
  [start, effective expiry], never on a CANCELLED subscription. Repeating a correction is a no-op.
- A6 A cancelled subscription is not entitled on the day it was cancelled (daily sheet excludes it from that day).
- A7 ATTENDANCE_VIEW/MANAGE go to OWNER, MANAGER and WORKER (PRD §9). Views carry no price/phone. Matrix test updated.
- A8 Locking: every write takes the subscription row lock; order is provider row -> subscription row. Phase 7 `cancel`
  now also locks the subscription row. Attendance only reads provider state (`ProviderLookupService.intakeOf`, new,
  non-locking) so customers of one provider never queue behind each other; closed provider -> 409 `PROVIDER_CLOSED`.
- A9 Staff history exposes actor account ids (no phones); customer views never expose staff ids.

**API:** see README Phase 8. **Frontend:** `lib/attendance-api.ts`, `AttendancePanel` (daily sheet, correct, history) in
`ProviderDetail`, `MyAttendance` inside `MySubscriptions`. Server's `changeable`/`lockedReason` drive the UI.
**Tests:** `AttendanceRulesTest` (unit), `AttendanceApiIntegrationTest` (~30 cases incl. 4 concurrency; phones
`+91987657xxxx`); `ProviderRoleMatrixTest` + two sets in `ProviderRoleApiIntegrationTest` updated.

**Verification (honest):** V1-V11 applied on a fresh PostgreSQL 16; `v11` checks 34/34 pass. `AttendanceRules` logic run
against a stubbed `HttpStatus` (all pass). Frontend `tsc`, `eslint`, `next build` pass. `javac` over main/test sources shows
no unresolved project symbols (external-jar noise only) - not a real compile. **No Java test has been run**; the
Hibernate mapping (`currentRow`/`is_current`, merge-on-assigned-id), JPQL (`findDayEntitledOn`) and the integration tests
are unverified until `./mvnw -B clean verify`. Midnight caveat: cutoff tests use 00:00 / 23:59:59.

