import type { LucideIcon } from "lucide-react";
import {
  Activity,
  BookOpenText,
  ChartNoAxesCombined,
  Code2,
  FileText,
  KeyRound,
  LifeBuoy,
  Lightbulb,
  Plug,
  Rocket,
  Settings2,
  ShieldCheck,
  Users,
  Webhook,
} from "lucide-react";

export type PageId =
  | "overview"
  | "settings"
  | "organizations"
  | "organization-detail"
  | "organization-members"
  | "organization-invitations"
  | "organization-settings"
  | "projects"
  | "project-overview"
  | "project-members"
  | "project-settings"
  | "project-activity"
  | "environments"
  | "environment-detail"
  | "environment-settings"
  | "api-explorer"
  | "developer-tools"
  | "api-lifecycle"
  | "go-live"
  | "api-catalog"
  | "external-integrations"
  | "api-details"
  | "api-versions"
  | "api-endpoint"
  | "api-keys"
  | "oauth-applications"
  | "service-accounts"
  | "webhooks"
  | "events"
  | "load-testing"
  | "usage"
  | "logs"
  | "debugging"
  | "activity"
  | "security"
  | "security-center"
  | "security-events"
  | "sessions"
  | "production"
  | "production-access"
  | "production-requirements"
  | "verification"
  | "audit-logs"
  | "audit-details"
  | "notifications"
  | "notification-settings"
  | "suggestions"
  | "support-hub"
  | "sandbox"
  | "sandbox-activity"
  | "sandbox-settings"
  | "account-settings"
  | "security-settings"
  | "appearance-settings"
  | "developer-settings"
  | "team-settings"
  | "danger-zone"
  | "not-found"
  | "unauthorized"
  | "forbidden"
  | "server-error"
  | "maintenance";

export const pageIds = [
  "overview",
  "organizations",
  "organization-detail",
  "organization-members",
  "organization-invitations",
  "organization-settings",
  "projects",
  "project-overview",
  "project-members",
  "project-settings",
  "project-activity",
  "environments",
  "environment-detail",
  "environment-settings",
  "api-explorer",
  "developer-tools",
  "api-lifecycle",
  "go-live",
  "api-catalog",
  "external-integrations",
  "api-details",
  "api-versions",
  "api-endpoint",
  "api-keys",
  "oauth-applications",
  "service-accounts",
  "webhooks",
  "events",
  "load-testing",
  "usage",
  "logs",
  "debugging",
  "activity",
  "security",
  "security-center",
  "security-events",
  "sessions",
  "production",
  "production-access",
  "production-requirements",
  "verification",
  "audit-logs",
  "audit-details",
  "notifications",
  "notification-settings",
  "suggestions",
  "support-hub",
  "sandbox",
  "sandbox-activity",
  "sandbox-settings",
  "settings",
  "account-settings",
  "security-settings",
  "appearance-settings",
  "developer-settings",
  "team-settings",
  "danger-zone",
  "not-found",
  "unauthorized",
  "forbidden",
  "server-error",
  "maintenance",
] as const satisfies readonly PageId[];

export type NavigationGroup = "Overview" | "Build" | "Monitor" | "Management" | "Resources";
export type NavigationDrawer = "integrations" | "changelog";

export type NavigationTarget =
  | { kind: "page"; page: PageId }
  | { kind: "drawer"; drawer: NavigationDrawer }
  | { kind: "external"; href: string };

export interface NavigationItem {
  id: string;
  label: string;
  icon: LucideIcon;
  group: NavigationGroup;
  target: NavigationTarget;
}

export const navigationItems: NavigationItem[] = [
  { id: "workspace", label: "Workspace", icon: Activity, group: "Overview", target: { kind: "page", page: "overview" } },
  { id: "organizations", label: "Organizations", icon: Users, group: "Overview", target: { kind: "page", page: "organizations" } },
  { id: "projects", label: "Projects", icon: Code2, group: "Overview", target: { kind: "page", page: "projects" } },
  { id: "environments", label: "Environments", icon: Activity, group: "Overview", target: { kind: "page", page: "environments" } },
  { id: "api-explorer", label: "API Explorer", icon: Code2, group: "Build", target: { kind: "page", page: "api-explorer" } },
  { id: "api-keys", label: "API Keys", icon: KeyRound, group: "Build", target: { kind: "page", page: "api-keys" } },
  { id: "webhooks", label: "Webhooks", icon: Webhook, group: "Build", target: { kind: "page", page: "webhooks" } },
  { id: "load-testing", label: "Load Testing", icon: Activity, group: "Build", target: { kind: "page", page: "load-testing" } },
  { id: "usage", label: "Usage", icon: ChartNoAxesCombined, group: "Monitor", target: { kind: "page", page: "usage" } },
  { id: "activity", label: "Activity", icon: Activity, group: "Monitor", target: { kind: "page", page: "activity" } },
  { id: "security-center", label: "Security", icon: ShieldCheck, group: "Monitor", target: { kind: "page", page: "security-center" } },
  { id: "go-live", label: "Go-Live", icon: Rocket, group: "Build", target: { kind: "page", page: "go-live" } },
  { id: "settings", label: "Settings", icon: Settings2, group: "Management", target: { kind: "page", page: "settings" } },
  { id: "team-access", label: "Team & Access", icon: Users, group: "Management", target: { kind: "page", page: "organization-members" } },
  { id: "integrations", label: "Integrations", icon: Plug, group: "Management", target: { kind: "page", page: "external-integrations" } },
  { id: "notifications", label: "Notifications", icon: Activity, group: "Management", target: { kind: "page", page: "notifications" } },
  { id: "documentation", label: "Documentation", icon: BookOpenText, group: "Resources", target: { kind: "external", href: "https://docs.pesaguard.co.ke" } },
  { id: "api-reference", label: "API Reference", icon: Code2, group: "Resources", target: { kind: "page", page: "api-catalog" } },
  { id: "changelog", label: "Changelog", icon: FileText, group: "Resources", target: { kind: "drawer", drawer: "changelog" } },
  { id: "suggestions", label: "Suggestions", icon: Lightbulb, group: "Resources", target: { kind: "page", page: "suggestions" } },
  { id: "support", label: "Support", icon: LifeBuoy, group: "Resources", target: { kind: "page", page: "support-hub" } },
];

export const apiEndpointSearchIndex = [
  { method: "GET", path: "/v1/transactions", title: "List transactions" },
  { method: "POST", path: "/v1/reconciliation/runs", title: "Create reconciliation run" },
  { method: "GET", path: "/v1/accounts/{account_id}", title: "Retrieve account" },
  { method: "POST", path: "/v1/transactions", title: "Create transaction" },
  { method: "GET", path: "/v1/transactions/{transaction_id}", title: "Retrieve transaction" },
  { method: "GET", path: "/v1/accounts", title: "List accounts" },
  { method: "GET", path: "/v1/reconciliation/runs", title: "List reconciliation runs" },
  { method: "GET", path: "/v1/reconciliation/runs/{run_id}", title: "Retrieve reconciliation run" },
  { method: "GET", path: "/v1/webhooks/endpoints", title: "List webhook endpoints" },
  { method: "POST", path: "/v1/webhooks/endpoints", title: "Create webhook endpoint" },
];

export const externalLinks = {
  docs: "https://docs.pesaguard.co.ke",
  status: "https://status.pesaguard.co.ke",
  publicSite: "https://pesaguard.co.ke",
};

export const helpLinks = [
  { label: "Documentation", href: externalLinks.docs, icon: BookOpenText },
];
