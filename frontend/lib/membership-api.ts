import { apiFetch } from "./api-client";

export type MembershipStatus = "ACTIVE" | "INACTIVE";

/** One row of a provider's customer list (a membership + its person). */
export type Customer = {
  membershipId: string;
  providerId: string;
  personId: string;
  fullName: string;
  phone: string | null;
  status: MembershipStatus;
  joinedAt: string;
  leftAt: string | null;
  addedBy: string;
  leftBy: string | null;
};

export type Person = {
  id: string;
  fullName: string;
  primaryPerson: boolean;
  createdAt: string;
};

/** A membership as the customer sees it (their own, across providers). */
export type MyMembership = {
  membershipId: string;
  providerId: string;
  providerName: string;
  personId: string;
  status: MembershipStatus;
  joinedAt: string;
  leftAt: string | null;
};

/**
 * One function per Phase 5 endpoint. The acting account is always the JWT - these
 * calls never send an actor id, and the backend re-checks every permission
 * regardless of what the UI chose to show.
 */
export const membershipApi = {
  list(accessToken: string, providerId: string, includeInactive: boolean): Promise<Customer[]> {
    return apiFetch<Customer[]>(`/providers/${providerId}/customers?includeInactive=${includeInactive}`, {
      accessToken,
    });
  },

  /** fullName is only used when the customer has no profile yet; it never renames an existing one. */
  add(
    accessToken: string,
    providerId: string,
    input: { phone: string; fullName?: string },
  ): Promise<Customer> {
    return apiFetch<Customer>(`/providers/${providerId}/customers`, {
      method: "POST",
      accessToken,
      body: input,
    });
  },

  deactivate(accessToken: string, providerId: string, membershipId: string): Promise<Customer> {
    return apiFetch<Customer>(`/providers/${providerId}/customers/${membershipId}/deactivate`, {
      method: "POST",
      accessToken,
    });
  },

  /** 404 PERSON_NOT_FOUND until the account has a profile. */
  getMyPerson(accessToken: string): Promise<Person> {
    return apiFetch<Person>("/me/person", { accessToken });
  },

  saveMyPerson(accessToken: string, fullName: string): Promise<Person> {
    return apiFetch<Person>("/me/person", { method: "PUT", accessToken, body: { fullName } });
  },

  myMemberships(accessToken: string): Promise<MyMembership[]> {
    return apiFetch<MyMembership[]>("/me/memberships", { accessToken });
  },
};
