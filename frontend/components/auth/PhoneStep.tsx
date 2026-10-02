"use client";

import { useState } from "react";
import { ErrorBanner } from "./ErrorBanner";

export function PhoneStep({
  onOtpRequested,
}: {
  onOtpRequested: (phone: string) => Promise<void>;
}) {
  const [phone, setPhone] = useState("+91");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await onOtpRequested(phone);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not send the code. Try again.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="ff-card">
      <h1>Sign in</h1>
      <p className="ff-sub">Enter your phone number and we&apos;ll send a code to verify it.</p>
      <ErrorBanner message={error} />
      <form onSubmit={handleSubmit}>
        <div className="ff-field">
          <label htmlFor="phone">Phone number</label>
          <input
            id="phone"
            type="tel"
            value={phone}
            onChange={(e) => setPhone(e.target.value)}
            placeholder="+919876543210"
            required
          />
        </div>
        <button type="submit" className="ff-button" disabled={submitting}>
          {submitting ? "Sending code…" : "Send code"}
        </button>
      </form>
    </div>
  );
}
