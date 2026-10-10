/**
 * Proxy smoke test - proves the same-origin /api/v1 proxy behaves, WITHOUT needing
 * the real backend, Postgres or Docker.
 *
 * It starts a stub "backend" on 127.0.0.1:18080 that records every request, builds
 * the frontend in proxy mode pointing at it (this OVERWRITES ./.next), starts
 * `next start` on :13000, then runs the checks below through the public frontend
 * origin only. It covers risks R-02, R-03, R-05, R-11, R-12, R-16 of the migration
 * register. It does NOT replace testing against the real Spring backend
 * (CORS, real auth, real data) - do that too, see the README.
 *
 * Run: npm run smoke:proxy
 */
import http from "node:http";
import { spawn, spawnSync } from "node:child_process";

const STUB_PORT = 18080;
const WEB_PORT = 13000;
const WEB = `http://127.0.0.1:${WEB_PORT}`;

// ---------------------------------------------------------------- stub backend
const hits = [];
const stub = http.createServer((req, res) => {
  const chunks = [];
  req.on("data", (c) => chunks.push(c));
  req.on("end", () => {
    const body = Buffer.concat(chunks);
    hits.push({ method: req.method, url: req.url, headers: req.headers, body });
    const path = req.url.split("?")[0];
    const json = (status, obj, extra = {}) => {
      res.writeHead(status, { "Content-Type": "application/json", "Cache-Control": "no-cache, no-store", ...extra });
      res.end(JSON.stringify(obj));
    };
    if (path === "/api/v1/auth/otp/request") {
      res.writeHead(202, { "Cache-Control": "no-cache, no-store" });
      return res.end();
    }
    if (path === "/api/v1/me") {
      if (req.headers.authorization === "Bearer test-token") return json(200, { id: "acct-1", via: "stub" });
      return json(401, { code: "UNAUTHENTICATED", message: "Authentication required." });
    }
    if (path === "/api/v1/boom") return json(500, { code: "INTERNAL_ERROR", message: "Something went wrong." });
    if (path === "/api/v1/html-502") {
      res.writeHead(502, { "Content-Type": "text/html" });
      return res.end("<html><body>Bad gateway</body></html>");
    }
    if (path.startsWith("/api/v1/")) {
      return json(200, {
        method: req.method,
        url: req.url,
        bodyLength: body.length,
        body: body.length < 2000 ? body.toString() : undefined,
        host: req.headers.host,
        origin: req.headers.origin ?? null,
        xff: req.headers["x-forwarded-for"] ?? null,
        xfProto: req.headers["x-forwarded-proto"] ?? null,
      });
    }
    // Anything else is "internal": must NEVER be reachable through the proxy.
    res.writeHead(200, { "Content-Type": "text/plain" });
    res.end("BACKEND-INTERNAL " + req.url);
  });
});
await new Promise((r) => stub.listen(STUB_PORT, "127.0.0.1", r));

// ---------------------------------------------------------------- build + start
const env = {
  ...process.env,
  NEXT_PUBLIC_API_BASE_URL: "",
  BACKEND_ORIGIN: `http://127.0.0.1:${STUB_PORT}`,
  NEXT_TELEMETRY_DISABLED: "1",
};
console.log("Building frontend in proxy mode ...");
const build = spawnSync("npx", ["next", "build"], { env, stdio: "inherit" });
if (build.status !== 0) {
  stub.close();
  console.error("BUILD FAILED");
  process.exit(1);
}
const web = spawn("npx", ["next", "start", "-p", String(WEB_PORT), "-H", "127.0.0.1"], { env, stdio: "ignore" });

async function waitUp() {
  for (let i = 0; i < 60; i++) {
    try {
      await fetch(`${WEB}/`);
      return;
    } catch {
      await new Promise((r) => setTimeout(r, 500));
    }
  }
  throw new Error("frontend did not start");
}

// raw request: Node's fetch would normalise ../ and // before sending, hiding the attack
function raw(path, { method = "GET", headers = {}, body } = {}) {
  return new Promise((resolve, reject) => {
    const req = http.request({ host: "127.0.0.1", port: WEB_PORT, path, method, headers }, (res) => {
      const chunks = [];
      res.on("data", (c) => chunks.push(c));
      res.on("end", () => resolve({ status: res.statusCode, headers: res.headers, text: Buffer.concat(chunks).toString() }));
    });
    req.on("error", reject);
    if (body) req.write(body);
    req.end();
  });
}

// ---------------------------------------------------------------- checks
const results = [];
function check(id, name, ok, detail = "") {
  results.push({ id, name, ok });
  console.log(`${ok ? "PASS" : "FAIL"}  ${id}  ${name}${detail ? "  -> " + detail : ""}`);
}
const info = (m) => console.log("INFO  " + m);

try {
  await waitUp();

  // 1. 202 with empty body (OTP request) - R-16
  let r = await fetch(`${WEB}/api/v1/auth/otp/request`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ phone: "+911234567890" }),
  });
  check("T1", "POST 202 with empty body passes through", r.status === 202 && (await r.text()) === "");

  // 2. Authorization forwarded verbatim; 401 JSON preserved - R-03
  r = await fetch(`${WEB}/api/v1/me`, { headers: { Authorization: "Bearer test-token" } });
  let j = await r.json();
  check("T2a", "Authorization header reaches backend (200)", r.status === 200 && j.via === "stub", `status ${r.status}`);
  r = await fetch(`${WEB}/api/v1/me`);
  j = await r.json();
  check("T2b", "No token -> backend's 401 JSON preserved", r.status === 401 && j.code === "UNAUTHENTICATED");

  // 3. Every method, body intact - R-16
  for (const method of ["POST", "PUT", "PATCH", "DELETE"]) {
    r = await fetch(`${WEB}/api/v1/providers/p1/roles/r1`, {
      method,
      headers: { "Content-Type": "application/json", Authorization: "Bearer test-token" },
      body: method === "DELETE" ? undefined : JSON.stringify({ n: method }),
    });
    j = await r.json();
    const bodyOk = method === "DELETE" ? j.bodyLength === 0 : j.body === JSON.stringify({ n: method });
    check("T3-" + method, `${method} method + body forwarded`, r.status === 200 && j.method === method && bodyOk);
  }

  // 4. Query string and encoded path params
  r = await fetch(`${WEB}/api/v1/providers/abc/customers?includeInactive=true&x=a%20b`);
  j = await r.json();
  check("T4", "Query string forwarded intact", j.url === "/api/v1/providers/abc/customers?includeInactive=true&x=a%20b", j.url);

  // 5. Cache headers - R-02
  r = await fetch(`${WEB}/api/v1/me`, { headers: { Authorization: "Bearer test-token" } });
  const cc = r.headers.get("cache-control") ?? "";
  check("T5", "Authenticated response is no-store", /no-store/.test(cc), cc);

  // 6. Non-API / internal paths not proxied - R-12
  const before = hits.length;
  for (const p of ["/actuator/health", "/actuator/info", "/api/other", "/api", "/internal"]) {
    r = await raw(p);
    check("T6", `${p} is not proxied`, r.status === 404 && !r.text.includes("BACKEND-INTERNAL"), `status ${r.status}`);
  }
  check("T6-hits", "Backend received no request for those paths", hits.length === before);

  // 7. Path tricks must never reach a non-/api/v1 backend path - R-05
  const tricks = [
    "/api/v1/../actuator/health",
    "/api/v1/%2e%2e/actuator/health",
    "/api/v1/%2E%2E%2Factuator/health",
    "/api/v1/..%2factuator/health",
    "//actuator/health",
    "/api/v1//../../actuator/health",
    "/api/v1/..;/actuator/health",
    "/api/v1/%00/../../actuator",
    "/api/v1/http://evil.example/x",
    "/api/v1/\\..\\actuator",
  ];
  const mark = hits.length;
  for (const p of tricks) {
    try {
      r = await raw(p);
      info(`trick ${p} -> ${r.status}${r.text.includes("BACKEND-INTERNAL") ? "  LEAKED INTERNAL" : ""}`);
    } catch (e) {
      info(`trick ${p} -> request error (${e.code})`);
    }
  }
  const stray = hits.slice(mark).filter((h) => !h.url.startsWith("/api/v1/"));
  check("T7", "No path trick reached a non-/api/v1 backend path", stray.length === 0, stray.map((h) => h.url).join(", "));

  // 8. Backend 500 JSON preserved - R-16
  r = await fetch(`${WEB}/api/v1/boom`);
  j = await r.json();
  check("T8", "Backend 500 JSON body preserved", r.status === 500 && j.code === "INTERNAL_ERROR");

  // 9. Large body - R-11
  const big = "x".repeat(2 * 1024 * 1024);
  r = await fetch(`${WEB}/api/v1/providers/p1/roles/r1`, { method: "PATCH", headers: { "Content-Type": "text/plain" }, body: big });
  j = await r.json().catch(() => ({}));
  check("T9", "2 MB request body passes", r.status === 200 && j.bodyLength === big.length, `status ${r.status}, stub got ${j.bodyLength}`);

  // 10. What the backend sees (informational - decisions R-08/R-09/R-10)
  r = await fetch(`${WEB}/api/v1/echo`, { method: "POST", headers: { "Content-Type": "application/json", Origin: WEB }, body: "{}" });
  j = await r.json();
  info(`backend sees Host=${j.host} Origin=${j.origin} X-Forwarded-For=${j.xff} X-Forwarded-Proto=${j.xfProto}`);
  info("Spring treats a request as CORS when Origin differs from the Host it sees - so the frontend origin must stay in CORS_ALLOWED_ORIGINS.");

  // 11. Backend down - R-11
  await new Promise((r2) => stub.close(r2));
  stub.closeAllConnections?.();
  r = await raw("/api/v1/me", { headers: { Authorization: "Bearer test-token" } });
  check("T11", "Backend down -> clean 5xx (no hang/crash)", r.status >= 500 && r.status < 600, `status ${r.status}, body ${JSON.stringify(r.text.slice(0, 60))}`);
  r = await raw("/");
  check("T11b", "Frontend still serves pages while backend is down", r.status === 200);
} catch (e) {
  console.error("SMOKE TEST ERROR", e);
  results.push({ id: "ERR", ok: false });
} finally {
  web.kill("SIGTERM");
  stub.close();
}

const failed = results.filter((x) => !x.ok);
console.log(`\n${results.length - failed.length}/${results.length} checks passed`);
process.exit(failed.length ? 1 : 0);
