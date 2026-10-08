import { useEffect, useState } from "react";
import { Building2, RefreshCw } from "lucide-react";
import type { PageId } from "../../app/routes";
import { PageHeader } from "../../components/ui/PageHeader";
import { apiData } from "../../lib/api";

type Organization = {
  id: string;
  name: string;
  slug: string;
  type: string;
  status: string;
  createdAt: string;
  updatedAt: string;
};

export function OrganizationsPage({ onNavigate }: { onNavigate: (page: PageId) => void }) {
  const [organization, setOrganization] = useState<Organization | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  async function loadOrganization() {
    setLoading(true);
    setError("");
    try {
      setOrganization(await apiData<Organization>("/api/v1/organization"));
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Could not load the organization.");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void loadOrganization();
  }, []);

  return (
    <>
      <PageHeader
        eyebrow="WORKSPACE"
        title="Organizations"
        description="The organization currently selected for your authenticated session."
        action={<button className="button button--secondary" type="button" disabled={loading} onClick={() => void loadOrganization()}><RefreshCw size={14} />{loading ? "Refreshing…" : "Refresh"}</button>}
      />
      {error && <p className="notification-alert" role="alert">{error}</p>}
      {loading && !organization
        ? <div className="panel organization-live-empty">Loading organization…</div>
        : organization
          ? <section className="panel organization-live-panel">
            <div className="organization-live-identity"><span className={`organization-avatar resource-icon--organization-${organization.type.toLowerCase()}`}><Building2 size={17} /></span><div><h2>{organization.name}</h2><p>{organization.slug}</p></div><span className={`organization-status${organization.status === "ACTIVE" ? " organization-status--active" : ""}`}>{organization.status}</span></div>
            <div className="organization-live-facts">
              <span><small>TYPE</small><strong>{organization.type}</strong></span>
              <span><small>ORGANIZATION ID</small><strong>{organization.id}</strong></span>
              <span><small>CREATED</small><strong>{new Date(organization.createdAt).toLocaleDateString()}</strong></span>
              <span><small>UPDATED</small><strong>{new Date(organization.updatedAt).toLocaleDateString()}</strong></span>
            </div>
            <div className="organization-live-actions"><button className="button button--primary" type="button" onClick={() => onNavigate("organization-detail")}>Open organization settings</button></div>
          </section>
          : !loading && !error && <div className="panel organization-live-empty">No organization is selected for this session.</div>}
    </>
  );
}
