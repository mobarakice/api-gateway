import type { Json, JsonObject } from '../api/types';

/** A JSON Schema node as plain data (plugin schemas are 2020-12). */
export type Schema = JsonObject;

export function isObject(v: Json | undefined): v is JsonObject {
  return typeof v === 'object' && v !== null && !Array.isArray(v);
}

function str(v: Json | undefined): string | undefined {
  return typeof v === 'string' ? v : undefined;
}

/** Follows local `$ref`s ("#/$defs/name") and merges `allOf` branches that only add properties. */
export function resolve(node: Schema, root: Schema, depth = 0): Schema {
  if (depth > 16) {
    return node;
  }
  let out: Schema = node;
  const ref = str(node.$ref);
  if (ref?.startsWith('#/')) {
    let target: Json | undefined = root;
    for (const part of ref.slice(2).split('/')) {
      target = isObject(target) ? target[part.replace(/~1/g, '/').replace(/~0/g, '~')] : undefined;
    }
    if (isObject(target)) {
      out = { ...resolve(target, root, depth + 1), ...without(node, '$ref') };
    }
  }
  return out;
}

export type Shape =
  | { kind: 'enum'; options: Json[] }
  | { kind: 'string' }
  | { kind: 'number'; integer: boolean }
  | { kind: 'boolean' }
  | { kind: 'object'; properties: [string, Schema][]; required: Set<string> }
  | { kind: 'stringMap' }
  | { kind: 'list'; item: 'string' | 'number' | 'integer' }
  | { kind: 'array'; item: Schema }
  | { kind: 'json' };

function primaryType(s: Schema): string | undefined {
  const t = s.type;
  if (typeof t === 'string') {
    return t;
  }
  if (Array.isArray(t)) {
    return t.find((x): x is string => typeof x === 'string' && x !== 'null');
  }
  if (isObject(s.properties)) {
    return 'object';
  }
  return undefined;
}

/** Decides how a node is edited. Anything a form can't express faithfully falls back to JSON. */
export function shape(raw: Schema, root: Schema): Shape {
  const s = resolve(raw, root);
  if (Array.isArray(s.enum)) {
    return { kind: 'enum', options: s.enum };
  }
  if (s.const !== undefined) {
    return { kind: 'enum', options: [s.const] };
  }
  const branches = Array.isArray(s.oneOf) ? s.oneOf : Array.isArray(s.anyOf) ? s.anyOf : undefined;
  if (branches && primaryType(s) === undefined) {
    const consts: Json[] = [];
    for (const b of branches) {
      const c: Json | undefined = isObject(b) ? resolve(b, root).const : undefined;
      if (c !== undefined) {
        consts.push(c);
      }
    }
    if (consts.length === branches.length) {
      return { kind: 'enum', options: consts };
    }
    return { kind: 'json' };
  }
  switch (primaryType(s)) {
    case 'string':
      return { kind: 'string' };
    case 'integer':
      return { kind: 'number', integer: true };
    case 'number':
      return { kind: 'number', integer: false };
    case 'boolean':
      return { kind: 'boolean' };
    case 'object': {
      const props = isObject(s.properties) ? Object.entries(s.properties).filter(
        (e): e is [string, Schema] => isObject(e[1])) : [];
      const extra = s.additionalProperties;
      if (props.length === 0 && isObject(extra) && primaryType(resolve(extra, root)) === 'string') {
        return { kind: 'stringMap' };
      }
      if (props.length === 0 || (isObject(extra) && Object.keys(extra).length > 0)
        || isObject(s.patternProperties)) {
        return { kind: 'json' };
      }
      const req = Array.isArray(s.required) ? s.required.filter((r): r is string => typeof r === 'string') : [];
      return { kind: 'object', properties: props, required: new Set(req) };
    }
    case 'array': {
      if (!isObject(s.items)) {
        return { kind: 'json' };
      }
      const item = resolve(s.items, root);
      const it = primaryType(item);
      if ((it === 'string' || it === 'number' || it === 'integer') && !Array.isArray(item.enum)) {
        return { kind: 'list', item: it };
      }
      if (Array.isArray(item.enum) && item.enum.every((x) => typeof x === 'string')) {
        return { kind: 'list', item: 'string' };
      }
      if (it === 'object') {
        return { kind: 'array', item };
      }
      return { kind: 'json' };
    }
    default:
      return { kind: 'json' };
  }
}

/** A copy of `o` without `key`. */
export function without(o: JsonObject, key: string): JsonObject {
  return Object.fromEntries(Object.entries(o).filter(([k]) => k !== key));
}

/** A copy of `o` with `key` set, or removed when `v` is undefined. */
export function withKey(o: JsonObject, key: string, v: Json | undefined): JsonObject {
  return v === undefined ? without(o, key) : { ...o, [key]: v };
}

/** Text for display: strings as-is, everything else as JSON. */
export function text(v: Json | undefined): string {
  return v === undefined || v === null ? '' : typeof v === 'string' ? v : JSON.stringify(v);
}

export function title(s: Schema, fallback: string): string {
  return str(s.title) ?? fallback;
}

export function description(s: Schema): string | undefined {
  return str(s.description);
}

/** "global.failMode", "key[0]": the same notation the Admin API uses for field errors. */
export function childPath(parent: string, child: string | number): string {
  if (typeof child === 'number') {
    return `${parent}[${child}]`;
  }
  return parent === '' ? child : `${parent}.${child}`;
}

/** Drops empty strings, lists and objects so server defaults apply. */
export function prune(v: Json | undefined): Json | undefined {
  if (v === undefined || v === '') {
    return undefined;
  }
  if (Array.isArray(v)) {
    return v.length === 0 ? undefined : v;
  }
  if (isObject(v)) {
    const out: JsonObject = {};
    for (const [k, x] of Object.entries(v)) {
      const p = prune(x);
      if (p !== undefined) {
        out[k] = p;
      }
    }
    return Object.keys(out).length === 0 ? undefined : out;
  }
  return v;
}
