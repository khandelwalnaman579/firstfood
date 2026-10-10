"use client";

import { useCallback, useEffect, useState } from "react";
import { ApiError } from "@/lib/api-client";
import { LOCKED_REASON_TEXT, attendanceApi, isoDay, type MyAttendanceDay } from "@/lib/attendance-api";
import { formatDay, type MySubscription } from "@/lib/subscription-api";
import { ErrorBanner } from "@/components/auth/ErrorBanner";

type Props = {
  accessToken: string;
  subscription: MySubscription;
};

function describe(err: unknown): string {
  if (err instanceof ApiError && err.status === 401) {
    return "Your session expired. Go to Account and press “Refresh session”, then try again.";
  }
  return err instanceof Error ? err.message : "Something went wrong.";
}

/**
 * "Open subscription -> select date -> Not eating -> confirm". Attendance is opt-out, so a day is eating unless
 * declared otherwise. Whether a day can still be changed (cutoff, same-day policy, provider-set days) is decided by
 * the server and shown here; this component never re-derives it.
 */
export function MyAttendance({ accessToken, subscription }: Props) {
  const [days, setDays] = useState<MyAttendanceDay[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [open, setOpen] = useState(false);
  const [from, setFrom] = useState(isoDay(new Date()));
  const [to, setTo] = useState("");
  const [reason, setReason] = useState("");

  const reload = useCallback(async () => {
    try {
      setDays(await attendanceApi.myDays(accessToken, subscription.id));
      setError(null);
    } catch (err) {
      setError(describe(err));
    }
  }, [accessToken, subscription.id]);

  useEffect(() => {
    if (open) {
      void reload();
    }
  }, [open, reload]);

  async function run(action: () => Promise<unknown>) {
    setBusy(true);
    setError(null);
    try {
      await action();
      await reload();
    } catch (err) {
      setError(describe(err));
    } finally {
      setBusy(false);
    }
  }

  if (subscription.consumptionType !== "DAY") {
    return null;
  }

  return (
    <div>
      <button type="button" className="ff-link-button" onClick={() => setOpen(!open)}>
        {open ? "Hide days" : "Days & absences"}
      </button>
      {open && (
        <>
          <ErrorBanner message={error} />
          <div className="ff-field">
            <label htmlFor={`abs-from-${subscription.id}`}>Not eating from</label>
            <input id={`abs-from-${subscription.id}`} type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
            <label htmlFor={`abs-to-${subscription.id}`}>Until (optional)</label>
            <input id={`abs-to-${subscription.id}`} type="date" value={to} onChange={(e) => setTo(e.target.value)} />
            <label htmlFor={`abs-reason-${subscription.id}`}>Reason (optional)</label>
            <input
              id={`abs-reason-${subscription.id}`}
              value={reason}
              maxLength={500}
              onChange={(e) => setReason(e.target.value)}
            />
            <div className="ff-button-row">
              <button
                type="button"
                className="ff-button"
                disabled={busy || !from}
                onClick={() =>
                  void run(() => attendanceApi.declare(accessToken, subscription.id, from, to || undefined, reason.trim() || undefined))
                }
              >
                Mark not eating
              </button>
            </div>
          </div>
          {days === null ? (
            <p className="ff-sub">Loading…</p>
          ) : days.length === 0 ? (
            <p className="ff-sub">No days to show.</p>
          ) : (
            days.map((d) => (
              <p key={d.date} className="ff-plan-terms">
                {formatDay(d.date)} · {d.status === "ABSENT" ? "Not eating" : "Eating"}
                {d.providerSet && " (set by provider)"}
                {d.reason ? ` · “${d.reason}”` : ""}
                {d.status === "ABSENT" && d.absenceId && d.changeable && (
                  <>
                    {" "}
                    <button
                      type="button"
                      className="ff-link-button"
                      disabled={busy}
                      onClick={() => void run(() => attendanceApi.cancelAbsence(accessToken, subscription.id, d.absenceId as string))}
                    >
                      Undo
                    </button>
                  </>
                )}
                {!d.changeable && d.lockedReason && d.lockedReason !== "PAST" && ` · ${LOCKED_REASON_TEXT[d.lockedReason]}`}
              </p>
            ))
          )}
        </>
      )}
    </div>
  );
}
