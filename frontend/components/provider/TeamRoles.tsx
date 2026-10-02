"use client";

import { useCallback, useEffect, useState } from "react";
import { ApiError } from "@/lib/api-client";
import { can, type Provider } from "@/lib/provider-api";
import { roleApi, type AssignableRole, type RoleAssignment } from "@/lib/role-api";
import { ErrorBanner } from "@/components/auth/ErrorBanner";

type Props = {
  accessToken: string;
  provider: Provider;
  onRolesChanged: () => void;
};

function describe(err: unknown): string {
  if (err instanceof ApiError && err.status === 401) {
    return "Your session expired. Go to Account and press “Refresh session”, then try again.";
  }
  return err instanceof Error ? err.message : "Something went wrong.";
}

/**
 * Team / roles for one provider. Controls are shown or hidden from the
 * permissions the backend reported for the caller - a usability nicety only.
 * Every action is re-authorized server-side, so a hidden button is never the
 * thing standing between a user and an operation.
 */
export function TeamRoles({ accessToken, provider, onRolesChanged }: Props) {
  const canView = can(provider, "ROLE_VIEW");
  const canAssign = can(provider, "ROLE_ASSIGN");
  const canRevoke = can(provider, "ROLE_REVOKE");
  const canTransfer = can(provider, "OWNER_TRANSFER");
  const closed = provider.status === "CLOSED";

  const [members, setMembers] = useState<RoleAssignment[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [phone, setPhone] = useState("");
  const [role, setRole] = useState<AssignableRole>("WORKER");
  const [transferPhone, setTransferPhone] = useState("");
  const [confirmingTransfer, setConfirmingTransfer] = useState(false);

  const providerId = provider.id;

  const reload = useCallback(async () => {
    if (!canView) {
      return;
    }
    try {
      setMembers(await roleApi.list(accessToken, providerId));
    } catch (err) {
      setError(describe(err));
    }
  }, [accessToken, providerId, canView]);

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

  function handleAssign() {
    return guarded(async () => {
      await roleApi.assign(accessToken, providerId, { phone: phone.trim(), role });
      setPhone("");
      await reload();
    });
  }

  function handleRevoke(assignmentId: string) {
    return guarded(async () => {
      await roleApi.revoke(accessToken, providerId, assignmentId);
      await reload();
    });
  }

  function handleTransfer() {
    return guarded(async () => {
      await roleApi.transferOwnership(accessToken, providerId, transferPhone.trim());
      setTransferPhone("");
      setConfirmingTransfer(false);
      // My own role just changed (OWNER -> MANAGER): refresh provider + permissions.
      onRolesChanged();
      await reload();
    });
  }

  if (!canView) {
    return (
      <div>
        <p className="ff-section-title">Team</p>
        <p className="ff-sub">Your role ({provider.myRole}) does not include viewing the team.</p>
      </div>
    );
  }

  return (
    <div>
      <p className="ff-section-title">Team</p>
      <ErrorBanner message={error} />

      {members === null ? (
        <p className="ff-sub">Loading…</p>
      ) : (
        <dl>
          {members.map((m) => (
            <div key={m.id} className="ff-profile-row">
              <dt>{m.accountPhone ?? "Unknown account"}</dt>
              <dd>
                {m.role}
                {canRevoke && !closed && m.role !== "OWNER" && (
                  <>
                    {" "}
                    <button
                      type="button"
                      className="ff-link-button"
                      disabled={busy}
                      onClick={() => void handleRevoke(m.id)}
                    >
                      Remove
                    </button>
                  </>
                )}
              </dd>
            </div>
          ))}
        </dl>
      )}

      {closed && <p className="ff-dev-note">This provider is closed, so the team can no longer be changed.</p>}

      {canAssign && !closed && (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            void handleAssign();
          }}
        >
          <p className="ff-section-title">Add member</p>
          <div className="ff-field">
            <label htmlFor="member-phone">Registered phone number</label>
            <input
              id="member-phone"
              type="tel"
              value={phone}
              onChange={(e) => setPhone(e.target.value)}
              placeholder="+919876543210"
              maxLength={32}
              required
            />
          </div>
          <div className="ff-field">
            <label htmlFor="member-role">Role</label>
            <select id="member-role" value={role} onChange={(e) => setRole(e.target.value as AssignableRole)}>
              <option value="MANAGER">MANAGER</option>
              <option value="WORKER">WORKER</option>
            </select>
          </div>
          <button type="submit" className="ff-button" disabled={busy || !phone.trim()}>
            Add member
          </button>
        </form>
      )}

      {canTransfer && !closed && (
        <>
          <hr className="ff-section-divider" />
          <p className="ff-section-title">Transfer ownership</p>
          {!confirmingTransfer ? (
            <button type="button" className="ff-button ff-button-secondary" onClick={() => setConfirmingTransfer(true)}>
              Transfer ownership…
            </button>
          ) : (
            <div>
              <p className="ff-sub" style={{ marginBottom: "0.75rem" }}>
                The new owner must already have a FirstFood account. You will become a MANAGER and lose owner
                controls immediately.
              </p>
              <div className="ff-field">
                <label htmlFor="transfer-phone">New owner&apos;s registered phone number</label>
                <input
                  id="transfer-phone"
                  type="tel"
                  value={transferPhone}
                  onChange={(e) => setTransferPhone(e.target.value)}
                  maxLength={32}
                />
              </div>
              <div className="ff-button-row">
                <button
                  type="button"
                  className="ff-button ff-button-secondary"
                  onClick={() => setConfirmingTransfer(false)}
                >
                  Cancel
                </button>
                <button
                  type="button"
                  className="ff-button"
                  disabled={busy || !transferPhone.trim()}
                  onClick={() => void handleTransfer()}
                >
                  Transfer ownership
                </button>
              </div>
            </div>
          )}
        </>
      )}
    </div>
  );
}
