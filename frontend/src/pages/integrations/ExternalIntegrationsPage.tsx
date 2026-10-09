import { getUserMessage } from "../../lib/errors";
import {
  Activity,
  ArrowRight,
  Building2,
  Code2,
  GitBranch,
  Network,
  Plug,
  Search,
  ServerCog,
  ScrollText,
  ShieldCheck,
  Webhook,
  Workflow,
  Wrench,
} from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import type { PageId } from "../../app/routes";
import { PageHeader } from "../../components/ui/PageHeader";
import { apiFetch } from "../../lib/api";
import { ProjectIntegrationsPanel } from "./ProjectIntegrationsPanel";

type IntegrationAvailability = "AVAILABLE" | "NOT_CONFIGURED" | "UNAVAILABLE";
type IntegrationProvider = {
  providerId: string;
  displayName: string;
  category: string;
  description: string;
  availability: IntegrationAvailability;
  connectionApiAvailable: boolean;
};
type ProviderEnvelope = { data?: IntegrationProvider[] };
type IntegrationPresentation = {
  icon: React.ReactNode;
  destination?: PageId;
};

const integrationPresentation: Record<string, IntegrationPresentation> = {
  tenant_dashboard: { icon: <Building2 size={18} />, destination: "organizations" },
  pesaguard_application: { icon: <Network size={18} />, destination: "oauth-applications" },
  api: { icon: <Code2 size={18} />, destination: "api-explorer" },
  webhook: { icon: <Webhook size={18} />, destination: "webhooks" },
  sdk: { icon: <Wrench size={18} />, destination: "developer-tools" },
  mcp: { icon: <Network size={18} />, destination: "developer-tools" },
  cicd: { icon: <Workflow size={18} />, destination: "api-lifecycle" },
  github: { icon: <GitBranch size={18} />, destination: "projects" },
  monitoring: { icon: <Activity size={18} />, destination: "usage" },
  logging: { icon: <ScrollText size={18} />, destination: "logs" },
  siem: { icon: <ShieldCheck size={18} />, destination: "security-center" },
};

const availabilityLabels: Record<IntegrationAvailability, string> = {
  AVAILABLE: "Available",
  NOT_CONFIGURED: "Not configured",
  UNAVAILABLE: "Unavailable",
};

interface ExternalIntegrationsPageProps {
  onNavigate: (page: PageId) => void;
}

export function ExternalIntegrationsPage({ onNavigate }: ExternalIntegrationsPageProps) {
  const [providers, setProviders] = useState<IntegrationProvider[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");
  const [retry, setRetry] = useState(0);
  const [activeView, setActiveView] = useState<"connections" | "directory">("connections");
  const [query, setQuery] = useState("");
  const [categoryFilter, setCategoryFilter] = useState("All categories");
  const [availabilityFilter, setAvailabilityFilter] = useState<IntegrationAvailability | "ALL">("ALL");

  useEffect(() => {
    let active = true;
    const controller = new AbortController();
    const timeoutId = window.setTimeout(() => controller.abort(), 12_000);
    setLoading(true);
    setLoadError("");
    apiFetch<ProviderEnvelope>("/api/v1/integrations/providers", { signal: controller.signal })
      .then((response) => {
        if (!Array.isArray(response.data)) {
          throw new Error("The integration provider catalog response was invalid.");
        }
        if (active) setProviders(response.data);
      })
      .catch((error: unknown) => {
        if (active) {
          setLoadError(error instanceof DOMException && error.name === "AbortError"
            ? "The integration provider catalog request timed out."
            : error instanceof TypeError
              ? "The integration catalog API could not be reached. Check the API base URL, network, and backend availability."
            : getUserMessage(error, "Could not load the integration provider catalog."));
        }
      })
      .finally(() => {
        window.clearTimeout(timeoutId);
        if (active) setLoading(false);
      });

    return () => {
      active = false;
      window.clearTimeout(timeoutId);
      controller.abort();
    };
  }, [retry]);

  const providerGroups = useMemo(() => {
    const grouped = new Map<string, IntegrationProvider[]>();
    for (const provider of providers) {
      if (categoryFilter !== "All categories" && provider.category !== categoryFilter) continue;
      if (availabilityFilter !== "ALL" && provider.availability !== availabilityFilter) continue;
      if (query.trim() && !`${provider.displayName} ${provider.category} ${provider.description}`
        .toLowerCase().includes(query.trim().toLowerCase())) continue;
      const entries = grouped.get(provider.category) ?? [];
      entries.push(provider);
      grouped.set(provider.category, entries);
    }
    return [...grouped.entries()];
  }, [availabilityFilter, categoryFilter, providers, query]);

  const categories = [...new Set(providers.map((provider) => provider.category))].sort();
  const availableCount = providers.filter((provider) => provider.availability === "AVAILABLE").length;
  const notConfiguredCount = providers.filter((provider) => provider.availability === "NOT_CONFIGURED").length;
  const unavailableCount = providers.filter((provider) => provider.availability === "UNAVAILABLE").length;

  return (
    <>
      <PageHeader
        eyebrow="MANAGEMENT"
        title="External integrations"
        description="Connect environments to PesaGuard and explore the integrations available across your developer workflow."
      />

      <section className="external-integrations-hero">
        <div className="external-integrations-hero__copy">
          <span className="external-integrations-hero__eyebrow"><span /> PESAGUARD CONNECT</span>
          <h2>One control plane.<br /><em>Every environment.</em></h2>
          <p>Verify your API connection securely, monitor environment health, and discover the tools that fit your workflow.</p>
          <div className="external-integrations-hero__trust">
            <span><ShieldCheck size={14} /> Credentials stay server-side</span>
            <span><Activity size={14} /> Health checks are auditable</span>
          </div>
        </div>
        <div className="external-integrations-hero__visual" aria-hidden="true">
          <div className="integration-orbit integration-orbit--outer" />
          <div className="integration-orbit integration-orbit--inner" />
          <span className="integration-orbit-node integration-orbit-node--one"><Code2 size={16} /></span>
          <span className="integration-orbit-node integration-orbit-node--two"><Webhook size={16} /></span>
          <span className="integration-orbit-node integration-orbit-node--three"><ShieldCheck size={16} /></span>
          <span className="integration-orbit-core"><Plug size={25} /></span>
          <span className="integration-orbit-pulse" />
        </div>
      </section>

      <div className="external-integrations-tabs" role="tablist" aria-label="Integration views">
        <button
          className={activeView === "connections" ? "external-integrations-tab external-integrations-tab--active" : "external-integrations-tab"}
          id="integrations-connections-tab"
          type="button"
          role="tab"
          aria-selected={activeView === "connections"}
          aria-controls="integrations-connections-panel"
          onClick={() => setActiveView("connections")}
        >
          <ServerCog size={16} /> Environment connections
          <span>SECURE</span>
        </button>
        <button
          className={activeView === "directory" ? "external-integrations-tab external-integrations-tab--active" : "external-integrations-tab"}
          id="integrations-directory-tab"
          type="button"
          role="tab"
          aria-selected={activeView === "directory"}
          aria-controls="integrations-directory-panel"
          onClick={() => setActiveView("directory")}
        >
          <Plug size={16} /> Provider directory
          <span>{loading ? "…" : providers.length}</span>
        </button>
      </div>

      {activeView === "connections" ? (
        <div id="integrations-connections-panel" role="tabpanel" aria-labelledby="integrations-connections-tab" className="external-integrations-content">
          <div className="external-integrations-section-heading">
            <div><span className="page-eyebrow">CONNECTION HEALTH</span><h2>Your PesaGuard environments</h2><p>Connection checks run server-to-server against each environment’s canonical API host.</p></div>
            <span className="external-integrations-secure-badge"><ShieldCheck size={14} /> Secure by design</span>
          </div>
          <ProjectIntegrationsPanel onNavigate={onNavigate} />
        </div>
      ) : (
        <div id="integrations-directory-panel" role="tabpanel" aria-labelledby="integrations-directory-tab" className="external-integrations-content">
          <div className="external-integrations-directory-heading">
            <div><span className="page-eyebrow">CONNECTOR ECOSYSTEM</span><h2>Explore integrations</h2><p>Find a provider by category and availability. Select one to explore its supported workflow.</p></div>
            <span className="external-integrations-directory-count">{loading ? "Loading providers…" : `${providerGroups.reduce((count, [, entries]) => count + entries.length, 0)} of ${providers.length} providers`}</span>
          </div>

          {loadError && (
            <div className="external-integration-error" role="alert">
              <p>{loadError}</p>
              <button className="text-button" type="button" onClick={() => setRetry((current) => current + 1)}>Retry catalog</button>
            </div>
          )}

          <div className="external-integrations-directory-toolbar">
            <label className="external-integrations-search">
              <Search size={16} />
              <input type="search" value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Search providers" aria-label="Search integrations" />
            </label>
            <label className="external-integrations-category">
              <span>Category</span>
              <select value={categoryFilter} onChange={(event) => setCategoryFilter(event.target.value)}>
                <option>All categories</option>
                {categories.map((category) => <option key={category}>{category}</option>)}
              </select>
            </label>
            <div className="external-integrations-availability" role="group" aria-label="Filter by availability">
              <button type="button" className={availabilityFilter === "ALL" ? "is-active" : ""} onClick={() => setAvailabilityFilter("ALL")}>All <span>{providers.length}</span></button>
              <button type="button" className={availabilityFilter === "AVAILABLE" ? "is-active" : ""} onClick={() => setAvailabilityFilter("AVAILABLE")}>Available <span>{availableCount}</span></button>
              <button type="button" className={availabilityFilter === "NOT_CONFIGURED" ? "is-active" : ""} onClick={() => setAvailabilityFilter("NOT_CONFIGURED")}>Setup required <span>{notConfiguredCount}</span></button>
              <button type="button" className={availabilityFilter === "UNAVAILABLE" ? "is-active" : ""} onClick={() => setAvailabilityFilter("UNAVAILABLE")}>Coming later <span>{unavailableCount}</span></button>
            </div>
          </div>

          {loading && <p className="workflow-hint" role="status">Loading integration directory…</p>}
          {!loading && !loadError && providerGroups.length === 0 && (
            <section className="external-integrations-empty panel" aria-live="polite">
              <Network size={22} />
              <strong>{query ? "No matching integrations" : "No integration providers are registered"}</strong>
              <span>{query || categoryFilter !== "All categories" || availabilityFilter !== "ALL"
                ? "Adjust your search, category, or availability filter."
                : "The backend returned an empty provider catalog."}</span>
            </section>
          )}
          {!loading && !loadError && providerGroups.map(([category, entries]) => (
            <IntegrationGroup key={category} title={category} entries={entries} onNavigate={onNavigate} />
          ))}
        </div>
      )}
    </>
  );
}

function IntegrationGroup({
  title,
  entries,
  onNavigate,
}: {
  title: string;
  entries: IntegrationProvider[];
  onNavigate: (page: PageId) => void;
}) {
  return (
    <section className="external-integration-group" aria-label={title}>
      <div className="panel-heading">
        <div>
          <h2>{title}</h2>
          <p>Provider support and availability from the integration registry.</p>
        </div>
        <span className="settings-group-count">{entries.length} integrations</span>
      </div>
      <div className="external-integration-grid">
        {entries.map((entry) => {
          const presentation = integrationPresentation[entry.providerId] ?? { icon: <Plug size={18} /> };
          const destination = presentation.destination;
          const statusClass = entry.availability.toLowerCase().replace("_", "-");
          return (
            <article className="external-integration-card panel" key={entry.providerId}>
              <div className="external-integration-card-top">
                <span className="external-integration-icon">{presentation.icon}</span>
                <span className={`external-integration-status external-integration-status--${statusClass}`}>
                  {availabilityLabels[entry.availability]}
                </span>
              </div>
              <div>
                <h3>{entry.displayName}</h3>
                <p>{entry.description}</p>
              </div>
              {entry.availability === "AVAILABLE" && destination ? (
                <button className="text-button" type="button" onClick={() => onNavigate(destination)}>
                  Explore capability<ArrowRight size={13} />
                </button>
              ) : (
                <span className="external-integration-unavailable">
                  {entry.connectionApiAvailable ? "Connection management available" : "Connection management unavailable"}
                </span>
              )}
            </article>
          );
        })}
      </div>
    </section>
  );
}
