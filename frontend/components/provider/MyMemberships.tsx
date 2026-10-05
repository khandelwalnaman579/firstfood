"use client";

import { useCallback, useEffect, useState } from "react";
import { ApiError } from "@/lib/api-client";
import { membershipApi, type MyMembership, type Person } from "@/lib/membership-api";
import { ErrorBanner } from "@/components/auth/ErrorBanner";

type Props = {
  accessToken: string;
};

function describe(err: unknown): string {
  if (err instanceof ApiError && err.status === 401) {
    return "Your session expired. Go to Account and press “Refresh session”, then try again.";
  }
  return err instanceof Error ? err.message : "Something went wrong.";
}

/**
 * The customer's own side: their Person profile (the individual who eats - not the
 * login) and every provider they belong to, current and past.
 */
export function MyMemberships({ accessToken }: Props) {
  const [person, setPerson] = useState<Person | null | undefined>(undefined); // undefined = loading
  const [memberships, setMemberships] = useState<MyMembership[] | null>(null);
  const [name, setName] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try {
      let p: Person | null = null;
      try {
        p = await membershipApi.getMyPerson(accessToken);
      } catch (err) {
        if (!(err instanceof ApiError && err.code === "PERSON_NOT_FOUND")) {
          throw err;
        }
      }
      setPerson(p);
      setName(p?.fullName ?? "");
      setMemberships(await membershipApi.myMemberships(accessToken));
    } catch (err) {
      setError(describe(err));
    }
  }, [accessToken]);

  useEffect(() => {
    void load();
  }, [load]);

  async function handleSave() {
    setError(null);
    setBusy(true);
    try {
      const saved = await membershipApi.saveMyPerson(accessToken, name.trim());
      setPerson(saved);
      setName(saved.fullName);
    } catch (err) {
      setError(describe(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div>
      <h1>As a customer</h1>
      <p className="ff-sub">Your profile and the providers you eat with.</p>
      <ErrorBanner message={error} />

      <p className="ff-section-title">Your name</p>
      {person === undefined ? (
        <p className="ff-sub">Loading…</p>
      ) : (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            void handleSave();
          }}
        >
          {person === null && (
            <p className="ff-sub" style={{ marginBottom: "0.75rem" }}>
              You have no customer profile yet. One is created automatically when a provider adds you, or you can
              set it up now.
            </p>
          )}
          <div className="ff-field">
            <label htmlFor="person-name">Full name</label>
            <input id="person-name" value={name} onChange={(e) => setName(e.target.value)} maxLength={120} required />
          </div>
          <button
            type="submit"
            className="ff-button"
            disabled={busy || !name.trim() || name.trim() === (person?.fullName ?? "")}
          >
            {person === null ? "Create profile" : "Save name"}
          </button>
        </form>
      )}

      <hr className="ff-section-divider" />
      <p className="ff-section-title">My providers</p>
      {memberships === null ? (
        <p className="ff-sub">Loading…</p>
      ) : memberships.length === 0 ? (
        <p className="ff-sub">You are not a customer of any provider yet.</p>
      ) : (
        <dl>
          {memberships.map((m) => (
            <div key={m.membershipId} className="ff-profile-row">
              <dt>{m.providerName}</dt>
              <dd>
                {m.status === "ACTIVE" ? (
                  <span className="ff-status-active">Since {new Date(m.joinedAt).toLocaleDateString()}</span>
                ) : (
                  <span className="ff-status-suspended">
                    Left {m.leftAt ? new Date(m.leftAt).toLocaleDateString() : ""}
                  </span>
                )}
              </dd>
            </div>
          ))}
        </dl>
      )}
    </div>
  );
}
