"use client";

import { useCallback, useEffect, useState } from "react";
import { ApiError } from "@/lib/api-client";
import { membershipApi, type Customer } from "@/lib/membership-api";
import { describeQuantity, planApi, type Plan } from "@/lib/plan-api";
import { can, type Provider } from "@/lib/provider-api";
import {
  PHASE_LABEL,
  describeRemaining,
  formatDay,
  subscriptionApi,
  type Subscription,
} from "@/lib/subscription-api";
import { ErrorBanner } from "@/components/auth/ErrorBanner";
import { ExtensionDetails } from "./ExtensionDetails";

type Props = {
  accessToken: string;
  provider: Provider;
};

function describe(err: unknown): string {
  if (err instanceof ApiError && err.status === 401) {
    return "Your session expired. Go to Account and press “Refresh session”, then try again.";
  }
  return err instanceof Error ? err.message : "Something went wrong.";
}

/**
 * Subscriptions of one provider: sell, cancel, renew. Buttons come from the server's own
 * `renewable` / `cancellable` flags and the caller's permissions - hints only; every action is
 * re-authorized and re-validated server-side, which also owns price, dates and overlap.
 */
export function SubscriptionsPanel({ accessToken, provider }: Props) {
  const canView = can(provider, "SUBSCRIPTION_VIEW");
  const canManage = can(provider, "SUBSCRIPTION_MANAGE");
  const canViewExtension = can(provider, "EXTENSION_VIEW");
  const canApplyExtension = can(provider, "EXTENSION_MANAGE") && provider.status !== "CLOSED";
  const closed = provider.status === "CLOSED";
  const providerId = provider.id;

  const [subs, setSubs] = useState<Subscription[] | null>(null);
  const [customers, setCustomers] = useState<Customer[]>([]);
  const [plans, setPlans] = useState<Plan[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [membershipId, setMembershipId] = useState("");
  const [planId, setPlanId] = useState("");
  const [startDate, setStartDate] = useState("");
  const [cancelling, setCancelling] = useState<string | null>(null);
  const [reason, setReason] = useState("");

  const reload = useCallback(async () => {
    if (!canView) {
      return;
    }
    try {
      setSubs(await subscriptionApi.list(accessToken, providerId));
      if (canManage && can(provider, "MEMBERSHIP_VIEW")) {
        setCustomers(await membershipApi.list(accessToken, providerId, false));
      }
      if (canManage && can(provider, "PLAN_VIEW")) {
        setPlans((await planApi.list(accessToken, providerId, false)).filter((p) => p.status === "ACTIVE"));
      }
    } catch (err) {
      setError(describe(err));
    }
  }, [accessToken, providerId, provider, canView, canManage]);

  useEffect(() => {
    void reload();
  }, [reload]);

  async function guarded(action: () => Promise<void>) {
    setError(null);
    setBusy(true);
    try {
      await action();
    } catch (err) {
      setError(describe(err));
    } finally {
      setBusy(false);
    }
  }

  function handleSell() {
    return guarded(async () => {
      await subscriptionApi.create(accessToken, providerId, {
        membershipId,
        planId,
        ...(startDate ? { startDate } : {}),
      });
      setMembershipId("");
      setPlanId("");
      setStartDate("");
      await reload();
    });
  }

  function handleCancel(id: string) {
    return guarded(async () => {
      await subscriptionApi.cancel(accessToken, providerId, id, reason.trim() || undefined);
      setCancelling(null);
      setReason("");
      await reload();
    });
  }

  function handleRenew(id: string) {
    return guarded(async () => {
      await subscriptionApi.renew(accessToken, providerId, id);
      await reload();
    });
  }

  if (!canView) {
    return (
      <div>
        <p className="ff-section-title">Subscriptions</p>
        <p className="ff-sub">Your role ({provider.myRole}) does not include viewing subscriptions.</p>
      </div>
    );
  }

  return (
    <div>
      <p className="ff-section-title">Subscriptions</p>
      <ErrorBanner message={error} />

      {subs === null ? (
        <p className="ff-sub">Loading…</p>
      ) : subs.length === 0 ? (
        <p className="ff-sub">No subscriptions yet.</p>
      ) : (
        subs.map((s) => (
          <div key={s.id} className="ff-plan">
            <div className="ff-plan-head">
              <span>
                {s.customerName} · {s.terms.planName}
              </span>
              <span className={s.phase === "RUNNING" || s.phase === "UPCOMING" ? "ff-status-active" : "ff-status-suspended"}>
                {PHASE_LABEL[s.phase]}
              </span>
            </div>
            <p className="ff-plan-terms">
              {formatDay(s.startDate)} → {formatDay(s.effectiveExpiryDate)} · {describeRemaining(s)}
            </p>
            <p className="ff-plan-terms">
              Sold at {s.terms.currency} {s.terms.price} for{" "}
              {s.terms.purchasedDays !== null ? `${s.terms.purchasedDays} days` : `${s.terms.purchasedMeals} meals`}
              {s.renewedFromSubscriptionId ? " · renewal" : ""}
              {s.renewed ? " · renewed" : ""}
            </p>
            {s.cancellationReason && <p className="ff-plan-terms">Cancelled: {s.cancellationReason}</p>}
            {canViewExtension && s.consumptionType === "DAY" && s.terms.extensionAllowed && (
              <ExtensionDetails
                accessToken={accessToken}
                providerId={providerId}
                subscriptionId={s.id}
                canApply={canApplyExtension}
                onApplied={() => void reload()}
              />
            )}

            {canManage && !closed && cancelling === s.id ? (
              <div style={{ marginTop: "0.5rem" }}>
                <div className="ff-field">
                  <label htmlFor={`reason-${s.id}`}>Reason (optional)</label>
                  <input
                    id={`reason-${s.id}`}
                    value={reason}
                    onChange={(e) => setReason(e.target.value)}
                    maxLength={500}
                  />
                </div>
                <div className="ff-button-row">
                  <button type="button" className="ff-button ff-button-secondary" onClick={() => setCancelling(null)}>
                    Keep
                  </button>
                  <button type="button" className="ff-button" disabled={busy} onClick={() => void handleCancel(s.id)}>
                    Cancel subscription
                  </button>
                </div>
              </div>
            ) : (
              canManage &&
              !closed && (
                <p className="ff-plan-terms">
                  {s.cancellable && (
                    <button type="button" className="ff-link-button" disabled={busy} onClick={() => setCancelling(s.id)}>
                      Cancel
                    </button>
                  )}
                  {s.renewable && (
                    <button type="button" className="ff-link-button" disabled={busy} onClick={() => void handleRenew(s.id)}>
                      Renew
                    </button>
                  )}
                </p>
              )
            )}
          </div>
        ))
      )}

      {closed && <p className="ff-dev-note">This provider is closed, so subscriptions can no longer be changed.</p>}

      {canManage && !closed && (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            void handleSell();
          }}
        >
          <p className="ff-section-title">Sell a subscription</p>
          <div className="ff-field">
            <label htmlFor="sub-customer">Customer</label>
            <select id="sub-customer" value={membershipId} onChange={(e) => setMembershipId(e.target.value)} required>
              <option value="">Choose…</option>
              {customers.map((c) => (
                <option key={c.membershipId} value={c.membershipId}>
                  {c.fullName}
                  {c.phone ? ` · ${c.phone}` : ""}
                </option>
              ))}
            </select>
          </div>
          <div className="ff-field">
            <label htmlFor="sub-plan">Plan</label>
            <select id="sub-plan" value={planId} onChange={(e) => setPlanId(e.target.value)} required>
              <option value="">Choose…</option>
              {plans.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name} · {describeQuantity(p)} · {p.currency} {p.price}
                </option>
              ))}
            </select>
          </div>
          <div className="ff-field">
            <label htmlFor="sub-start">Start date (blank = today)</label>
            <input id="sub-start" type="date" value={startDate} onChange={(e) => setStartDate(e.target.value)} />
          </div>
          <p className="ff-sub" style={{ marginBottom: "0.75rem" }}>
            The plan&apos;s price and terms are copied at the moment of sale and never change afterwards, even if
            the plan is edited.
          </p>
          <button type="submit" className="ff-button" disabled={busy || !membershipId || !planId}>
            Sell subscription
          </button>
        </form>
      )}
    </div>
  );
}
