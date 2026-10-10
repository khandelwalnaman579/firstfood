/**
 * Same-origin reverse proxy for the FirstFood API.
 *
 * PROXY mode is on when NEXT_PUBLIC_API_BASE_URL is the empty string (see
 * lib/env.ts). Then the browser calls /api/v1/... on THIS origin and Next
 * forwards it to BACKEND_ORIGIN. In direct mode (a non-empty
 * NEXT_PUBLIC_API_BASE_URL) nothing here is registered, so rolling back is a
 * single environment change.
 *
 * IMPORTANT - build time, not runtime: rewrites are compiled into the build
 * (.next/routes-manifest.json), so BACKEND_ORIGIN must be present when
 * `next build` runs (the Dockerfile passes it as a build ARG). Changing it on a
 * running container does nothing; rebuild instead.
 *
 * Safety rules baked in (see the migration risk register):
 *  - Only /api/v1/** is forwarded. /actuator and every other backend path stay
 *    unreachable from the public origin (R-12).
 *  - The destination is a FIXED origin read from the environment, never from
 *    the request, so this cannot be turned into an open proxy / SSRF (R-05).
 *  - BACKEND_ORIGIN is server-only (no NEXT_PUBLIC_ prefix): never shipped to
 *    browsers (R-13).
 *  - Authenticated responses are marked no-store here as well as by the backend,
 *    so no CDN/browser cache can serve one user's data to another (R-02).
 */
function backendOrigin() {
  const raw = process.env.BACKEND_ORIGIN;
  if (!raw || raw.trim() === "") {
    throw new Error(
      "BACKEND_ORIGIN is required when NEXT_PUBLIC_API_BASE_URL is empty (same-origin proxy mode). " +
        "Example: BACKEND_ORIGIN=http://localhost:8080 (native dev) or http://backend:8080 (docker compose).",
    );
  }
  let url;
  try {
    url = new URL(raw.trim());
  } catch {
    throw new Error(`BACKEND_ORIGIN is not a valid URL: "${raw}"`);
  }
  if (url.protocol !== "http:" && url.protocol !== "https:") {
    throw new Error(`BACKEND_ORIGIN must be http(s), got "${url.protocol}"`);
  }
  if (url.username || url.password) {
    throw new Error("BACKEND_ORIGIN must not contain credentials.");
  }
  if ((url.pathname !== "/" && url.pathname !== "") || url.search || url.hash) {
    throw new Error(
      `BACKEND_ORIGIN must be an origin only (scheme://host[:port]) with no path, query or fragment, got "${raw}"`,
    );
  }
  return url.origin; // normalised: no trailing slash
}

const proxyMode = process.env.NEXT_PUBLIC_API_BASE_URL === "";

/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,

  async rewrites() {
    if (!proxyMode) return [];
    return [
      {
        source: "/api/v1/:path*",
        destination: `${backendOrigin()}/api/v1/:path*`,
      },
    ];
  },

  async headers() {
    return [
      {
        source: "/api/:path*",
        headers: [{ key: "Cache-Control", value: "no-store" }],
      },
    ];
  },
};

// Fail the build (not the first user request) if proxy mode is on but misconfigured.
if (proxyMode) backendOrigin();

module.exports = nextConfig;
