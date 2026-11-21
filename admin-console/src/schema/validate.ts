import { Validator, type OutputUnit } from '@cfworker/json-schema';
import type { Json } from '../api/types';
import type { Schema } from './model';

// Interpreting validator (no eval / new Function), so it works under the console's strict CSP.
const cache = new WeakMap<Schema, Validator>();

const COMBINATORS = new Set(['properties', 'items', 'prefixItems', 'allOf', 'anyOf', 'oneOf', '$ref', 'if', 'then',
  'else', 'additionalProperties', 'unevaluatedProperties', 'dependentSchemas', 'contains', 'propertyNames', 'not']);

/** "#/global/failMode" -> "global.failMode", "#/key/0" -> "key[0]" (Admin API notation). */
export function toPath(instanceLocation: string): string {
  const parts = instanceLocation.replace(/^#\/?/, '').split('/').filter((p) => p !== '')
    .map((p) => decodeURIComponent(p).replace(/~1/g, '/').replace(/~0/g, '~'));
  let out = '';
  for (const p of parts) {
    out = /^\d+$/.test(p) ? `${out}[${p}]` : out === '' ? p : `${out}.${p}`;
  }
  return out;
}

function message(u: OutputUnit): { path: string; text: string } {
  const path = toPath(u.instanceLocation);
  if (u.keyword === 'required') {
    const m = /"([^"]+)"/.exec(u.error);
    if (m?.[1] !== undefined) {
      return { path: path === '' ? m[1] : `${path}.${m[1]}`, text: 'required' };
    }
  }
  if (u.keyword === 'false') {
    return { path, text: 'unknown field' };
  }
  return { path, text: u.error };
}

/**
 * Client-side mirror of the server's schema check, mapped to field paths. The server stays
 * authoritative: its field errors are shown the same way.
 */
export function validate(schema: Schema, value: Json | undefined): Map<string, string[]> {
  let v = cache.get(schema);
  if (v === undefined) {
    v = new Validator(schema, '2020-12', false);
    cache.set(schema, v);
  }
  const result = v.validate(value === undefined ? {} : value);
  const out = new Map<string, string[]>();
  if (result.valid) {
    return out;
  }
  const specific = result.errors.filter((u) => !COMBINATORS.has(u.keyword));
  const units = specific.length > 0 ? specific : result.errors.slice(0, 1);
  for (const u of units) {
    const { path, text } = message(u);
    const list = out.get(path) ?? [];
    if (!list.includes(text)) {
      list.push(text);
    }
    out.set(path, list);
  }
  return out;
}
