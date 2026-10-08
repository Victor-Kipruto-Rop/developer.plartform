import { useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import {
  ArrowLeft,
  ArrowUpRight,
  Check,
  CircleAlert,
  Clock3,
  FileText,
  Lightbulb,
  MessageCircle,
  Paperclip,
  Plus,
  RefreshCw,
  Search,
  Send,
  Shield,
  Sparkles,
  ThumbsUp,
} from "lucide-react";
import { PageHeader } from "../../components/ui/PageHeader";
import { AdminFeedbackPage } from "./AdminFeedbackPage";
import { useAuth } from "../../context/AuthContext";
import { apiData } from "../../lib/api";
import { createUuid } from "../../lib/uuid";
import { readActiveEnvironmentContext, readActiveProjectId } from "../../lib/activeEnvironment";
import { version as portalVersion } from "../../../package.json";

type FeedbackType =
  | "SUGGESTION" | "FEATURE_REQUEST" | "BUG" | "COMPLAINT"
  | "DOCUMENTATION" | "DEVELOPER_EXPERIENCE" | "OTHER";
type FeedbackPriority = "LOW" | "NORMAL" | "HIGH" | "CRITICAL";
type FeedbackStatus =
  | "NEW" | "ACKNOWLEDGED" | "REVIEWING" | "PLANNED"
  | "IN_PROGRESS" | "RESOLVED" | "CLOSED" | "REJECTED";
type FeedbackItem = {
  reference: string;
  type: FeedbackType;
  title: string;
  description: string;
  priority: FeedbackPriority;
  status: FeedbackStatus;
  createdAt: string;
  updatedAt: string;
  projectId: string | null;
  projectName: string | null;
  environmentId: string | null;
  environmentName: string | null;
  commentCount: number;
};
type FeedbackComment = {
  id: string;
  authorName: string;
  authorRole: "DEVELOPER" | "ADMIN";
  body: string;
  createdAt: string;
};
type FeedbackPage = {
  items: FeedbackItem[];
  page: number;
  pageSize: number;
  totalItems: number;
  totalPages: number;
};
type Project = { id: string; name: string };
type Environment = { id: string; name: string; type: string; projectId: string };
type Filter = "ALL" | FeedbackType;
type DateFilter = "ANY" | "WEEK" | "MONTH" | "QUARTER";

const feedbackTypes: Array<{ value: FeedbackType; label: string }> = [
  { value: "SUGGESTION", label: "Suggestion" },
  { value: "FEATURE_REQUEST", label: "Feature request" },
  { value: "BUG", label: "Bug / problem" },
  { value: "COMPLAINT", label: "Complaint" },
  { value: "DOCUMENTATION", label: "Documentation" },
  { value: "DEVELOPER_EXPERIENCE", label: "Developer experience" },
  { value: "OTHER", label: "Other" },
];
const statusLabels: Record<FeedbackStatus, string> = {
  NEW: "New",
  ACKNOWLEDGED: "Acknowledged",
  REVIEWING: "Reviewing",
  PLANNED: "Planned",
  IN_PROGRESS: "In progress",
  RESOLVED: "Resolved",
  CLOSED: "Closed",
  REJECTED: "Not planned",
};
const priorityLabels: Record<FeedbackPriority, string> = {
  LOW: "Low",
  NORMAL: "Normal",
  HIGH: "High",
  CRITICAL: "Critical",
};
const filters: Array<{ value: Filter; label: string }> = [
  { value: "ALL", label: "All feedback" },
  ...feedbackTypes,
];
const allowedFiles = new Set(["image/png", "image/jpeg", "image/webp", "application/pdf", "text/plain"]);
const maxFileBytes = 5 * 1024 * 1024;

function routeParts() {
  const parts = window.location.pathname.split("/").filter(Boolean);
  return parts[0] === "suggestions" ? parts.slice(1) : [];
}

function publicReference(value: unknown): value is string {
  return typeof value === "string" && /^FB-[0-9]{6,}$/.test(value);
}

function isFeedbackItem(value: unknown): value is FeedbackItem {
  if (!value || typeof value !== "object") return false;
  const item = value as Partial<FeedbackItem>;
  return publicReference(item.reference)
    && typeof item.title === "string"
    && typeof item.description === "string"
    && typeof item.createdAt === "string"
    && typeof item.updatedAt === "string"
    && typeof item.status === "string"
    && typeof item.type === "string"
    && typeof item.priority === "string";
}

function isFeedbackPage(value: unknown): value is FeedbackPage {
  if (!value || typeof value !== "object") return false;
  const page = value as Partial<FeedbackPage>;
  return Array.isArray(page.items) && page.items.every(isFeedbackItem)
    && typeof page.totalItems === "number" && typeof page.totalPages === "number";
}

function displayDate(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? "Date unavailable"
    : new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" }).format(date);
}

function relativeDate(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "Date unavailable";
  const hours = Math.max(0, Math.floor((Date.now() - date.getTime()) / 3_600_000));
  if (hours < 1) return "Updated less than an hour ago";
  if (hours < 24) return `Updated ${hours}h ago`;
  const days = Math.floor(hours / 24);
  return `Updated ${days}d ago`;
}

function cleanBrowserContext() {
  const ua = navigator.userAgent;
  const browser = /Edg\//.test(ua) ? "Edge"
    : /Firefox\//.test(ua) ? "Firefox"
      : /Chrome\//.test(ua) ? "Chrome"
        : /Safari\//.test(ua) ? "Safari" : "Other";
  const operatingSystem = /Windows/.test(ua) ? "Windows"
    : /Mac OS/.test(ua) ? "macOS"
      : /Android/.test(ua) ? "Android"
        : /iPhone|iPad/.test(ua) ? "iOS"
          : /Linux/.test(ua) ? "Linux" : "Other";
  return { browser, operatingSystem };
}

function navigate(path: string) {
  if (`${window.location.pathname}${window.location.search}` !== path) {
    window.history.pushState(null, "", path);
  }
  window.dispatchEvent(new PopStateEvent("popstate"));
}

export function SuggestionsPage() {
  const { status: authStatus, organization } = useAuth();
  const authenticated = authStatus === "authenticated";
  const [routeVersion, setRouteVersion] = useState(0);
  const [feedback, setFeedback] = useState<FeedbackItem[]>([]);
  const [listPage, setListPage] = useState(0);
  const [totalItems, setTotalItems] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [selected, setSelected] = useState<FeedbackItem | null>(null);
  const [comments, setComments] = useState<FeedbackComment[]>([]);
  const [projects, setProjects] = useState<Project[]>([]);
  const [environments, setEnvironments] = useState<Environment[]>([]);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [success, setSuccess] = useState("");
  const [submittedReference, setSubmittedReference] = useState("");
  const [search, setSearch] = useState("");
  const [filter, setFilter] = useState<Filter>("ALL");
  const [statusFilter, setStatusFilter] = useState("");
  const [priorityFilter, setPriorityFilter] = useState("");
  const [projectFilter, setProjectFilter] = useState("");
  const [environmentFilter, setEnvironmentFilter] = useState("");
  const [dateFilter, setDateFilter] = useState<DateFilter>("ANY");
  const [comment, setComment] = useState("");
  const [type, setType] = useState<FeedbackType>("SUGGESTION");
  const [priority, setPriority] = useState<FeedbackPriority>("NORMAL");
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const initialContext = useMemo(() => readActiveEnvironmentContext(), []);
  const [projectId, setProjectId] = useState(() => readActiveProjectId());
  const [environmentId, setEnvironmentId] = useState(() => readActiveEnvironmentContext().environmentId);
  const [adminAccess, setAdminAccess] = useState<boolean | null>(null);
  const [attachments, setAttachments] = useState<File[]>([]);
  const fileInput = useRef<HTMLInputElement>(null);
  const idempotencyKey = useRef<string | null>(null);
  const currentRoute = useMemo(routeParts, [routeVersion]);
  const isAdminView = currentRoute[0] === "admin";
  const isNew = currentRoute[0] === "new";
  const selectedReference = currentRoute[0] && currentRoute[0] !== "new" && !isAdminView ? currentRoute[0] : null;
  const query = search.trim();

  useEffect(() => {
    const sync = () => setRouteVersion((version) => version + 1);
    window.addEventListener("popstate", sync);
    return () => window.removeEventListener("popstate", sync);
  }, []);

  useEffect(() => {
    if (!authenticated) {
      setAdminAccess(false);
      return;
    }
    let active = true;
    apiData<{ allowed: boolean }>("/api/v1/admin/feedback/access")
      .then((result) => {
        if (active) setAdminAccess(result.allowed === true);
      })
      .catch(() => {
        if (active) setAdminAccess(false);
      });
    return () => { active = false; };
  }, [authenticated]);

  useEffect(() => {
    if (!authenticated) return;
    const controller = new AbortController();
    apiData<{ items: Project[] }>("/api/v1/projects?page=0&size=100", { signal: controller.signal })
      .then((response) => {
        if (Array.isArray(response.items)) setProjects(response.items);
      })
      .catch(() => {
        if (!controller.signal.aborted) setError("Project options couldn't be loaded. You can still submit feedback without a project.");
      });
    return () => controller.abort();
  }, [authenticated]);

  useEffect(() => {
    if (!authenticated || !projectId) {
      setEnvironments([]);
      return;
    }
    const controller = new AbortController();
    apiData<{ items: Environment[] }>(`/api/v1/projects/${encodeURIComponent(projectId)}/environments`, { signal: controller.signal })
      .then((response) => setEnvironments(Array.isArray(response.items) ? response.items : []))
      .catch(() => {
        if (!controller.signal.aborted) setEnvironments([]);
      });
    return () => controller.abort();
  }, [authenticated, projectId]);

  useEffect(() => {
    if (!authenticated || selectedReference || isNew) return;
    let active = true;
    setLoading(true);
    setError("");
    const params = new URLSearchParams({ page: String(listPage), pageSize: "20" });
    if (query) params.set("search", query);
    if (filter !== "ALL") params.set("type", filter);
    if (statusFilter) params.set("status", statusFilter);
    if (priorityFilter) params.set("priority", priorityFilter);
    if (projectFilter) params.set("projectId", projectFilter);
    if (environmentFilter) params.set("environmentId", environmentFilter);
    if (dateFilter !== "ANY") {
      const days = dateFilter === "WEEK" ? 7 : dateFilter === "MONTH" ? 30 : 90;
      params.set("createdAfter", new Date(Date.now() - days * 86_400_000).toISOString());
    }
    apiData<FeedbackPage>(`/api/v1/feedback?${params.toString()}`)
      .then((response) => {
        if (!isFeedbackPage(response)) throw new Error("The feedback service returned an invalid list.");
        if (active) {
          setFeedback(response.items);
          setTotalItems(response.totalItems);
          setTotalPages(response.totalPages);
        }
      })
      .catch((caught: unknown) => {
        if (active) setError(caught instanceof Error ? caught.message : "Feedback couldn't be loaded.");
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => { active = false; };
  }, [authenticated, dateFilter, environmentFilter, filter, isNew, listPage, priorityFilter, projectFilter, query, routeVersion, selectedReference, statusFilter]);

  useEffect(() => {
    if (!authenticated || !selectedReference) {
      setSelected(null);
      setComments([]);
      return;
    }
    let active = true;
    setSelected(null);
    setComments([]);
    setLoading(true);
    setError("");
    Promise.all([
      apiData<FeedbackItem>(`/api/v1/feedback/${encodeURIComponent(selectedReference)}`),
      apiData<FeedbackComment[]>(`/api/v1/feedback/${encodeURIComponent(selectedReference)}/comments`),
    ])
      .then(([item, resultComments]) => {
        if (!isFeedbackItem(item) || !Array.isArray(resultComments)) {
          throw new Error("The feedback service returned an invalid response.");
        }
        if (active) {
          setSelected(item);
          setComments(resultComments);
        }
      })
      .catch((caught: unknown) => {
        if (active) setError(caught instanceof Error ? caught.message : "This feedback couldn't be loaded.");
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => { active = false; };
  }, [authenticated, selectedReference]);

  async function submitFeedback(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!authenticated) {
      setError("Sign in to submit feedback.");
      return;
    }
    setSaving(true);
    setError("");
    setSuccess("");
    const safeLocation = `${window.location.origin}${window.location.pathname}`;
    const context = cleanBrowserContext();
    idempotencyKey.current ??= createUuid();
    try {
      const created = await apiData<FeedbackItem>("/api/v1/feedback", {
        method: "POST",
        headers: { "Idempotency-Key": idempotencyKey.current },
        body: JSON.stringify({
          type,
          title: title.trim(),
          description: description.trim(),
          priority,
          projectId: projectId || null,
          environmentId: environmentId || null,
          pageUrl: safeLocation,
          route: window.location.pathname,
          browser: context.browser,
          operatingSystem: context.operatingSystem,
          timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
          applicationVersion: import.meta.env.VITE_APP_VERSION || portalVersion,
          frontendVersion: portalVersion,
        }),
      });
      if (!isFeedbackItem(created)) throw new Error("The feedback service returned an invalid confirmation.");
      for (const file of attachments) {
        const form = new FormData();
        form.set("file", file);
        await apiData(`/api/v1/feedback/${encodeURIComponent(created.reference)}/attachments`, {
          method: "POST",
          body: form,
        });
      }
      setSuccess("Thanks for helping us improve the platform.");
      setSubmittedReference(created.reference);
      setTitle("");
      setDescription("");
      setType("SUGGESTION");
      setPriority("NORMAL");
      setAttachments([]);
      idempotencyKey.current = null;
      setProjectId(initialContext.projectId);
      setEnvironmentId(initialContext.environmentId);
      navigate("/suggestions");
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "We couldn't submit your feedback. Please try again.");
    } finally {
      setSaving(false);
    }
  }

  if (isAdminView) {
    if (adminAccess === null) return <section className="suggestions-page"><p className="suggestions-loading" role="status">Checking administrator access…</p></section>;
    if (!adminAccess) return <section className="suggestions-page"><PageHeader eyebrow="ACCESS RESTRICTED" title="Administrator feedback" description="This workspace is available only to authorized PesaGuard platform administrators." /><div className="suggestions-empty"><Shield size={22} /><strong>Administrator access required</strong><button className="button secondary" type="button" onClick={() => navigate("/suggestions")}>Back to suggestions</button></div></section>;
    return <AdminFeedbackPage onBack={() => navigate("/suggestions")} />;
  }

  async function sendComment(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!selectedReference || !comment.trim()) return;
    setSaving(true);
    setError("");
    try {
      await apiData<FeedbackComment>(`/api/v1/feedback/${encodeURIComponent(selectedReference)}/comments`, {
        method: "POST",
        body: JSON.stringify({ body: comment.trim() }),
      });
      const updatedComments = await apiData<FeedbackComment[]>(`/api/v1/feedback/${encodeURIComponent(selectedReference)}/comments`);
      setComments(updatedComments);
      setComment("");
      window.dispatchEvent(new Event("pesaguard:notifications-updated"));
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "Your reply couldn't be sent.");
    } finally {
      setSaving(false);
    }
  }

  async function reopenFeedback() {
    if (!selectedReference) return;
    setSaving(true);
    setError("");
    try {
      const updated = await apiData<FeedbackItem>(`/api/v1/feedback/${encodeURIComponent(selectedReference)}/reopen`, {
        method: "POST",
      });
      if (!isFeedbackItem(updated)) throw new Error("The feedback service returned an invalid update.");
      setSelected(updated);
      setSuccess("Feedback reopened. The team will be notified.");
      window.dispatchEvent(new Event("pesaguard:notifications-updated"));
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "This feedback couldn't be reopened.");
    } finally {
      setSaving(false);
    }
  }

  function selectAttachments(fileList: FileList | null) {
    if (!fileList) return;
    const next = Array.from(fileList);
    const invalid = next.find((file) => !allowedFiles.has(file.type) || file.size > maxFileBytes);
    if (invalid) {
      setError("Attachments must be PNG, JPEG, WebP, PDF, or plain text and no larger than 5 MB each.");
      return;
    }
    if (attachments.length + next.length > 5) {
      setError("You can attach up to 5 files.");
      return;
    }
    setError("");
    setAttachments((current) => [...current, ...next]);
  }

  const filterFeedback = (value: Filter) => {
    setFilter(value);
    setListPage(0);
  };

  if (!authenticated) {
    return (
      <section className="suggestions-page">
        <PageHeader eyebrow="DEVELOPER FEEDBACK" title="Suggestions" description="Sign in to submit ideas, report problems, and follow updates from the PesaGuard team." />
        <div className="suggestions-empty"><Lightbulb size={22} /><strong>Your feedback belongs to your workspace</strong><p>Sign in to securely view and submit organization feedback.</p></div>
      </section>
    );
  }

  if (isNew) {
    return (
      <section className="suggestions-page">
        <button className="suggestions-back" type="button" onClick={() => navigate("/suggestions")}><ArrowLeft size={15} /> All suggestions</button>
        <PageHeader eyebrow="HELP US IMPROVE" title="Submit feedback" description="Share an idea, report a problem, or tell us about your experience." />
        {error && <p className="suggestions-alert" role="alert"><CircleAlert size={16} />{error}</p>}
        {success && <p className="suggestions-success" role="status"><Check size={16} />{success}</p>}
        <form className="suggestions-form" onSubmit={submitFeedback}>
          <label className="suggestions-field">
            <span>Type <b aria-hidden="true">*</b></span>
            <select className="field-control" value={type} onChange={(event) => setType(event.target.value as FeedbackType)} required>
              {feedbackTypes.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
            </select>
          </label>
          <label className="suggestions-field">
            <span>Title <b aria-hidden="true">*</b></span>
            <input className="field-control" value={title} onChange={(event) => setTitle(event.target.value)} maxLength={120} required />
            <small>{title.length}/120 characters</small>
          </label>
          <label className="suggestions-field">
            <span>Description <b aria-hidden="true">*</b></span>
            <textarea className="field-control" value={description} onChange={(event) => setDescription(event.target.value)} maxLength={10000} rows={8} required aria-describedby="suggestion-description-hint" />
            <small id="suggestion-description-hint">Describe what happened, what you expected, or how your idea would help.</small>
          </label>
          <div className="suggestions-form-row">
            <label className="suggestions-field">
              <span>Priority</span>
              <select className="field-control" value={priority} onChange={(event) => setPriority(event.target.value as FeedbackPriority)}>
                {(["LOW", "NORMAL", "HIGH", "CRITICAL"] as const).map((value) => <option key={value} value={value}>{priorityLabels[value]}</option>)}
              </select>
              <small>Priority helps us triage; our team may adjust it.</small>
            </label>
            <label className="suggestions-field">
              <span>Project</span>
              <select className="field-control" value={projectId} onChange={(event) => { setProjectId(event.target.value); setEnvironmentId(""); }}>
                <option value="">No project</option>
                {projects.map((project) => <option key={project.id} value={project.id}>{project.name}</option>)}
              </select>
            </label>
            <label className="suggestions-field">
              <span>Environment</span>
              <select className="field-control" value={environmentId} onChange={(event) => setEnvironmentId(event.target.value)} disabled={!projectId}>
                <option value="">Not applicable</option>
                {environments.map((environment) => <option key={environment.id} value={environment.id}>{environment.name}</option>)}
              </select>
            </label>
          </div>
          <div className="suggestions-attachment">
            <div><Paperclip size={16} /><span><strong>Attachments (optional)</strong><small>Up to 5 files, 5 MB each. PNG, JPEG, WebP, PDF, or plain text.</small></span></div>
            <input ref={fileInput} type="file" accept="image/png,image/jpeg,image/webp,application/pdf,text/plain" multiple hidden onChange={(event) => selectAttachments(event.currentTarget.files)} />
            <button className="button secondary" type="button" onClick={() => fileInput.current?.click()}><Plus size={15} /> Add files</button>
            {attachments.length > 0 && <ul>{attachments.map((file, index) => <li key={`${file.name}-${index}`}>{file.name}<button type="button" aria-label={`Remove ${file.name}`} onClick={() => setAttachments((current) => current.filter((_, itemIndex) => itemIndex !== index))}>Remove</button></li>)}</ul>}
          </div>
          <p className="suggestions-privacy"><Sparkles size={15} />We include basic browser and page context to help troubleshoot. Credentials, cookies, and private request data are never collected.</p>
          <div className="suggestions-form-actions">
            <button className="button secondary" type="button" onClick={() => navigate("/suggestions")}>Cancel</button>
            <button className="button primary" type="submit" disabled={saving || !organization}><Send size={15} />{saving ? "Submitting…" : "Submit feedback"}</button>
          </div>
        </form>
      </section>
    );
  }

  if (selectedReference) {
    return (
      <section className="suggestions-page">
        <button className="suggestions-back" type="button" onClick={() => navigate("/suggestions")}><ArrowLeft size={15} /> All suggestions</button>
        {loading && !selected ? <p className="suggestions-loading" role="status">Loading feedback…</p> : error && !selected ? <p className="suggestions-alert" role="alert">{error}</p> : selected && (
          <>
            <PageHeader eyebrow={selected.reference} title={selected.title} description={`${feedbackTypes.find((item) => item.value === selected.type)?.label ?? "Feedback"} · ${priorityLabels[selected.priority] ?? "Normal"} priority`} />
            {submittedReference && <div className="suggestions-confirmation" role="status"><Check size={17} /><div><strong>Feedback submitted</strong><p>{success} Reference: {submittedReference}</p></div><button className="button secondary" type="button" onClick={() => { const reference = submittedReference; setSubmittedReference(""); navigate(`/suggestions/${encodeURIComponent(reference)}`); }}>View feedback</button></div>}
            {success && !submittedReference && <p className="suggestions-success" role="status"><Check size={16} />{success}</p>}
            <div className="suggestions-detail-grid">
              <article className="suggestions-detail-main">
                <div className="suggestions-status-banner"><span className={`suggestions-status-dot status-${selected.status.toLowerCase()}`} /><strong>{statusLabels[selected.status] ?? selected.status}</strong><span>Submitted {displayDate(selected.createdAt)}</span>{selected.status === "RESOLVED" && <button className="suggestions-reopen" type="button" onClick={() => void reopenFeedback()} disabled={saving}>Reopen feedback</button>}</div>
                <div className="suggestions-description"><h2>Description</h2><p>{selected.description}</p></div>
                {(selected.projectName || selected.environmentName) && <div className="suggestions-context"><h2>Context</h2>{selected.projectName && <p><span>Project</span><strong>{selected.projectName}</strong></p>}{selected.environmentName && <p><span>Environment</span><strong>{selected.environmentName}</strong></p>}</div>}
                <div className="suggestions-conversation">
                  <h2><MessageCircle size={17} /> Conversation <span>{comments.length}</span></h2>
                  {comments.length === 0 ? <p className="suggestions-muted">No replies yet. We’ll notify you when the team responds.</p> : comments.map((item) => (
                    <article className={`suggestions-comment${item.authorRole === "ADMIN" ? " is-team" : ""}`} key={item.id}>
                      <div><strong>{item.authorRole === "ADMIN" ? "PesaGuard team" : item.authorName}</strong><time dateTime={item.createdAt}>{displayDate(item.createdAt)}</time></div>
                      <p>{item.body}</p>
                    </article>
                  ))}
                  <form className="suggestions-reply-form" onSubmit={sendComment}>
                    <label className="suggestions-field"><span className="sr-only">Write a reply</span><textarea className="field-control" value={comment} onChange={(event) => setComment(event.target.value)} maxLength={5000} rows={3} placeholder="Write a reply…" required /></label>
                    <button className="button primary" type="submit" disabled={saving || !comment.trim()}><Send size={15} /> Send reply</button>
                  </form>
                  {error && <p className="suggestions-alert" role="alert">{error}</p>}
                </div>
              </article>
              <aside className="suggestions-detail-aside">
                <div className="suggestions-aside-card"><h2>Feedback details</h2><dl><div><dt>Reference</dt><dd>{selected.reference}</dd></div><div><dt>Type</dt><dd>{feedbackTypes.find((item) => item.value === selected.type)?.label ?? selected.type}</dd></div><div><dt>Priority</dt><dd>{priorityLabels[selected.priority] ?? selected.priority}</dd></div><div><dt>Created</dt><dd>{displayDate(selected.createdAt)}</dd></div><div><dt>Last updated</dt><dd>{displayDate(selected.updatedAt)}</dd></div></dl></div>
                <div className="suggestions-aside-card"><h2>Updates</h2><p>We’ll keep you informed as the team reviews your feedback.</p><button type="button" className="suggestions-notification-link" onClick={() => navigate("/?page=notification-settings")}>Manage notification preferences <ArrowUpRight size={14} /></button></div>
              </aside>
            </div>
          </>
        )}
      </section>
    );
  }

  return (
    <section className="suggestions-page">
      <PageHeader
        eyebrow="DEVELOPER FEEDBACK"
        title="Suggestions"
        description="Help us improve the platform. Share an idea, report a problem, or tell us about your experience."
        action={<div className="suggestions-header-actions">{adminAccess && <button className="button secondary" type="button" onClick={() => navigate("/suggestions/admin")}><Shield size={15} /> Admin queue</button>}<button className="button primary" type="button" onClick={() => { setError(""); setSuccess(""); setSubmittedReference(""); navigate("/suggestions/new"); }}><Plus size={16} /> Submit feedback</button></div>}
      />
      {error && <p className="suggestions-alert" role="alert"><CircleAlert size={16} />{error}</p>}
      {success && <p className="suggestions-success" role="status"><Check size={16} />{success}</p>}
      <div className="suggestions-toolbar">
        <div className="suggestions-filters" role="group" aria-label="Filter feedback by type">
          {filters.map((item) => <button key={item.value} type="button" className={filter === item.value ? "is-active" : ""} aria-pressed={filter === item.value} onClick={() => filterFeedback(item.value)}>{item.label}</button>)}
        </div>
        <label className="suggestions-search"><Search size={16} /><span className="sr-only">Search feedback</span><input value={search} onChange={(event) => { setSearch(event.target.value); setListPage(0); }} placeholder="Search your feedback…" /></label>
      </div>
      <div className="suggestions-advanced-filters" aria-label="Additional feedback filters">
        <label><span>Status</span><select value={statusFilter} onChange={(event) => { setStatusFilter(event.target.value); setListPage(0); }}><option value="">Any status</option>{Object.entries(statusLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
        <label><span>Priority</span><select value={priorityFilter} onChange={(event) => { setPriorityFilter(event.target.value); setListPage(0); }}><option value="">Any priority</option>{Object.entries(priorityLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
        <label><span>Project</span><select value={projectFilter} onChange={(event) => { setProjectFilter(event.target.value); setListPage(0); }}><option value="">All projects</option>{projects.map((project) => <option key={project.id} value={project.id}>{project.name}</option>)}</select></label>
        <label><span>Environment</span><select value={environmentFilter} onChange={(event) => { setEnvironmentFilter(event.target.value); setListPage(0); }}><option value="">All environments</option>{environments.map((environment) => <option key={environment.id} value={environment.id}>{environment.name}</option>)}</select></label>
        <label><span>Date</span><select value={dateFilter} onChange={(event) => { setDateFilter(event.target.value as DateFilter); setListPage(0); }}><option value="ANY">Any time</option><option value="WEEK">Past week</option><option value="MONTH">Past month</option><option value="QUARTER">Past 3 months</option></select></label>
      </div>
      <div className="suggestions-list-heading"><div><h2>Your feedback</h2><p>Private to your organization and the PesaGuard team.</p></div><button type="button" className="suggestions-refresh" aria-label="Refresh suggestions" onClick={() => setRouteVersion((version) => version + 1)}><RefreshCw size={16} /></button></div>
      {loading ? <p className="suggestions-loading" role="status"><Clock3 size={16} /> Loading feedback…</p>
        : feedback.length ? <div className="suggestions-list">{feedback.map((item) => (
          <button className="suggestions-card" key={item.reference} type="button" onClick={() => navigate(`/suggestions/${encodeURIComponent(item.reference)}`)}>
            <span className="suggestions-card-icon">{item.type === "BUG" ? <CircleAlert size={17} /> : <Lightbulb size={17} />}</span>
            <span className="suggestions-card-content"><span className="suggestions-card-heading"><strong>{item.title}</strong><ArrowUpRight size={15} /></span><span className="suggestions-card-description">{item.description}</span><span className="suggestions-card-meta">{feedbackTypes.find((option) => option.value === item.type)?.label ?? item.type}<i />{priorityLabels[item.priority] ?? item.priority}<i /><span className={`suggestions-status-dot status-${item.status.toLowerCase()}`} />{statusLabels[item.status] ?? item.status}<i /><span>{relativeDate(item.updatedAt)}</span></span></span>
            {item.commentCount > 0 && <span className="suggestions-card-comments"><MessageCircle size={14} />{item.commentCount}</span>}
          </button>
        ))}</div> : <div className="suggestions-empty">{query || filter !== "ALL" ? <><Search size={22} /><strong>No suggestions found</strong><p>Try changing your filters or search terms.</p></> : <><FileText size={22} /><strong>No feedback yet</strong><p>Have an idea or found a problem? Tell us what you think.</p><button className="button primary" type="button" onClick={() => navigate("/suggestions/new")}><Plus size={15} /> Submit feedback</button></>}</div>}
      {feedback.length > 0 && <div className="suggestions-pagination"><p>Showing {listPage * 20 + 1}–{listPage * 20 + feedback.length} of {totalItems}</p><div><button type="button" className="button secondary" disabled={listPage === 0 || loading} onClick={() => setListPage((page) => Math.max(0, page - 1))}>Previous</button><span>Page {listPage + 1} of {Math.max(totalPages, 1)}</span><button type="button" className="button secondary" disabled={listPage + 1 >= totalPages || loading} onClick={() => setListPage((page) => page + 1)}>Next</button></div></div>}
      <div className="suggestions-idea-callout"><span><ThumbsUp size={18} /></span><div><strong>Every piece of feedback helps</strong><p>We review suggestions and keep you updated as they progress.</p></div></div>
    </section>
  );
}
