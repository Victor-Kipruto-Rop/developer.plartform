import { useEffect, useState } from "react";
import { ArrowRight, RefreshCw, Users } from "lucide-react";
import type { PageId } from "../../app/routes";
import { PageHeader } from "../../components/ui/PageHeader";
import { apiData } from "../../lib/api";
import { useAuth } from "../../context/AuthContext";

type Member = {
  id: string;
  userId: string;
  email: string;
  displayName: string;
  role: string;
  status: string;
  createdAt: string;
  updatedAt: string;
  assignedProjects: string[];
  lastActivityAt: string | null;
};

export function MembersPage({ onNavigate }: { onNavigate: (page: PageId) => void }) {
  const { user } = useAuth();
  const [members, setMembers] = useState<Member[]>([]);
  const [loading, setLoading] = useState(true);
  const [savingMemberId, setSavingMemberId] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const currentMember = members.find((member) => member.userId === user?.id);
  const canManageMembers = currentMember?.status === "ACTIVE"
    && (currentMember.role === "OWNER" || currentMember.role === "ADMIN");
  const canChangeRoles = currentMember?.status === "ACTIVE" && currentMember.role === "OWNER";
  const activeCount = members.filter((member) => member.status === "ACTIVE").length;
  const suspendedCount = members.filter((member) => member.status === "SUSPENDED").length;

  async function loadMembers() {
    setLoading(true);
    setError("");
    try {
      setMembers(await apiData<Member[]>("/api/v1/organization/members"));
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Could not load organization members.");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void loadMembers();
  }, []);

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
        body: JSON.stringify({
          status,
          reason: status === "REVOKED" ? "membership.removed" : `membership.${status.toLowerCase()}`,
        }),
      });
      setMembers((current) => current.map((item) => item.id === updated.id ? updated : item));
      setNotice(status === "REVOKED" ? "Member removed." : `Member ${status === "ACTIVE" ? "reactivated" : "suspended"}.`);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Could not update this member.");
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
      setError(requestError instanceof Error ? requestError.message : "Could not update this member's role.");
    } finally {
      setSavingMemberId("");
    }
  }

  return (
    <div className="premium-page members-page">
      <PageHeader
        eyebrow="ACCESS"
        title="Members"
        description="Organization membership records returned by the authenticated backend."
        action={<><button className="button button--secondary" type="button" disabled={loading} onClick={() => void loadMembers()}><RefreshCw size={14} />Refresh</button><button className="button button--primary" type="button" onClick={() => onNavigate("organization-invitations")}>Invitations<ArrowRight size={14} /></button></>}
      />
      {error && <p className="notification-alert" role="alert">{error}</p>}
      {notice && <p className="notification-success" role="status">{notice}</p>}
      <section className="premium-metric-grid" aria-label="Organization access overview">
        <article className="panel premium-metric premium-metric--green"><span>Active members</span><strong>{activeCount}</strong><small>Enabled organization access</small></article>
        <article className="panel premium-metric premium-metric--amber"><span>Suspended</span><strong>{suspendedCount}</strong><small>Access paused by an administrator</small></article>
        <article className="panel premium-metric"><span>Your access</span><strong>{currentMember?.role ?? "—"}</strong><small>{currentMember?.status ?? "Membership unavailable"}</small></article>
      </section>
      <section className="panel table-panel">
        <div className="panel-heading"><div><h2>Organization members</h2><p>Membership status and roles saved by the backend.</p></div><span className="table-tag">{members.length} RECORDS</span></div>
        {loading && members.length === 0
          ? <div className="organization-live-empty"><Users size={18} />Loading members…</div>
          : members.length === 0
            ? <div className="organization-live-empty"><Users size={18} />No member records returned.</div>
          : <div className="table-scroll"><table className="data-table"><thead><tr><th>MEMBER</th><th>ROLE</th><th>STATUS</th><th>JOINED</th><th>LAST ACTIVITY</th><th>ASSIGNED PROJECTS</th><th>MEMBERSHIP UPDATED</th>{canManageMembers && <th>ACTIONS</th>}</tr></thead><tbody>{members.map((member) => {
              const isCurrentMember = member.userId === user?.id;
              const isProtectedOwner = member.role === "OWNER";
              const canManageThisMember = canManageMembers && !isCurrentMember && !isProtectedOwner && member.status !== "REVOKED";
              return <tr key={member.id}>
                <td><span className="member-identity"><span className="member-avatar" aria-hidden="true">{(member.displayName || member.email).slice(0, 2).toUpperCase()}</span><span><strong>{member.displayName || member.email}</strong><code>{member.email}</code></span></span></td>
                <td>{canChangeRoles && !isCurrentMember && !isProtectedOwner
                  ? <select className="field-control" aria-label={`Change ${member.displayName || member.email}'s role`} value={member.role} disabled={Boolean(savingMemberId) || member.status === "REVOKED"} onChange={(event) => void updateMemberRole(member, event.target.value)}>
                    {["ADMIN", "DEVELOPER", "ANALYST", "VIEWER", "READ_ONLY"].map((role) => <option key={role} value={role}>{role}</option>)}
                  </select>
                  : member.role}</td>
                <td><span className={`premium-status premium-status--${member.status.toLowerCase()}`}>{member.status}</span></td>
                <td>{new Date(member.createdAt).toLocaleDateString()}</td>
                <td>{member.lastActivityAt ? new Date(member.lastActivityAt).toLocaleString() : "No session recorded"}</td>
                <td>{member.assignedProjects.length ? member.assignedProjects.join(", ") : "None"}</td>
                <td>{new Date(member.updatedAt).toLocaleDateString()}</td>
                {canManageMembers && <td>{canManageThisMember && <div className="organization-member-actions">
                  {member.status === "ACTIVE"
                    ? <button className="text-button" type="button" disabled={Boolean(savingMemberId)} onClick={() => void updateMemberStatus(member, "SUSPENDED")}>Suspend</button>
                    : <button className="text-button" type="button" disabled={Boolean(savingMemberId)} onClick={() => void updateMemberStatus(member, "ACTIVE")}>Reactivate</button>}
                  <button className="text-button text-button--danger" type="button" disabled={Boolean(savingMemberId)} onClick={() => void updateMemberStatus(member, "REVOKED")}>Remove</button>
                </div>}</td>}
              </tr>;
            })}</tbody></table></div>}
      </section>
    </div>
  );
}
