"use client";

import { useState } from "react";
import { PROVIDER_TYPES, type CreateProviderInput, type ProviderType } from "@/lib/provider-api";

type Props = {
  busy: boolean;
  onSubmit: (input: CreateProviderInput) => Promise<void>;
  onCancel: () => void;
};

export function ProviderForm({ busy, onSubmit, onCancel }: Props) {
  const [name, setName] = useState("");
  const [providerType, setProviderType] = useState<ProviderType>("MESS");
  const [addressLine, setAddressLine] = useState("");
  const [locality, setLocality] = useState("");
  const [city, setCity] = useState("");
  const [pincode, setPincode] = useState("");
  const [contactPhone, setContactPhone] = useState("");
  const [description, setDescription] = useState("");
  const [capacity, setCapacity] = useState("");

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        void onSubmit({
          name,
          providerType,
          addressLine,
          locality,
          city,
          ...(pincode ? { pincode } : {}),
          ...(contactPhone ? { contactPhone } : {}),
          ...(description ? { description } : {}),
          ...(capacity ? { maxActiveSubscriptions: Number(capacity) } : {}),
        });
      }}
    >
      <div className="ff-field">
        <label htmlFor="pname">Business name</label>
        <input id="pname" value={name} onChange={(e) => setName(e.target.value)} maxLength={120} required />
      </div>
      <div className="ff-field">
        <label htmlFor="ptype">Type</label>
        <select id="ptype" value={providerType} onChange={(e) => setProviderType(e.target.value as ProviderType)}>
          {PROVIDER_TYPES.map((t) => (
            <option key={t} value={t}>
              {t}
            </option>
          ))}
        </select>
      </div>
      <div className="ff-field">
        <label htmlFor="paddr">Address</label>
        <input id="paddr" value={addressLine} onChange={(e) => setAddressLine(e.target.value)} maxLength={255} required />
      </div>
      <div className="ff-field">
        <label htmlFor="ploc">Locality</label>
        <input id="ploc" value={locality} onChange={(e) => setLocality(e.target.value)} maxLength={120} required />
      </div>
      <div className="ff-field">
        <label htmlFor="pcity">City</label>
        <input id="pcity" value={city} onChange={(e) => setCity(e.target.value)} maxLength={100} required />
      </div>
      <div className="ff-field">
        <label htmlFor="ppin">Pincode (optional)</label>
        <input id="ppin" inputMode="numeric" value={pincode} onChange={(e) => setPincode(e.target.value.replace(/\D/g, ""))} maxLength={6} />
      </div>
      <div className="ff-field">
        <label htmlFor="pphone">Contact phone (optional)</label>
        <input id="pphone" type="tel" value={contactPhone} onChange={(e) => setContactPhone(e.target.value)} placeholder="+919876543210" />
      </div>
      <div className="ff-field">
        <label htmlFor="pdesc">Description (optional)</label>
        <input id="pdesc" value={description} onChange={(e) => setDescription(e.target.value)} maxLength={1000} />
      </div>
      <div className="ff-field">
        <label htmlFor="pcap">Max active subscriptions (optional, blank = no limit)</label>
        <input id="pcap" inputMode="numeric" value={capacity} onChange={(e) => setCapacity(e.target.value.replace(/\D/g, ""))} />
      </div>
      <div className="ff-button-row">
        <button type="button" className="ff-button ff-button-secondary" onClick={onCancel}>
          Cancel
        </button>
        <button type="submit" className="ff-button" disabled={busy}>
          Create provider
        </button>
      </div>
    </form>
  );
}
