export interface TokenClaims { sub: string; org?: string; exp: number; authTime?: number }

const toBytes = (s: string) => {
  const p = s.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - (s.length % 4)) % 4);
  return Uint8Array.from(atob(p), (c) => c.charCodeAt(0));
};

/**
 * Lit les claims d'un PASETO v4.public (payload en clair suivi de 64 octets de signature).
 * ATTENTION : aucune vérification de signature ici, c'est le serveur qui décide.
 * Ces valeurs ne servent qu'à l'affichage (expiration, ancienneté de la preuve TOTP).
 */
export function decodeClaims(token: string): TokenClaims | null {
  try {
    const [v, purpose, body] = token.split('.');
    if (v !== 'v4' || purpose !== 'public' || !body) return null;
    const bytes = toBytes(body);
    if (bytes.length <= 64) return null;
    const c = JSON.parse(new TextDecoder().decode(bytes.slice(0, bytes.length - 64)));
    const exp = Date.parse(c.exp);
    if (typeof c.sub !== 'string' || Number.isNaN(exp)) return null;
    const at = typeof c.auth_time === 'string' ? Date.parse(c.auth_time) : NaN;
    return { sub: c.sub, org: c.org, exp, authTime: Number.isNaN(at) ? undefined : at };
  } catch {
    return null;
  }
}
