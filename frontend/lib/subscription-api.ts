import { apiFetch } from "./api-client";
import type { ConsumptionType } from "./plan-api";

/** Stored lifecycle. ACTIVE may already be over by date: use `phase` for what a person should see. */
export type SubscriptionStatus = "ACTIVE" | "EXPIRED" | "CANCELLED";
/** Derived by the server for "today" in the business time zone. */
export type SubscriptionPhase = "UPCOMING" | "RUNNING" | "ENDED" | "CANCELLED";

/** What the subscription was sold under - frozen at sale time, never re-read from the plan. */
export type SubscriptionTerms = {
  planName: string;
  price: number;
  currency: string;
  purchasedDays: number | null;
  purchasedMeals: number | null;
  policyVersion: number;
  extensionAllowed: boolean;
  minConsecutiveAbsenceDays: number | null;
  sameDayAbsenceAllowed: boolean;
  absenceCutoffTime: string | null;
  maxCalendarWindowDays: number | null;
  capturedAt: string;
};

/** A subscription as provider staff see it. Dates are calendar days ("YYYY-MM-DD"). */
export type Subscription = {
  id: string;
  providerId: string;
  membershipId: string;
  personId: string;
  customerName: string;
  planId: string;
  consumptionType: ConsumptionType;
  status: SubscriptionStatus;
  phase: SubscriptionPhase;
  startDate: string;
  baseExpiryDate: string | null;
  effectiveExpiryDate: string | null;
  maximumExpiryDate: string | null;
  remainingDays: number | null;
  remainingMeals: number | null;
  renewedFromSubscriptionId: string | null;
  renewed: boolean;
  /** Server-decided: show Renew / Cancel from these, never from dates. */
  renewable: boolean;
  cancellable: boolean;
  createdBy: string;
  createdAt: string;
  expiredAt: string | null;
  cancelledAt: string | null;
  cancelledBy: string | null;
  cancellationReason: string | null;
  terms: SubscriptionTerms;
};

/** A subscription as its customer sees it, across providers. */
export type MySubscription = {
  id: string;
  providerId: string;
  providerName: string;
  personId: string;
  consumptionType: ConsumptionType;
  status: SubscriptionStatus;
  phase: SubscriptionPhase;
  startDate: string;
  effectiveExpiryDate: string | null;
  remainingDays: number | null;
  remainingMeals: number | null;
  cancelledAt: string | null;
  terms: SubscriptionTerms;
};

export type CreateSubscriptionInput = {
  membershipId: string;
  planId: string;
  /** "YYYY-MM-DD"; omitted = today. Price and terms are never sent: the server snapshots the plan. */
  startDate?: string;
};

export type RenewSubscriptionInput = { planId?: string; startDate?: string };

/**
 * One function per Phase 7 endpoint. The acting account is always the JWT; the backend decides
 * every rule (overlap, capacity, dates, who may sell) - nothing here duplicates them.
 * There is deliberately no delete: a subscription ends as expired or cancelled and stays on record.
 */
export const subscriptionApi = {
  list(accessToken: string, providerId: string): Promise<Subscription[]> {
    return apiFetch<Subscription[]>(`/providers/${providerId}/subscriptions`, { accessToken });
  },

  create(accessToken: string, providerId: string, input: CreateSubscriptionInput): Promise<Subscription> {
    return apiFetch<Subscription>(`/providers/${providerId}/subscriptions`, {
      method: "POST",
      accessToken,
      body: input,
    });
  },

  cancel(accessToken: string, providerId: string, id: string, reason?: string): Promise<Subscription> {
    return apiFetch<Subscription>(`/providers/${providerId}/subscriptions/${id}/cancel`, {
      method: "POST",
      accessToken,
      body: reason ? { reason } : {},
    });
  },

  renew(
    accessToken: string,
    providerId: string,
    id: string,
    input: RenewSubscriptionInput = {},
  ): Promise<Subscription> {
    return apiFetch<Subscription>(`/providers/${providerId}/subscriptions/${id}/renew`, {
      method: "POST",
      accessToken,
      body: input,
    });
  },

  mine(accessToken: string): Promise<MySubscription[]> {
    return apiFetch<MySubscription[]>("/subscriptions", { accessToken });
  },
};

/** "2026-09-30" -> local date string, without the one-day shift `new Date("2026-09-30")` can cause. */
export function formatDay(day: string | null): string {
  if (!day) {
    return "—";
  }
  const [y, m, d] = day.split("-").map(Number);
  if (y === undefined || m === undefined || d === undefined) {
    return day;
  }
  return new Date(y, m - 1, d).toLocaleDateString();
}

export const PHASE_LABEL: Record<SubscriptionPhase, string> = {
  UPCOMING: "Upcoming",
  RUNNING: "Running",
  ENDED: "Ended",
  CANCELLED: "Cancelled",
};

/** "12 days left" / "30 meals left" / "ended" - display only. */
export function describeRemaining(s: {
  phase: SubscriptionPhase;
  consumptionType: ConsumptionType;
  remainingDays: number | null;
  remainingMeals: number | null;
}): string {
  if (s.phase === "ENDED") return "Ended";
  if (s.phase === "CANCELLED") return "Cancelled";
  const parts: string[] = [];
  if (s.consumptionType === "MEAL" && s.remainingMeals !== null) {
    parts.push(`${s.remainingMeals} meals left`);
  }
  if (s.remainingDays !== null) {
    parts.push(`${s.remainingDays} days${s.consumptionType === "MEAL" ? " in window" : " left"}`);
  }
  return parts.join(" · ") || "No expiry";
}
