import { apiFetch } from "./api-client";
import type { ProviderRole } from "./provider-api";

export type RoleAssignment = {
  id: string;
  providerId: string;
  accountId: string;
  accountPhone: string | null;
  role: ProviderRole;
  status: "ACTIVE" | "REVOKED";
  assignedBy: string | null;
  assignedAt: string;
  revokedAt: string | null;
  revokedBy: string | null;
};

/** Roles that can be handed out through the role endpoint (OWNER only changes via transfer). */
export type AssignableRole = Exclude<ProviderRole, "OWNER">;

/**
 * One function per role-management endpoint. The acting account is always the
 * JWT - these calls never send an actor id, and the backend re-checks every
 * permission regardless of what the UI chose to show.
 */
export const roleApi = {
  list(accessToken: string, providerId: string): Promise<RoleAssignment[]> {
    return apiFetch<RoleAssignment[]>(`/providers/${providerId}/roles`, { accessToken });
  },

  assign(
    accessToken: string,
    providerId: string,
    input: { phone: string; role: AssignableRole },
  ): Promise<RoleAssignment> {
    return apiFetch<RoleAssignment>(`/providers/${providerId}/roles`, {
      method: "POST",
      accessToken,
      body: input,
    });
  },

  revoke(accessToken: string, providerId: string, assignmentId: string): Promise<RoleAssignment> {
    return apiFetch<RoleAssignment>(`/providers/${providerId}/roles/${assignmentId}`, {
      method: "DELETE",
      accessToken,
    });
  },

  /** Returns [newOwner, formerOwner-now-MANAGER]. */
  transferOwnership(accessToken: string, providerId: string, phone: string): Promise<RoleAssignment[]> {
    return apiFetch<RoleAssignment[]>(`/providers/${providerId}/ownership/transfer`, {
      method: "POST",
      accessToken,
      body: { phone },
    });
  },
};
