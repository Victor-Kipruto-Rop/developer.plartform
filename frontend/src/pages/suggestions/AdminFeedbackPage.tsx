import { useEffect, useState, type FormEvent } from "react";
import { ArrowLeft, Check, CircleAlert, MessageCircle, RefreshCw, Search, Send, Shield, UserRound } from "lucide-react";
import { apiData } from "../../lib/api";

type FeedbackStatus =
  | "NEW" | "ACKNOWLEDGED" | "REVIEWING" | "PLANNED"
  | "IN_PROGRESS" | "RESOLVED" | "CLOSED" | "REJECTED";
type FeedbackPriority = "LOW" | "NORMAL" | "HIGH" | "CRITICAL";
type AdminComment = { authorName: string; authorRole: string; body: string; createdAt: string };
type AdminFeedback = {
  reference: string;
  type: string;
  title: string;
  description: string;
  priority: FeedbackPriority;
  status: FeedbackStatus;
  createdAt: string;
  updatedAt: string;
  organizationName: string;
  projectName: string | null;
  environmentName: string | null;
  submitterName: string;
  submitterEmail: string;
  assignedTeam: string | null;
  assignedToName: string | null;
  pageUrl: string | null;
  route: string | null;
  browser: string | null;
  operatingSystem: string | null;
  applicationVersion: string | null;
  frontendVersion: string | null;
  requestId: string | null;
  correlationId: string | null;
  comments: AdminComment[];
  internalNotes: AdminComment[];
  activity: Array<{ eventType: string; actorName: string; createdAt: string }>;
};
type AdminFeedbackPageData = {
  items: AdminFeedback[];
  page: number;
  pageSize: number;
  totalItems: number;
  totalPages: number;
};

const statuses: Array<{ value: FeedbackStatus; label: string }> = [
  { value: "NEW", label: "New" },
  { value: "ACKNOWLEDGED", label: "Acknowledged" },
  { value: "REVIEWING", label: "Reviewing" },
  { value: "PLANNED", label: "Planned" },
  { value: "IN_PROGRESS", label: "In progress" },
  { value: "RESOLVED", label: "Resolved" },
  { value: "CLOSED", label: "Closed" },
  { value: "REJECTED", label: "Not planned" },
];
const priorities: FeedbackPriority[] = ["LOW", "NORMAL", "HIGH", "CRITICAL"];
const teams = ["SUPPORT", "ENGINEERING", "SECURITY", "DEVELOPER_RELATIONS", "PRODUCT"];

function formattedDate(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "Date unavailable"
    : new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" }).format(date);
}

export function AdminFeedbackPage({ onBack }: { onBack: () => void }) {
  const [page, setPage] = useState(0);
  const [data, setData] = useState<AdminFeedbackPageData | null>(null);
  const [selected, setSelected] = useState<AdminFeedback | null>(null);
  const [search, setSearch] = useState("");
  const [status, setStatus] = useState("");
  const [priority, setPriority] = useState("");
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [reply, setReply] = useState("");
  const [internalNote, setInternalNote] = useState("");
  const [team, setTeam] = useState("");
  const [assigneeId, setAssigneeId] = useState("");

  async function loadList() {
    setLoading(true);
    setError("");
    const params = new URLSearchParams({ page: String(page), pageSize: "20" });
    if (search.trim()) params.set("search", search.trim());
    if (status) params.set("status", status);
    if (priority) params.set("priority", priority);
    try {
      const result = await apiData<AdminFeedbackPageData>(`/api/v1/admin/feedback?${params.toString()}`);
      if (!result || !Array.isArray(result.items) || typeof result.totalItems !== "number") {
        throw new Error("The administrator feedback service returned an invalid list.");
      }
      setData(result);
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "The administrator feedback list couldn't be loaded.");
    } finally {
      setLoading(false);
    }
  }

  async function loadDetail(reference: string) {
    setLoading(true);
    setError("");
    try {
      const result = await apiData<AdminFeedback>(`/api/v1/admin/feedback/${encodeURIComponent(reference)}`);
      setSelected(result);
      setTeam(result.assignedTeam ?? "");
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "Feedback details couldn't be loaded.");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    const match = window.location.pathname.match(/^\/suggestions\/admin\/(FB-[0-9]{6,})\/?$/);
    if (match) {
      void loadDetail(match[1]);
    } else {
      setSelected(null);
      void loadList();
    }
  }, [page, search, status, priority, window.location.pathname]);

  function openDetail(reference: string) {
    window.history.pushState(null, "", `/suggestions/admin/${encodeURIComponent(reference)}`);
    window.dispatchEvent(new PopStateEvent("popstate"));
  }

  function backToList() {
    window.history.pushState(null, "", "/suggestions/admin");
    window.dispatchEvent(new PopStateEvent("popstate"));
  }

  async function runAction(action: string, body?: unknown, successMessage?: string) {
    if (!selected) return;
    setSaving(true);
    setError("");
    setMessage("");
    try {
      const updated = await apiData<AdminFeedback>(`/api/v1/admin/feedback/${encodeURIComponent(selected.reference)}${action}`, {
        method: action === "" ? "PATCH" : "POST",
        ...(body === undefined ? {} : { body: JSON.stringify(body) }),
      });
      if (updated && typeof updated === "object" && "reference" in updated) setSelected(updated);
      setMessage(successMessage ?? "Feedback updated.");
      await loadList();
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "This administrator action couldn't be completed.");
    } finally {
      setSaving(false);
    }
  }

  async function updateStatusAndPriority(nextStatus: FeedbackStatus, nextPriority: FeedbackPriority) {
    await runAction("", { status: nextStatus, priority: nextPriority }, "Feedback updated.");
    if (selected) await loadDetail(selected.reference);
  }

  async function assign(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!selected || !team) return;
    await runAction("/assign", { team, assigneeId: assigneeId || null }, "Feedback assignment updated.");
    await loadDetail(selected.reference);
  }

  async function sendMessage(event: FormEvent<HTMLFormElement>, internal: boolean) {
    event.preventDefault();
    if (!selected) return;
    const body = internal ? internalNote.trim() : reply.trim();
    if (!body) return;
    const action = internal ? "/internal-notes" : "/comments";
    await runAction(action, { body }, internal ? "Internal note added." : "Public reply sent.");
    if (internal) setInternalNote(""); else setReply("");
    await loadDetail(selected.reference);
  }

  async function resolveOrClose(action: "resolve" | "close") {
    if (!selected) return;
    await runAction(`/${action}`, undefined, `Feedback ${action}d.`);
    await loadDetail(selected.reference);
  }

  const list = (
    <>
      <div className="suggestions-admin-header">
        <div><span className="suggestions-admin-eyebrow"><Shield size={13} /> PLATFORM ADMINISTRATION</span><h1>Feedback</h1><p>Review developer suggestions, assign work, and send updates.</p></div>
        <button type="button" className="button secondary" onClick={() => void loadList()} disabled={loading}><RefreshCw size={14} /> Refresh</button>
      </div>
      <div className="suggestions-admin-summary"><span>Total matching submissions</span><strong>{data?.totalItems ?? "—"}</strong><small>Count reflects the current filters.</small></div>
      <div className="suggestions-admin-filters">
        <label><Search size={15} /><span className="sr-only">Search feedback</span><input value={search} onChange={(event) => { setPage(0); setSearch(event.target.value); }} placeholder="Search reference, title, organization…" /></label>
        <select aria-label="Filter by status" value={status} onChange={(event) => { setPage(0); setStatus(event.target.value); }}><option value="">All statuses</option>{statuses.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}</select>
        <select aria-label="Filter by priority" value={priority} onChange={(event) => { setPage(0); setPriority(event.target.value); }}><option value="">All priorities</option>{priorities.map((item) => <option key={item} value={item}>{item}</option>)}</select>
      </div>
      {error && <p className="suggestions-alert" role="alert"><CircleAlert size={16} />{error}</p>}
      {loading && !data ? <p className="suggestions-loading" role="status">Loading feedback…</p> : data?.items.length ? <div className="suggestions-admin-list" aria-label="Feedback submissions">
        {data.items.map((item) => <button className="suggestions-admin-row" type="button" key={item.reference} onClick={() => openDetail(item.reference)}>
          <span className="suggestions-admin-ref">{item.reference}</span><span className="suggestions-admin-title"><strong>{item.title}</strong><small>{item.organizationName}{item.projectName ? ` · ${item.projectName}` : ""}</small></span><span className={`suggestions-admin-chip priority-${item.priority.toLowerCase()}`}>{item.priority}</span><span className={`suggestions-admin-chip status-${item.status.toLowerCase()}`}>{statuses.find((candidate) => candidate.value === item.status)?.label ?? item.status}</span><time>{formattedDate(item.createdAt)}</time>
        </button>)}
      </div> : !error && <div className="suggestions-empty"><MessageCircle size={22} /><strong>No feedback matches these filters</strong><p>Try a different search or filter.</p></div>}
      {data && data.totalPages > 1 && <div className="suggestions-pagination"><p>{data.totalItems} submissions</p><div><button className="button secondary" type="button" disabled={page === 0 || loading} onClick={() => setPage((value) => value - 1)}>Previous</button><span>Page {page + 1} of {data.totalPages}</span><button className="button secondary" type="button" disabled={page + 1 >= data.totalPages || loading} onClick={() => setPage((value) => value + 1)}>Next</button></div></div>}
    </>
  );

  return (
    <section className="suggestions-page suggestions-admin">
      <button type="button" className="suggestions-back" onClick={selected ? backToList : onBack}><ArrowLeft size={15} />{selected ? "Feedback queue" : "Developer suggestions"}</button>
      {!selected ? list : <>
        <div className="suggestions-admin-header"><div><span className="suggestions-admin-eyebrow"><Shield size={13} /> {selected.reference}</span><h1>{selected.title}</h1><p>{selected.type.replaceAll("_", " ")} · {selected.priority} · {selected.status.replaceAll("_", " ")}</p></div><button className="button secondary" type="button" onClick={() => void loadDetail(selected.reference)} disabled={loading}><RefreshCw size={14} /> Refresh</button></div>
        {message && <p className="suggestions-success" role="status"><Check size={16} />{message}</p>}
        {error && <p className="suggestions-alert" role="alert"><CircleAlert size={16} />{error}</p>}
        <div className="suggestions-admin-detail-grid">
          <div className="suggestions-admin-detail-main">
            <section className="suggestions-admin-section"><h2>Description</h2><p>{selected.description}</p></section>
            <section className="suggestions-admin-section"><h2>Developer conversation</h2>{selected.comments.map((item, index) => <article className="suggestions-comment" key={`${item.createdAt}-${index}`}><div><strong>{item.authorName}</strong><time>{formattedDate(item.createdAt)}</time></div><p>{item.body}</p></article>)}<form className="suggestions-reply-form" onSubmit={(event) => void sendMessage(event, false)}><label className="suggestions-field"><span>Public reply</span><textarea className="field-control" rows={3} value={reply} onChange={(event) => setReply(event.target.value)} maxLength={5000} required /></label><button className="button primary" type="submit" disabled={saving || !reply.trim()}><Send size={14} /> Send reply</button></form></section>
            <section className="suggestions-admin-section suggestions-internal-notes"><h2><Shield size={15} /> Internal notes <span>Admin only</span></h2>{selected.internalNotes.map((item, index) => <article className="suggestions-comment" key={`${item.createdAt}-${index}`}><div><strong>{item.authorName}</strong><time>{formattedDate(item.createdAt)}</time></div><p>{item.body}</p></article>)}<form className="suggestions-reply-form" onSubmit={(event) => void sendMessage(event, true)}><label className="suggestions-field"><span>Private note</span><textarea className="field-control" rows={3} value={internalNote} onChange={(event) => setInternalNote(event.target.value)} maxLength={5000} required /></label><button className="button secondary" type="submit" disabled={saving || !internalNote.trim()}><Shield size={14} /> Add internal note</button></form></section>
            <section className="suggestions-admin-section"><h2>Technical context</h2><dl className="suggestions-admin-context">{[["Organization", selected.organizationName], ["Project", selected.projectName], ["Environment", selected.environmentName], ["Submitted by", selected.submitterName], ["Email", selected.submitterEmail], ["Route", selected.route], ["Browser", selected.browser], ["OS", selected.operatingSystem], ["Application", selected.applicationVersion], ["Frontend", selected.frontendVersion], ["Request ID", selected.requestId], ["Correlation ID", selected.correlationId]].filter(([, value]) => Boolean(value)).map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value}</dd></div>)}</dl></section>
            <section className="suggestions-admin-section"><h2>Activity</h2><ol className="suggestions-admin-activity">{selected.activity.map((item, index) => <li key={`${item.createdAt}-${index}`}><span className="suggestions-status-dot" /><div><strong>{item.eventType.replaceAll("_", " ")}</strong><small>{item.actorName} · {formattedDate(item.createdAt)}</small></div></li>)}</ol></section>
          </div>
          <aside className="suggestions-aside-card suggestions-admin-action-panel">
            <h2>Manage feedback</h2>
            <label className="suggestions-field"><span>Status</span><select className="field-control" value={selected.status} onChange={(event) => void updateStatusAndPriority(event.target.value as FeedbackStatus, selected.priority)} disabled={saving}>{statuses.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}</select></label>
            <label className="suggestions-field"><span>Priority</span><select className="field-control" value={selected.priority} onChange={(event) => void updateStatusAndPriority(selected.status, event.target.value as FeedbackPriority)} disabled={saving}>{priorities.map((item) => <option key={item} value={item}>{item}</option>)}</select></label>
            <form className="suggestions-admin-assign" onSubmit={(event) => void assign(event)}><label className="suggestions-field"><span>Team</span><select className="field-control" value={team} onChange={(event) => setTeam(event.target.value)}><option value="">Select team</option>{teams.map((item) => <option key={item} value={item}>{item.replaceAll("_", " ")}</option>)}</select></label><label className="suggestions-field"><span>Administrator ID (optional)</span><input className="field-control" value={assigneeId} onChange={(event) => setAssigneeId(event.target.value)} /></label><button className="button secondary" type="submit" disabled={saving || !team}><UserRound size={14} /> Assign</button></form>
            <div className="suggestions-admin-actions"><button className="button primary" type="button" onClick={() => void resolveOrClose("resolve")} disabled={saving || selected.status === "RESOLVED"}><Check size={14} /> Resolve</button><button className="button secondary" type="button" onClick={() => void resolveOrClose("close")} disabled={saving || selected.status === "CLOSED"}>Close</button></div>
          </aside>
        </div>
      </>}
    </section>
  );
}
