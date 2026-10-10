import { env } from "./env";

/**
 * Thin wrapper around fetch for the FirstFood v1 API.
 *
 * ALL browser HTTP to the backend goes through this one function. In proxy
 * mode env.apiBaseUrl is "" so the URL is the relative /api/v1/... (same
 * origin, rewritten to the backend by next.config.js); in direct mode it is
 * the backend host. Do not add other fetch() calls to the backend elsewhere.
 *
 * The backend is the authoritative source for business rules (rules.md
 * Rule 28.1 "Frontend Is Not the Source of Truth") - this client should
 * stay dumb: build requests, attach auth, parse responses, surface
 * errors. No subscription/expiry/extension calculation belongs here.
 */
export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    message: string,
  ) {
    super(message);
  }
}

type RequestOptions = {
  method?: "GET" | "POST" | "PUT" | "PATCH" | "DELETE";
  body?: unknown;
  accessToken?: string;
};

export async function apiFetch<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = "GET", body, accessToken } = options;

  const response = await fetch(`${env.apiBaseUrl}/api/v1${path}`, {
    method,
    headers: {
      "Content-Type": "application/json",
      ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });

  if (!response.ok) {
    const errorBody = await response.json().catch(() => ({}));
    // A 5xx with no FirstFood error body did not come from our API: it is the
    // proxy (or a load balancer) reporting that the backend is unreachable,
    // slow, or crashed - typically a plain-text/HTML page, not JSON. Give it
    // a stable code instead of a misleading "Request failed.".
    const gatewayFailure = response.status >= 500 && !errorBody.code;
    throw new ApiError(
      response.status,
      errorBody.code ?? (gatewayFailure ? "SERVICE_UNAVAILABLE" : "UNKNOWN_ERROR"),
      errorBody.message ??
        (gatewayFailure ? "The server is temporarily unreachable. Please try again." : "Request failed."),
    );
  }

  // Not just 204: this API also returns 202 Accepted with an empty body
  // (e.g. the OTP request endpoints) - .json() throws on an empty body,
  // so check for actual content first rather than special-casing 204.
  const text = await response.text();
  if (!text) {
    return undefined as T;
  }
  return JSON.parse(text) as T;
}
