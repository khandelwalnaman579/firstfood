# FirstFood V2

Greenfield rebuild of FirstFood. See `memory.md` for the full project
context, frozen design decisions, and phase progress before making
changes.

## Repo layout

```text
backend/    Spring Boot modular monolith (com.firstfood, Java 25)
frontend/   Next.js + React + TypeScript
infra/      docker-compose for local Postgres + Valkey (Redis-compatible)
            + both apps
memory.md   Project memory - read this first
```

## Status: Phase 1 — Project Foundation

This scaffold satisfies the Phase 1 exit criteria from `phases.md`:

- [x] Backend starts (Spring Boot, `/api/v1/version` responds)
- [x] Frontend starts (Next.js app router, basic layout/nav)
- [x] Database connection configured (Postgres/Neon via Spring Data JPA)
- [x] Migrations execute (Flyway wired, baseline placeholder migration —
      see note below)
- [x] Redis connection configured (Spring Data Redis client; local/CI run
      against Valkey 8, production targets Upstash Redis - see "Redis:
      Valkey locally, Upstash in production" below)
- [x] Health checks work (`/actuator/health`)
- [x] Docker build works (multi-stage Dockerfiles for both apps)
- [x] CI can build/test the application (`.github/workflows/ci.yml`)

**Note on migrations:** `V1__baseline.sql` intentionally does *not*
create the domain schema. Several schema-affecting decisions are still
open (MEAL attendance granularity, review-reply cardinality, policy
version uniqueness — see `memory.md` §3). The real domain migrations land
once those are resolved, per Phase 0/1 sequencing in `phases.md`. Note
that the identity module's tables (added in Phase 2, below) were
unaffected by those open items and already have real migrations
(`V2__identity_schema.sql`).

## Status: Phase 2 — Identity & Authentication

Implemented in `backend/src/main/java/com/firstfood/identity`: mobile-OTP
login (an account is created automatically on first successful login -
there's no separate sign-up step), JWT access tokens + revocable/rotating
refresh sessions, a protected `/api/v1/me` profile endpoint, and the
OTP-gated phone-number-change flow rules.md Rule 3.3 requires.

| Endpoint | Auth | Purpose |
|---|---|---|
| `POST /api/v1/auth/otp/request` | public | `{ "phone": "+91..." }` → sends a login OTP |
| `POST /api/v1/auth/otp/verify` | public | `{ "phone", "code" }` → `{ accessToken, refreshToken }` |
| `POST /api/v1/auth/refresh` | public | `{ "refreshToken" }` → new token pair (old one is revoked) |
| `POST /api/v1/auth/logout` | public | `{ "refreshToken" }` → revokes it |
| `GET /api/v1/me` | Bearer token | current profile |
| `PATCH /api/v1/me/email` | Bearer token | `{ "email" }` |
| `POST /api/v1/me/phone/otp/request` | Bearer token | `{ "newPhone" }` → OTP sent to the *new* number |
| `POST /api/v1/me/phone/otp/verify` | Bearer token | `{ "newPhone", "code" }` → applies the change |

**Trying it locally without a real SMS provider:** the `local` profile
uses a console OTP sender - run `docker compose logs -f backend` (or
watch the terminal if running natively) and the OTP prints directly to
stdout after you call `/api/v1/auth/otp/request`. Never wired in
production - see `BrevoSmsOtpSender`, which is written against Brevo's
published API but hasn't been exercised against a real account yet
(`memory.md` §7).

**New required env var for production**: `BREVO_API_KEY` (SMS OTP
delivery). `BREVO_SMS_SENDER_NAME` is optional, defaults to `FirstFood`.
Also new: `CORS_ALLOWED_ORIGINS` (required in production, no default -
see "Redis: Valkey locally..." section's sibling note below on CORS; local/
test default to `http://localhost:3000`).

**Frontend UI**: `frontend/app/page.tsx` + `frontend/components/auth/*`
implement the full flow - phone entry → OTP entry → authenticated screen
(profile, refresh, logout, email update, phone-number change). Tokens
live in React state only, not localStorage - reload the page and you're
logged out. That's deliberate for Phase 2, not a bug; see the note at the
bottom of the authenticated screen and `memory.md` §7 for the production
plan (HttpOnly refresh cookie).

**Hardening pass (13 Sept 2026)**: after an external Phase 2 review,
fixed two real security gaps (suspended accounts could keep using an
already-issued access token / could still refresh; a JWT-only check
without an account-status lookup wasn't enough), a concurrency bug in
refresh-token rotation (two simultaneous refresh calls on the same token
could both succeed), CORS was entirely unconfigured (would have blocked
any real browser frontend), and restored a working Maven Wrapper (an
earlier attempt was deferred, then claimed-but-not-actually-added - it's
genuinely there now). Full detail in `memory.md` §7.

## Running locally

Prerequisites: Java 25, Node.js 24 (Active LTS), Docker. Maven itself is
*not* required on your machine for either path below - Docker builds it
inside the build container, and native runs use the committed Maven
Wrapper (`./mvnw` / `mvnw.cmd`), which downloads the pinned Maven version
for you on first use.

**If `./mvnw` fails with "permission denied"**: zip extraction (and a
fresh `git clone` before the executable bit is committed) doesn't always
preserve it. Run `chmod +x backend/mvnw` once, and if committing this to
git for the first time, make sure that bit is actually staged (`git
update-index --chmod=+x backend/mvnw` if a plain `chmod` + `git add`
doesn't seem to stick).

All tool/runtime versions were audited against current LTS/support status
on Sept 10, 2026 - see `memory.md` §4 for the full rationale and a couple
of deliberate exceptions (e.g. TypeScript pinned to 6.0.3, not the newer
7.x, because the lint toolchain doesn't support 7 yet).

```bash
cd infra
docker compose up --build
```

- Frontend: http://localhost:3000
- Backend: http://localhost:8080/api/v1/version
- Backend health: http://localhost:8080/actuator/health

This uses the `docker` Spring profile (`application-docker.yml`), not
`local` - see "Two 'local' profiles" below for why that distinction
matters.

Or run each app natively:

```bash
# Backend (needs local Postgres 17 on 5432 and Valkey on 6379, or use
# `docker compose up postgres redis` from infra/ first)
cd backend
cp .env.example .env

# macOS/Linux
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
# Windows
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"

# Frontend
cd frontend
cp .env.example .env.local
npm ci
npm run dev
```

## Two "local" profiles - `local` vs `docker`

Easy to trip over, so it gets its own section. There are two ways to run
the backend against a local Postgres/Redis, and they need different
hostnames because "localhost" means something different in each:

| | Profile | Postgres/Redis host | When |
|---|---|---|---|
| Native | `local` (`application-local.yml`) | `localhost` | Running `./mvnw spring-boot:run` (or an IDE run config) directly on your machine, against `docker compose up postgres redis`'s ports exposed on your host |
| Containerized | `docker` (`application-docker.yml`) | `postgres` / `redis` (Compose service names) | `docker compose up --build`, where the backend itself runs as a container alongside postgres/redis |

Inside a container, `localhost` refers to that container itself, not a
sibling container - a backend container trying to reach `localhost:5432`
gets a "connection refused" that looks like Postgres isn't running, when
it's actually running fine just not reachable at that address. Container-
to-container communication needs the Compose service name instead. If you
ever add a new local-running mode, it needs its own profile+hostname
combination too, not a reused `local`.

## Redis: Valkey locally, Upstash in production

`infra/docker-compose.yml` and the Testcontainers-based backend test both
run `valkey/valkey:8-alpine` (a BSD-licensed, wire-compatible Redis fork)
rather than the official `redis` image, purely to sidestep Redis Ltd.'s
2024 SSPL/RSALv2 relicensing for local tooling. This is a **local-only**
substitution - it doesn't change what ships to production. The frozen
architecture decision (`architecture.md`, `memory.md` §4) is still
**Upstash Redis** in production; the Spring Data Redis client code is
identical either way since both speak the same protocol. If that
production choice ever changes, update `memory.md` §4/§7, not this file.

## CORS

`SecurityConfig`'s `CorsConfigurationSource` allows only the origins
listed in `app.cors.allowed-origins` (`CORS_ALLOWED_ORIGINS` env var).
Local/test default to `http://localhost:3000` (where `npm run dev` serves
the frontend) - production has **no default**, same fail-fast pattern as
`JWT_SECRET`: set `CORS_ALLOWED_ORIGINS` to the real frontend origin
before deploying, or the backend won't start. Comma-separate if there's
more than one (e.g. a staging URL alongside production).

## Frontend API URL: build-time vs. runtime (decide before this goes beyond local Docker Compose)

`frontend/lib/env.ts` reads `NEXT_PUBLIC_API_BASE_URL` from the
environment, and the Phase 2 UI (`app/page.tsx` + `lib/auth-api.ts`) now
genuinely calls the backend with it. Any `NEXT_PUBLIC_*` variable used in
browser-side code gets **baked into the JavaScript bundle at `npm run
build` time** - the frontend Dockerfile runs `npm run build` in its
build stage, so setting `NEXT_PUBLIC_API_BASE_URL` later via
`infra/docker-compose.yml`'s `environment:` block (as it does today) has
**no effect on the already-built client bundle**. Harmless for local dev
(build and run happen together, same machine), but a built image will
silently keep pointing at whatever URL was set at build time no matter
what you set at deploy time - a real problem the moment this runs
anywhere beyond a laptop's `docker compose up`.

Pick one before this runs anywhere beyond a local machine:

1. **Build-time injection per environment** - pass `NEXT_PUBLIC_API_BASE_URL`
   as a Docker build ARG and rebuild the frontend image per environment.
   Simple, but couples the image to one backend URL.
2. **Runtime config endpoint** - serve config (e.g. `/api/config`) that
   the client fetches on load instead of relying on a build-time
   constant. More moving parts, but one image works everywhere.
3. **Same-origin `/api` + reverse proxy** - put Next.js and the backend
   behind the same origin (e.g. Next.js rewrites, or a proxy in front of
   both) so the browser always calls a relative `/api/...` path and never
   needs to know the backend's real address. Avoids CORS entirely too,
   and is generally the least error-prone option for a containerized
   deployment - the likely default unless there's a reason to prefer 1
   or 2.

Whichever is chosen, update this section and `memory.md` §7 with the
decision and reasoning.

## Next phase

Phase 3 — FoodProvider Management (FoodProvider CRUD, provider isolation,
provider types). See `phases.md` §6.