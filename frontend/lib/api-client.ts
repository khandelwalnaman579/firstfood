import { env } from "./env";

/**
 * Thin wrapper around fetch for the FirstFood v1 API.
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
    throw new ApiError(
      response.status,
      errorBody.code ?? "UNKNOWN_ERROR",
      errorBody.message ?? "Request failed.",
    );
  }

  if (response.status === 204) {
    return undefined as T;
  }

  return (await response.json()) as T;
}
