import { ArrowRight, Check, Code2, KeyRound, LoaderCircle, Network, Save } from "lucide-react";
import { useEffect, useState } from "react";
import type { PageId } from "../../app/routes";
import { PageHeader } from "../../components/ui/PageHeader";
import { apiData } from "../../lib/api";

export function DeveloperSettingsPage({ onNavigate }: { onNavigate: (page: PageId) => void }) {
  const [preferences, setPreferences] = useState<{ requestTimeoutMs: number; retryCount: number } | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    let active = true;
    apiData<{ requestTimeoutMs: number; retryCount: number }>("/api/v1/developer/preferences")
      .then((result) => { if (active) setPreferences(result); })
      .catch((cause: unknown) => {
        if (active) setError(cause instanceof Error ? cause.message : "Unable to load developer preferences.");
      })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, []);

  async function savePreferences() {
    if (!preferences) return;
    setSaving(true);
    setError("");
    setSaved(false);
    try {
      const result = await apiData<{ requestTimeoutMs: number; retryCount: number }>(
        "/api/v1/developer/preferences",
        { method: "PUT", body: JSON.stringify(preferences) },
      );
      setPreferences(result);
      setSaved(true);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Unable to save developer preferences.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="premium-page developer-settings-page">
      <PageHeader
        eyebrow="DEVELOPER"
        title="Developer settings"
        description="Save request behavior for API Explorer. These preferences are stored on your developer account."
      />
      <section className="premium-context-banner"><span className="premium-icon-tile"><Code2 size={18} /></span><div><span className="section-eyebrow">LOCAL REQUEST CONTROLS</span><strong>API Explorer tuning, scoped to your account.</strong><small>Safe retries apply only to idempotent GET requests. Mutations are never retried automatically.</small></div><span className="premium-live-mark"><i />ACCOUNT ONLY</span></section>
      <section className="panel settings-form-panel">
        <div className="panel-heading"><div><h2>API Explorer request behavior</h2><p>Retries apply only to safe GET requests. Writes are never automatically retried.</p></div></div>
        {loading ? <p role="status">Loading saved preferences…</p> : preferences ? (
          <div className="settings-form-grid">
            <label className="settings-field">Request timeout (milliseconds)
              <input className="field-control" type="number" min={1000} max={60000} step={1000} value={preferences.requestTimeoutMs} onChange={(event) => setPreferences({ ...preferences, requestTimeoutMs: Number(event.target.value) })} />
            </label>
            <label className="settings-field">Safe GET retries
              <select className="field-control" value={preferences.retryCount} onChange={(event) => setPreferences({ ...preferences, retryCount: Number(event.target.value) })}>
                {[0, 1, 2, 3, 4, 5].map((count) => <option key={count} value={count}>{count}</option>)}
              </select>
            </label>
            <div className="settings-form-actions">
              <button className="button button--primary" type="button" disabled={saving} onClick={() => void savePreferences()}>
                {saving ? <LoaderCircle size={14} className="spin" /> : <Save size={14} />}
                {saving ? "Saving…" : "Save preferences"}
              </button>
              {saved && <span className="form-success" role="status"><Check size={14} />Saved</span>}
            </div>
          </div>
        ) : <p role="alert">Preferences could not be loaded, so there are no assumed values to edit.</p>}
        {error && <p className="form-error" role="alert">{error}</p>}
      </section>
      <section className="panel organization-live-empty">
        <Code2 size={18} />
        <div>
          <strong>Account preferences only affect API Explorer requests.</strong>
          <p>They do not change server-side timeouts, retry behavior, billing, or production API behavior.</p>
          <div className="settings-groups">
            <button className="button button--secondary" type="button" onClick={() => onNavigate("api-catalog")}><Network size={14} />API catalog<ArrowRight size={14} /></button>
            <button className="button button--secondary" type="button" onClick={() => onNavigate("api-keys")}><KeyRound size={14} />API credentials<ArrowRight size={14} /></button>
            <button className="button button--secondary" type="button" onClick={() => onNavigate("environments")}><Code2 size={14} />Environments<ArrowRight size={14} /></button>
          </div>
        </div>
      </section>
    </div>
  );
}
