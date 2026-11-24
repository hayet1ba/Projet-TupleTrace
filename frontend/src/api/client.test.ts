import { afterEach, describe, expect, it, vi } from 'vitest';
import { api, ApiError, kindFromStatus, setUnauthenticatedHandler } from './client';

const respond = (status: number, body: unknown = {}) =>
  vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify(body), { status }));

afterEach(() => vi.restoreAllMocks());

describe('client API', () => {
  it('associe chaque statut HTTP à une catégorie', () => {
    expect(kindFromStatus(401)).toBe('unauthenticated');
    expect(kindFromStatus(403)).toBe('forbidden');
    expect(kindFromStatus(422)).toBe('invalid');
    expect(kindFromStatus(429)).toBe('rate_limited');
    expect(kindFromStatus(500)).toBe('server');
  });

  it('déclenche la déconnexion sur 401', async () => {
    const handler = vi.fn();
    setUnauthenticatedHandler(handler);
    respond(401);
    await expect(api('/datasets')).rejects.toBeInstanceOf(ApiError);
    expect(handler).toHaveBeenCalledOnce();
  });

  it("n'expose pas le détail interne d'une erreur 500", async () => {
    respond(500, { detail: 'java.lang.NullPointerException at ...' });
    await expect(api('/datasets')).rejects.toMatchObject({ kind: 'server' });
  });

  it('désactive le cache navigateur', async () => {
    const spy = respond(200, []);
    await api('/datasets');
    expect(spy.mock.calls[0][1]).toMatchObject({ cache: 'no-store' });
  });
});
