import { useMemo, useState } from "react";
import {
  ArrowRight,
  Bell,
  Building2,
  Code2,
  KeyRound,
  LockKeyhole,
  Search,
  Settings2,
  ShieldCheck,
  SlidersHorizontal,
  Users,
  Webhook,
  type LucideIcon,
} from "lucide-react";
import type { PageId } from "../../app/routes";
import { PageHeader } from "../../components/ui/PageHeader";
import { useAuth } from "../../context/AuthContext";

type SettingsItem = {
  label: string;
  description: string;
  keywords: string;
  page: PageId;
  permission?: string | string[];
};

type SettingsGroup = {
  title: string;
  description: string;
  scope: string;
  icon: LucideIcon;
  items: SettingsItem[];
};

interface SettingsPageProps {
  onNavigate?: (page: PageId) => void;
}

const settingsGroups: SettingsGroup[] = [
  {
    title: "Account",
    description: "Identity, account lifecycle, and your active sign-in sessions.",
    scope: "Personal",
    icon: Users,
    items: [
      { label: "Profile and contact", description: "Update your developer identity and verified contact details.", keywords: "profile email phone language timezone", page: "account-settings" },
      { label: "Active sessions", description: "Review and revoke sessions on your account.", keywords: "devices browser login revoke", page: "sessions", permission: "security:read" },
    ],
  },
  {
    title: "Security",
    description: "Authentication controls, security posture, and audit activity.",
    scope: "Personal · Organization",
    icon: ShieldCheck,
    items: [
      { label: "Security overview", description: "Review account security status and available safeguards.", keywords: "mfa password recovery passkey", page: "security-center", permission: "security:read" },
      { label: "Authentication and MFA", description: "Manage multi-factor authentication and recovery options.", keywords: "mfa totp recovery backup codes passkeys", page: "security-settings", permission: "security:read" },
      { label: "Security activity", description: "Inspect sign-ins and security-sensitive events.", keywords: "login failed password suspicious device audit", page: "security-events", permission: "security:read" },
      { label: "Audit log", description: "Review organization-scoped administrative changes.", keywords: "history audit configuration changes", page: "audit-logs", permission: "audit:read" },
    ],
  },
  {
    title: "Organization",
    description: "Manage the current organization and its access boundaries.",
    scope: "Organization",
    icon: Building2,
    items: [
      { label: "Organization profile and policies", description: "Update organization settings and security policies.", keywords: "name slug access policies security", page: "organization-settings", permission: "workspace_settings:update" },
      { label: "Members and roles", description: "Review membership, roles, and organization access.", keywords: "team members permissions access", page: "organization-members", permission: "organization:read" },
      { label: "Invitations", description: "Manage pending organization invitations.", keywords: "invite resend cancel pending", page: "organization-invitations", permission: "workspace:invite" },
    ],
  },
  {
    title: "Projects",
    description: "Settings and access are scoped to the selected project.",
    scope: "Project",
    icon: Code2,
    items: [
      { label: "Project configuration", description: "Manage safe project metadata and defaults.", keywords: "project name description api version", page: "project-settings", permission: "project:update" },
      { label: "Project access", description: "Review project membership and permissions.", keywords: "members role permissions", page: "project-members", permission: "project:read" },
    ],
  },
  {
    title: "Environments and production",
    description: "Keep development, staging, and production controls distinct.",
    scope: "Environment",
    icon: LockKeyhole,
    items: [
      { label: "Environment configuration", description: "Review environment-specific settings and access policy.", keywords: "development staging production environment", page: "environment-settings", permission: "environment:update" },
      { label: "Production readiness", description: "Open the guarded Go-Live and production approval workflow.", keywords: "production activate suspend go live approval", page: "go-live", permission: "production:view" },
    ],
  },
  {
    title: "Developer tools",
    description: "Personal API Explorer preferences and integration controls.",
    scope: "Personal · Project",
    icon: Settings2,
    items: [
      { label: "API preferences", description: "Set safe request timeout and GET retry defaults for API Explorer.", keywords: "timeout retry api version sdk code sample", page: "developer-settings" },
      { label: "API keys", description: "Create, rotate, inspect, or revoke project-scoped credentials.", keywords: "credentials secrets expiration rotate", page: "api-keys", permission: ["credential:read", "credential:create"] },
      { label: "Webhooks", description: "Manage endpoints, signatures, and delivery behavior.", keywords: "webhook events retry signing", page: "webhooks", permission: ["webhook:read", "webhook:create"] },
    ],
  },
  {
    title: "Notifications",
    description: "Configure in-app and email delivery without disabling mandatory security notices.",
    scope: "Personal",
    icon: Bell,
    items: [
      { label: "Notification preferences", description: "Choose delivery channels for supported event categories.", keywords: "email in-app security production api delivery", page: "notification-settings" },
    ],
  },
  {
    title: "Appearance and preferences",
    description: "Personal interface preferences are isolated from security policy.",
    scope: "Personal",
    icon: SlidersHorizontal,
    items: [
      { label: "Appearance", description: "The platform uses a consistent light theme.", keywords: "appearance light theme accessibility", page: "appearance-settings" },
    ],
  },
  {
    title: "Privacy and data",
    description: "Export your account data or review the protected deletion workflow.",
    scope: "Personal",
    icon: KeyRound,
    items: [
      { label: "Export or delete account", description: "Request an authenticated data export or start account deletion.", keywords: "privacy data export deletion", page: "account-settings" },
    ],
  },
  {
    title: "Danger zone",
    description: "High-impact actions are separated from routine settings.",
    scope: "Organization · Project",
    icon: Webhook,
    items: [
      { label: "Organization deletion", description: "Review owner-only organization deletion safeguards.", keywords: "delete disable ownership", page: "danger-zone", permission: "organization:update" },
      { label: "Credential and production controls", description: "Review credential revocation and Go-Live controls.", keywords: "revoke keys production suspend", page: "danger-zone", permission: "organization:update" },
    ],
  },
];

export function SettingsPage({ onNavigate }: SettingsPageProps = {}) {
  const { user, organization, hasPermission, permissionsLoaded } = useAuth();
  const [query, setQuery] = useState("");
  const visibleGroups = useMemo(() => {
    const normalizedQuery = query.trim().toLocaleLowerCase();
    return settingsGroups.map((group) => {
      const items = group.items.filter((item) => {
        const requiredPermissions = (Array.isArray(item.permission) ? item.permission : [item.permission])
          .filter((permission): permission is string => typeof permission === "string");
        if (item.permission && (!permissionsLoaded || !requiredPermissions.some(hasPermission))) return false;
        if (!normalizedQuery) return true;
        const searchable = `${item.label} ${item.description} ${item.keywords} ${group.title} ${group.scope}`.toLocaleLowerCase();
        return searchable.includes(normalizedQuery);
      });
      return { ...group, items };
    }).filter((group) => group.items.length > 0);
  }, [hasPermission, permissionsLoaded, query]);
  const resultCount = visibleGroups.reduce((total, group) => total + group.items.length, 0);

  return (
    <>
      <PageHeader
        eyebrow="CONTROL PLANE"
        title="Settings"
        description="Account, security, organization, project, and environment controls—each routed to its owning service."
      />
      <section className="settings-context-strip" aria-label="Settings scope">
        <div><span>Personal</span><strong>{user?.displayName || user?.email || "Your account"}</strong></div>
        <div><span>Organization</span><strong>{organization?.name || "Current workspace"}</strong></div>
        <p>Resource changes remain scoped to the selected project or environment and are authorized by the backend.</p>
      </section>
      <section className="settings-directory">
        <label className="settings-search">
          <Search size={17} aria-hidden="true" />
          <span className="visually-hidden">Search settings</span>
          <input
            type="search"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder="Search settings, security, webhooks…"
            aria-describedby="settings-search-help"
          />
        </label>
        <p id="settings-search-help" className="settings-search-help">Search settings by name, scope, or capability. Only areas available to your role are shown.</p>
      </section>
      {visibleGroups.length === 0
        ? <section className="panel settings-no-results" role="status">
          <Search size={20} />
          <strong>No matching settings</strong>
          <p>{resultCount === 0 && !query ? "Settings are loading or unavailable for your current permissions." : "Try a different term or clear your search."}</p>
          {query && <button type="button" className="button button--secondary" onClick={() => setQuery("")}>Clear search</button>}
        </section>
        : <section className="settings-groups settings-directory-grid" aria-label="Available settings">
          {visibleGroups.map((group) => {
            const Icon = group.icon;
            return (
              <article key={group.title} className="settings-group settings-directory-group panel">
                <div className="settings-group-header">
                  <div className="settings-group-title-wrap">
                    <span className="settings-group-icon"><Icon size={17} /></span>
                    <div><h2>{group.title}</h2><p>{group.description}</p></div>
                  </div>
                  <span className="settings-group-count">{group.scope}</span>
                </div>
                <ul className="settings-group-list" aria-label={`${group.title} settings`}>
                  {group.items.map((item) => (
                    <li key={`${group.title}-${item.label}`}>
                      <button
                        type="button"
                        className="settings-link settings-directory-link"
                        onClick={() => onNavigate?.(item.page)}
                      >
                        <span><strong>{item.label}</strong><small>{item.description}</small></span>
                        <ArrowRight size={15} aria-hidden="true" />
                      </button>
                    </li>
                  ))}
                </ul>
              </article>
            );
          })}
        </section>}
      <p className="settings-directory-footnote"><Webhook size={14} /> API keys, webhooks, notifications, and audit history continue to use their existing platform workflows.</p>
    </>
  );
}
