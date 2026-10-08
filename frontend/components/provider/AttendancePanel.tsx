"use client";

import { useCallback, useEffect, useState } from "react";
import { ApiError } from "@/lib/api-client";
import {
  attendanceApi,
  isoDay,
  type AttendanceHistoryEntry,
  type DailySheet,
  type DailySheetRow,
} from "@/lib/attendance-api";
import { can, type Provider } from "@/lib/provider-api";
import { formatDay } from "@/lib/subscription-api";
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

const SOURCE_TEXT = {
  SYSTEM: "default",
  CUSTOMER: "customer",
  OWNER_CORRECTION: "provider",
} as const;

/**
 * The daily sheet that replaces the notebook: who is eating on a date, and who said they will not.
 * Staff can mark a customer absent or restore them, always with a reason; each day's history is one
 * click away. Buttons come from the caller's permissions (a hint) - the server re-checks everything.
 */
export function AttendancePanel({ accessToken, provider }: Props) {
  const canView = can(provider, "ATTENDANCE_VIEW");
  const canManage = can(provider, "ATTENDANCE_MANAGE") && provider.status !== "CLOSED";
  const providerId = provider.id;

  const [date, setDate] = useState(isoDay(new Date()));
  const [sheet, setSheet] = useState<DailySheet | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [editing, setEditing] = useState<string | null>(null);
  const [reason, setReason] = useState("");
  const [historyFor, setHistoryFor] = useState<string | null>(null);
  const [history, setHistory] = useState<AttendanceHistoryEntry[]>([]);

  const reload = useCallback(async () => {
    if (!canView || !date) {
      return;
    }
    try {
      setSheet(await attendanceApi.dailySheet(accessToken, providerId, date));
      setError(null);
    } catch (err) {
      setError(describe(err));
    }
  }, [accessToken, providerId, date, canView]);

  useEffect(() => {
    void reload();
  }, [reload]);

  if (!canView) {
    return null;
  }

  async function decide(row: DailySheetRow) {
    setBusy(true);
    setError(null);
    try {
      const next = row.status === "ABSENT" ? "PRESENT" : "ABSENT";
      await attendanceApi.correct(accessToken, providerId, row.subscriptionId, date, next, reason.trim());
      setEditing(null);
      setReason("");
      await reload();
    } catch (err) {
      setError(describe(err));
    } finally {
      setBusy(false);
    }
  }

  async function toggleHistory(row: DailySheetRow) {
    if (historyFor === row.subscriptionId) {
      setHistoryFor(null);
      return;
    }
    try {
      setHistory(await attendanceApi.history(accessToken, providerId, row.subscriptionId, date));
      setHistoryFor(row.subscriptionId);
    } catch (err) {
      setError(describe(err));
    }
  }

  return (
    <div>
      <p className="ff-section-title">Attendance</p>
      <ErrorBanner message={error} />
      <div className="ff-field">
        <label htmlFor={`att-date-${providerId}`}>Date</label>
        <input
          id={`att-date-${providerId}`}
          type="date"
          value={date}
          onChange={(e) => setDate(e.target.value)}
        />
      </div>
      {sheet === null ? (
        <p className="ff-sub">Loading…</p>
      ) : (
        <>
          <p className="ff-sub">
            {formatDay(sheet.date)}: <strong>{sheet.present}</strong> eating · <strong>{sheet.absent}</strong> not
            eating · {sheet.expected} expected
          </p>
          {sheet.rows.length === 0 && <p className="ff-sub">No day-based subscriptions are running on this date.</p>}
          {sheet.rows.map((row) => (
            <div key={row.subscriptionId} className="ff-plan">
              <div className="ff-plan-head">
                <span>{row.customerName}</span>
                <span className={row.status === "ABSENT" ? "ff-status-suspended" : "ff-status-active"}>
                  {row.status === "ABSENT" ? "Not eating" : "Eating"}
                </span>
              </div>
              <p className="ff-plan-terms">
                {row.planName} · until {formatDay(row.effectiveExpiryDate)}
                {!row.assumed && ` · set by ${SOURCE_TEXT[row.source]}`}
                {row.reason ? ` · “${row.reason}”` : ""}
              </p>
              <div className="ff-button-row">
                {canManage && editing !== row.subscriptionId && (
                  <button
                    type="button"
                    className="ff-button ff-button-secondary"
                    onClick={() => {
                      setEditing(row.subscriptionId);
                      setReason("");
                    }}
                  >
                    {row.status === "ABSENT" ? "Mark eating" : "Mark not eating"}
                  </button>
                )}
                <button type="button" className="ff-link-button" onClick={() => void toggleHistory(row)}>
                  {historyFor === row.subscriptionId ? "Hide history" : "History"}
                </button>
              </div>
              {editing === row.subscriptionId && (
                <div className="ff-field">
                  <label htmlFor={`att-reason-${row.subscriptionId}`}>Reason (required)</label>
                  <input
                    id={`att-reason-${row.subscriptionId}`}
                    value={reason}
                    maxLength={500}
                    onChange={(e) => setReason(e.target.value)}
                  />
                  <div className="ff-button-row">
                    <button
                      type="button"
                      className="ff-button"
                      disabled={busy || reason.trim() === ""}
                      onClick={() => void decide(row)}
                    >
                      {row.status === "ABSENT" ? "Confirm: eating" : "Confirm: not eating"}
                    </button>
                    <button type="button" className="ff-link-button" onClick={() => setEditing(null)}>
                      Cancel
                    </button>
                  </div>
                </div>
              )}
              {historyFor === row.subscriptionId && (
                <div>
                  {history.length === 0 ? (
                    <p className="ff-plan-terms">Nothing recorded: assumed eating.</p>
                  ) : (
                    history.map((h) => (
                      <p key={h.id} className="ff-plan-terms">
                        {new Date(h.recordedAt).toLocaleString()} · {h.status === "ABSENT" ? "not eating" : "eating"}{" "}
                        · {SOURCE_TEXT[h.source]}
                        {h.reason ? ` · “${h.reason}”` : ""}
                        {h.current ? " · current" : ""}
                      </p>
                    ))
                  )}
                </div>
              )}
            </div>
          ))}
        </>
      )}
    </div>
  );
}
