import { ValidatedForm } from "../../components/forms/ValidatedForm";
import { getUserMessage } from "../../lib/errors";
import { useEffect, useState, type FormEvent } from "react";
import { AlertTriangle, KeyRound, ShieldCheck } from "lucide-react";
import type { PageId } from "../../app/routes";
import { PageHeader } from "../../components/ui/PageHeader";
import { useAuth } from "../../context/AuthContext";
import { apiData } from "../../lib/api";

type Organization = {
  id: string;
  name: string;
  status: string;
  ownerUserId: string;
};

function errorMessage(error: unknown) {
  return getUserMessage(error, "The backend request failed.");
}

export function DangerZonePage({ onNavigate }: { onNavigate: (page: PageId) => void }) {
  const { user, logout } = useAuth();
  const [organization, setOrganization] = useState<Organization | null>(null);
  const [confirmation, setConfirmation] = useState("");
  const [loading, setLoading] = useState(true);
  const [deleting, setDeleting] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    const controller = new AbortController();
    void apiData<Organization>("/api/v1/organization", { signal: controller.signal })
      .then(setOrganization)
      .catch((requestError: unknown) => {
        if (!controller.signal.aborted) setError(errorMessage(requestError));
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, []);

  async function deleteOrganization(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!organization || confirmation !== organization.name) return;
    setDeleting(true);
    setError("");
    try {
      const result = await apiData<Organization>("/api/v1/organization", { method: "DELETE" });
      if (result.status !== "DELETED") {
        throw new Error("The backend did not confirm organization deletion. Refresh the organization state before trying again.");
      }
      await logout();
      window.location.replace("/login");
    } catch (requestError) {
      setError(errorMessage(requestError));
    } finally {
      setDeleting(false);
    }
  }

  const isOwner = Boolean(organization && user && organization.ownerUserId === user.id);

  return (
    <div className="premium-page danger-zone-page">
      <PageHeader
        eyebrow="DANGER"
        title="Danger zone"
        description="Review backend-supported high-impact actions. No operation is reported complete until the backend confirms it."
      />

      {error && <p className="notification-alert" role="alert">{error}</p>}

      <section className="panel security-controls-panel danger-zone-card">
        <div className="panel-heading">
          <div><h2>Organization deletion</h2><p>Organization state is loaded from the backend.</p></div>
          <AlertTriangle size={17} className="heading-icon" />
        </div>
        {loading
          ? <p className="security-empty-state">Loading current organization…</p>
          : organization
            ? <>
              <div className="security-policy-grid">
                <PolicyValue label="Organization" value={organization.name} />
                <PolicyValue label="Backend status" value={organization.status} />
              </div>
              {isOwner
                ? <ValidatedForm className="settings-groups" onSubmit={(event) => void deleteOrganization(event)}>
                  <p>Deleting this organization revokes its active sessions. This action is available to the organization owner and cannot be undone from this page. Type <strong>{organization.name}</strong> to confirm.</p>
                  <label className="settings-field"><span>Confirm organization name</span>
                    <input className="field-control" required autoComplete="off" value={confirmation} onChange={(event) => setConfirmation(event.target.value)} />
                  </label>
                  <button className="button button--danger" type="submit" disabled={deleting || confirmation !== organization.name}>
                    {deleting ? "Deleting organization…" : "Delete organization"}
                  </button>
                </ValidatedForm>
                : <p className="security-empty-state">The backend restricts organization deletion to its owner. No deletion action is available for this account.</p>}
            </>
            : <p className="security-empty-state">{error ? "Organization state could not be loaded." : "No organization state was returned by the backend."}</p>}
      </section>

      <section className="panel security-controls-panel">
        <div className="panel-heading"><div><h2>Credential and production controls</h2><p>Use the connected backend workflows for scoped, individually confirmed actions.</p></div></div>
        <div className="settings-groups">
          <button className="button button--secondary" type="button" onClick={() => onNavigate("api-keys")}><KeyRound size={14} />Review or revoke API keys</button>
          <button className="button button--secondary" type="button" onClick={() => onNavigate("go-live")}><ShieldCheck size={14} />Open Go-Live readiness</button>
        </div>
      </section>
    </div>
  );
}

function PolicyValue({ label, value }: { label: string; value: string }) {
  return <div className="security-policy-value"><span>{label}</span><strong>{value}</strong></div>;
}
