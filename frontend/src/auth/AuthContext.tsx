import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { ApiError, api, apiWithStatus, setAccessToken, setUnauthenticatedHandler } from '../api/client';
import type { Role, SessionUser } from '../api/types';
import { MOCK_USERS, USE_MOCK_AUTH } from '../mocks/data';
import { BACKEND_ROLES, PRIVILEGED } from './roles';
import { decodeClaims } from './token';

export interface Credentials { email: string; password: string; otp?: string; mockRole?: Role }
export type LoginOutcome = 'ok' | 'otp_required';
const STRONG_WINDOW_MS = 5 * 60 * 1000; // ré-authentification ≤ 5 min (triptyque 2)

interface AuthState {
  user: SessionUser | null;
  notice: string | null;
  login: (c: Credentials) => Promise<LoginOutcome>;
  reauth: (password: string, otp: string) => Promise<void>;
  isStrong: () => boolean;
  logout: () => Promise<void>;
}
const AuthContext = createContext<AuthState | null>(null);

async function purgeClientCaches() {
  if ('caches' in globalThis) {
    const keys = await caches.keys();
    await Promise.all(keys.map((k) => caches.delete(k)));
  }
}

/** Contrat backend : POST /api/auth/login {email, password, otp?} -> 200 {access_token} | 202 {next:'totp'}. */
async function requestToken(email: string, password: string, otp?: string): Promise<string | null> {
  const r = await apiWithStatus<{ access_token?: string }>('/auth/login', {
    method: 'POST',
    body: JSON.stringify(otp ? { email, password, otp } : { email, password }),
  });
  if (r.status === 202) return null;
  if (!r.body.access_token) throw new ApiError('server', r.status, 'Réponse de connexion invalide.');
  return r.body.access_token;
}

export function AuthProvider({ children, initialUser }: { children: ReactNode; initialUser?: SessionUser }) {
  // État en mémoire : un rechargement de page déconnecte (voulu, pas de stockage de jeton).
  const [user, setUser] = useState<SessionUser | null>(initialUser ?? null);
  const [notice, setNotice] = useState<string | null>(null);
  const email = useRef('');

  const clear = useCallback((message?: string) => {
    setAccessToken(null); setUser(null);
    if (message) setNotice(message);
  }, []);
  useEffect(() => { setUnauthenticatedHandler(() => clear('Session expirée. Reconnectez-vous.')); }, [clear]);

  // Pas d'endpoint de refresh : le jeton vit 15 min, puis nouvelle connexion.
  useEffect(() => {
    if (!user?.expiresAt) return;
    const t = setTimeout(() => clear('Votre session de 15 minutes a expiré. Reconnectez-vous.'),
      Math.max(0, user.expiresAt - Date.now()));
    return () => clearTimeout(t);
  }, [user?.expiresAt, clear]);

  const finish = useCallback(async (token: string, mail: string) => {
    const claims = decodeClaims(token);
    if (!claims) throw new ApiError('server', 200, 'Jeton reçu illisible.');
    setAccessToken(token);
    // PROPOSITION : GET /api/auth/me {displayName, roles:[...]} n'existe pas encore côté backend.
    let me: { displayName?: string; roles?: string[] } = {};
    try { me = await api('/auth/me'); } catch (e) { if (e instanceof ApiError && e.kind === 'unauthenticated') throw e; }
    const roles = (me.roles ?? []).map((r) => BACKEND_ROLES[r]).filter((r): r is Role => !!r);
    email.current = mail;
    setNotice(null);
    setUser({ id: claims.sub, displayName: me.displayName ?? mail, roles, expiresAt: claims.exp, strongAuthAt: claims.authTime });
  }, []);

  const login = useCallback(async (c: Credentials): Promise<LoginOutcome> => {
    if (USE_MOCK_AUTH) { setUser(MOCK_USERS[c.mockRole ?? 'owner']); return 'ok'; }
    const token = await requestToken(c.email, c.password, c.otp);
    if (!token) return 'otp_required';
    await finish(token, c.email);
    return 'ok';
  }, [finish]);

  const reauth = useCallback(async (password: string, otp: string) => {
    if (USE_MOCK_AUTH) return;
    const token = await requestToken(email.current, password, otp);
    if (!token) throw new ApiError('unauthenticated', 401, 'Code TOTP requis.');
    await finish(token, email.current);
  }, [finish]);

  // Seuls les rôles privilégiés (TOTP) sont soumis à la preuve récente.
  const isStrong = useCallback(() => {
    if (USE_MOCK_AUTH || !user || !user.roles.some((r) => PRIVILEGED.includes(r))) return true;
    return user.strongAuthAt !== undefined && Date.now() - user.strongAuthAt <= STRONG_WINDOW_MS;
  }, [user]);

  const logout = useCallback(async () => {
    try { if (!USE_MOCK_AUTH) await api('/auth/logout', { method: 'POST' }); } catch { /* on purge quand même */ }
    clear();
    await purgeClientCaches();
  }, [clear]);

  const value = useMemo(() => ({ user, notice, login, reauth, isStrong, logout }), [user, notice, login, reauth, isStrong, logout]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth doit être utilisé dans <AuthProvider>');
  return ctx;
}
