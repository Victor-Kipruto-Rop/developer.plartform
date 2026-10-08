import { useEffect, useMemo, useState } from "react";
import {
  Activity,
  ArrowRight,
  Braces,
  Check,
  Code2,
  Copy,
  KeyRound,
  LockKeyhole,
  Network,
  Search,
  Webhook,
  Wrench,
  X,
} from "lucide-react";
import type { PageId } from "../../app/routes";
import { PageHeader } from "../../components/ui/PageHeader";
import { ApiError, apiData } from "../../lib/api";
import { copyTextToClipboard } from "../../lib/clipboard";
import { useAuth } from "../../context/AuthContext";
import {
  readActiveEnvironmentContext,
  readActiveEnvironmentId,
  readActiveProjectId,
  setActiveEnvironment,
} from "../../lib/activeEnvironment";

type ToolId =
  | "api-explorer"
  | "request-builder"
  | "webhook-tester"
  | "event-simulator"
  | "json-viewer"
  | "jwt-inspector"
  | "signature-verifier"
  | "api-key-tester"
  | "request-generator"
  | "code-generator"
  | "sdk-generator"
  | "request-history"
  | "environment-switcher";

type ToolEntry = {
  id: ToolId;
  title: string;
  description: string;
  kind: "Platform" | "Browser utility";
  destination?: PageId;
  tags: string[];
  category: "API" | "Credentials" | "Events" | "Code";
};
type GeneratorEnvironment = {
  id: string;
  projectId: string;
  projectName: string;
  name: string;
  type: string;
  status: string;
  baseUrl: string;
};

const toolEntries: ToolEntry[] = [
  { id: "api-explorer", title: "API Explorer", description: "Send supported authenticated requests to the selected PesaGuard environment and inspect real responses.", kind: "Platform", destination: "api-explorer", tags: ["api", "catalog", "request", "http"], category: "API" },
  { id: "request-builder", title: "Request builder", description: "Build and send supported read-only requests with session or environment-scoped API-key authentication.", kind: "Platform", destination: "api-explorer", tags: ["http", "request", "builder"], category: "API" },
  { id: "webhook-tester", title: "Webhook deliveries", description: "Inspect delivery attempts, response codes, and replay failed deliveries.", kind: "Platform", destination: "webhooks", tags: ["webhook", "delivery", "retry"], category: "Events" },
  { id: "event-simulator", title: "Event delivery monitor", description: "Inspect configured event subscriptions and persisted delivery outcomes.", kind: "Platform", destination: "events", tags: ["event", "delivery", "webhook"], category: "Events" },
  { id: "json-viewer", title: "JSON workbench", description: "Parse, validate, and format JSON input with useful syntax errors.", kind: "Browser utility", tags: ["json", "format", "validate"], category: "Code" },
  { id: "jwt-inspector", title: "JWT inspector", description: "Decode token headers and claims and inspect registered claim expiry. Decoding does not verify signatures.", kind: "Browser utility", tags: ["jwt", "token", "decode", "claims"], category: "Credentials" },
  { id: "signature-verifier", title: "Webhook signing", description: "Configure webhook signing and inspect persisted delivery signatures.", kind: "Platform", destination: "webhooks", tags: ["hmac", "signature", "webhook"], category: "Events" },
  { id: "api-key-tester", title: "API-key connection test", description: "Authenticate against the selected environment and verify the key's real environment and scopes.", kind: "Platform", tags: ["api key", "credential", "test", "validate"], category: "Credentials" },
  { id: "request-generator", title: "Request generator", description: "Generate an environment-aware cURL request for an endpoint and HTTP method.", kind: "Browser utility", tags: ["request", "curl", "generate"], category: "API" },
  { id: "code-generator", title: "Client code generator", description: "Generate request examples for cURL, JavaScript, Java, Python, and Go.", kind: "Browser utility", tags: ["code", "javascript", "java", "python", "go"], category: "Code" },
  { id: "sdk-generator", title: "Client configuration", description: "Generate a server-side client configuration using the selected environment's configured API host.", kind: "Browser utility", tags: ["sdk", "client", "generate", "configuration"], category: "Code" },
  { id: "request-history", title: "Request history", description: "Review executed requests and persisted API request logs.", kind: "Platform", destination: "logs", tags: ["history", "saved", "requests"], category: "API" },
  { id: "environment-switcher", title: "Environment selection", description: "Choose a project environment and view its configured API host.", kind: "Platform", destination: "environments", tags: ["sandbox", "production", "environment"], category: "API" },
];

function decodeBase64Url(value: string) {
  const normalized = value.replaceAll("-", "+").replaceAll("_", "/");
  const binary = atob(normalized + "=".repeat((4 - (normalized.length % 4)) % 4));
  const bytes = Uint8Array.from(binary, (character) => character.charCodeAt(0));
  return new TextDecoder("utf-8", { fatal: true }).decode(bytes);
}

export function DeveloperToolsPage({ onNavigate }: { onNavigate: (page: PageId) => void }) {
  const { isAuthenticated } = useAuth();
  const [search, setSearch] = useState("");
  const [activeTool, setActiveTool] = useState<ToolId | null>(null);
  const [message, setMessage] = useState("");
  const [jsonInput, setJsonInput] = useState("");
  const [jwtInput, setJwtInput] = useState("");
  const [jwtOutput, setJwtOutput] = useState("");
  const [keyInput, setKeyInput] = useState("");
  const [keyOutput, setKeyOutput] = useState("");
  const [keyTesting, setKeyTesting] = useState(false);
  const [method, setMethod] = useState("GET");
  const [codeLanguage, setCodeLanguage] = useState("javascript");
  const [endpoint, setEndpoint] = useState("");
  const [copied, setCopied] = useState(false);
  const [generatorEnvironments, setGeneratorEnvironments] = useState<GeneratorEnvironment[]>([]);
  const [selectedEnvironmentId, setSelectedEnvironmentId] = useState("");
  const [environmentLoading, setEnvironmentLoading] = useState(false);
  const [environmentError, setEnvironmentError] = useState("");
  const selectedEnvironment = generatorEnvironments.find((environment) => environment.id === selectedEnvironmentId);

  useEffect(() => {
    if (!isAuthenticated) {
      setGeneratorEnvironments([]);
      setSelectedEnvironmentId("");
      setEnvironmentLoading(false);
      return;
    }
    let active = true;
    setEnvironmentLoading(true);
    setEnvironmentError("");
    apiData<{ items: { id: string; name: string }[] }>("/api/v1/projects?page=0&size=100")
      .then(async ({ items }) => {
        if (!Array.isArray(items)) throw new Error("The projects API returned an invalid list.");
        const environments = (await Promise.all(items.map(async (project) => {
          const projectEnvironments = await apiData<Omit<GeneratorEnvironment, "projectName">[]>(
            `/api/v1/projects/${encodeURIComponent(project.id)}/environments`,
          );
          return projectEnvironments.map((environment) => ({ ...environment, projectName: project.name }));
        }))).flat();
        if (!active) return;
        setGeneratorEnvironments(environments);
        const activeProjectId = readActiveProjectId();
        const activeEnvironmentId = readActiveEnvironmentId(activeProjectId);
        const selected = environments.find((environment) =>
          environment.projectId === activeProjectId
          && environment.id === activeEnvironmentId
          && environment.status === "ACTIVE")
          ?? environments.find((environment) =>
            environment.projectId === activeProjectId
            && environment.type === "SANDBOX"
            && environment.status === "ACTIVE")
          ?? environments.find((environment) =>
            environment.projectId === activeProjectId && environment.status === "ACTIVE")
          ?? environments.find((environment) => environment.type === "SANDBOX" && environment.status === "ACTIVE")
          ?? environments.find((environment) => environment.status === "ACTIVE");
        setSelectedEnvironmentId(selected?.id ?? "");
      })
      .catch((cause: unknown) => {
        if (active) {
          setGeneratorEnvironments([]);
          setSelectedEnvironmentId("");
          setEnvironmentError(cause instanceof Error ? cause.message : "Unable to load environments.");
        }
      })
      .finally(() => {
        if (active) setEnvironmentLoading(false);
      });
    return () => { active = false; };
  }, [isAuthenticated]);

  useEffect(() => {
    function syncActiveEnvironment() {
      const context = readActiveEnvironmentContext();
      const selected = generatorEnvironments.find((environment) =>
        environment.projectId === context.projectId
        && environment.id === context.environmentId
        && environment.status === "ACTIVE")
        ?? generatorEnvironments.find((environment) =>
          environment.projectId === context.projectId
          && environment.type === "SANDBOX"
          && environment.status === "ACTIVE")
        ?? generatorEnvironments.find((environment) =>
          environment.projectId === context.projectId && environment.status === "ACTIVE");
      setSelectedEnvironmentId(selected?.id ?? "");
    }
    window.addEventListener("pesaguard:active-context-changed", syncActiveEnvironment);
    return () => window.removeEventListener("pesaguard:active-context-changed", syncActiveEnvironment);
  }, [generatorEnvironments]);

  const filteredTools = useMemo(() => {
    const query = search.trim().toLowerCase();
    if (!query) return toolEntries;
    return toolEntries.filter((tool) => [tool.title, tool.description, ...tool.tags].join(" ").toLowerCase().includes(query));
  }, [search]);

  const activeToolDetails = toolEntries.find((tool) => tool.id === activeTool);
  const requestExample = `curl -X ${method} "https://api.pesaguard.co.ke${endpoint}" \\\n  -H "Accept: application/json" \\\n  -H "Authorization: Bearer YOUR_API_KEY"`;
  const javascriptExample = `const response = await fetch("https://api.pesaguard.co.ke${endpoint}", {\n  method: "${method}",\n  headers: {\n    "Accept": "application/json",\n    "Authorization": "Bearer YOUR_API_KEY",\n  },\n});\nconst data = await response.json();`;
  const sdkExample = `// Install the official PesaGuard SDK from your package registry.\n// Confirm the current package name and version in PesaGuard docs.\nconst client = new PesaGuardClient({\n  apiKey: process.env.PESAGUARD_API_KEY,\n  environment: "sandbox",\n});`;
  const generatedBaseUrl = selectedEnvironment?.baseUrl ?? "<select-an-environment>";
  const generatedRequestExample = requestExample
    .replaceAll("https://api.pesaguard.co.ke", generatedBaseUrl)
    .replace('-H "Authorization: ******"', '-H "Authorization: Bearer $PESAGUARD_API_KEY"');
  const generatedJavascriptExample = javascriptExample
    .replaceAll("https://api.pesaguard.co.ke", generatedBaseUrl)
    .replace('"Authorization": "******",', '"Authorization": "Bearer " + process.env.PESAGUARD_API_KEY,');
  const generatedSdkExample = sdkExample.replace('environment: "sandbox",', `baseUrl: "${generatedBaseUrl}",`);
  const activeToolOutput = activeTool === "request-generator" ? generatedRequestExample
    : activeTool === "code-generator" ? generatedJavascriptExample
      : activeTool === "sdk-generator" ? generatedSdkExample
        : "";
  const generatedEndpointIsSafe = endpoint.startsWith("/")
    && !endpoint.startsWith("//")
    && !/[\r\n"'`]/.test(endpoint)
    && (() => {
      try {
        return new URL(endpoint, generatedBaseUrl).origin === new URL(generatedBaseUrl).origin;
      } catch {
        return false;
      }
    })();
  const generatedUrl = generatedEndpointIsSafe
    ? new URL(endpoint, generatedBaseUrl).toString()
    : "";
  const generatedUrlLiteral = JSON.stringify(generatedUrl);
  const usableClientCode: Record<string, string> = {
    curl: `curl --request ${method} '${generatedUrl}' \\\n  --header 'Accept: application/json' \\\n  --header "Authorization: Bearer \${PESAGUARD_API_KEY}"`,
    javascript: `const apiKey = process.env.PESAGUARD_API_KEY;\nif (!apiKey) throw new Error("Set PESAGUARD_API_KEY in the server environment.");\n\nconst response = await fetch(${generatedUrlLiteral}, {\n  method: ${JSON.stringify(method)},\n  headers: {\n    Accept: "application/json",\n    Authorization: \`Bearer \${apiKey}\`,\n  },\n});\nif (!response.ok) throw new Error(\`PesaGuard API returned \${response.status}\`);\nconst data = await response.json();`,
    java: `import java.net.URI;\nimport java.net.http.HttpClient;\nimport java.net.http.HttpRequest;\nimport java.net.http.HttpResponse;\n\nString apiKey = System.getenv("PESAGUARD_API_KEY");\nif (apiKey == null || apiKey.isBlank()) throw new IllegalStateException("Set PESAGUARD_API_KEY in the server environment.");\nHttpRequest request = HttpRequest.newBuilder(URI.create(${generatedUrlLiteral}))\n    .header("Accept", "application/json")\n    .header("Authorization", "Bearer " + apiKey)\n    .method(${JSON.stringify(method)}, HttpRequest.BodyPublishers.noBody())\n    .build();\nHttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());\nif (response.statusCode() < 200 || response.statusCode() >= 300) {\n    throw new IllegalStateException("PesaGuard API returned HTTP " + response.statusCode());\n}\nSystem.out.println(response.body());`,
    python: `import os\nimport requests\n\napi_key = os.environ["PESAGUARD_API_KEY"]\nresponse = requests.request(\n    ${JSON.stringify(method)}, ${generatedUrlLiteral},\n    headers={\n        "Accept": "application/json",\n        "Authorization": f"Bearer {api_key}",\n    },\n    timeout=15,\n)\nresponse.raise_for_status()\ndata = response.json()\nprint(data)`,
    go: `package main\n\nimport (\n    "fmt"\n    "net/http"\n    "os"\n)\n\nfunc main() {\n    apiKey := os.Getenv("PESAGUARD_API_KEY")\n    if apiKey == "" {\n        panic("Set PESAGUARD_API_KEY in the server environment.")\n    }\n    request, err := http.NewRequest(${JSON.stringify(method)}, ${generatedUrlLiteral}, nil)\n    if err != nil {\n        panic(err)\n    }\n    request.Header.Set("Accept", "application/json")\n    request.Header.Set("Authorization", "Bearer "+apiKey)\n    response, err := http.DefaultClient.Do(request)\n    if err != nil {\n        panic(err)\n    }\n    defer response.Body.Close()\n    if response.StatusCode < 200 || response.StatusCode >= 300 {\n        panic(fmt.Sprintf("PesaGuard API returned HTTP %d", response.StatusCode))\n    }\n}`,
  };
  const clientCode: Record<string, string> = {
    curl: `curl --request ${method} ${JSON.stringify(generatedUrl)} \\\n  --header "Accept: application/json" \\\n  --header "Authorization: Bearer $PESAGUARD_API_KEY"`,
    javascript: `const response = await fetch(${JSON.stringify(generatedUrl)}, {\n  method: ${JSON.stringify(method)},\n  headers: { Accept: "application/json", Authorization: \`Bearer \${process.env.PESAGUARD_API_KEY}\` },\n});\nif (!response.ok) throw new Error(\`PesaGuard API returned \${response.status}\`);\nconst data = await response.json();`,
    java: `var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(${JSON.stringify(generatedUrl)}))\n    .header("Accept", "application/json")\n    .header("Authorization", "Bearer " + System.getenv("PESAGUARD_API_KEY"))\n    .method(${JSON.stringify(method)}, java.net.http.HttpRequest.BodyPublishers.noBody())\n    .build();\nvar response = java.net.http.HttpClient.newHttpClient().send(request, java.net.http.HttpResponse.BodyHandlers.ofString());`,
    python: `import os\nimport requests\n\nresponse = requests.request(\n    ${JSON.stringify(method)}, ${JSON.stringify(generatedUrl)},\n    headers={"Accept": "application/json", "Authorization": f"Bearer {os.environ['PESAGUARD_API_KEY']}"},\n    timeout=15,\n)\nresponse.raise_for_status()\ndata = response.json()`,
    go: `request, err := http.NewRequest(${JSON.stringify(method)}, ${JSON.stringify(generatedUrl)}, nil)\nif err != nil { return err }\nrequest.Header.Set("Accept", "application/json")\nrequest.Header.Set("Authorization", "Bearer "+os.Getenv("PESAGUARD_API_KEY"))\nresponse, err := http.DefaultClient.Do(request)\nif err != nil { return err }\ndefer response.Body.Close()`,
  };
  const clientConfiguration = selectedEnvironment
    ? `PESAGUARD_BASE_URL=${JSON.stringify(selectedEnvironment.baseUrl)}\nPESAGUARD_ENVIRONMENT=${JSON.stringify(selectedEnvironment.type.toLowerCase())}\n# Store the secret in your server-side secret manager.\nPESAGUARD_API_KEY=replace-with-environment-scoped-secret`
    : "";
  const fullToolOutput = activeTool === "request-generator" ? usableClientCode.curl
    : activeTool === "code-generator" ? usableClientCode[codeLanguage] ?? usableClientCode.javascript
      : activeTool === "sdk-generator" ? clientConfiguration : activeToolOutput || clientCode.javascript;

  function selectGeneratorEnvironment(environmentId: string) {
    const environment = generatorEnvironments.find((item) => item.id === environmentId);
    if (!environment) return;
    setSelectedEnvironmentId(environment.id);
    setActiveEnvironment(
      { id: environment.projectId, name: environment.projectName },
      environment,
    );
  }

  function openTool(tool: ToolEntry) {
    if (tool.destination) {
      if (tool.id === "request-history") {
        try {
          window.sessionStorage.setItem("pesaguard.developer-tools.open-request-history", "true");
        } catch (error) {
          console.warn("Unable to prepare the request-history shortcut.");
          setMessage("The history shortcut could not be prepared. Open History from API Explorer instead.");
        }
      }
      if (tool.id === "signature-verifier") {
        try {
          window.sessionStorage.setItem("pesaguard.developer-tools.open-signature-verifier", "true");
        } catch (error) {
          console.warn("Unable to prepare the signature-verifier shortcut.");
        }
      }
      onNavigate(tool.destination);
      return;
    }
    setActiveTool(tool.id);
    setMessage("");
    setJwtOutput("");
    setKeyOutput("");
    setCopied(false);
  }

  async function copyOutput(value: string) {
    try {
      await copyTextToClipboard(value);
      setCopied(true);
      setMessage("Copied to clipboard.");
      window.setTimeout(() => setCopied(false), 1500);
    } catch (error) {
      console.warn("Unable to copy generated developer-tool output.");
      setMessage("Clipboard access was denied. Select and copy the output manually.");
    }
  }

  function inspectJwt() {
    const parts = jwtInput.trim().split(".");
    if (parts.length !== 3) {
      setJwtOutput("Invalid JWT shape: expected three dot-separated segments.");
      return;
    }
    try {
      const header: unknown = JSON.parse(decodeBase64Url(parts[0]));
      const claims: unknown = JSON.parse(decodeBase64Url(parts[1]));
      setJwtOutput(JSON.stringify({ header, claims, verification: "Not performed. Decoding does not verify signature, issuer, audience, or expiry." }, null, 2));
    } catch {
      setJwtOutput("Could not decode the JWT header and claims. Check that both segments are valid base64url-encoded JSON.");
    }
  }

  async function testApiKey() {
    const value = keyInput.trim();
    if (!value || !selectedEnvironment) {
      setKeyOutput(!value ? "Enter an environment-scoped API key." : "Select the environment this key belongs to.");
      return;
    }
    setKeyTesting(true);
    setKeyOutput("");
    try {
      const context = await apiData<{
        environmentType: string;
        mode: string;
        status: string;
        scopes: string[];
      }>("/api/v1/key-data/auth/context", {
        anonymous: true,
        baseUrl: selectedEnvironment.baseUrl,
        headers: { Authorization: `Bearer ${value}` },
      });
      setKeyOutput(JSON.stringify({
        authenticated: true,
        environmentMatches: context.environmentType.toUpperCase() === selectedEnvironment.type,
        selectedEnvironment: selectedEnvironment.type,
        keyEnvironment: context.environmentType,
        mode: context.mode,
        status: context.status,
        scopes: context.scopes,
        secretStored: false,
      }, null, 2));
    } catch (error) {
      setKeyOutput(error instanceof ApiError
        ? `Authentication failed: ${error.message}`
        : "Connection test failed. Please try again.");
    } finally {
      setKeyTesting(false);
      setKeyInput("");
    }
  }

  function runJsonViewer() {
    try {
      const parsed: unknown = JSON.parse(jsonInput);
      setJsonInput(JSON.stringify(parsed, null, 2));
      setMessage("Valid JSON. Formatted locally.");
    } catch (error) {
      setMessage("Invalid JSON. Check the syntax and try again.");
    }
  }

  return (
    <>
      <PageHeader eyebrow="BUILD · TOOLCHAIN" title="Developer tools" description="A connected workbench for building, testing, and operating PesaGuard integrations." />
      <section className="developer-tools-hero">
        <div><span className="section-eyebrow"><Wrench size={13} /> INTEGRATION WORKSPACE</span><h2>Build with live platform context.</h2><p>Tools use configured PesaGuard APIs and selected project environments. Provider connections are reported only when actually configured.</p></div>
        <div className="developer-tools-hero__stats"><span><strong>{toolEntries.length}</strong> available tools</span><span><strong>{generatorEnvironments.length}</strong> environments discovered</span><span><strong>{isAuthenticated ? "Connected" : "Sign in"}</strong> workspace API</span></div>
      </section>
      <label className="developer-tools-search"><Search size={16} /><span className="visually-hidden">Search developer tools</span><input aria-label="Search developer tools" type="search" value={search} onChange={(event) => setSearch(event.target.value)} placeholder="Search tools, e.g. JWT, webhook, code generator" /></label>
      {message && !activeTool && <p className="security-feedback" role="status">{message}</p>}
      {(["API", "Credentials", "Events", "Code"] as const).map((category) => {
        const tools = filteredTools.filter((tool) => tool.category === category);
        if (tools.length === 0) return null;
        const CategoryIcon = category === "API" ? Network : category === "Credentials" ? KeyRound : category === "Events" ? Activity : Code2;
        return <section className="developer-tools-section" aria-label={`${category} tools`} key={category}>
          <div className="developer-tools-section__heading"><span><CategoryIcon size={15} /><strong>{category}</strong></span><small>{tools.length} tool{tools.length === 1 ? "" : "s"}</small></div>
          <div className="developer-tools-grid">{tools.map((tool) => <article className="panel developer-tool-card" key={tool.id}>
            <span className="developer-tool-icon">{tool.category === "Events" ? <Webhook size={17} /> : tool.category === "Credentials" ? <KeyRound size={17} /> : tool.category === "Code" ? <Braces size={17} /> : <Network size={17} />}</span>
            <div className="developer-tool-copy"><div><h2>{tool.title}</h2><span className="developer-tool-kind">{tool.kind}</span></div><p>{tool.description}</p></div>
            <button className="button button--secondary" type="button" onClick={() => openTool(tool)}>{tool.destination ? "Open" : "Launch"}<ArrowRight size={13} /></button>
          </article>)}</div>
        </section>;
      })}
      {filteredTools.length === 0 && <div className="panel developer-tools-empty"><strong>No tools match that search</strong><span>Try another tool name or keyword.</span></div>}

      {activeToolDetails && <section className="panel developer-tool-workbench">
        <div className="panel-heading"><div><span className="table-tag">INTERACTIVE WORKBENCH</span><h2>{activeToolDetails.title}</h2><p>{activeToolDetails.description}</p></div><button className="icon-button" type="button" aria-label="Close workbench" onClick={() => setActiveTool(null)}><X size={16} /></button></div>
        {activeTool !== null && ["request-generator", "code-generator", "sdk-generator", "api-key-tester"].includes(activeTool) && <div className="developer-tool-form developer-tool-environment">
          <label>Project environment<select aria-label="Integration project environment" value={selectedEnvironmentId} onChange={(event) => selectGeneratorEnvironment(event.target.value)} disabled={!isAuthenticated || generatorEnvironments.length === 0}>
            <option value="">{environmentLoading ? "Loading project environments…" : "Select a project environment"}</option>
            {generatorEnvironments.filter((environment) => environment.status === "ACTIVE").map((environment) => <option key={environment.id} value={environment.id}>{environment.projectName} · {environment.name} · {environment.type}</option>)}
          </select></label>
          {environmentError && <p className="workflow-error" role="alert">Could not load integration environments: {environmentError}</p>}
          {selectedEnvironment && <p className="developer-tool-integration-base"><strong>Official {selectedEnvironment.type} Base URL</strong><code>{selectedEnvironment.baseUrl}</code><span>Authentication: Bearer API key · use this key only in trusted server-side code.</span></p>}
          {selectedEnvironment?.type === "PRODUCTION" && <p className="workflow-hint" role="alert">Live Production environment. Confirm the endpoint and use only a Production key stored in your trusted server.</p>}
        </div>}
        {activeTool === "json-viewer" && <div className="developer-tool-form"><label>JSON input<textarea aria-label="JSON input" value={jsonInput} onChange={(event) => setJsonInput(event.target.value)} spellCheck={false} /></label><div className="developer-tool-actions"><button className="button button--primary" type="button" onClick={runJsonViewer}><Braces size={14} />Format and validate</button></div>{message && <p role="status">{message}</p>}</div>}
        {activeTool === "jwt-inspector" && <div className="developer-tool-form"><label>JWT<input type="password" autoComplete="off" aria-label="JWT input" value={jwtInput} onChange={(event) => setJwtInput(event.target.value)} placeholder="Paste a token (kept in memory only)" /></label><button className="button button--primary" type="button" onClick={inspectJwt}><LockKeyhole size={14} />Decode token</button><p className="developer-tool-caution">Decoding does not verify a signature or establish that the token is trusted.</p>{jwtOutput && <pre>{jwtOutput}</pre>}</div>}
        {activeTool === "api-key-tester" && <div className="developer-tool-form"><label>Environment API key<input type="password" autoComplete="off" aria-label="Environment API key" value={keyInput} onChange={(event) => setKeyInput(event.target.value)} placeholder="Secret is cleared after the verification request" /></label><button className="button button--primary" type="button" disabled={keyTesting || !isAuthenticated || !selectedEnvironment} onClick={() => void testApiKey()}><KeyRound size={14} />{keyTesting ? "Verifying…" : "Verify against environment"}</button>{keyOutput && <pre>{keyOutput}</pre>}<p className="developer-tool-caution">The key is sent only to the selected environment's authentication-context endpoint, is never saved, and is cleared after the attempt.</p></div>}
        {activeTool !== null && ["request-generator", "code-generator", "sdk-generator"].includes(activeTool) && <div className="developer-tool-form">{activeTool !== "sdk-generator" && <div className="developer-tool-request-options"><label>HTTP method<select aria-label="Generator HTTP method" value={method} onChange={(event) => setMethod(event.target.value)}>{["GET", "POST", "PUT", "PATCH", "DELETE"].map((item) => <option key={item}>{item}</option>)}</select></label><label>Endpoint path<input aria-label="Generator endpoint path" value={endpoint} onChange={(event) => setEndpoint(event.target.value)} placeholder="/api/v1/…" aria-invalid={Boolean(endpoint) && !generatedEndpointIsSafe} /></label></div>}{activeTool === "code-generator" && <label className="developer-tool-language">Target platform<select value={codeLanguage} onChange={(event) => setCodeLanguage(event.target.value)}>{["curl", "javascript", "java", "python", "go"].map((language) => <option value={language} key={language}>{language === "curl" ? "cURL" : language}</option>)}</select></label>}{activeTool === "sdk-generator" && <p className="workflow-hint">No official SDK package registry is configured here. This generates the real environment configuration without claiming an SDK package is available.</p>}<div className="developer-tool-output-heading"><strong>Generated {activeTool === "code-generator" ? codeLanguage : activeTool === "sdk-generator" ? "environment config" : "cURL"}</strong><button className="icon-button" aria-label="Copy generated example" type="button" disabled={activeTool !== "sdk-generator" && !generatedEndpointIsSafe} onClick={() => void copyOutput(fullToolOutput)}>{copied ? <Check size={14} /> : <Copy size={14} />}</button></div>{activeTool === "sdk-generator" || generatedEndpointIsSafe ? <pre>{fullToolOutput}</pre> : <p>Enter a same-host path beginning with <code>/</code> to generate a safe request.</p>}{message && <p role="status">{message}</p>}<p className="developer-tool-caution">Examples use the selected host and read secrets from server-side environment variables. Never embed a production secret in client-side code.</p></div>}
      </section>}
    </>
  );
}
