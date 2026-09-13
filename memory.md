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
- [ ] Phase 3 — FoodProvider Management
- [ ] Phase 4 — Provider Roles & Access Control
- [ ] Phase 5 — Person & Provider Membership
- [ ] Phase 6 — Plans
- [ ] Phase 7 — Subscription Core
- [ ] Phase 8 — Attendance & Absence
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

- **Next.js `NEXT_PUBLIC_*` build-time API URL** — real risk now that
  Phase 2 exists to call: `NEXT_PUBLIC_API_BASE_URL` gets baked into the
  browser bundle at `npm run build` time, so changing it at container
  runtime (as `infra/docker-compose.yml` currently does via an
  `environment:` entry) won't actually change already-built client code.
  Full write-up and the three candidate strategies (build-time injection
  per environment, a runtime config endpoint, or same-origin `/api` +
  reverse proxy) live in README "Frontend API URL: build-time vs.
  runtime" - decide there before Phase 3's frontend work adds a real
  client-side API call, and update both README and this section with the
  outcome.

---

## 8. Environment Notes

- This sandbox's outbound network allowlist covers npm/PyPI/crates/GitHub
  domains but **not Maven Central** (`repo.maven.apache.org`), so the
  Spring Boot backend scaffold can be authored here but not
  `mvn install`-verified in this environment. Verify the build locally or
  in CI where Maven Central is reachable.
- Frontend (`npm`) dependencies *can* be resolved in this sandbox.

---

## 9. Next Action

Continue Phase 1 exit criteria: confirm backend starts, frontend starts,
DB connection + migrations work, Redis connects, health checks respond,
Docker build succeeds, CI can build/test — then move to Phase 2
(Identity & Authentication).
