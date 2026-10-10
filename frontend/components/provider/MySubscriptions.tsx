"use client";

import { useEffect, useState } from "react";
import { ApiError } from "@/lib/api-client";
import {
  PHASE_LABEL,
  describeRemaining,
  formatDay,
  subscriptionApi,
  type MySubscription,
} from "@/lib/subscription-api";
import { ErrorBanner } from "@/components/auth/ErrorBanner";
import { ExtensionDetails } from "./ExtensionDetails";
import { MyAttendance } from "./MyAttendance";

type Props = {
  accessToken: string;
};

function describe(err: unknown): string {
  if (err instanceof ApiError && err.status === 401) {
    return "Your session expired. Go to Account and press “Refresh session”, then try again.";
  }
  return err instanceof Error ? err.message : "Something went wrong.";
}

/** The customer's own subscriptions, with the terms they were sold under. Read-only. */
export function MySubscriptions({ accessToken }: Props) {
  const [subs, setSubs] = useState<MySubscription[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    subscriptionApi
      .mine(accessToken)
      .then((list) => {
        if (!cancelled) setSubs(list);
      })
      .catch((err: unknown) => {
        if (!cancelled) setError(describe(err));
      });
    return () => {
      cancelled = true;
    };
  }, [accessToken]);

  return (
    <div>
      <p className="ff-section-title">My subscriptions</p>
      <ErrorBanner message={error} />
      {subs === null ? (
        <p className="ff-sub">Loading…</p>
      ) : subs.length === 0 ? (
        <p className="ff-sub">You have no subscriptions yet.</p>
      ) : (
        subs.map((s) => (
          <div key={s.id} className="ff-plan">
            <div className="ff-plan-head">
              <span>
                {s.providerName} · {s.terms.planName}
              </span>
              <span className={s.phase === "RUNNING" || s.phase === "UPCOMING" ? "ff-status-active" : "ff-status-suspended"}>
                {PHASE_LABEL[s.phase]}
              </span>
            </div>
            <p className="ff-plan-terms">
              {formatDay(s.startDate)} → {formatDay(s.effectiveExpiryDate)} · {describeRemaining(s)}
            </p>
            <p className="ff-plan-terms">
              {s.terms.currency} {s.terms.price} for{" "}
              {s.terms.purchasedDays !== null ? `${s.terms.purchasedDays} days` : `${s.terms.purchasedMeals} meals`}
            </p>
            {s.consumptionType === "DAY" && s.terms.extensionAllowed && (
              <ExtensionDetails accessToken={accessToken} subscriptionId={s.id} />
            )}
            {(s.phase === "RUNNING" || s.phase === "UPCOMING") && (
              <MyAttendance accessToken={accessToken} subscription={s} />
            )}
          </div>
        ))
      )}
    </div>
  );
}
