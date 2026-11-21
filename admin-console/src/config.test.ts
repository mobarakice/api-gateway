import { describe, expect, it } from 'vitest';
import { parseConfig } from './config';

const ok = { apiBase: 'https://cp.example.test/', oidc: { authority: 'https://idp.example.test/realms/x', clientId: 'c' } };

describe('parseConfig', () => {
  it('accepts https endpoints and defaults the scope', () => {
    expect(parseConfig(ok)).toEqual({ apiBase: 'https://cp.example.test',
      oidc: { authority: 'https://idp.example.test/realms/x', clientId: 'c', scope: 'openid' } });
    expect(parseConfig({ ...ok, apiBase: '' }).apiBase).toBe('');
  });

  it('refuses plaintext or malformed endpoints and missing fields', () => {
    expect(() => parseConfig({ ...ok, apiBase: 'http://cp.example.test' })).toThrow(/https/);
    expect(() => parseConfig({ ...ok, oidc: { ...ok.oidc, authority: 'http://idp' } })).toThrow(/https/);
    expect(() => parseConfig({ ...ok, oidc: { authority: ok.oidc.authority } })).toThrow(/clientId/);
    expect(() => parseConfig({ ...ok, oidc: { ...ok.oidc, scope: 'profile' } })).toThrow(/openid/);
    expect(() => parseConfig('nope')).toThrow();
  });
});
