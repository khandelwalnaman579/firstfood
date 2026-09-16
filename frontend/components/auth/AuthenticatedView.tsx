"use client";

import { useState } from "react";
import type { Profile } from "@/lib/auth-api";
import { ErrorBanner } from "./ErrorBanner";

type Props = {
  profile: Profile;
  onRefresh: () => Promise<void>;
  onLogout: () => Promise<void>;
  onUpdateEmail: (email: string) => Promise<void>;
  onRequestPhoneChange: (newPhone: string) => Promise<void>;
  onVerifyPhoneChange: (newPhone: string, code: string) => Promise<void>;
};

export function AuthenticatedView({
  profile,
  onRefresh,
  onLogout,
  onUpdateEmail,
  onRequestPhoneChange,
  onVerifyPhoneChange,
}: Props) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [email, setEmail] = useState(profile.email ?? "");
  const [emailSaved, setEmailSaved] = useState(false);

  const [newPhone, setNewPhone] = useState("");
  const [phoneOtpSent, setPhoneOtpSent] = useState(false);
  const [phoneCode, setPhoneCode] = useState("");

  async function guarded(action: () => Promise<void>) {
    setError(null);
    setBusy(true);
    try {
      await action();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Something went wrong.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="ff-card">
      <h1>Welcome</h1>
      <p className="ff-sub">You&apos;re signed in.</p>
      <ErrorBanner message={error} />

      <dl>
        <div className="ff-profile-row">
          <dt>Phone</dt>
          <dd>{profile.phone}</dd>
        </div>
        <div className="ff-profile-row">
          <dt>Status</dt>
          <dd className={profile.status === "ACTIVE" ? "ff-status-active" : "ff-status-suspended"}>
            {profile.status}
          </dd>
        </div>
        <div className="ff-profile-row">
          <dt>Email</dt>
          <dd>{profile.email ?? "—"}</dd>
        </div>
      </dl>

      <div className="ff-button-row" style={{ marginTop: "1.25rem" }}>
        <button
          type="button"
          className="ff-button ff-button-secondary"
          disabled={busy}
          onClick={() => guarded(onRefresh)}
        >
          Refresh session
        </button>
        <button type="button" className="ff-button" disabled={busy} onClick={() => guarded(onLogout)}>
          Log out
        </button>
      </div>

      <hr className="ff-section-divider" />
      <p className="ff-section-title">Update email</p>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          setEmailSaved(false);
          guarded(async () => {
            await onUpdateEmail(email);
            setEmailSaved(true);
          });
        }}
      >
        <div className="ff-field">
          <label htmlFor="email">Email</label>
          <input
            id="email"
            type="email"
            value={email}
            onChange={(e) => {
              setEmail(e.target.value);
              setEmailSaved(false);
            }}
            placeholder="you@example.com"
          />
        </div>
        <button type="submit" className="ff-button ff-button-secondary" disabled={busy}>
          {emailSaved ? "Saved" : "Update email"}
        </button>
      </form>

      <hr className="ff-section-divider" />
      <p className="ff-section-title">Change phone number</p>
      {!phoneOtpSent ? (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            guarded(async () => {
              await onRequestPhoneChange(newPhone);
              setPhoneOtpSent(true);
            });
          }}
        >
          <div className="ff-field">
            <label htmlFor="newPhone">New phone number</label>
            <input
              id="newPhone"
              type="tel"
              value={newPhone}
              onChange={(e) => setNewPhone(e.target.value)}
              placeholder="+919876543210"
              required
            />
          </div>
          <button type="submit" className="ff-button ff-button-secondary" disabled={busy}>
            Send code to new number
          </button>
        </form>
      ) : (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            guarded(async () => {
              await onVerifyPhoneChange(newPhone, phoneCode);
              setPhoneOtpSent(false);
              setPhoneCode("");
            });
          }}
        >
          <p className="ff-sub" style={{ marginBottom: "0.75rem" }}>
            Code sent to {newPhone}.
          </p>
          <div className="ff-field">
            <label htmlFor="phoneCode">Verification code</label>
            <input
              id="phoneCode"
              className="ff-otp-input"
              inputMode="numeric"
              maxLength={6}
              value={phoneCode}
              onChange={(e) => setPhoneCode(e.target.value.replace(/\D/g, ""))}
              placeholder="123456"
              required
            />
          </div>
          <div className="ff-button-row">
            <button
              type="button"
              className="ff-button ff-button-secondary"
              onClick={() => setPhoneOtpSent(false)}
            >
              Cancel
            </button>
            <button type="submit" className="ff-button" disabled={busy || phoneCode.length !== 6}>
              Confirm change
            </button>
          </div>
        </form>
      )}

      <p className="ff-dev-note">
        Phase 2 dev note: access and refresh tokens are kept in memory only (React state) for this
        test screen - they&apos;re gone on page reload. That&apos;s deliberate, not a bug: don&apos;t
        add localStorage here. Before production, move the refresh token to a Secure + HttpOnly +
        SameSite cookie instead (see memory.md and README).
      </p>
    </div>
  );
}
