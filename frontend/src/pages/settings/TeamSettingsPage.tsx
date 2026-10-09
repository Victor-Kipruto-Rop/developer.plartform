import { getUserMessage } from "../../lib/errors";
import { useEffect, useState } from "react";
import { Users } from "lucide-react";
import { PageHeader } from "../../components/ui/PageHeader";
import { apiData } from "../../lib/api";

type Member = {
  id: string;
  email: string;
  displayName: string;
  role: string;
  status: string;
  createdAt: string;
  updatedAt: string;
};

export function TeamSettingsPage() {
  const [members, setMembers] = useState<Member[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  useEffect(() => {
    let active = true;
    void apiData<Member[]>("/api/v1/organization/members")
      .then((result) => { if (active) setMembers(result); })
      .catch((requestError: unknown) => {
        if (active) setError(getUserMessage(requestError, "Could not load team settings."));
      })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, []);

  return (
    <>
      <PageHeader eyebrow="TEAM" title="Team settings" description="Review current organization membership and roles returned by the backend." />
      {error && <p className="notification-alert" role="alert">{error}</p>}
      <section className="panel table-panel">
        <div className="panel-heading"><div><h2>Members</h2><p>Persisted organization membership records.</p></div><span className="table-tag">{members.length} RECORDS</span></div>
        {loading && members.length === 0
          ? <div className="organization-live-empty"><Users size={18} />Loading members…</div>
          : members.length === 0
            ? <div className="organization-live-empty"><Users size={18} />No member records returned.</div>
            : <div className="table-scroll"><table className="data-table"><thead><tr><th>MEMBER</th><th>ROLE</th><th>STATUS</th><th>JOINED</th><th>UPDATED</th></tr></thead><tbody>{members.map((member) => <tr key={member.id}><td><strong>{member.displayName || member.email}</strong><code>{member.email}</code></td><td>{member.role}</td><td>{member.status}</td><td>{new Date(member.createdAt).toLocaleDateString()}</td><td>{new Date(member.updatedAt).toLocaleDateString()}</td></tr>)}</tbody></table></div>}
      </section>
    </>
  );
}
