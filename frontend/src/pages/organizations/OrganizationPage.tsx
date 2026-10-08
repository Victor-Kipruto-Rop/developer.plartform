import { useEffect, useState } from "react";
import { Building2, Check, Clock3, Download, LockKeyhole, RefreshCw, Users } from "lucide-react";
import { PageHeader } from "../../components/ui/PageHeader";
import { apiBlob, apiData } from "../../lib/api";
import { useAuth } from "../../context/AuthContext";

type Organization = {
  id: string;
  name: string;
  slug: string;
  type: "DEVELOPER" | "ENTERPRISE" | "PARTNER";
  status: string;
  ownerUserId: string;
  metadata: Record<string, unknown>;
  verifiedAt: string | null;
  createdAt: string;
  updatedAt: string;
};

type Member = {
  id: string;
  userId: string;
  email: string;
  displayName: string;
  role: string;
  status: string;
  createdAt: string;
  updatedAt: string;
};

type Project = { id: string; name: string; status: string; createdAt?: string };
type AuditEvent = {
  id: string;
  sequenceNumber: number;
  actorUserId: string;
  action: string;
  resourceType: string;
  resourceId: string;
  createdAt: string;
};
type Page<T> = { items: T[]; totalElements: number };
type SecuritySettings = {
  allowedAuthMethods: string[];
  sessionTtlMinutes: number;
  idleTimeoutMinutes: number;
  maxSessions: number;
  credentialMinLength: number;
  credentialMaxLength: number;
  mfaRequired: boolean;
  mfaRequiredForAdmins: boolean;
  ipAllowlist: string[];
  securityEventTypes: string[];
  updatedAt: string;
};
type Tab = "Overview" | "Members" | "Projects" | "Activity" | "Settings";
const TABS: Tab[] = ["Overview", "Members", "Projects", "Activity", "Settings"];

function displayDate(value: string | null | undefined) {
  if (!value) return "—";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat(undefined, {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(date);
}

function message(error: unknown) {
  return error instanceof Error ? error.message : "The request failed.";
}

export function OrganizationPage({ initialTab = "Overview" }: { initialTab?: Tab } = {}) {
  const { user, switchWorkspace, hasPermission } = useAuth();
  const [organization, setOrganization] = useState<Organization | null>(null);
  const [members, setMembers] = useState<Member[]>([]);
  const [projects, setProjects] = useState<Project[]>([]);
  const [events, setEvents] = useState<AuditEvent[]>([]);
  const [security, setSecurity] = useState<SecuritySettings | null>(null);
  const [tab, setTab] = useState<Tab>(initialTab);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [savingMemberId, setSavingMemberId] = useState("");
  const [error, setError] = useState("");
  const [sectionErrors, setSectionErrors] = useState<string[]>([]);
  const [notice, setNotice] = useState("");
  const [nameDraft, setNameDraft] = useState("");
  const [typeDraft, setTypeDraft] = useState<Organization["type"]>("DEVELOPER");
  const [descriptionDraft, setDescriptionDraft] = useState("");
  const [contactEmailDraft, setContactEmailDraft] = useState("");
  const [createOpen, setCreateOpen] = useState(false);
  const [createName, setCreateName] = useState("");
  const [createType, setCreateType] = useState<Organization["type"]>("DEVELOPER");

  async function loadOrganization() {
    setLoading(true);
    setError("");
    const [organizationResult, membersResult, projectsResult, eventsResult, securityResult] =
      await Promise.allSettled([
        apiData<Organization>("/api/v1/organization"),
        apiData<Member[]>("/api/v1/organization/members"),
        apiData<Page<Project>>("/api/v1/projects?page=0&size=100"),
        apiData<Page<AuditEvent>>("/api/v1/audit-events?page=0&size=20"),
        apiData<SecuritySettings>("/api/v1/organization/security-settings"),
      ]);

    const failures: string[] = [];
    if (organizationResult.status === "fulfilled") {
      setOrganization(organizationResult.value);
      setNameDraft(organizationResult.value.name);
      setTypeDraft(organizationResult.value.type);
      setDescriptionDraft(typeof organizationResult.value.metadata.description === "string" ? organizationResult.value.metadata.description : "");
      setContactEmailDraft(typeof organizationResult.value.metadata.contactEmail === "string" ? organizationResult.value.metadata.contactEmail : "");
    } else {
      setError(message(organizationResult.reason));
    }
    if (membersResult.status === "fulfilled") setMembers(membersResult.value);
    else failures.push(`Members: ${message(membersResult.reason)}`);
    if (projectsResult.status === "fulfilled") setProjects(projectsResult.value.items);
    else failures.push(`Projects: ${message(projectsResult.reason)}`);
    if (eventsResult.status === "fulfilled") setEvents(eventsResult.value.items);
    else failures.push(`Audit activity: ${message(eventsResult.reason)}`);
    if (securityResult.status === "fulfilled") setSecurity(securityResult.value);
    else failures.push(`Security policy: ${message(securityResult.reason)}`);
    setSectionErrors(failures);
    setLoading(false);
  }

  async function exportOrganization() {
    setExporting(true);
    setError("");
    try {
      const blob = await apiBlob("/api/v1/exports/organization");
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = "pesaguard-organization.csv";
      link.click();
      window.setTimeout(() => URL.revokeObjectURL(url), 0);
    } catch (requestError) {
      setError(message(requestError));
    } finally {
      setExporting(false);
    }
  }

  useEffect(() => {
    void loadOrganization();
  }, []);

  async function saveOrganization() {
    if (!organization) return;
    setSaving(true);
    setError("");
    setNotice("");
    try {
      const updated = await apiData<Organization>("/api/v1/organization", {
        method: "PATCH",
        body: JSON.stringify({
          name: nameDraft.trim(),
          type: typeDraft,
          metadata: { ...organization.metadata, description: descriptionDraft.trim(), contactEmail: contactEmailDraft.trim() },
        }),
      });
      setOrganization(updated);
      setNameDraft(updated.name);
      setTypeDraft(updated.type);
      setDescriptionDraft(typeof updated.metadata.description === "string" ? updated.metadata.description : "");
      setContactEmailDraft(typeof updated.metadata.contactEmail === "string" ? updated.metadata.contactEmail : "");
      setNotice("Organization settings saved.");
    } catch (requestError) {
      setError(message(requestError));
    } finally {
      setSaving(false);
    }
  }

  const currentMember = members.find((member) => member.userId === user?.id);
  const canManageMembers = currentMember?.status === "ACTIVE"
    && (currentMember.role === "OWNER" || currentMember.role === "ADMIN");
  const canChangeMemberRoles = currentMember?.status === "ACTIVE" && currentMember.role === "OWNER";

  async function updateMemberStatus(member: Member, status: "ACTIVE" | "SUSPENDED" | "REVOKED") {
    if (savingMemberId) return;
    if (status === "REVOKED" && !window.confirm(`Remove ${member.displayName || member.email} from this organization?`)) {
      return;
    }
    setSavingMemberId(member.id);
    setError("");
    setNotice("");
    try {
      const updated = await apiData<Member>(`/api/v1/organization/members/${encodeURIComponent(member.id)}/status`, {
        method: "PATCH",
        body: JSON.stringify({ status, reason: `membership.${status.toLowerCase()}` }),
      });
      setMembers((current) => current.map((item) => item.id === updated.id ? updated : item));
      setNotice(status === "REVOKED" ? "Member removed." : `Member ${status === "ACTIVE" ? "reactivated" : "suspended"}.`);
    } catch (requestError) {
      setError(message(requestError));
    } finally {
      setSavingMemberId("");
    }
  }

  async function updateMemberRole(member: Member, role: string) {
    if (savingMemberId || role === member.role) return;
    setSavingMemberId(member.id);
    setError("");
    setNotice("");
    try {
      const updated = await apiData<Member>(`/api/v1/organization/members/${encodeURIComponent(member.id)}/role`, {
        method: "PATCH",
        body: JSON.stringify({ role }),
      });
      setMembers((current) => current.map((item) => item.id === updated.id ? updated : item));
      setNotice(`${updated.displayName || updated.email}'s role updated to ${updated.role}.`);
    } catch (requestError) {
      setError(message(requestError));
    } finally {
      setSavingMemberId("");
    }
  }

  async function createOrganization() {
    if (saving || createName.trim().length < 2) return;
    setSaving(true);
    setError("");
    setNotice("");
    try {
      const created = await apiData<Organization>("/api/v1/organization", {
        method: "POST",
        body: JSON.stringify({ name: createName.trim(), type: createType, metadata: {} }),
      });
      setCreateOpen(false);
      setCreateName("");
      try {
        await switchWorkspace(created.id);
      } catch (switchError) {
        setError(`Organization “${created.name}” was created, but switching failed. Reload the page to select it. ${message(switchError)}`);
        return;
      }
      setNotice(`Workspace “${created.name}” created and selected.`);
      await loadOrganization();
    } catch (requestError) {
      setError(message(requestError));
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="premium-page organization-page">
      {canManageMembers && <div className="organization-live-actions">
        <button className="button button--primary" type="button" onClick={() => setCreateOpen((open) => !open)}>Create organization</button>
      </div>}
      {createOpen && <section className="panel organization-live-panel" aria-labelledby="create-organization-title">
        <div className="resource-create-heading">
          <span className={`resource-create-icon resource-icon--organization-${createType.toLowerCase()}`} aria-hidden="true"><Building2 size={19} /></span>
          <div><h2 id="create-organization-title">Create an organization</h2><p>You will become its owner. On success, this workspace will be selected.</p></div>
        </div>
        <label className="organization-live-field">Organization name<input className="field-control" value={createName} onChange={(event) => setCreateName(event.target.value)} minLength={2} maxLength={120} required /></label>
        <label className="organization-live-field">Organization type<select className="field-control" value={createType} onChange={(event) => setCreateType(event.target.value as Organization["type"])}><option value="DEVELOPER">Developer</option><option value="ENTERPRISE">Enterprise</option><option value="PARTNER">Partner</option></select></label>
        <div className="organization-live-actions">
          <button className="button button--secondary" type="button" onClick={() => setCreateOpen(false)} disabled={saving}>Cancel</button>
          <button className="button button--primary" type="button" onClick={() => void createOrganization()} disabled={saving || createName.trim().length < 2}>{saving ? "Creating..." : "Create and switch"}</button>
        </div>
      </section>}
      <PageHeader
        eyebrow="ORGANIZATION"
        title={organization?.name ?? "Organization"}
        description="Manage persisted organization details, memberships, projects, activity, and security policy."
        action={<div className="usage-header-actions"><button className="button button--secondary" type="button" onClick={() => void loadOrganization()} disabled={loading}><RefreshCw size={14} />{loading ? "Refreshing…" : "Refresh"}</button><button className="button button--primary" type="button" onClick={() => void exportOrganization()} disabled={loading || exporting || !organization}><Download size={14} />{exporting ? "Preparing CSV…" : "Export organization"}</button></div>}
      />
      {error && <p className="notification-alert" role="alert">{error}</p>}
      {notice && <p className="notification-success" role="status">{notice}</p>}
      {sectionErrors.length > 0 && <div className="notification-alert" role="alert">{sectionErrors.map((item) => <p key={item}>{item}</p>)}</div>}
      {!organization && loading && <div className="panel organization-live-empty">Loading organization data…</div>}
      {!organization && !loading && !error && <div className="panel organization-live-empty">No organization was returned for this account.</div>}
      {organization && (
        <>
          <section className="organization-live-summary">
            <article className="panel organization-live-card"><Building2 size={16} /><small>STATUS</small><strong>{organization.status}</strong></article>
            <article className="panel organization-live-card"><Users size={16} /><small>MEMBERS</small><strong>{members.length}</strong><span>backend records</span></article>
            <article className="panel organization-live-card"><Building2 size={16} /><small>PROJECTS</small><strong>{projects.length}</strong><span>backend records</span></article>
            <article className="panel organization-live-card"><Clock3 size={16} /><small>CREATED</small><strong>{displayDate(organization.createdAt)}</strong></article>
          </section>
          <nav className="organization-live-tabs" aria-label="Organization sections">
            {TABS.map((item) => <button key={item} type="button" className={tab === item ? "is-active" : ""} aria-current={tab === item ? "page" : undefined} onClick={() => setTab(item)}>{item}</button>)}
          </nav>

          {tab === "Overview" && (
            <section className="organization-live-grid">
              <article className="panel organization-live-panel"><h2>Organization profile</h2><dl>
                <LiveFact label="Name" value={organization.name} />
                <LiveFact label="Slug" value={organization.slug} />
                <LiveFact label="Type" value={organization.type} />
                <LiveFact label="Owner user ID" value={organization.ownerUserId} />
                <LiveFact label="Verified" value={displayDate(organization.verifiedAt)} />
                <LiveFact label="Last updated" value={displayDate(organization.updatedAt)} />
              </dl></article>
              <article className="panel organization-live-panel"><h2>Organization metadata</h2><pre className="organization-live-json">{JSON.stringify(organization.metadata, null, 2)}</pre></article>
              <article className="panel organization-live-panel organization-live-wide"><h2>Security posture</h2>{security
                ? <div className="organization-live-security">
                  <LiveFact label="MFA required" value={security.mfaRequired ? "Everyone" : security.mfaRequiredForAdmins ? "Administrators" : "No"} />
                  <LiveFact label="Authentication methods" value={security.allowedAuthMethods.join(", ") || "None configured"} />
                  <LiveFact label="Session lifetime" value={`${security.sessionTtlMinutes} minutes`} />
                  <LiveFact label="Maximum sessions" value={String(security.maxSessions)} />
                </div>
                : <p>Security policy is unavailable; see the API error above.</p>}</article>
            </section>
          )}
          {tab === "Members" && (
            <section className="panel organization-live-panel"><h2>Organization members</h2><p>Membership records from the authenticated organization.</p>{members.length
              ? <div className="table-scroll"><table className="data-table"><thead><tr><th>MEMBER</th><th>ROLE</th><th>STATUS</th><th>JOINED</th><th>UPDATED</th>{canManageMembers && <th>ACTIONS</th>}</tr></thead><tbody>{members.map((member) => {
                const isCurrentMember = member.userId === user?.id;
                const isOwner = member.role === "OWNER";
                const canManageThisMember = canManageMembers && !isCurrentMember && !isOwner && member.status !== "REVOKED";
                return <tr key={member.id}>
                  <td><strong>{member.displayName || member.email}</strong><code>{member.email}</code></td>
                  <td>{canChangeMemberRoles && !isCurrentMember && !isOwner
                    ? <select className="field-control" aria-label={`Change ${member.displayName || member.email}'s role`} value={member.role} disabled={Boolean(savingMemberId) || member.status === "REVOKED"} onChange={(event) => void updateMemberRole(member, event.target.value)}>
                      {["ADMIN", "DEVELOPER", "ANALYST", "VIEWER", "READ_ONLY"].map((role) => <option key={role} value={role}>{role}</option>)}
                    </select>
                    : member.role}</td>
                  <td>{member.status}</td><td>{displayDate(member.createdAt)}</td><td>{displayDate(member.updatedAt)}</td>
                  {canManageMembers && <td>{canManageThisMember && <div className="organization-member-actions">
                    {member.status === "ACTIVE"
                      ? <button className="text-button" type="button" disabled={Boolean(savingMemberId)} onClick={() => void updateMemberStatus(member, "SUSPENDED")}>Suspend</button>
                      : <button className="text-button" type="button" disabled={Boolean(savingMemberId)} onClick={() => void updateMemberStatus(member, "ACTIVE")}>Reactivate</button>}
                    <button className="text-button text-button--danger" type="button" disabled={Boolean(savingMemberId)} onClick={() => void updateMemberStatus(member, "REVOKED")}>Remove</button>
                  </div>}</td>}
                </tr>;
              })}</tbody></table></div>
              : <div className="organization-live-empty">No member records returned.</div>}</section>
          )}
          {tab === "Projects" && (
            <section className="panel organization-live-panel"><h2>Projects</h2><p>Projects returned for this organization.</p>{projects.length
              ? <div className="table-scroll"><table className="data-table"><thead><tr><th>PROJECT</th><th>STATUS</th><th>ID</th><th>CREATED</th></tr></thead><tbody>{projects.map((project) => <tr key={project.id}><td><strong>{project.name}</strong></td><td>{project.status}</td><td><code>{project.id}</code></td><td>{displayDate(project.createdAt)}</td></tr>)}</tbody></table></div>
              : <div className="organization-live-empty">No projects returned.</div>}</section>
          )}
          {tab === "Activity" && (
            <section className="panel organization-live-panel"><h2>Audit activity</h2><p>Latest organization audit records.</p>{events.length
              ? <div className="table-scroll"><table className="data-table"><thead><tr><th>SEQUENCE</th><th>ACTION</th><th>RESOURCE</th><th>ACTOR</th><th>TIME</th></tr></thead><tbody>{events.map((event) => <tr key={event.id}><td>{event.sequenceNumber}</td><td>{event.action}</td><td>{event.resourceType}<code>{event.resourceId}</code></td><td><code>{event.actorUserId}</code></td><td>{displayDate(event.createdAt)}</td></tr>)}</tbody></table></div>
              : <div className="organization-live-empty">No audit events returned.</div>}</section>
          )}
          {tab === "Settings" && (
            <section className="panel organization-live-panel"><h2>Organization settings</h2><p>Changes are persisted by the organization API.</p>
              <label className="organization-live-field">Organization name<input className="field-control" value={nameDraft} onChange={(event) => setNameDraft(event.target.value)} maxLength={120} disabled={!hasPermission("organization:update")} /></label>
              <label className="organization-live-field">Organization type<select className="field-control" value={typeDraft} onChange={(event) => setTypeDraft(event.target.value as Organization["type"])} disabled={!hasPermission("organization:update")}><option value="DEVELOPER">Developer</option><option value="ENTERPRISE">Enterprise</option><option value="PARTNER">Partner</option></select></label>
              <label className="organization-live-field">Description<textarea className="field-control" value={descriptionDraft} onChange={(event) => setDescriptionDraft(event.target.value)} maxLength={500} rows={3} disabled={!hasPermission("organization:update")} /></label>
              <label className="organization-live-field">Contact email<input className="field-control" type="email" value={contactEmailDraft} onChange={(event) => setContactEmailDraft(event.target.value)} maxLength={320} disabled={!hasPermission("organization:update")} /></label>
              <div className="organization-live-setting-note"><LockKeyhole size={15} />Security preferences are read from the organization security settings API and managed in Security Center.</div>
              <div className="organization-live-actions"><button className="button button--primary" type="button" disabled={saving || !nameDraft.trim() || !hasPermission("organization:update")} onClick={() => void saveOrganization()}><Check size={14} />{saving ? "Saving…" : "Save organization"}</button></div>
            </section>
          )}
        </>
      )}
    </div>
  );
}

function LiveFact({ label, value }: { label: string; value: string }) {
  return <div className="organization-live-fact"><dt>{label}</dt><dd>{value || "—"}</dd></div>;
}
