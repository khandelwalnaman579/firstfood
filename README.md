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
once those are resolved, per Phase 0/1 sequencing in `phases.md`.

## Running locally

Prerequisites: Java 25, Node.js 24 (Active LTS), Docker. Maven itself is
*not* required on your machine for either path below - Docker builds it
inside the build container, and native runs use the committed Maven
Wrapper (`./mvnw` / `mvnw.cmd`), which downloads the pinned Maven version
for you on first use.

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

## Frontend API URL: build-time vs. runtime (decide before Phase 2)

`frontend/lib/env.ts` currently reads `NEXT_PUBLIC_API_BASE_URL` from the
environment. Any `NEXT_PUBLIC_*` variable used in browser-side code gets
**baked into the JavaScript bundle at `npm run build` time** - the
frontend Dockerfile runs `npm run build` in its build stage, so setting
`NEXT_PUBLIC_API_BASE_URL` later via `infra/docker-compose.yml`'s
`environment:` block (as it does today) has **no effect on the already-
built client bundle**. That's harmless right now because nothing calls
the backend from a client component yet, but it will silently do the
wrong thing the moment Phase 2 adds a real fetch from the browser.

Before writing that first client-side API call, pick one:

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

Phase 2 — Identity & Authentication (mobile/OTP auth, JWT, UserAccount ↔
Person boundary). See `phases.md` §5.