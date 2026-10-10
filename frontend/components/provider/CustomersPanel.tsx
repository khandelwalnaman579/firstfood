"use client";

import { useCallback, useEffect, useState } from "react";
import { ApiError } from "@/lib/api-client";
import { membershipApi, type Customer } from "@/lib/membership-api";
import { can, type Provider } from "@/lib/provider-api";
import { ErrorBanner } from "@/components/auth/ErrorBanner";

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
 * Customers (memberships) of one provider. Controls are shown or hidden from the
 * permissions the backend reported for the caller - a usability nicety only; every
 * action is re-authorized server-side.
 */
export function CustomersPanel({ accessToken, provider }: Props) {
  const canView = can(provider, "MEMBERSHIP_VIEW");
  const canManage = can(provider, "MEMBERSHIP_MANAGE");
  const closed = provider.status === "CLOSED";
  const accepting = provider.acceptingNewCustomers && !closed;

  const [customers, setCustomers] = useState<Customer[] | null>(null);
  const [showFormer, setShowFormer] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [phone, setPhone] = useState("");
  const [fullName, setFullName] = useState("");

  const providerId = provider.id;

  const reload = useCallback(async () => {
    if (!canView) {
      return;
    }
    try {
      setCustomers(await membershipApi.list(accessToken, providerId, showFormer));
    } catch (err) {
      setError(describe(err));
    }
  }, [accessToken, providerId, canView, showFormer]);

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

  function handleAdd() {
    return guarded(async () => {
      const name = fullName.trim();
      await membershipApi.add(accessToken, providerId, {
        phone: phone.trim(),
        ...(name ? { fullName: name } : {}),
      });
      setPhone("");
      setFullName("");
      await reload();
    });
  }

  function handleDeactivate(membershipId: string) {
    return guarded(async () => {
      await membershipApi.deactivate(accessToken, providerId, membershipId);
      await reload();
    });
  }

  if (!canView) {
    return (
      <div>
        <p className="ff-section-title">Customers</p>
        <p className="ff-sub">Your role ({provider.myRole}) does not include viewing customers.</p>
      </div>
    );
  }

  return (
    <div>
      <p className="ff-section-title">Customers</p>
      <ErrorBanner message={error} />

      {customers === null ? (
        <p className="ff-sub">Loading…</p>
      ) : customers.length === 0 ? (
        <p className="ff-sub">{showFormer ? "No customers yet." : "No active customers yet."}</p>
      ) : (
        <dl>
          {customers.map((c) => (
            <div key={c.membershipId} className="ff-profile-row">
              <dt>
                {c.fullName}
                {c.phone ? ` · ${c.phone}` : ""}
              </dt>
              <dd>
                {c.status === "ACTIVE" ? (
                  <>
                    Since {new Date(c.joinedAt).toLocaleDateString()}
                    {canManage && !closed && (
                      <>
                        {" "}
                        <button
                          type="button"
                          className="ff-link-button"
                          disabled={busy}
                          onClick={() => void handleDeactivate(c.membershipId)}
                        >
                          Remove
                        </button>
                      </>
                    )}
                  </>
                ) : (
                  <span className="ff-status-suspended">
                    Left {c.leftAt ? new Date(c.leftAt).toLocaleDateString() : ""}
                  </span>
                )}
              </dd>
            </div>
          ))}
        </dl>
      )}

      <button type="button" className="ff-link-button" onClick={() => setShowFormer((v) => !v)}>
        {showFormer ? "Hide former customers" : "Show former customers"}
      </button>

      {closed && <p className="ff-dev-note">This provider is closed, so customers can no longer be changed.</p>}
      {!closed && !accepting && canManage && (
        <p className="ff-dev-note">
          This provider is not accepting new customers. Turn new customers back on under Availability to add
          someone.
        </p>
      )}

      {canManage && accepting && (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            void handleAdd();
          }}
        >
          <p className="ff-section-title">Add customer</p>
          <div className="ff-field">
            <label htmlFor="customer-phone">Registered phone number</label>
            <input
              id="customer-phone"
              type="tel"
              value={phone}
              onChange={(e) => setPhone(e.target.value)}
              placeholder="+919876543210"
              maxLength={32}
              required
            />
          </div>
          <div className="ff-field">
            <label htmlFor="customer-name">Full name (needed only for a first-time customer)</label>
            <input id="customer-name" value={fullName} onChange={(e) => setFullName(e.target.value)} maxLength={120} />
          </div>
          <p className="ff-sub" style={{ marginBottom: "0.75rem" }}>
            The customer must already have a FirstFood account. Re-adding a former customer starts a new
            membership and keeps the old one in their history.
          </p>
          <button type="submit" className="ff-button" disabled={busy || !phone.trim()}>
            Add customer
          </button>
        </form>
      )}
    </div>
  );
}
