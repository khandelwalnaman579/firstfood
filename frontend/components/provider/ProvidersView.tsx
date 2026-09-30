"use client";

import { useCallback, useEffect, useState } from "react";
import { ApiError } from "@/lib/api-client";
import {
  providerApi,
  type CreateProviderInput,
  type Provider,
  type UpdateProviderInput,
} from "@/lib/provider-api";
import { ErrorBanner } from "@/components/auth/ErrorBanner";
import { ProviderDetail } from "./ProviderDetail";
import { ProviderForm } from "./ProviderForm";

type Props = {
  accessToken: string;
};

type Mode = { kind: "list" } | { kind: "create" } | { kind: "detail"; providerId: string };

function describe(err: unknown): string {
  if (err instanceof ApiError && err.status === 401) {
    return "Your session expired. Go to Account and press “Refresh session”, then try again.";
  }
  return err instanceof Error ? err.message : "Something went wrong.";
}

/**
 * Phase 3 provider management screen: list your providers, create one, open
 * one to edit/change status/close it. All authorization happens on the
 * backend - this component only ever asks for "my" providers and shows what
 * comes back (a provider you don't own simply never appears / 404s).
 */
export function ProvidersView({ accessToken }: Props) {
  const [providers, setProviders] = useState<Provider[] | null>(null);
  const [mode, setMode] = useState<Mode>({ kind: "list" });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const reload = useCallback(async () => {
    try {
      setProviders(await providerApi.list(accessToken));
    } catch (err) {
      setError(describe(err));
    }
  }, [accessToken]);

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

  function handleCreate(input: CreateProviderInput) {
    return guarded(async () => {
      const created = await providerApi.create(accessToken, input);
      await reload();
      setMode({ kind: "detail", providerId: created.id });
    });
  }

  function handleUpdate(providerId: string, input: UpdateProviderInput) {
    return guarded(async () => {
      const updated = await providerApi.update(accessToken, providerId, input);
      setProviders((current) => current?.map((p) => (p.id === updated.id ? updated : p)) ?? null);
    });
  }

  const selected = mode.kind === "detail" ? providers?.find((p) => p.id === mode.providerId) : undefined;

  return (
    <div>
      <ErrorBanner message={error} />

      {mode.kind === "create" && (
        <>
          <h1>New provider</h1>
          <p className="ff-sub">You will be the owner of this provider.</p>
          <ProviderForm busy={busy} onSubmit={handleCreate} onCancel={() => setMode({ kind: "list" })} />
        </>
      )}

      {mode.kind === "detail" && selected && (
        <ProviderDetail
          provider={selected}
          busy={busy}
          onUpdate={(input) => handleUpdate(selected.id, input)}
          onBack={() => setMode({ kind: "list" })}
        />
      )}

      {mode.kind === "list" || (mode.kind === "detail" && !selected) ? (
        <>
          <h1>Your providers</h1>
          <p className="ff-sub">Food businesses you own and manage.</p>
          {providers === null ? (
            <p className="ff-sub">Loading…</p>
          ) : providers.length === 0 ? (
            <p className="ff-sub">You don&apos;t have any providers yet.</p>
          ) : (
            <dl>
              {providers.map((p) => (
                <div key={p.id} className="ff-profile-row">
                  <dt>
                    <button
                      type="button"
                      className="ff-link-button"
                      onClick={() => setMode({ kind: "detail", providerId: p.id })}
                    >
                      {p.name}
                    </button>
                  </dt>
                  <dd className={p.status === "CLOSED" ? "ff-status-suspended" : "ff-status-active"}>
                    {p.status.replace(/_/g, " ")}
                  </dd>
                </div>
              ))}
            </dl>
          )}
          <div style={{ marginTop: "1.25rem" }}>
            <button type="button" className="ff-button" onClick={() => setMode({ kind: "create" })}>
              Create provider
            </button>
          </div>
        </>
      ) : null}
    </div>
  );
}
