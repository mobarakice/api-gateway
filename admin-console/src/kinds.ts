import type { Json, StoredObject } from './api/types';
import type { Role } from './roles';

export type FieldDef =
  | { key: string; label: string; type: 'text' | 'lines' | 'map'; required?: boolean; hint?: string;
    placeholder?: string }
  | { key: string; label: string; type: 'number'; required?: boolean; hint?: string; integer?: boolean }
  | { key: string; label: string; type: 'bool'; hint?: string; defaultValue: boolean }
  | { key: string; label: string; type: 'select'; options: string[]; required?: boolean; hint?: string;
    defaultLabel?: string }
  | { key: string; label: string; type: 'ref'; collection: string; required?: boolean; hint?: string }
  | { key: string; label: string; type: 'targets' }
  | { key: string; label: string; type: 'plugins'; hint?: string }
  | { key: string; label: string; type: 'pluginName'; required: true }
  /** The config of the plugin named by field `pluginField`, or by the object's id when it is undefined. */
  | { key: string; label: string; type: 'pluginConfig'; pluginField?: string };

export interface KindDef {
  /** URL segment in the console. */
  slug: string;
  /** Admin API collection path. */
  collection: string;
  label: string;
  singular: string;
  writeRole: Role;
  tenant: boolean;
  /** The id is the plugin name (global plugins). */
  idIsPlugin?: boolean;
  fields: FieldDef[];
  columns: { label: string; value: (o: StoredObject) => string }[];
  intro: string;
}

function s(v: Json | undefined): string {
  if (v === undefined || v === null) {
    return '';
  }
  if (Array.isArray(v)) {
    return v.map((x) => s(x)).join(', ');
  }
  return typeof v === 'object' ? JSON.stringify(v) : String(v);
}

function pluginNames(v: Json | undefined): string {
  return Array.isArray(v) ? v.map((p) => (typeof p === 'object' && p !== null && !Array.isArray(p) ? s(p.name) : ''))
    .filter(Boolean).join(', ') : '';
}

export const KINDS: KindDef[] = [
  {
    slug: 'routes', collection: 'routes', label: 'Routes', singular: 'route', writeRole: 'editor', tenant: true,
    intro: 'Match requests by host, method and path, and send them to a service.',
    fields: [
      { key: 'path', label: 'Path', type: 'text', required: true, placeholder: '/users/{id}' },
      { key: 'match', label: 'Path match', type: 'select', options: ['prefix', 'exact', 'template'],
        defaultLabel: '(default: prefix)' },
      { key: 'serviceId', label: 'Service', type: 'ref', collection: 'services', required: true },
      { key: 'methods', label: 'Methods', type: 'lines', hint: 'One per line; empty = all methods.' },
      { key: 'hosts', label: 'Hosts', type: 'lines', hint: 'One per line; empty = any host. *.example.com allowed.' },
      { key: 'priority', label: 'Priority', type: 'number', integer: true, hint: 'Higher wins among equal matches.' },
      { key: 'headers', label: 'Header matches', type: 'map' },
      { key: 'query', label: 'Query matches', type: 'map' },
      { key: 'preserveHost', label: 'Preserve Host header', type: 'bool', defaultValue: false },
      { key: 'plugins', label: 'Plugins', type: 'plugins' },
    ],
    columns: [
      { label: 'Path', value: (o) => s(o.spec.path) },
      { label: 'Methods', value: (o) => s(o.spec.methods) || 'all' },
      { label: 'Service', value: (o) => s(o.spec.serviceId) },
      { label: 'Plugins', value: (o) => pluginNames(o.spec.plugins) },
    ],
  },
  {
    slug: 'services', collection: 'services', label: 'Services', singular: 'service', writeRole: 'editor', tenant: true,
    intro: 'A service points at an upstream and carries shared timeouts, retries and plugins.',
    fields: [
      { key: 'upstreamId', label: 'Upstream', type: 'ref', collection: 'upstreams', required: true },
      { key: 'plugins', label: 'Plugins', type: 'plugins' },
    ],
    columns: [
      { label: 'Upstream', value: (o) => s(o.spec.upstreamId) },
      { label: 'Plugins', value: (o) => pluginNames(o.spec.plugins) },
    ],
  },
  {
    slug: 'upstreams', collection: 'upstreams', label: 'Upstreams', singular: 'upstream', writeRole: 'editor',
    tenant: true, intro: 'Target hosts, load balancing, health checks and circuit breaking.',
    fields: [
      { key: 'targets', label: 'Targets', type: 'targets' },
      { key: 'scheme', label: 'Scheme', type: 'select', options: ['http', 'https'], defaultLabel: '(default: http)' },
      { key: 'balancer', label: 'Balancer', type: 'select',
        options: ['round-robin', 'weighted-round-robin', 'least-requests', 'consistent-hash'],
        defaultLabel: '(default: round-robin)' },
      { key: 'hashKey', label: 'Hash key', type: 'text', hint: 'consistent-hash only: ip or header:<name>.' },
      { key: 'http2', label: 'HTTP/2 to targets', type: 'bool', defaultValue: false },
    ],
    columns: [
      { label: 'Targets', value: (o) => (Array.isArray(o.spec.targets) ? o.spec.targets.map((t) =>
        (typeof t === 'object' && t !== null && !Array.isArray(t) ? `${s(t.host)}:${s(t.port)}` : '')).join(', ') : '') },
      { label: 'Balancer', value: (o) => s(o.spec.balancer) || 'round-robin' },
    ],
  },
  {
    slug: 'consumers', collection: 'consumers', label: 'Consumers', singular: 'consumer', writeRole: 'editor',
    tenant: true, intro: 'API clients: identity, roles and attributes for authorization, and their credentials.',
    fields: [
      { key: 'username', label: 'Username', type: 'text' },
      { key: 'roles', label: 'Roles', type: 'lines' },
      { key: 'attributes', label: 'Attributes', type: 'map' },
      { key: 'plugins', label: 'Plugin overrides', type: 'plugins',
        hint: 'Overrides apply only to plugins already in the route chain.' },
    ],
    columns: [
      { label: 'Username', value: (o) => s(o.spec.username) },
      { label: 'Roles', value: (o) => s(o.spec.roles) },
    ],
  },
  {
    slug: 'plugin-configs', collection: 'plugin-configs', label: 'Plugin configs', singular: 'plugin config',
    writeRole: 'editor', tenant: true, intro: 'Shared plugin configurations, referenced from routes by configRef.',
    fields: [
      { key: 'plugin', label: 'Plugin', type: 'pluginName', required: true },
      { key: 'config', label: 'Configuration', type: 'pluginConfig', pluginField: 'plugin' },
    ],
    columns: [{ label: 'Plugin', value: (o) => s(o.spec.plugin) }],
  },
  {
    slug: 'global-plugins', collection: 'global-plugins', label: 'Global plugins', singular: 'global plugin',
    writeRole: 'editor', tenant: false, idIsPlugin: true, intro: 'Plugins that run on every route.',
    fields: [
      { key: 'enabled', label: 'Enabled', type: 'bool', defaultValue: true },
      { key: 'config', label: 'Configuration', type: 'pluginConfig' },
    ],
    columns: [{ label: 'Enabled', value: (o) => (o.spec.enabled === false ? 'no' : 'yes') }],
  },
  {
    slug: 'authz-policies', collection: 'policies/authz', label: 'Authorization policies', singular: 'policy',
    writeRole: 'editor', tenant: true, intro: 'Allow/deny rules evaluated by the authz plugin (ADR 0016).',
    fields: [
      { key: 'effect', label: 'Effect', type: 'select', options: ['allow', 'deny'], required: true },
      { key: 'routes', label: 'Routes', type: 'lines', hint: 'Route IDs, one per line; empty = all.' },
      { key: 'methods', label: 'Methods', type: 'lines' },
    ],
    columns: [
      { label: 'Effect', value: (o) => s(o.spec.effect) },
      { label: 'Routes', value: (o) => s(o.spec.routes) || 'all' },
    ],
  },
  {
    slug: 'revoked-tokens', collection: 'policies/revoked-tokens', label: 'Revoked tokens', singular: 'revoked token',
    writeRole: 'editor', tenant: false, intro: 'JWT IDs that gateways reject until they expire.',
    fields: [
      { key: 'jti', label: 'Token ID (jti)', type: 'text', required: true },
      { key: 'issuer', label: 'Issuer', type: 'text' },
      { key: 'expiresAt', label: 'Expires at', type: 'text', required: true, placeholder: '2026-12-31T00:00:00Z' },
    ],
    columns: [{ label: 'jti', value: (o) => s(o.spec.jti) }, { label: 'Expires', value: (o) => s(o.spec.expiresAt) }],
  },
  {
    slug: 'cache-purges', collection: 'policies/cache-purge', label: 'Cache purges', singular: 'cache purge',
    writeRole: 'editor', tenant: false, intro: 'Invalidate cached responses by route or tag (ADR 0019).',
    fields: [
      { key: 'routeId', label: 'Route', type: 'ref', collection: 'routes' },
      { key: 'tag', label: 'Tag', type: 'text', hint: 'Set either a route or a tag.' },
      { key: 'issuedAt', label: 'Issued at', type: 'text', required: true, placeholder: 'now, e.g. 2026-11-21T10:00:00Z' },
    ],
    columns: [{ label: 'Target', value: (o) => s(o.spec.routeId ?? o.spec.tag) },
      { label: 'Issued', value: (o) => s(o.spec.issuedAt) }],
  },
  {
    slug: 'certificates', collection: 'certificates', label: 'Certificates', singular: 'certificate',
    writeRole: 'admin', tenant: false,
    intro: 'References to key material held outside etcd. Private keys are never stored or accepted here.',
    fields: [
      { key: 'certRef', label: 'Certificate reference', type: 'text', required: true },
      { key: 'keyRef', label: 'Private key reference', type: 'text', required: true,
        hint: 'A reference (e.g. a secret name), never the key itself.' },
      { key: 'sni', label: 'SNI host names', type: 'lines' },
    ],
    columns: [{ label: 'Certificate', value: (o) => s(o.spec.certRef) }, { label: 'SNI', value: (o) => s(o.spec.sni) }],
  },
];

export function kindBySlug(slug: string | undefined): KindDef | undefined {
  return KINDS.find((k) => k.slug === slug);
}

export function kindByCollection(collection: string): KindDef | undefined {
  return KINDS.find((k) => k.collection === collection);
}

/** "routes/a" -> console link; credentials link to their consumer. */
export function linkForKey(key: string): string | undefined {
  if (key === 'meta/settings') {
    return '/settings';
  }
  const cred = /^credentials\/([^/]+)\/[^/]+$/.exec(key);
  if (cred?.[1] !== undefined) {
    return `/objects/consumers/${encodeURIComponent(cred[1])}`;
  }
  for (const k of KINDS) {
    if (key.startsWith(`${k.collection}/`)) {
      return `/objects/${k.slug}/${encodeURIComponent(key.slice(k.collection.length + 1))}`;
    }
  }
  return undefined;
}
