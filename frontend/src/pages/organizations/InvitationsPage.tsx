import { useEffect, useState, type FormEvent } from "react";
import { Mail, Plus, RefreshCw, UserPlus, UserX } from "lucide-react";
import { PageHeader } from "../../components/ui/PageHeader";
import { useAuth } from "../../context/AuthContext";
import { apiData } from "../../lib/api";
import { createUuid } from "../../lib/uuid";

type Invitation = {
  id: string;
  email: string;
  role: string;
  status: string;
  expiresAt: string;
  createdAt: string;
};
type CreatedInvitation = { id: string; email: string; role: string; expiresAt: string; deliveryStatus: "QUEUED" | "SENT" | "FAILED" | "CANCELLED" };

function invitationStatusClass(status: string) {
  return `invitation-status invitation-status--${status.toLowerCase().replace(/[^a-z0-9-]/g, "")}`;
}

export function InvitationsPage() {
  const { isAuthenticated, organization } = useAuth();
  const invitationPath = organization?.id
    ? `/api/v1/organizations/${encodeURIComponent(organization.id)}/invitations`
    : null;
  const [invitations, setInvitations] = useState<Invitation[]>([]);
  const [loading, setLoading] = useState(isAuthenticated);
  const [saving, setSaving] = useState(false);
  const [email, setEmail] = useState("");
  const [role, setRole] = useState("DEVELOPER");
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [refreshKey, setRefreshKey] = useState(0);
  const pendingCount = invitations.filter((invitation) => invitation.status === "PENDING").length;
  const expiredCount = invitations.filter((invitation) => invitation.status === "EXPIRED").length;
  const acceptedCount = invitations.filter((invitation) => invitation.status === "ACCEPTED").length;

  useEffect(() => {
    if (!isAuthenticated || !invitationPath) {
      setInvitations([]);
      setLoading(false);
      return;
    }
    const controller = new AbortController();
    setLoading(true);
    setError("");
    void apiData<Invitation[]>(invitationPath, { signal: controller.signal })
      .then((items) => {
        if (!Array.isArray(items)) throw new Error("The invitations API returned an invalid list.");
        setInvitations(items);
      })
      .catch((requestError: unknown) => {
        if (!controller.signal.aborted) setError(requestError instanceof Error ? requestError.message : "Could not load invitations.");
      })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [isAuthenticated, invitationPath, refreshKey]);

  async function createInvitation(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSaving(true);
    setError("");
    setMessage("");
    try {
      if (!invitationPath) throw new Error("Select an organization before inviting a member.");
      const created = await apiData<CreatedInvitation>(invitationPath, {
        method: "POST",
        headers: { "Idempotency-Key": createUuid() },
        body: JSON.stringify({ email: email.trim(), role }),
      });
      if (!created?.id) throw new Error("The invitations API did not return the created invitation.");
      if (!created?.id || !created.email) throw new Error("The invitations API did not confirm email delivery.");
      setMessage(created.deliveryStatus === "SENT"
        ? `Invitation email sent to ${created.email} with ${created.role} access.`
        : created.deliveryStatus === "FAILED" || created.deliveryStatus === "CANCELLED"
          ? `Invitation created, but email delivery is ${created.deliveryStatus.toLowerCase()}. Use resend to try again.`
          : `Invitation email queued for ${created.email} with ${created.role} access.`);
      setEmail("");
      setRefreshKey((key) => key + 1);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "The invitation could not be created.");
    } finally {
      setSaving(false);
    }
  }

  async function cancelInvitation(invitation: Invitation) {
    if (!window.confirm(`Cancel the invitation for ${invitation.email}?`)) return;
    setSaving(true);
    setError("");
    setMessage("");
    try {
      if (!invitationPath) throw new Error("Select an organization before cancelling an invitation.");
      await apiData<void>(`${invitationPath}/${encodeURIComponent(invitation.id)}/cancel`, { method: "POST" });
      setMessage(`Invitation for ${invitation.email} was cancelled.`);
      setRefreshKey((key) => key + 1);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "The invitation could not be revoked.");
    } finally {
      setSaving(false);
    }
  }

  async function resendInvitation(invitation: Invitation) {
    setSaving(true);
    setError("");
    setMessage("");
    try {
      if (!invitationPath) throw new Error("Select an organization before resending an invitation.");
      const created = await apiData<CreatedInvitation>(`${invitationPath}/${encodeURIComponent(invitation.id)}/resend`, { method: "POST" });
      if (!created?.id || !created.email) throw new Error("The invitations API did not confirm email delivery.");
      setMessage(`A replacement invitation email was queued for ${created.email}. The previous link is invalid.`);
      setRefreshKey((key) => key + 1);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "The invitation could not be resent.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="premium-page invitations-page">
      <PageHeader eyebrow="ACCESS" title="Invitations" description="Invite real organization members and manage outstanding access requests." action={<button className="button button--secondary" type="button" disabled={loading} onClick={() => setRefreshKey((key) => key + 1)}><RefreshCw size={14} />Refresh</button>} />
      {!isAuthenticated && <div className="preview-notice"><span className="notice-icon"><Mail size={16} /></span><p><strong>Sign in required</strong> — Invitations are loaded and managed through the organization API.</p></div>}
      {error && <p className="workflow-error" role="alert">{error}</p>}
      {message && <p className="workflow-success" role="status">{message}</p>}
      <section className="invitation-overview" aria-label="Invitation tracking summary">
        <article className="panel invitation-stat"><span>Awaiting response</span><strong>{pendingCount}</strong><small>Active invitations</small></article>
        <article className="panel invitation-stat"><span>Accepted</span><strong>{acceptedCount}</strong><small>Memberships activated</small></article>
        <article className="panel invitation-stat"><span>Expired</span><strong>{expiredCount}</strong><small>Resend to issue a fresh link</small></article>
      </section>
      <section className="panel invitation-create-panel">
        <div className="panel-heading">
          <div className="resource-create-heading">
            <span className="resource-create-icon invitation-create-icon" aria-hidden="true"><UserPlus size={19} /></span>
            <div><span className="section-eyebrow">WORKSPACE ACCESS</span><h2>Invite a member</h2><p>Grant a teammate access to this organization with the role they need.</p></div>
          </div>
          <span className="table-tag">ADMIN ACTION</span>
        </div>
        <form className="workflow-form" onSubmit={createInvitation}>
          <label>Email address<input type="email" required maxLength={320} value={email} onChange={(event) => setEmail(event.target.value)} placeholder="developer@example.com" disabled={!isAuthenticated || saving} /></label>
          <label>Organization role<select value={role} onChange={(event) => setRole(event.target.value)} disabled={!isAuthenticated || saving}><option value="ADMIN">Admin</option><option value="DEVELOPER">Developer</option><option value="VIEWER">Viewer</option></select></label>
          <div className="workflow-form-actions"><button className="button button--primary" type="submit" disabled={!isAuthenticated || saving}><Plus size={14} />{saving ? "Working…" : "Send invitation"}</button></div>
        </form>
        <p className="workflow-hint">A single-use invitation link will be sent directly to the invited email address. The private link is never shown in this dashboard.</p>
      </section>
      <section className="panel table-panel">
        <div className="panel-heading"><div><h2>Organization invitations</h2><p>Persisted invitation status and expiration.</p></div><span className="table-tag">{invitations.length} RECORDS</span></div>
        {loading ? <p className="workflow-hint" role="status">Loading invitations…</p> : invitations.length === 0 ? <div className="organization-live-empty"><Mail size={18} />No invitations returned by the organization API.</div> :
          <div className="table-scroll"><table className="data-table"><thead><tr><th>EMAIL</th><th>ROLE</th><th>STATUS</th><th>INVITED</th><th>EXPIRES</th><th>ACTIONS</th></tr></thead><tbody>{invitations.map((invitation) => <tr key={invitation.id}><td>{invitation.email}</td><td>{invitation.role}</td><td><span className={invitationStatusClass(invitation.status)}>{invitation.status}</span></td><td>{new Date(invitation.createdAt).toLocaleDateString()}</td><td>{new Date(invitation.expiresAt).toLocaleString()}</td><td>{(invitation.status === "PENDING" || invitation.status === "EXPIRED") && <div className="organization-member-actions"><button className="text-button" type="button" disabled={saving} onClick={() => void resendInvitation(invitation)}><RefreshCw size={13} />Resend</button>{invitation.status === "PENDING" && <button className="text-button text-button--danger" type="button" disabled={saving} onClick={() => void cancelInvitation(invitation)}><UserX size={13} />Cancel</button>}</div>}</td></tr>)}</tbody></table></div>}
      </section>
    </div>
  );
}
