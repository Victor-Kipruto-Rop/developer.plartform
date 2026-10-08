/**
 * The single HTTP entry point for the whole application.
 *
 * <p>Everything here exists because the access token is short-lived (5 minutes).
 * Without help, a user working normally would be signed out every five minutes.
 * Three mechanisms prevent that, and they compose:
 *
 * <ol>
 *   <li><b>Proactive refresh.</b> A request made while the token is close to
 *       expiry refreshes first, so an ordinary call never fails.</li>
 *   <li><b>Reactive refresh.</b> A 401 triggers one refresh and one retry.</li>
 *   <li><b>Single-flight.</b> Concurrent refreshes share one network call. Two
 *       simultaneous requests must not both spend the single-use refresh token;
 *       the second is treated by the backend as a replay and revokes the whole
 *       family, signing the user out everywhere.</li>
 * </ol>
 *
 * <p>Refresh is attempted <b>once</b> per request. A retry loop here would turn a
 * genuinely dead session into an infinite storm against `/auth/refresh`.
 */

import type {
  ApiEnvelope,
  ApiErrorBody,
  ApiViolation,
  AuthSession,
  SelectableOrganization,
} from "../types/auth";
import {
  clearSession,
  getAccessToken,
  getRefreshToken,
  isAccessTokenExpiring,
  persistSession,
  publishRotated,
  publishSignedOut,
} from "./session";

type ViteEnvironment = {
  VITE_API_BASE_URL?: string;
  DEV?: boolean;
};

const viteEnv =
  typeof import.meta !== "undefined"
    ? (import.meta as ImportMeta & { env?: ViteEnvironment }).env
    : undefined;

export const API_BASE_URL =
  viteEnv?.VITE_API_BASE_URL
  || (viteEnv?.DEV
    ? `http://${typeof window !== "undefined" ? window.location.hostname : "localhost"}:8080`
    : "https://api.pesaguard.co.ke");
export const IS_DEVELOPMENT = Boolean(viteEnv?.DEV);

export const SAFE_ERROR_MESSAGES = {
  REQUEST_FAILED: "We couldn't complete your request.",
  LOAD_FAILED: "We couldn't load this information.",
  SAVE_FAILED: "We couldn't save your changes.",
  DELETE_FAILED: "We couldn't complete the deletion.",
  NETWORK_ERROR: "We couldn't complete the request right now. Please try again.",
  TIMEOUT: "The request took too long to complete. Please try again.",
  UNAUTHORIZED: "Please sign in to continue.",
  FORBIDDEN: "You don't have permission to perform this action.",
  NOT_FOUND: "We couldn't find what you're looking for.",
  CONFLICT: "This action couldn't be completed because the information has changed.",
  VALIDATION: "Please check the information you entered.",
  RATE_LIMITED: "You're sending requests too quickly. Please try again shortly.",
  SERVICE_UNAVAILABLE: "This service is temporarily unavailable.",
  UNKNOWN_ERROR: "Something went wrong. Please try again.",
} as const;

function safeMessageForStatus(status: number, code: string): string {
  switch (code) {
    case "INVALID_CREDENTIALS":
    case "LOGIN_FAILED":
      return "We couldn't sign you in with those details.";
    case "EMAIL_NOT_VERIFIED":
      return "Please verify your email to continue.";
    case "MFA_REQUIRED":
    case "LOGIN_EMAIL_MFA_REQUIRED":
      return "Please verify your identity to continue.";
    case "MFA_ENROLLMENT_REQUIRED":
      return "Set up additional sign-in protection to continue.";
    case "ORGANIZATION_SELECTION_REQUIRED":
      return "Choose a workspace to continue.";
    case "USERNAME_REQUIRED":
      return "Enter a username to create your account.";
    case "USERNAME_INVALID":
      return "Username must be 6 to 25 lowercase letters (a-z) and cannot be an email address.";
    case "USERNAME_ALREADY_TAKEN":
      return "That username is already in use. Choose another one.";
    case "EMAIL_ALREADY_REGISTERED":
      return "An account already exists for this email. Sign in or reset your password.";
    case "REGISTRATION_DISABLED":
      return "New account registration is currently unavailable.";
    case "RESOURCE_CONFLICT":
      return "This account or resource already exists. Check your details and try again.";
    case "RATE_LIMITED":
      return SAFE_ERROR_MESSAGES.RATE_LIMITED;
  }
  if (status === 400) return "We couldn't process that request.";
  if (status === 401) return SAFE_ERROR_MESSAGES.UNAUTHORIZED;
  if (status === 403) return SAFE_ERROR_MESSAGES.FORBIDDEN;
  if (status === 404) return SAFE_ERROR_MESSAGES.NOT_FOUND;
  if (status === 409) return SAFE_ERROR_MESSAGES.CONFLICT;
  if (status === 422) return SAFE_ERROR_MESSAGES.VALIDATION;
  if (status === 429) return SAFE_ERROR_MESSAGES.RATE_LIMITED;
  if (status === 502) return "We couldn't complete the request right now. Please try again.";
  if (status === 503) return SAFE_ERROR_MESSAGES.SERVICE_UNAVAILABLE;
  if (status === 504) return SAFE_ERROR_MESSAGES.TIMEOUT;
  if (status >= 500) return SAFE_ERROR_MESSAGES.UNKNOWN_ERROR;
  return SAFE_ERROR_MESSAGES.REQUEST_FAILED;
}

function safeViolations(violations: ApiViolation[] | null | undefined): ApiViolation[] {
  if (!Array.isArray(violations)) return [];
  return violations.slice(0, 20).map(() => ({
    field: "request",
    message: "Please check the information you entered.",
  }));
}

function safeRequestId(value: string | null | undefined): string | null {
  return value && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value)
    ? value
    : null;
}

/**
 * A failed API call, carrying the backend's own error code.
 *
 * <p>Pages branch on {@link code}: INVALID_CREDENTIALS versus MFA_REQUIRED versus
 * REFRESH_TOKEN_REUSE need different screens. Collapsing them into
 * `Error.message` loses that, which is why the previous implementation threw
 * bare `Error`s and pages could only guess.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  private readonly requestId: string | null;
  readonly timestamp: string | null;
  readonly violations: ApiViolation[];
  /**
   * Workspaces offered on an organization-selection challenge.
   *
   * <p>Empty for every other error. Exposed on the error rather than re-fetched, so
   * the picker renders from data the backend already proved the caller may see.
   */
  readonly selectableOrganizations: SelectableOrganization[];
  readonly verificationExpiresAt: string | null;
  readonly verificationResendAvailableAt: string | null;
  readonly verificationEmail: string | null;
  readonly mfaEnrollmentToken: string | null;
  readonly mfaEnrollmentExpiresAt: string | null;
  readonly loginChallengeId: string | null;
  readonly loginChallengeExpiresAt: string | null;
  readonly loginChallengeResendAvailableAt: string | null;
  readonly maskedLoginEmail: string | null;

  constructor(status: number, body: ApiErrorBody | null, _fallback: string) {
    const code = body?.code && /^[A-Z][A-Z0-9_]{1,63}$/.test(body.code)
      ? body.code
      : "UNKNOWN";
    super(safeMessageForStatus(status, code));
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.requestId = safeRequestId(body?.requestId);
    this.timestamp = body?.timestamp ?? null;
    this.violations = safeViolations(body?.violations);
    this.selectableOrganizations = body?.selectableOrganizations ?? [];
    this.verificationExpiresAt = body?.verificationExpiresAt ?? null;
    this.verificationResendAvailableAt = body?.verificationResendAvailableAt ?? null;
    this.verificationEmail = body?.verificationEmail ?? null;
    this.mfaEnrollmentToken = body?.mfaEnrollmentToken ?? null;
    this.mfaEnrollmentExpiresAt = body?.mfaEnrollmentExpiresAt ?? null;
    this.loginChallengeId = body?.loginChallengeId ?? null;
    this.loginChallengeExpiresAt = body?.loginChallengeExpiresAt ?? null;
    this.loginChallengeResendAvailableAt = body?.loginChallengeResendAvailableAt ?? null;
    this.maskedLoginEmail = body?.maskedLoginEmail ?? null;
  }

  /** True when the credential is missing, expired or revoked: sign out. */
  get isUnauthenticated(): boolean {
    return this.status === 401;
  }

  get supportReference(): string | null {
    return this.requestId
      ? `PG-${this.requestId.replaceAll("-", "").slice(0, 6).toUpperCase()}`
      : null;
  }
}

/**
 * A transport failure has no API response, request id, or trustworthy status.
 * Keeping it distinct from a server error lets every page show a useful retry
 * message without claiming that an operation reached the backend.
 */
export class NetworkRequestError extends Error {
  readonly code = "NETWORK_UNAVAILABLE";

  constructor(cause?: unknown) {
    super(SAFE_ERROR_MESSAGES.NETWORK_ERROR);
    this.name = "NetworkRequestError";
    void cause;
  }
}

function networkError(cause: unknown): Error {
  if (cause instanceof DOMException && cause.name === "AbortError") {
    return new Error("This request was cancelled.");
  }
  return new NetworkRequestError(cause);
}

export class RequestTimeoutError extends Error {
  readonly code = "TIMEOUT";

  constructor() {
    super(SAFE_ERROR_MESSAGES.TIMEOUT);
    this.name = "RequestTimeoutError";
  }
}

export class InvalidApiResponseError extends Error {
  readonly code = "INVALID_API_RESPONSE";

  constructor() {
    super("We couldn't process the server response. Please try again.");
    this.name = "InvalidApiResponseError";
  }
}

export function safeUserErrorMessage(error: unknown, fallback: string): string {
  if (error instanceof ApiError
    || error instanceof NetworkRequestError
    || error instanceof RequestTimeoutError
    || error instanceof InvalidApiResponseError) {
    return error.message;
  }
  return fallback;
}

type SessionListener = (session: ReturnType<typeof persistSession>) => void;

const sessionListeners = new Set<SessionListener>();
const signOutListeners = new Set<() => void>();

/** Notified whenever a new credential pair is stored. */
export function onSessionRefreshed(listener: SessionListener): () => void {
  sessionListeners.add(listener);
  return () => sessionListeners.delete(listener);
}

/** Notified when the session is gone and the UI must return to sign-in. */
export function onSignedOut(listener: () => void): () => void {
  signOutListeners.add(listener);
  return () => signOutListeners.delete(listener);
}

function emitSession(session: ReturnType<typeof persistSession>): void {
  for (const listener of sessionListeners) {
    listener(session);
  }
}

function emitSignedOut(): void {
  for (const listener of signOutListeners) {
    listener();
  }
}

async function readError(response: Response): Promise<ApiErrorBody | null> {
  const text = await response.text().catch(() => "");
  if (!text) {
    return null;
  }
  try {
    const parsed = JSON.parse(text) as { error?: ApiErrorBody };
    return parsed.error ?? null;
  } catch {
    // A non-JSON body means a proxy or gateway answered, not the API. The status
    // code still carries meaning, so the caller keeps that.
    return null;
  }
}

/**
 * In-flight refresh, shared by every caller that needs one.
 *
 * <p>The single most important object in this file. Without it, two requests
 * firing at once both refresh, the second presentation of the refresh token is
 * classified as reuse by the backend, and the whole family is revoked: a
 * self-inflicted sign-out caused entirely by ordinary concurrent UI.
 */
let refreshInFlight: Promise<AuthSession> | null = null;

async function performRefresh(): Promise<AuthSession> {
  const refreshToken = getRefreshToken();
  if (!refreshToken) {
    throw new ApiError(401, null, "Your session has ended. Please sign in again.");
  }

  let response: Response;
  const controller = new AbortController();
  let timedOut = false;
  const timeout = setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, 30000);
  try {
    response = await fetch(`${API_BASE_URL}/api/v1/auth/refresh`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ refreshToken }),
      signal: controller.signal,
    });
  } catch (cause) {
    // A missing network must not clear a valid local session. The user can retry
    // when connectivity returns, and the normal refresh flow will decide whether
    // the session itself is still valid.
    if (timedOut) throw new RequestTimeoutError();
    throw networkError(cause);
  } finally {
    clearTimeout(timeout);
  }

  if (!response.ok) {
    const body = await readError(response);
    // Any refresh failure ends the session: the token is spent, revoked or
    // expired, and there is nothing further to try.
    clearSession();
    publishSignedOut();
    emitSignedOut();
    throw new ApiError(
      response.status,
      body,
      body?.code === "REFRESH_TOKEN_REUSE"
        ? "This session was ended for security reasons. Please sign in again."
        : "Your session has ended. Please sign in again.",
    );
  }

  let payload: ApiEnvelope<AuthSession>;
  let stored: ReturnType<typeof persistSession>;
  try {
    payload = (await response.json()) as ApiEnvelope<AuthSession>;
    if (!payload?.data?.accessToken || !payload.data.refreshToken
        || !payload.data.expiresAt || !payload.data.user || !payload.data.organization) {
      throw new InvalidApiResponseError();
    }
    stored = persistSession(payload.data);
  } catch {
    throw new InvalidApiResponseError();
  }
  // Tell other tabs. Without this a second tab keeps spending the old token and
  // trips reuse detection.
  publishRotated(stored);
  emitSession(stored);
  return payload.data;
}

function refreshOnce(): Promise<AuthSession> {
  if (!refreshInFlight) {
    refreshInFlight = performRefresh().finally(() => {
      // Cleared in `finally` so a failed refresh does not wedge every later
      // request against a permanently rejected promise.
      refreshInFlight = null;
    });
  }
  return refreshInFlight;
}

export async function refreshSession(): Promise<void> {
  await refreshOnce();
}

export type ApiFetchOptions = RequestInit & {
  /** Set for calls that must not trigger a refresh, e.g. /auth/refresh itself. */
  skipRefresh?: boolean;
  /** Set for calls that must not carry a bearer token, e.g. /auth/login. */
  anonymous?: boolean;
  /** In-memory restricted challenge credential; never persisted or refreshed. */
  authorizationToken?: string;
  /** Optional policy for developer-tool requests. Automatic retries are GET/HEAD only. */
  requestPolicy?: { timeoutMs: number; retryCount: number };
  /** Backend-provided environment API base URL for environment-bound API-key calls. */
  baseUrl?: string;
  responseType?: "json" | "blob";
  /** Inspect final response headers without consuming the response body. */
  onResponse?: (response: Response) => void;
};

/**
 * Calls the API with the current credential, refreshing it if needed.
 *
 * <p>Resolves to the unwrapped `data` field rather than the whole envelope, so
 * callers never repeat `.data`. 204 responses resolve to `undefined`.
 */
export async function apiFetch<T>(path: string, options: ApiFetchOptions = {}): Promise<T> {
  const { skipRefresh, anonymous, authorizationToken, requestPolicy, baseUrl, responseType = "json", onResponse, ...init } = options;

  // Proactive path: refresh before the token lapses rather than letting this
  // request be the one that fails.
  if (!skipRefresh && !anonymous && getAccessToken() && isAccessTokenExpiring()) {
    try {
      await refreshOnce();
    } catch {
      // Fall through and let the request produce the 401. Refreshing is an
      // optimisation, not a gate; swallowing here avoids masking the real error.
    }
  }

  const headers = new Headers(init.headers ?? {});
  const token = anonymous ? null : authorizationToken ?? getAccessToken();
  if (token && !headers.has("Authorization")) {
    headers.set("Authorization", `Bearer ${token}`);
  }
  if (!headers.has("Content-Type") && init.body && !(init.body instanceof FormData)) {
    headers.set("Content-Type", "application/json");
  }

  const url = `${baseUrl ?? API_BASE_URL}${path.startsWith("/") ? path : `/${path}`}`;
  const method = (init.method ?? "GET").toUpperCase();
  const retryable = method === "GET" || method === "HEAD";
  const maxRetries = requestPolicy && retryable
    ? Math.max(0, Math.min(5, Math.trunc(requestPolicy.retryCount)))
    : 0;
  let response: Response;

  for (let attempt = 0; ; attempt += 1) {
    const controller = new AbortController();
    let timedOut = false;
    let timeout: ReturnType<typeof setTimeout> | undefined;
    let abortExternal: (() => void) | undefined;
    if (init.signal?.aborted) throw new Error("This request was cancelled.");
    abortExternal = () => controller.abort(init.signal?.reason);
    init.signal?.addEventListener("abort", abortExternal, { once: true });
    timeout = setTimeout(() => {
      timedOut = true;
      controller.abort();
    }, Math.max(1000, Math.min(60000, requestPolicy?.timeoutMs ?? 30000)));
    try {
      response = await fetch(url, {
        ...init,
        headers,
        signal: controller.signal,
      });
      if (response.status >= 500 && attempt < maxRetries) {
        await new Promise((resolve) => setTimeout(resolve, 250 * (2 ** attempt)));
        continue;
      }
      break;
    } catch (cause) {
      if (attempt < maxRetries && !init.signal?.aborted) {
        await new Promise((resolve) => setTimeout(resolve, 250 * (2 ** attempt)));
        continue;
      }
      if (timedOut) {
        throw new RequestTimeoutError();
      }
      throw networkError(cause);
    } finally {
      if (timeout !== undefined) clearTimeout(timeout);
      if (abortExternal) init.signal?.removeEventListener("abort", abortExternal);
    }
  }

  onResponse?.(response);

  if (response.ok) {
    if (response.status === 204) {
      return undefined as T;
    }
    if (responseType === "blob") {
      return await response.blob() as T;
    }
    const contentType = response.headers.get("content-type") ?? "";
    if (!contentType.includes("json")) {
      if (response.status === 202 && !(await response.text()).trim()) {
        return undefined as T;
      }
      throw new InvalidApiResponseError();
    }
    // The whole envelope is returned, not the unwrapped `data`. Every existing
    // caller declares `apiFetch<ApiEnvelope<T>>` and reads `.data`; unwrapping
    // here would silently break all of them to an `undefined`. New code that
    // wants the payload directly should use `apiData`.
    try {
      return (await response.json()) as T;
    } catch {
      throw new InvalidApiResponseError();
    }
  }

  // The error body is read at most once and reused.
  //
  // A Response body is a single-use stream: reading it here for the 401 check
  // leaves nothing for the throw below, so the second read resolved to null and
  // every final 401 surfaced as code "UNKNOWN". That silently discarded
  // MFA_REQUIRED and INVALID_CREDENTIALS, which is precisely the distinction the
  // sign-in screen needs to decide which prompt to show.
  const errorBody = await readError(response);

  // Reactive path: one 401 means the token expired sooner than expected, or was
  // revoked. Refresh once and replay the request exactly once.
  if (response.status === 401 && !skipRefresh && !anonymous) {
    // REFRESH_TOKEN_REUSE means the family is already dead; refreshing again
    // cannot help and would only produce a second, noisier failure.
    if (errorBody?.code !== "REFRESH_TOKEN_REUSE") {
      await refreshOnce();
      return apiFetch<T>(path, { ...options, skipRefresh: true });
    }
  }

  throw new ApiError(response.status, errorBody, SAFE_ERROR_MESSAGES.REQUEST_FAILED);
}

/**
 * Like {@link apiFetch}, but resolves to the unwrapped `data` field.
 *
 * <p>Preferred for new code: it removes a layer of nesting from every call site
 * and makes it obvious which shape a function returns. {@link apiFetch} keeps the
 * envelope because the existing pages are written against it.
 */
export async function apiData<T>(path: string, options: ApiFetchOptions = {}): Promise<T> {
  const envelope = await apiFetch<ApiEnvelope<T> | undefined>(path, options);
  if (envelope === undefined) {
    return undefined as T;
  }
  if (!envelope || typeof envelope !== "object" || !("data" in envelope)) {
    throw new InvalidApiResponseError();
  }
  return envelope?.data;
}

/** Download a binary or text export through the same authenticated refresh path as API JSON requests. */
export async function apiBlob(path: string, options: ApiFetchOptions = {}): Promise<Blob> {
  return apiFetch<Blob>(path, { ...options, responseType: "blob" });
}
