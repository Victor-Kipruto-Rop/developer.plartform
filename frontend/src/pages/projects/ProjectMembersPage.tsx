import { getUserMessage } from "../../lib/errors";
import { useEffect, useState } from "react";
import { Users } from "lucide-react";
import { PageHeader } from "../../components/ui/PageHeader";
import { apiData } from "../../lib/api";

type ProjectMember = {
  id: string;
  projectId: string;
  userId: string;
  email: string;
  displayName: string;
  role: string;
  status: string;
  createdAt: string;
  updatedAt: string;
};

export function ProjectMembersPage() {
  const projectId = window.sessionStorage.getItem("pesaguard.project.id") ?? "";
  const projectName = window.sessionStorage.getItem("pesaguard.project.name") ?? "";
  const [members, setMembers] = useState<ProjectMember[]>([]);
  const [loading, setLoading] = useState(Boolean(projectId));
  const [error, setError] = useState("");
  const activeMembers = members.filter((member) => member.status === "ACTIVE").length;

  useEffect(() => {
    if (!projectId) {
      setError("Choose a project before viewing project members.");
      return;
    }
    let active = true;
    void apiData<ProjectMember[]>(`/api/v1/projects/${encodeURIComponent(projectId)}/members`)
      .then((result) => { if (active) setMembers(result); })
      .catch((requestError: unknown) => {
        if (active) setError(getUserMessage(requestError, "Could not load project members."));
      })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [projectId]);

  return (
    <div className="premium-page project-members-page">
      <PageHeader eyebrow="ACCESS" title="Project members" description={projectName ? `Persisted member access for ${projectName}.` : "Persisted member access for the selected project."} />
      {error && <p className="notification-alert" role="alert">{error}</p>}
      <section className="premium-metric-grid" aria-label="Project access summary">
        <article className="panel premium-metric premium-metric--green"><span>Team members</span><strong>{members.length}</strong><small>Assigned to this project</small></article>
        <article className="panel premium-metric"><span>Active access</span><strong>{activeMembers}</strong><small>Members with enabled status</small></article>
        <article className="panel premium-metric premium-metric--violet"><span>Project</span><strong className="premium-metric-project">{projectName || "Not selected"}</strong><small>Access is scoped to this project</small></article>
      </section>
      <section className="panel table-panel">
        <div className="panel-heading"><div><h2>Team access</h2><p>Project members returned by the backend.</p></div><span className="table-tag">{members.length} RECORDS</span></div>
        {loading && members.length === 0
          ? <div className="organization-live-empty"><Users size={18} />Loading project members…</div>
          : members.length === 0
            ? <div className="organization-live-empty"><Users size={18} />No project member records returned.</div>
            : <div className="table-scroll"><table className="data-table"><thead><tr><th>MEMBER</th><th>ROLE</th><th>STATUS</th><th>ADDED</th><th>UPDATED</th></tr></thead><tbody>{members.map((member) => <tr key={member.id}><td><span className="member-identity"><span className="member-avatar" aria-hidden="true">{(member.displayName || member.email).slice(0, 2).toUpperCase()}</span><span><strong>{member.displayName || member.email}</strong><code>{member.email}</code></span></span></td><td>{member.role}</td><td><span className={`premium-status premium-status--${member.status.toLowerCase()}`}>{member.status}</span></td><td>{new Date(member.createdAt).toLocaleDateString()}</td><td>{new Date(member.updatedAt).toLocaleDateString()}</td></tr>)}</tbody></table></div>}
      </section>
    </div>
  );
}
