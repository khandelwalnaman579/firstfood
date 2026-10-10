import { apiFetch } from "./api-client";

export type ExtensionBlock =
  | "NOT_SUPPORTED_FOR_MEAL"
  | "NOT_ALLOWED_BY_POLICY"
  | "SUBSCRIPTION_CANCELLED"
  | "SUBSCRIPTION_EXPIRED"
  | "ALREADY_RENEWED";

export type ExtensionTrigger = "STAFF" | "SYSTEM";
/** EXTENSION_APPLIED added days; OVER_APPLIED is a report that earlier days are no longer eligible (never reversed). */
export type ExtensionKind = "EXTENSION_APPLIED" | "OVER_APPLIED";

/** Why a subscription cannot be extended - decided by the server, shown as-is. */
export const BLOCK_TEXT: Record<ExtensionBlock, string> = {
  NOT_SUPPORTED_FOR_MEAL: "Meal-based subscriptions are not extended by absence.",
  NOT_ALLOWED_BY_POLICY: "This subscription was sold without extension for absence.",
  SUBSCRIPTION_CANCELLED: "This subscription was cancelled.",
  SUBSCRIPTION_EXPIRED: "This subscription has ended.",
  ALREADY_RENEWED: "This subscription has already been renewed.",
};

export type ExtensionRun = { from: string; to: string; days: number };

export type ExtensionStatus = {
  subscriptionId: string;
  extensionAllowed: boolean;
  minConsecutiveAbsenceDays: number | null;
  startDate: string;
  baseExpiryDate: string | null;
  effectiveExpiryDate: string | null;
  /** start + calendar window - 1; null = nothing caps the extension. */
  maximumExpiryDate: string | null;
  evaluatedOn: string;
  /** Absent days up to and including this date are counted; today and later are not yet. */
  countedThrough: string;
  blockedReason: ExtensionBlock | null;
  countedAbsentDays: number;
  qualifyingRuns: ExtensionRun[];
  eligibleAbsenceDays: number;
  appliedExtensionDays: number;
  /** What applying would add right now, after the cap. */
  pendingExtensionDays: number;
  capped: boolean;
  projectedExpiryDate: string | null;
  canApply: boolean;
  overApplied: boolean;
  /** Applied days that are no longer eligible (reported, never taken back). */
  overAppliedDays: number;
  eventCount: number;
};

export type ExtensionEvent = {
  id: string;
  subscriptionId: string;
  sequenceNo: number;
  kind: ExtensionKind;
  triggerSource: ExtensionTrigger;
  createdBy: string | null;
  evaluatedOn: string;
  eligibleAbsenceDays: number;
  requestedExtensionDays: number;
  appliedExtensionDays: number;
  overAppliedDays: number;
  previousExpiryDate: string;
  newExpiryDate: string;
  maximumExpiryDate: string | null;
  capped: boolean;
  createdAt: string;
};

/** What the customer sees: the same facts without the staff account. */
export type MyExtensionEvent = Omit<ExtensionEvent, "subscriptionId" | "createdBy" | "evaluatedOn" | "requestedExtensionDays">;

export type ApplyExtensionResult = {
  applied: boolean;
  event: ExtensionEvent | null;
  status: ExtensionStatus;
};

/**
 * One function per Phase 9 endpoint. The backend decides what is eligible, what is capped and what
 * would change; the client sends no amounts and never re-implements the extension rule.
 */
export const extensionApi = {
  status(accessToken: string, providerId: string, subscriptionId: string): Promise<ExtensionStatus> {
    return apiFetch<ExtensionStatus>(`/providers/${providerId}/subscriptions/${subscriptionId}/extension`, {
      accessToken,
    });
  },

  events(accessToken: string, providerId: string, subscriptionId: string): Promise<ExtensionEvent[]> {
    return apiFetch<ExtensionEvent[]>(`/providers/${providerId}/subscriptions/${subscriptionId}/extension/events`, {
      accessToken,
    });
  },

  /** Idempotent: when nothing is left to add the server answers `applied: false`. */
  apply(accessToken: string, providerId: string, subscriptionId: string): Promise<ApplyExtensionResult> {
    return apiFetch<ApplyExtensionResult>(
      `/providers/${providerId}/subscriptions/${subscriptionId}/extension/apply`,
      { method: "POST", accessToken, body: {} },
    );
  },

  myStatus(accessToken: string, subscriptionId: string): Promise<ExtensionStatus> {
    return apiFetch<ExtensionStatus>(`/subscriptions/${subscriptionId}/extension`, { accessToken });
  },

  myEvents(accessToken: string, subscriptionId: string): Promise<MyExtensionEvent[]> {
    return apiFetch<MyExtensionEvent[]>(`/subscriptions/${subscriptionId}/extension/events`, { accessToken });
  },
};
