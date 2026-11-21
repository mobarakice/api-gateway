import type { KindDef } from '../kinds';
import { ObjectEditor } from './ObjectPage';

const SETTINGS: KindDef = {
  slug: 'settings', collection: 'settings', label: 'Settings', singular: 'settings', writeRole: 'admin', tenant: false,
  intro: 'Global gateway settings.',
  fields: [
    { key: 'upstreamAllowCidrs', label: 'Upstream allow CIDRs', type: 'lines',
      hint: 'Private/loopback ranges upstream targets may use (SSRF exemptions). Never 0.0.0.0/0.' },
    { key: 'trustedProxies', label: 'Trusted proxies', type: 'lines', hint: 'CIDRs whose X-Forwarded-For is honoured.' },
    { key: 'viaName', label: 'Via name', type: 'text' },
    { key: 'maxRequestBodyBytes', label: 'Max request body (bytes)', type: 'number', integer: true },
  ],
  columns: [],
};

export function SettingsPage() {
  return (
    <section aria-labelledby="page-title">
      <h1 id="page-title">Settings</h1>
      <p className="muted">Global gateway settings (admin only). Like every change, they are staged and published.</p>
      <ObjectEditor kind={SETTINGS} apiPath="settings" key_="meta/settings" id="settings" isNew={false} allowMissing />
    </section>
  );
}
