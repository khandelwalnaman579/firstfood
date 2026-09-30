"use client";

import { useState } from "react";
import {
  PROVIDER_TYPES,
  allowedTransitions,
  type Provider,
  type ProviderStatus,
  type ProviderType,
  type UpdateProviderInput,
} from "@/lib/provider-api";

type Props = {
  provider: Provider;
  busy: boolean;
  onUpdate: (input: UpdateProviderInput) => Promise<void>;
  onBack: () => void;
};

const STATUS_LABEL: Record<ProviderStatus, string> = {
  ACTIVE: "Active",
  FULL: "Full",
  TEMPORARILY_UNAVAILABLE: "Temporarily unavailable",
  CLOSED: "Closed",
};

export function ProviderDetail({ provider, busy, onUpdate, onBack }: Props) {
  const closed = provider.status === "CLOSED";
  const [name, setName] = useState(provider.name);
  const [providerType, setProviderType] = useState<ProviderType>(provider.providerType);
  const [addressLine, setAddressLine] = useState(provider.addressLine);
  const [locality, setLocality] = useState(provider.locality);
  const [city, setCity] = useState(provider.city);
  const [pincode, setPincode] = useState(provider.pincode ?? "");
  const [contactPhone, setContactPhone] = useState(provider.contactPhone ?? "");
  const [description, setDescription] = useState(provider.description ?? "");
  const [capacity, setCapacity] = useState(provider.maxActiveSubscriptions?.toString() ?? "");
  const [closureReason, setClosureReason] = useState("");
  const [confirmingClose, setConfirmingClose] = useState(false);

  const transitions = allowedTransitions(provider.status).filter((s) => s !== "CLOSED");

  return (
    <div>
      <button type="button" className="ff-link-button" onClick={onBack}>
        ← All providers
      </button>
      <h1 style={{ marginTop: "0.75rem" }}>{provider.name}</h1>
      <p className="ff-sub">
        {provider.providerType} · {provider.locality}, {provider.city}
      </p>

      <dl>
        <div className="ff-profile-row">
          <dt>Status</dt>
          <dd className={closed ? "ff-status-suspended" : "ff-status-active"}>{STATUS_LABEL[provider.status]}</dd>
        </div>
        <div className="ff-profile-row">
          <dt>New customers</dt>
          <dd>{provider.acceptingNewCustomers ? "Accepting" : "Not accepting"}</dd>
        </div>
        <div className="ff-profile-row">
          <dt>Capacity</dt>
          <dd>{provider.maxActiveSubscriptions ?? "No limit"}</dd>
        </div>
        {closed && (
          <div className="ff-profile-row">
            <dt>Closed</dt>
            <dd>
              {provider.closedAt ? new Date(provider.closedAt).toLocaleDateString() : "—"}
              {provider.closureReason ? ` — ${provider.closureReason}` : ""}
            </dd>
          </div>
        )}
      </dl>

      {closed ? (
        <p className="ff-dev-note">
          This provider is closed. Closure is permanent and the record is kept for history; it can no longer be
          edited.
        </p>
      ) : (
        <>
          <hr className="ff-section-divider" />
          <p className="ff-section-title">Availability</p>
          <div className="ff-button-row" style={{ flexWrap: "wrap" }}>
            {transitions.map((s) => (
              <button
                key={s}
                type="button"
                className="ff-button ff-button-secondary"
                disabled={busy}
                onClick={() => void onUpdate({ status: s })}
              >
                Mark {STATUS_LABEL[s].toLowerCase()}
              </button>
            ))}
            {provider.status === "ACTIVE" && (
              <button
                type="button"
                className="ff-button ff-button-secondary"
                disabled={busy}
                onClick={() => void onUpdate({ acceptingNewCustomers: !provider.acceptingNewCustomers })}
              >
                {provider.acceptingNewCustomers ? "Stop new customers" : "Accept new customers"}
              </button>
            )}
          </div>

          <hr className="ff-section-divider" />
          <p className="ff-section-title">Edit profile</p>
          <form
            onSubmit={(e) => {
              e.preventDefault();
              const input: UpdateProviderInput = {
                name,
                providerType,
                addressLine,
                locality,
                city,
                description,
                ...(pincode ? { pincode } : {}),
                ...(contactPhone ? { contactPhone } : {}),
                ...(capacity ? { maxActiveSubscriptions: Number(capacity) } : { unlimitedCapacity: true }),
              };
              void onUpdate(input);
            }}
          >
            <div className="ff-field">
              <label htmlFor="ename">Business name</label>
              <input id="ename" value={name} onChange={(e) => setName(e.target.value)} maxLength={120} required />
            </div>
            <div className="ff-field">
              <label htmlFor="etype">Type</label>
              <select id="etype" value={providerType} onChange={(e) => setProviderType(e.target.value as ProviderType)}>
                {PROVIDER_TYPES.map((t) => (
                  <option key={t} value={t}>
                    {t}
                  </option>
                ))}
              </select>
            </div>
            <div className="ff-field">
              <label htmlFor="eaddr">Address</label>
              <input id="eaddr" value={addressLine} onChange={(e) => setAddressLine(e.target.value)} maxLength={255} required />
            </div>
            <div className="ff-field">
              <label htmlFor="eloc">Locality</label>
              <input id="eloc" value={locality} onChange={(e) => setLocality(e.target.value)} maxLength={120} required />
            </div>
            <div className="ff-field">
              <label htmlFor="ecity">City</label>
              <input id="ecity" value={city} onChange={(e) => setCity(e.target.value)} maxLength={100} required />
            </div>
            <div className="ff-field">
              <label htmlFor="epin">Pincode</label>
              <input id="epin" inputMode="numeric" value={pincode} onChange={(e) => setPincode(e.target.value.replace(/\D/g, ""))} maxLength={6} />
            </div>
            <div className="ff-field">
              <label htmlFor="ephone">Contact phone</label>
              <input id="ephone" type="tel" value={contactPhone} onChange={(e) => setContactPhone(e.target.value)} />
            </div>
            <div className="ff-field">
              <label htmlFor="edesc">Description</label>
              <input id="edesc" value={description} onChange={(e) => setDescription(e.target.value)} maxLength={1000} />
            </div>
            <div className="ff-field">
              <label htmlFor="ecap">Max active subscriptions (blank = no limit)</label>
              <input id="ecap" inputMode="numeric" value={capacity} onChange={(e) => setCapacity(e.target.value.replace(/\D/g, ""))} />
            </div>
            <button type="submit" className="ff-button" disabled={busy}>
              Save changes
            </button>
          </form>

          <hr className="ff-section-divider" />
          <p className="ff-section-title">Close provider</p>
          {!confirmingClose ? (
            <button type="button" className="ff-button ff-button-secondary" onClick={() => setConfirmingClose(true)}>
              Close this provider…
            </button>
          ) : (
            <div>
              <p className="ff-sub" style={{ marginBottom: "0.75rem" }}>
                Closing is permanent. The provider stays in your history but cannot be reopened or edited.
              </p>
              <div className="ff-field">
                <label htmlFor="creason">Reason (optional)</label>
                <input id="creason" value={closureReason} onChange={(e) => setClosureReason(e.target.value)} maxLength={500} />
              </div>
              <div className="ff-button-row">
                <button type="button" className="ff-button ff-button-secondary" onClick={() => setConfirmingClose(false)}>
                  Keep open
                </button>
                <button
                  type="button"
                  className="ff-button"
                  disabled={busy}
                  onClick={() => void onUpdate({ status: "CLOSED", ...(closureReason ? { closureReason } : {}) })}
                >
                  Close permanently
                </button>
              </div>
            </div>
          )}
        </>
      )}
    </div>
  );
}
