import type { ProblemDetails } from './types';

export type ApiErrorKind =
  | 'unauthenticated' // 401
  | 'forbidden' // 403
  | 'not_found' // 404 (aussi réponse uniforme anti-IDOR)
  | 'invalid' // 422 / 413
  | 'rate_limited' // 429 / 503
  | 'server' // 5xx
  | 'network';

export class ApiError extends Error {
  constructor(
    public kind: ApiErrorKind,
    public status: number,
    message: string,
    public traceId?: string,
  ) {
    super(message);
  }
}

// Jeton d'accès : en mémoire uniquement. Jamais localStorage/sessionStorage.
let accessToken: string | null = null;
let onUnauthenticated: () => void = () => {};
export const setAccessToken = (t: string | null) => { accessToken = t; };
export const setUnauthenticatedHandler = (fn: () => void) => { onUnauthenticated = fn; };

const MESSAGES: Record<ApiErrorKind, string> = {
  unauthenticated: 'Session expirée. Reconnectez-vous.',
  forbidden: "Vous n'avez pas le droit d'effectuer cette action.",
  not_found: 'Ressource introuvable.',
  invalid: 'Les données envoyées sont refusées. Vérifiez le fichier ou le formulaire.',
  rate_limited: 'Trop de demandes. Réessayez dans quelques instants.',
  server: 'Erreur du serveur. Réessayez plus tard.',
  network: 'Serveur injoignable. Vérifiez votre connexion.',
};

export function kindFromStatus(status: number): ApiErrorKind {
  if (status === 401) return 'unauthenticated';
  if (status === 403) return 'forbidden';
  if (status === 404) return 'not_found';
  if (status === 413 || status === 422 || status === 400) return 'invalid';
  if (status === 429 || status === 503) return 'rate_limited';
  return 'server';
}

async function request(path: string, init: RequestInit = {}): Promise<Response> {
  const headers = new Headers(init.headers);
  if (!headers.has('Accept')) headers.set('Accept', 'application/json, application/problem+json');
  if (accessToken) headers.set('Authorization', `Bearer ${accessToken}`);
  if (init.body && !(init.body instanceof FormData) && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }

  let res: Response;
  try {
    res = await fetch(`/api${path}`, { ...init, headers, credentials: 'same-origin', cache: 'no-store' });
  } catch {
    throw new ApiError('network', 0, MESSAGES.network);
  }

  if (res.ok) return res;

  const kind = kindFromStatus(res.status);
  let problem: ProblemDetails = {};
  try { problem = await res.json(); } catch { /* corps non JSON */ }
  if (kind === 'unauthenticated') onUnauthenticated();
  // Le message affiché reste générique : jamais de trace interne (SR-ERR-01).
  throw new ApiError(kind, res.status, problem.title ?? MESSAGES[kind], problem.traceId);
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const res = await request(path, init);
  return (res.status === 204 ? undefined : await res.json()) as T;
}

export async function apiWithStatus<T>(path: string, init: RequestInit = {}): Promise<{ status: number; body: T }> {
  const res = await request(path, init);
  return { status: res.status, body: (res.status === 204 ? undefined : await res.json()) as T };
}

export const apiBlob = async (path: string): Promise<Blob> =>
  (await request(path, { headers: { Accept: '*/*' } })).blob();

export const errorMessage = (e: unknown): string =>
  e instanceof ApiError ? e.message : 'Une erreur inattendue est survenue.';
