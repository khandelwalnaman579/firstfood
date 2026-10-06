"use client";

import { useCallback, useEffect, useState } from "react";
import { ApiError } from "@/lib/api-client";
import {
  CONSUMPTION_TYPES,
  describePolicy,
  describeQuantity,
  planApi,
  shortTime,
  type ConsumptionType,
  type CreatePlanInput,
  type Plan,
  type PolicyInput,
} from "@/lib/plan-api";
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

const TYPE_LABEL: Record<ConsumptionType, string> = {
  DAY: "DAY - sold by number of days",
  MEAL: "MEAL - sold by number of meals",
};

/**
 * A provider's plans. Controls come from the permissions the backend reported for the caller -
 * a usability nicety only; every action is re-authorized and every rule re-validated
 * server-side. Plans are only ever deactivated, never deleted. Editing a plan changes what
 * is offered from now on; it never changes a subscription that already exists.
 */
export function PlansPanel({ accessToken, provider }: Props) {
  const canView = can(provider, "PLAN_VIEW");
  const canManage = can(provider, "PLAN_MANAGE");
  const closed = provider.status === "CLOSED";

  const [plans, setPlans] = useState<Plan[] | null>(null);
  const [showInactive, setShowInactive] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  // null = no form open, "new" = creating, otherwise the plan being edited.
  const [editing, setEditing] = useState<Plan | "new" | null>(null);

  const providerId = provider.id;

  const reload = useCallback(async () => {
    if (!canView) {
      return;
    }
    try {
      setPlans(await planApi.list(accessToken, providerId, showInactive));
    } catch (err) {
      setError(describe(err));
    }
  }, [accessToken, providerId, canView, showInactive]);

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

  function handleSave(input: CreatePlanInput) {
    return guarded(async () => {
      if (editing === "new") {
        await planApi.create(accessToken, providerId, input);
      } else if (editing) {
        const { consumptionType: _type, ...update } = input;
        void _type;
        await planApi.update(accessToken, providerId, editing.id, update);
      }
      setEditing(null);
      await reload();
    });
  }

  function handleToggle(plan: Plan) {
    return guarded(async () => {
      if (plan.status === "ACTIVE") {
        await planApi.deactivate(accessToken, providerId, plan.id);
      } else {
        await planApi.activate(accessToken, providerId, plan.id);
      }
      await reload();
    });
  }

  if (!canView) {
    return (
      <div>
        <p className="ff-section-title">Plans</p>
        <p className="ff-sub">Your role ({provider.myRole}) does not include viewing plans.</p>
      </div>
    );
  }

  return (
    <div>
      <p className="ff-section-title">Plans</p>
      <ErrorBanner message={error} />

      {plans === null ? (
        <p className="ff-sub">Loading…</p>
      ) : plans.length === 0 ? (
        <p className="ff-sub">{showInactive ? "No plans yet." : "No active plans yet."}</p>
      ) : (
        <div>
          {plans.map((plan) => (
            <div key={plan.id} className="ff-plan">
              <div className="ff-plan-head">
                <span>
                  {plan.name} · {describeQuantity(plan)}
                </span>
                <span>
                  ₹{plan.price.toLocaleString("en-IN")}
                  {plan.status === "INACTIVE" && <span className="ff-status-suspended"> (inactive)</span>}
                </span>
              </div>
              {plan.description && <p className="ff-plan-terms">{plan.description}</p>}
              {describePolicy(plan).map((line) => (
                <p key={line} className="ff-plan-terms">
                  {line}
                </p>
              ))}
              <p className="ff-plan-terms">Policy version {plan.policy.version}</p>
              {canManage && !closed && (
                <div className="ff-button-row">
                  <button type="button" className="ff-link-button" disabled={busy} onClick={() => setEditing(plan)}>
                    Edit
                  </button>
                  <button type="button" className="ff-link-button" disabled={busy} onClick={() => void handleToggle(plan)}>
                    {plan.status === "ACTIVE" ? "Deactivate" : "Activate"}
                  </button>
                </div>
              )}
            </div>
          ))}
        </div>
      )}

      <button type="button" className="ff-link-button" onClick={() => setShowInactive((v) => !v)}>
        {showInactive ? "Hide inactive plans" : "Show inactive plans"}
      </button>

      {closed && <p className="ff-dev-note">This provider is closed, so plans can no longer be changed.</p>}
      {!canManage && !closed && (
        <p className="ff-dev-note">Your role ({provider.myRole}) can view plans but not change them.</p>
      )}

      {canManage && !closed && (
        <>
          <hr className="ff-section-divider" />
          {editing === null ? (
            <button type="button" className="ff-button ff-button-secondary" onClick={() => setEditing("new")}>
              Add a plan
            </button>
          ) : (
            <PlanForm
              key={editing === "new" ? "new" : editing.id}
              plan={editing === "new" ? null : editing}
              busy={busy}
              onSave={handleSave}
              onCancel={() => setEditing(null)}
            />
          )}
          <p className="ff-sub" style={{ marginTop: "0.75rem" }}>
            Changing a plan affects only subscriptions created afterwards. Existing subscriptions keep the terms they were
            sold under. Plans are never deleted - deactivate one to stop offering it.
          </p>
        </>
      )}
    </div>
  );
}

type FormProps = {
  /** null = create a new plan. */
  plan: Plan | null;
  busy: boolean;
  onSave: (input: CreatePlanInput) => Promise<void>;
  onCancel: () => void;
};

/**
 * Create / edit form. Which fields are shown follows the plan type and the two switches - this
 * only spares the owner irrelevant inputs; the backend enforces the real combinations.
 */
function PlanForm({ plan, busy, onSave, onCancel }: FormProps) {
  const editing = plan !== null;
  const [name, setName] = useState(plan?.name ?? "");
  const [description, setDescription] = useState(plan?.description ?? "");
  const [type, setType] = useState<ConsumptionType>(plan?.consumptionType ?? "DAY");
  const [duration, setDuration] = useState(plan?.durationDays?.toString() ?? "");
  const [meals, setMeals] = useState(plan?.mealQuantity?.toString() ?? "");
  const [price, setPrice] = useState(plan ? String(plan.price) : "");
  const [extension, setExtension] = useState(plan?.policy.extensionAllowed ?? false);
  const [minAbsence, setMinAbsence] = useState(plan?.policy.minConsecutiveAbsenceDays?.toString() ?? "");
  const [sameDay, setSameDay] = useState(plan?.policy.sameDayAbsenceAllowed ?? false);
  const [cutoff, setCutoff] = useState(shortTime(plan?.policy.absenceCutoffTime ?? null));
  const [window, setWindow] = useState(plan?.policy.maxCalendarWindowDays?.toString() ?? "");

  const isDay = type === "DAY";
  const extensionOn = isDay && extension;
  const showWindow = !isDay || extensionOn;

  function submit() {
    const policy: PolicyInput = {
      extensionAllowed: extensionOn,
      sameDayAbsenceAllowed: sameDay,
      ...(extensionOn && minAbsence ? { minConsecutiveAbsenceDays: Number(minAbsence) } : {}),
      ...(sameDay && cutoff ? { absenceCutoffTime: cutoff } : {}),
      ...(showWindow && window ? { maxCalendarWindowDays: Number(window) } : {}),
    };
    const input: CreatePlanInput = {
      name: name.trim(),
      ...(description.trim() ? { description: description.trim() } : {}),
      consumptionType: type,
      ...(isDay ? { durationDays: Number(duration) } : { mealQuantity: Number(meals) }),
      price: Number(price),
      policy,
    };
    void onSave(input);
  }

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        submit();
      }}
    >
      <p className="ff-section-title">{editing ? `Edit “${plan.name}”` : "Add a plan"}</p>

      <div className="ff-field">
        <label htmlFor="plan-name">Plan name</label>
        <input id="plan-name" value={name} onChange={(e) => setName(e.target.value)} maxLength={120} required />
      </div>
      <div className="ff-field">
        <label htmlFor="plan-desc">Description (optional)</label>
        <input id="plan-desc" value={description} onChange={(e) => setDescription(e.target.value)} maxLength={500} />
      </div>

      <div className="ff-field">
        <label htmlFor="plan-type">Type</label>
        <select
          id="plan-type"
          value={type}
          disabled={editing}
          onChange={(e) => setType(e.target.value as ConsumptionType)}
        >
          {CONSUMPTION_TYPES.map((t) => (
            <option key={t} value={t}>
              {TYPE_LABEL[t]}
            </option>
          ))}
        </select>
      </div>

      {isDay ? (
        <div className="ff-field">
          <label htmlFor="plan-days">Number of days</label>
          <input
            id="plan-days"
            inputMode="numeric"
            value={duration}
            onChange={(e) => setDuration(e.target.value.replace(/\D/g, ""))}
            maxLength={3}
            required
          />
        </div>
      ) : (
        <div className="ff-field">
          <label htmlFor="plan-meals">Number of meals (lunch and dinner each count as one)</label>
          <input
            id="plan-meals"
            inputMode="numeric"
            value={meals}
            onChange={(e) => setMeals(e.target.value.replace(/\D/g, ""))}
            maxLength={4}
            required
          />
        </div>
      )}

      <div className="ff-field">
        <label htmlFor="plan-price">Price (₹)</label>
        <input
          id="plan-price"
          inputMode="decimal"
          value={price}
          onChange={(e) => setPrice(e.target.value.replace(/[^\d.]/g, ""))}
          maxLength={11}
          required
        />
      </div>

      <p className="ff-section-title">Absence policy</p>

      {isDay && (
        <div className="ff-field ff-check">
          <label htmlFor="plan-ext">
            <input id="plan-ext" type="checkbox" checked={extension} onChange={(e) => setExtension(e.target.checked)} />
            Extend the subscription when the customer is absent
          </label>
        </div>
      )}
      {extensionOn && (
        <div className="ff-field">
          <label htmlFor="plan-min">Minimum consecutive absent days before it extends</label>
          <input
            id="plan-min"
            inputMode="numeric"
            value={minAbsence}
            onChange={(e) => setMinAbsence(e.target.value.replace(/\D/g, ""))}
            maxLength={4}
            required
          />
        </div>
      )}
      {showWindow && (
        <div className="ff-field">
          <label htmlFor="plan-window">
            {isDay ? "Longest total span with extensions, in days from the start (optional)" : "Must be used within this many days (optional)"}
          </label>
          <input
            id="plan-window"
            inputMode="numeric"
            value={window}
            onChange={(e) => setWindow(e.target.value.replace(/\D/g, ""))}
            maxLength={4}
          />
        </div>
      )}

      <div className="ff-field ff-check">
        <label htmlFor="plan-sameday">
          <input id="plan-sameday" type="checkbox" checked={sameDay} onChange={(e) => setSameDay(e.target.checked)} />
          Customers may declare an absence for today
        </label>
      </div>
      {sameDay && (
        <div className="ff-field">
          <label htmlFor="plan-cutoff">Same-day cutoff time (optional - leave empty for no cutoff)</label>
          <input id="plan-cutoff" type="time" value={cutoff} onChange={(e) => setCutoff(e.target.value)} />
        </div>
      )}

      <div className="ff-button-row">
        <button type="button" className="ff-button ff-button-secondary" disabled={busy} onClick={onCancel}>
          Cancel
        </button>
        <button type="submit" className="ff-button" disabled={busy || !name.trim()}>
          {editing ? "Save changes" : "Add plan"}
        </button>
      </div>
    </form>
  );
}
