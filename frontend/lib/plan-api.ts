import { apiFetch } from "./api-client";

export const CONSUMPTION_TYPES = ["DAY", "MEAL"] as const;
export type ConsumptionType = (typeof CONSUMPTION_TYPES)[number];

export type PlanStatus = "ACTIVE" | "INACTIVE";

/** The current version of a plan's absence/extension terms. Old versions are kept server-side as history. */
export type PlanPolicy = {
  version: number;
  extensionAllowed: boolean;
  minConsecutiveAbsenceDays: number | null;
  sameDayAbsenceAllowed: boolean;
  /** Provider-local "HH:mm:ss"; null = no cutoff. */
  absenceCutoffTime: string | null;
  maxCalendarWindowDays: number | null;
  effectiveFrom: string;
};

/** A plan as the provider offers it TODAY. Not a record of what any past subscription was sold under. */
export type Plan = {
  id: string;
  providerId: string;
  name: string;
  description: string | null;
  consumptionType: ConsumptionType;
  durationDays: number | null;
  mealQuantity: number | null;
  price: number;
  currency: string;
  status: PlanStatus;
  policy: PlanPolicy;
  createdBy: string;
  updatedBy: string;
  createdAt: string;
  updatedAt: string;
};

/** Sent in full on create and update - there are no hidden defaults. */
export type PolicyInput = {
  extensionAllowed: boolean;
  minConsecutiveAbsenceDays?: number;
  sameDayAbsenceAllowed: boolean;
  /** "HH:mm" */
  absenceCutoffTime?: string;
  maxCalendarWindowDays?: number;
};

export type CreatePlanInput = {
  name: string;
  description?: string;
  consumptionType: ConsumptionType;
  /** DAY plans only. */
  durationDays?: number;
  /** MEAL plans only. */
  mealQuantity?: number;
  price: number;
  policy: PolicyInput;
};

/** PUT: a full replacement of the current terms. The consumption type cannot change. */
export type UpdatePlanInput = Omit<CreatePlanInput, "consumptionType">;

/**
 * One function per Phase 6 endpoint. The acting account is always the JWT; the backend decides
 * every rule (field combinations, name uniqueness, who may change plans) - nothing here
 * duplicates them. There is deliberately no delete: plans are deactivated, never deleted.
 */
export const planApi = {
  list(accessToken: string, providerId: string, includeInactive: boolean): Promise<Plan[]> {
    return apiFetch<Plan[]>(`/providers/${providerId}/plans?includeInactive=${includeInactive}`, { accessToken });
  },

  create(accessToken: string, providerId: string, input: CreatePlanInput): Promise<Plan> {
    return apiFetch<Plan>(`/providers/${providerId}/plans`, { method: "POST", accessToken, body: input });
  },

  update(accessToken: string, providerId: string, planId: string, input: UpdatePlanInput): Promise<Plan> {
    return apiFetch<Plan>(`/providers/${providerId}/plans/${planId}`, { method: "PUT", accessToken, body: input });
  },

  activate(accessToken: string, providerId: string, planId: string): Promise<Plan> {
    return apiFetch<Plan>(`/providers/${providerId}/plans/${planId}/activate`, { method: "POST", accessToken });
  },

  deactivate(accessToken: string, providerId: string, planId: string): Promise<Plan> {
    return apiFetch<Plan>(`/providers/${providerId}/plans/${planId}/deactivate`, { method: "POST", accessToken });
  },
};

/** "30 days" / "60 meals" - display only. */
export function describeQuantity(plan: Plan): string {
  return plan.consumptionType === "DAY" ? `${plan.durationDays} days` : `${plan.mealQuantity} meals`;
}

/** "HH:mm:ss" -> "HH:mm" for display and for <input type="time">. */
export function shortTime(time: string | null): string {
  return time ? time.slice(0, 5) : "";
}

/** One readable line per policy, display only (the backend evaluates the real rules). */
export function describePolicy(plan: Plan): string[] {
  const p = plan.policy;
  const lines: string[] = [];
  if (p.extensionAllowed) {
    lines.push(
      `Extends after ${p.minConsecutiveAbsenceDays}+ consecutive absent days` +
        (p.maxCalendarWindowDays ? `, up to ${p.maxCalendarWindowDays} days from the start` : ", no cap"),
    );
  } else {
    lines.push("No extension for absences");
  }
  if (p.sameDayAbsenceAllowed) {
    lines.push(p.absenceCutoffTime ? `Same-day absence until ${shortTime(p.absenceCutoffTime)}` : "Same-day absence allowed");
  } else {
    lines.push("No same-day absence");
  }
  if (!p.extensionAllowed && p.maxCalendarWindowDays) {
    lines.push(`Must be used within ${p.maxCalendarWindowDays} days`);
  }
  return lines;
}
