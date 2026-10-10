"use client";

import { useCallback, useEffect, useState } from "react";
import { ApiError } from "@/lib/api-client";
import {
  BLOCK_TEXT,
  extensionApi,
  type ExtensionEvent,
  type ExtensionStatus,
  type MyExtensionEvent,
} from "@/lib/extension-api";
import { formatDay } from "@/lib/subscription-api";
import { ErrorBanner } from "@/components/auth/ErrorBanner";

function describe(err: unknown): string {
  if (err instanceof ApiError && err.status === 401) {
    return "Your session expired. Go to Account and press “Refresh session”, then try again.";
  }
  return err instanceof Error ? err.message : "Something went wrong.";
}

type Shown = ExtensionEvent | MyExtensionEvent;

type Props = {
  accessToken: string;
  subscriptionId: string;
  /** Provider side: set both to show staff controls. Omit for the customer's read-only view. */
  providerId?: string;
  canApply?: boolean;
  /** Called after an extension was applied, so the parent can reload the new expiry. */
  onApplied?: () => void;
};

/**
 * Extension standing of one subscription: eligible absence days, what is pending, and the audit trail.
 * Everything shown is computed by the server; the Apply button is a hint from the caller's permissions
 * and the server's own `canApply` - the server re-checks both and applying twice is harmless.
 */
export function ExtensionDetails({ accessToken, subscriptionId, providerId, canApply = false, onApplied }: Props) {
  const staff = providerId !== undefined;
  const [status, setStatus] = useState<ExtensionStatus | null>(null);
  const [events, setEvents] = useState<Shown[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

  const reload = useCallback(async () => {
    try {
      const [s, e] = staff
        ? await Promise.all([
            extensionApi.status(accessToken, providerId, subscriptionId),
            extensionApi.events(accessToken, providerId, subscriptionId),
          ])
        : await Promise.all([
            extensionApi.myStatus(accessToken, subscriptionId),
            extensionApi.myEvents(accessToken, subscriptionId),
          ]);
      setStatus(s);
      setEvents(e);
      setError(null);
    } catch (err) {
      setError(describe(err));
    }
  }, [accessToken, providerId, staff, subscriptionId]);

  useEffect(() => {
    void reload();
  }, [reload]);

  async function handleApply() {
    if (!staff) return;
    setBusy(true);
    setNotice(null);
    try {
      const result = await extensionApi.apply(accessToken, providerId, subscriptionId);
      setNotice(
        result.applied && result.event
          ? `Extended by ${result.event.appliedExtensionDays} day(s) to ${formatDay(result.event.newExpiryDate)}.`
          : "Nothing to add right now.",
      );
      await reload();
      if (result.applied) onApplied?.();
    } catch (err) {
      setError(describe(err));
    } finally {
      setBusy(false);
    }
  }

  if (error && !status) {
    return <ErrorBanner message={error} />;
  }
  if (!status) {
    return <p className="ff-plan-terms">Loading extension…</p>;
  }

  return (
    <div style={{ marginTop: "0.5rem" }}>
      <p className="ff-plan-terms">
        <strong>Extension for absence</strong>
        {status.blockedReason ? ` · ${BLOCK_TEXT[status.blockedReason]}` : ""}
      </p>
      <ErrorBanner message={error} />
      {!status.blockedReason && (
        <>
          <p className="ff-plan-terms">
            Absences of {status.minConsecutiveAbsenceDays ?? "?"}+ days in a row count. Counted up to{" "}
            {formatDay(status.countedThrough)}: {status.eligibleAbsenceDays} eligible day(s),{" "}
            {status.appliedExtensionDays} already added
            {status.pendingExtensionDays > 0
              ? `, ${status.pendingExtensionDays} to add (new end ${formatDay(status.projectedExpiryDate)}).`
              : "."}
          </p>
          {status.maximumExpiryDate && (
            <p className="ff-plan-terms">Cannot go past {formatDay(status.maximumExpiryDate)}.</p>
          )}
          {status.capped && (
            <p className="ff-plan-terms">The extension is limited by that date; some absent days will not be added.</p>
          )}
          {status.overApplied && (
            <p className="ff-plan-terms">
              {status.overAppliedDays} day(s) added earlier are no longer eligible (an absence changed). Added days are
              never taken back.
            </p>
          )}
        </>
      )}
      {staff && canApply && status.canApply && (
        <div className="ff-button-row">
          <button type="button" className="ff-button" disabled={busy} onClick={() => void handleApply()}>
            Add {status.pendingExtensionDays} day(s)
          </button>
        </div>
      )}
      {notice && <p className="ff-plan-terms">{notice}</p>}
      {events.length > 0 && (
        <ul className="ff-plan-terms" style={{ margin: "0.25rem 0 0 1rem" }}>
          {events.map((e) => (
            <li key={e.id}>
              {e.kind === "OVER_APPLIED" ? (
                <>
                  #{e.sequenceNo}: {e.overAppliedDays} earlier day(s) are no longer eligible after a correction; the end
                  date stays {formatDay(e.newExpiryDate)}
                </>
              ) : (
                <>
                  #{e.sequenceNo}: +{e.appliedExtensionDays} day(s), {formatDay(e.previousExpiryDate)} →{" "}
                  {formatDay(e.newExpiryDate)}
                  {e.capped ? " (limited by the maximum)" : ""}
                </>
              )}{" "}
              · {e.triggerSource === "SYSTEM" ? "automatic" : "by staff"}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
