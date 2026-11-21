/** Runtime configuration, served as /config.json next to the app so one build runs in every environment. */
export interface RuntimeConfig {
  /** Admin API origin, e.g. https://cp.example.com; "" = same origin (dev proxy). */
  apiBase: string;
  oidc: { authority: string; clientId: string; scope: string };
}

function isRecord(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null && !Array.isArray(v);
}

function httpsUrl(v: unknown, name: string, allowEmpty: boolean): string {
  if (typeof v !== 'string') {
    throw new Error(`config.json: ${name} must be a string`);
  }
  if (v === '' && allowEmpty) {
    return v;
  }
  let u: URL;
  try {
    u = new URL(v);
  } catch {
    throw new Error(`config.json: ${name} is not a URL`);
  }
  if (u.protocol !== 'https:') {
    throw new Error(`config.json: ${name} must use https`);
  }
  return v.replace(/\/+$/, '');
}

export function parseConfig(raw: unknown): RuntimeConfig {
  if (!isRecord(raw) || !isRecord(raw.oidc)) {
    throw new Error('config.json: expected {apiBase, oidc}');
  }
  const { clientId, scope } = raw.oidc;
  if (typeof clientId !== 'string' || clientId === '') {
    throw new Error('config.json: oidc.clientId is required');
  }
  const s = typeof scope === 'string' && scope !== '' ? scope : 'openid';
  if (!s.split(' ').includes('openid')) {
    throw new Error('config.json: oidc.scope must include openid');
  }
  return {
    apiBase: httpsUrl(raw.apiBase, 'apiBase', true),
    oidc: { authority: httpsUrl(raw.oidc.authority, 'oidc.authority', false), clientId, scope: s },
  };
}

export async function loadConfig(): Promise<RuntimeConfig> {
  const r = await fetch('/config.json', { cache: 'no-store', credentials: 'omit' });
  if (!r.ok) {
    throw new Error(`config.json: HTTP ${r.status}`);
  }
  return parseConfig(await r.json());
}
