"use client";

import { useState } from "react";
import { ErrorBanner } from "./ErrorBanner";

export function OtpStep({
  phone,
  onVerify,
  onBack,
}: {
  phone: string;
  onVerify: (code: string) => Promise<void>;
  onBack: () => void;
}) {
  const [code, setCode] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await onVerify(code);
    } catch (err) {
      setError(err instanceof Error ? err.message : "That code didn't work. Try again.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="ff-card">
      <h1>Enter the code</h1>
      <p className="ff-sub">
        We sent a 6-digit code to {phone}.{" "}
        {process.env.NODE_ENV !== "production" && "Check your backend terminal - the local profile prints it there."}
      </p>
      <ErrorBanner message={error} />
      <form onSubmit={handleSubmit}>
        <div className="ff-field">
          <label htmlFor="otp">Verification code</label>
          <input
            id="otp"
            className="ff-otp-input"
            inputMode="numeric"
            pattern="\d{6}"
            maxLength={6}
            value={code}
            onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))}
            placeholder="123456"
            required
          />
        </div>
        <div className="ff-button-row">
          <button type="button" className="ff-button ff-button-secondary" onClick={onBack}>
            Back
          </button>
          <button type="submit" className="ff-button" disabled={submitting || code.length !== 6}>
            {submitting ? "Verifying…" : "Verify"}
          </button>
        </div>
      </form>
    </div>
  );
}
