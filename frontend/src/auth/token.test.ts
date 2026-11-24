import { describe, expect, it } from 'vitest';
import { decodeClaims } from './token';

function fakeToken(claims: object) {
  const body = new TextEncoder().encode(JSON.stringify(claims));
  const all = new Uint8Array(body.length + 64); all.set(body);
  let bin = ''; all.forEach((b) => { bin += String.fromCharCode(b); });
  return `v4.public.${btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')}.eyJraWQiOiJ4In0`;
}

describe('claims PASETO', () => {
  it('lit sub, exp et auth_time', () => {
    const c = decodeClaims(fakeToken({ sub: 'u1', org: 'o1', exp: '2026-11-22T10:15:00Z', auth_time: '2026-11-22T10:00:00Z' }));
    expect(c).toMatchObject({ sub: 'u1', org: 'o1', exp: Date.parse('2026-11-22T10:15:00Z') });
    expect(c?.authTime).toBe(Date.parse('2026-11-22T10:00:00Z'));
  });
  it('rejette un jeton mal formé', () => {
    expect(decodeClaims('v2.local.abc')).toBeNull();
    expect(decodeClaims('v4.public.AAAA')).toBeNull();
    expect(decodeClaims('n’importe quoi')).toBeNull();
  });
});
