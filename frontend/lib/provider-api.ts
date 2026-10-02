import { apiFetch } from "./api-client";

export const PROVIDER_TYPES = ["MESS", "TIFFIN", "PG", "HOSTEL", "CORPORATE", "OTHER"] as const;
export type ProviderType = (typeof PROVIDER_TYPES)[number];

export const PROVIDER_STATUSES = ["ACTIVE", "FULL", "TEMPORARILY_UNAVAILABLE", "CLOSED"] as const;
export type ProviderStatus = (typeof PROVIDER_STATUSES)[number];

export const PROVIDER_ROLES = ["OWNER", "MANAGER", "WORKER"] as const;
export type ProviderRole = (typeof PROVIDER_ROLES)[number];

/** Mirrors the backend's fixed permission set. The UI only uses it to hide controls. */
export type ProviderPermission =
  | "PROVIDER_VIEW"
  | "PROVIDER_EDIT"
  | "PROVIDER_CLOSE"
  | "ROLE_VIEW"
  | "ROLE_ASSIGN"
  | "ROLE_REVOKE"
  | "OWNER_TRANSFER"
  | "MEMBERSHIP_VIEW"
  | "MEMBERSHIP_MANAGE";

export type Provider = {
  id: string;
  name: string;
  providerType: ProviderType;
  description: string | null;
  addressLine: string;
  locality: string;
  city: string;
  pincode: string | null;
  contactPhone: string | null;
  status: ProviderStatus;
  acceptingNewCustomers: boolean;
  maxActiveSubscriptions: number | null;
  myRole: ProviderRole;
  /** Resolved server-side for the caller. Visibility hint only - the backend re-checks every request. */
  myPermissions: ProviderPermission[];
  createdAt: string;
  updatedAt: string;
  closedAt: string | null;
  closedByAccountId: string | null;
  closureReason: string | null;
};

export type CreateProviderInput = {
  name: string;
  providerType: ProviderType;
  description?: string;
  addressLine: string;
  locality: string;
  city: string;
  pincode?: string;
  contactPhone?: string;
  maxActiveSubscriptions?: number;
};

/** PATCH semantics: omitted = unchanged. `unlimitedCapacity: true` clears the limit. */
export type UpdateProviderInput = Partial<
  Omit<CreateProviderInput, "maxActiveSubscriptions">
> & {
  maxActiveSubscriptions?: number;
  unlimitedCapacity?: boolean;
  status?: ProviderStatus;
  acceptingNewCustomers?: boolean;
  closureReason?: string;
};

/**
 * One function per provider endpoint (com.firstfood.provider.web). Thin on
 * purpose: the backend owns every rule (status transitions, authorization,
 * invariants) - nothing here duplicates them. All calls go through
 * api-client so a future reverse proxy is a config change, not a rewrite of
 * these pages.
 */
export const providerApi = {
  list(accessToken: string): Promise<Provider[]> {
    return apiFetch<Provider[]>("/providers", { accessToken });
  },

  get(accessToken: string, providerId: string): Promise<Provider> {
    return apiFetch<Provider>(`/providers/${providerId}`, { accessToken });
  },

  create(accessToken: string, input: CreateProviderInput): Promise<Provider> {
    return apiFetch<Provider>("/providers", { method: "POST", accessToken, body: input });
  },

  update(accessToken: string, providerId: string, input: UpdateProviderInput): Promise<Provider> {
    return apiFetch<Provider>(`/providers/${providerId}`, {
      method: "PATCH",
      accessToken,
      body: input,
    });
  },
};

export function can(provider: Provider, permission: ProviderPermission): boolean {
  return provider.myPermissions.includes(permission);
}

/** Which target statuses the backend's transition graph allows from `from`. */
export function allowedTransitions(from: ProviderStatus): ProviderStatus[] {
  switch (from) {
    case "ACTIVE":
      return ["FULL", "TEMPORARILY_UNAVAILABLE", "CLOSED"];
    case "FULL":
      return ["ACTIVE", "TEMPORARILY_UNAVAILABLE", "CLOSED"];
    case "TEMPORARILY_UNAVAILABLE":
      return ["ACTIVE", "CLOSED"];
    case "CLOSED":
      return [];
  }
}
