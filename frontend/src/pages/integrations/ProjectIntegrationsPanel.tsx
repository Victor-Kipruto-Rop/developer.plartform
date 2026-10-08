import { useEffect, useMemo, useState, type ReactNode } from "react";
import {
  Activity,
  AlertTriangle,
  CheckCircle2,
  ChevronDown,
  ChevronUp,
  Clock3,
  KeyRound,
  Plug,
  RotateCw,
  ShieldCheck,
  ToggleLeft,
  ToggleRight,
} from "lucide-react";
import type { PageId } from "../../app/routes";
import { apiData, apiFetch } from "../../lib/api";
import { readActiveEnvironmentId, readActiveProjectId } from "../../lib/activeEnvironment";

type Capability = {
  capability: string;
  status: string;
  requiredScopes: string[];
  enabled: boolean;
  lastVerifiedAt: string | null;
};

type Integration = {
  id: string;
  projectId: string;
  environmentId: string;
  environmentType: string;
  type: string;
  provider: string;
  status: string;
  healthStatus: string;
  displayName: string;
  description: string;
  enabled: boolean;
  lastTestedAt: string | null;
  lastSuccessAt: string | null;
  lastFailureAt: string | null;
  lastRequestId: string | null;
  capabilities: Capability[];
};

type TestRun = {
  id: string;
  status: string;
  requestId: string;
  latencyMs: number | null;
  failureCategory: string | null;
  message: string;
  startedAt: string;
  completedAt: string | null;
};

type IntegrationEvent = {
  id: string;
  eventType: string;
  requestId: string;
  details: string;
  createdAt: string;
};

type TestResult = {
  status: string;
  integrationStatus: string;
  healthStatus: string;
  requestId: string;
  latencyMs: number;
  failureCategory: string | null;
  message: string;
};

const statusLabels: Record<string, string> = {
  NOT_CONFIGURED: "Needs API key or api:read",
  READY_TO_TEST: "Ready to test",
  TESTING: "Testing connection",
  CONNECTED: "Connected",
  DEGRADED: "Connection degraded",
  FAILED: "Connection failed",
  DISABLED: "Disabled",
};

function formatDate(value: string | null) {
  if (!value) return "Not tested yet";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "Unknown" : date.toLocaleString();
}

function statusClass(value: string) {
  if (value === "CONNECTED" || value === "HEALTHY") return "integration-state--healthy";
  if (value === "DEGRADED" || value === "READY_TO_TEST") return "integration-state--attention";
  if (value === "FAILED" || value === "UNHEALTHY") return "integration-state--failed";
  if (value === "DISABLED") return "integration-state--disabled";
  return "integration-state--quiet";
}

export function ProjectIntegrationsPanel({ onNavigate }: { onNavigate: (page: PageId) => void }) {
  const [projectId, setProjectId] = useState(readActiveProjectId);
  const [integrations, setIntegrations] = useState<Integration[]>([]);
  const [selectedEnvironmentId, setSelectedEnvironmentId] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [retry, setRetry] = useState(0);
  const [busyId, setBusyId] = useState("");
  const [result, setResult] = useState<TestResult | null>(null);
  const [expandedId, setExpandedId] = useState("");
  const [history, setHistory] = useState<Record<string, TestRun[]>>({});
  const [events, setEvents] = useState<Record<string, IntegrationEvent[]>>({});
  const [historyError, setHistoryError] = useState("");

  useEffect(() => {
    const updateProject = () => {
      setProjectId(readActiveProjectId());
      setSelectedEnvironmentId("");
    };
    window.addEventListener("pesaguard:project-selected", updateProject);
    return () => window.removeEventListener("pesaguard:project-selected", updateProject);
  }, []);

  useEffect(() => {
    if (!projectId) {
      setIntegrations([]);
      setLoading(false);
      return;
    }
    const controller = new AbortController();
    setLoading(true);
    setIntegrations([]);
    setResult(null);
    setHistory({});
    setEvents({});
    setError("");
    apiData<Integration[]>(`/api/v1/projects/${projectId}/integrations`, { signal: controller.signal })
      .then((items) => {
        if (!Array.isArray(items)) throw new Error("The integrations response was invalid.");
        setIntegrations(items);
        const preferredEnvironmentId = readActiveEnvironmentId(projectId);
        setSelectedEnvironmentId((current) => {
          if (items.some((item) => item.environmentId === current)) return current;
          if (items.some((item) => item.environmentId === preferredEnvironmentId)) return preferredEnvironmentId;
          return items[0]?.environmentId ?? "";
        });
      })
      .catch((cause: unknown) => {
        if (!controller.signal.aborted) {
          setError(cause instanceof Error ? cause.message : "Could not load project integrations.");
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [projectId, retry]);

  const visibleIntegrations = useMemo(
    () => integrations.filter((item) => item.environmentId === selectedEnvironmentId),
    [integrations, selectedEnvironmentId],
  );

  const connectedCount = integrations.filter((item) => item.status === "CONNECTED").length;
  const environments = useMemo(() => {
    const seen = new Set<string>();
    return integrations.filter((item) => {
      if (seen.has(item.environmentId)) return false;
      seen.add(item.environmentId);
      return true;
    });
  }, [integrations]);

  async function refreshIntegrations() {
    if (!projectId) return;
    const items = await apiData<Integration[]>(`/api/v1/projects/${projectId}/integrations`);
    setIntegrations(items);
  }

  async function testConnection(integration: Integration) {
    setBusyId(integration.id);
    setError("");
    setResult(null);
    try {
      const testResult = await apiData<TestResult>(
        `/api/v1/projects/${projectId}/integrations/${integration.id}/test`,
        { method: "POST" },
      );
      setResult(testResult);
      await refreshIntegrations();
      if (history[integration.id]) await loadHistory(integration.id);
      if (events[integration.id]) await loadEvents(integration.id);
    } catch (cause: unknown) {
      setError(cause instanceof Error ? cause.message : "The connection test could not be completed.");
      try {
        await refreshIntegrations();
      } catch {
        setError((current) => `${current} The latest integration state could not be refreshed.`);
      }
    } finally {
      setBusyId("");
    }
  }

  async function setIntegrationEnabled(integration: Integration, enabled: boolean) {
    setBusyId(integration.id);
    setError("");
    try {
      await apiFetch<void>(
        `/api/v1/projects/${projectId}/integrations/${integration.id}/${enabled ? "enable" : "disable"}`,
        { method: "POST" },
      );
      await refreshIntegrations();
    } catch (cause: unknown) {
      setError(cause instanceof Error ? cause.message : "The integration setting could not be updated.");
    } finally {
      setBusyId("");
    }
  }

  async function loadHistory(integrationId: string) {
    setHistoryError("");
    try {
      const runs = await apiData<TestRun[]>(
        `/api/v1/projects/${projectId}/integrations/${integrationId}/tests`,
      );
      setHistory((current) => ({ ...current, [integrationId]: runs }));
    } catch (cause: unknown) {
      setHistoryError(cause instanceof Error ? cause.message : "Connection history could not be loaded.");
    }
  }

  async function loadEvents(integrationId: string) {
    setHistoryError("");
    try {
      const items = await apiData<IntegrationEvent[]>(
        `/api/v1/projects/${projectId}/integrations/${integrationId}/events`,
      );
      setEvents((current) => ({ ...current, [integrationId]: items }));
    } catch (cause: unknown) {
      setHistoryError(cause instanceof Error ? cause.message : "Integration events could not be loaded.");
    }
  }

  function toggleHistory(integrationId: string) {
    if (expandedId === integrationId) {
      setExpandedId("");
      return;
    }
    setExpandedId(integrationId);
    if (!history[integrationId]) void loadHistory(integrationId);
    if (!events[integrationId]) void loadEvents(integrationId);
  }

  if (!projectId) {
    return (
      <section className="project-integrations panel">
        <div className="project-integrations-empty">
          <Plug size={22} />
          <h2>Select a project to manage its integrations</h2>
          <p>Integrations and connection health are scoped to a project and its environments.</p>
          <button className="button button--primary" type="button" onClick={() => onNavigate("projects")}>
            Open projects
          </button>
        </div>
      </section>
    );
  }

  return (
    <section className="project-integrations" aria-labelledby="project-integrations-title">
      <div className="project-integrations-heading">
        <div>
          <span className="page-eyebrow">PROJECT CONNECTIONS</span>
          <h2 id="project-integrations-title">Environment integrations</h2>
          <p>Verify the server-side API key connection and review its health without exposing the secret in the browser.</p>
        </div>
        <label className="project-integrations-environment">
          <span>Environment</span>
          <select
            value={selectedEnvironmentId}
            onChange={(event) => {
              setSelectedEnvironmentId(event.target.value);
              setResult(null);
            }}
            disabled={environments.length === 0}
          >
            {environments.map((environment) => (
              <option key={environment.environmentId} value={environment.environmentId}>
                {environment.environmentType}
              </option>
            ))}
          </select>
        </label>
      </div>

      <div className="settings-summary-grid project-integrations-summary">
        <Summary value={loading ? "…" : String(integrations.length)} label="Environments covered" icon={<Plug size={16} />} />
        <Summary value={loading ? "…" : String(connectedCount)} label="Connected" icon={<CheckCircle2 size={16} />} />
        <Summary value={loading ? "…" : String(integrations.filter((item) => item.status === "FAILED" || item.healthStatus === "UNHEALTHY").length)} label="Need attention" icon={<AlertTriangle size={16} />} />
        <Summary value={loading ? "…" : String(integrations.filter((item) => item.lastTestedAt).length)} label="Tested" icon={<Activity size={16} />} />
      </div>

      {error && (
        <div className="external-integration-error" role="alert">
          <p>{error}</p>
          <button className="text-button" type="button" onClick={() => setRetry((current) => current + 1)}>Retry</button>
        </div>
      )}

      {result && (
        <div className={`integration-test-result ${result.status === "SUCCESS" ? "integration-test-result--success" : "integration-test-result--failure"}`} role="status">
          {result.status === "SUCCESS" ? <CheckCircle2 size={18} /> : <AlertTriangle size={18} />}
          <div>
            <strong>{result.message}</strong>
            <span>{result.latencyMs} ms · Request {result.requestId}</span>
          </div>
        </div>
      )}

      {loading && <p className="workflow-hint" role="status">Loading environment integrations…</p>}
      {!loading && !error && integrations.length === 0 && (
        <div className="project-integrations-empty panel">
          <KeyRound size={22} />
          <h3>No environment integrations yet</h3>
          <p>Create an environment to provision its PesaGuard API connection.</p>
          <button className="text-button" type="button" onClick={() => onNavigate("environments")}>Open environments</button>
        </div>
      )}
      {!loading && visibleIntegrations.map((integration) => (
        <article className="project-integration-card panel" key={integration.id}>
          <header className="project-integration-card__header">
            <span className="external-integration-icon"><Plug size={18} /></span>
            <div className="project-integration-card__title">
              <h3>{integration.displayName}</h3>
              <p>{integration.environmentType} environment · {integration.description}</p>
            </div>
            <span className={`integration-state ${statusClass(integration.status)}`}>
              {statusLabels[integration.status] ?? integration.status}
            </span>
          </header>

          <div className="project-integration-health">
            <div>
              <span>Health</span>
              <strong className={`integration-state-text ${statusClass(integration.healthStatus)}`}>
                <ShieldCheck size={14} /> {integration.healthStatus.toLowerCase()}
              </strong>
            </div>
            <div>
              <span>Last checked</span>
              <strong><Clock3 size={14} /> {formatDate(integration.lastTestedAt)}</strong>
            </div>
            {integration.lastRequestId && (
              <div>
                <span>Last request</span>
                <strong className="integration-request-id">{integration.lastRequestId}</strong>
              </div>
            )}
          </div>

          <div className="project-integration-capabilities">
            <div className="panel-heading">
              <div><h4>Capabilities</h4><p>Access reflects scopes granted to the environment API key.</p></div>
              <span className="settings-group-count">{integration.capabilities.length}</span>
            </div>
            <div className="project-integration-capability-list">
              {integration.capabilities.map((capability) => (
                <div className="project-integration-capability" key={capability.capability}>
                  <span>{capability.capability.replaceAll("_", " ").toLowerCase()}</span>
                  <span className={`integration-state ${statusClass(capability.status)}`}>{capability.status.replaceAll("_", " ").toLowerCase()}</span>
                  <small>{capability.requiredScopes.join(", ")}</small>
                </div>
              ))}
            </div>
          </div>

          <footer className="project-integration-actions">
            {integration.status === "NOT_CONFIGURED" && (
              <button className="text-button" type="button" onClick={() => onNavigate("api-keys")}>
                <KeyRound size={15} /> Manage API keys
              </button>
            )}
            <button
              className="button button--primary"
              type="button"
              disabled={busyId === integration.id || !integration.enabled}
              onClick={() => void testConnection(integration)}
            >
              <RotateCw size={15} className={busyId === integration.id ? "integration-spin" : ""} />
              {busyId === integration.id ? "Working…" : "Test connection"}
            </button>
            <button
              className="button button--secondary"
              type="button"
              disabled={busyId === integration.id}
              aria-pressed={integration.enabled}
              onClick={() => void setIntegrationEnabled(integration, !integration.enabled)}
            >
              {integration.enabled ? <ToggleRight size={16} /> : <ToggleLeft size={16} />}
              {integration.enabled ? "Disable" : "Enable"}
            </button>
            <button className="text-button" type="button" onClick={() => toggleHistory(integration.id)}>
              {expandedId === integration.id ? <ChevronUp size={15} /> : <ChevronDown size={15} />}
              {expandedId === integration.id ? "Hide test history" : "Test history"}
            </button>
          </footer>

          {expandedId === integration.id && (
            <div className="project-integration-history">
              <h4>Recent connection tests</h4>
              {historyError && <p className="workflow-error" role="alert">{historyError}</p>}
              {!history[integration.id] && !historyError && <p className="workflow-hint">Loading test history…</p>}
              {history[integration.id]?.length === 0 && <p className="workflow-hint">No connection tests have been recorded.</p>}
              {history[integration.id]?.map((run) => (
                <div className="project-integration-history__row" key={run.id}>
                  <span className={`integration-state ${statusClass(run.status === "SUCCESS" ? "CONNECTED" : "FAILED")}`}>{run.status.toLowerCase()}</span>
                  <span>{formatDate(run.startedAt)}</span>
                  <span>{run.latencyMs === null ? "—" : `${run.latencyMs} ms`}</span>
                  <span>{run.failureCategory ?? run.message}</span>
                </div>
              ))}
              <h4>Integration events</h4>
              {!events[integration.id] && !historyError && <p className="workflow-hint">Loading integration events…</p>}
              {events[integration.id]?.length === 0 && <p className="workflow-hint">No integration events have been recorded.</p>}
              {events[integration.id]?.map((event) => (
                <div className="project-integration-history__row" key={event.id}>
                  <span className={`integration-state ${event.eventType.endsWith("SUCCEEDED") || event.eventType === "INTEGRATION_ENABLED" ? "integration-state--healthy" : event.eventType.endsWith("FAILED") ? "integration-state--failed" : "integration-state--quiet"}`}>
                    {event.eventType.replaceAll("_", " ").toLowerCase()}
                  </span>
                  <span>{formatDate(event.createdAt)}</span>
                  <span className="integration-request-id">{event.requestId}</span>
                  <span>{event.details}</span>
                </div>
              ))}
            </div>
          )}
        </article>
      ))}
    </section>
  );
}

function Summary({ value, label, icon }: { value: string; label: string; icon: ReactNode }) {
  return (
    <article className="stat-card settings-mini-card">
      <div className="stat-card-top"><span>{label}</span><span className="stat-icon">{icon}</span></div>
      <div className="stat-value">{value}</div>
    </article>
  );
}
