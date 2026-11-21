import type { FieldError, Problem } from './types';

/**
 * Server field errors name the etcd key and the field, e.g. "routes/a.spec.plugins[0].config.limit".
 * Returns paths relative to the object's spec ("plugins[0].config.limit"); envelope-level paths keep
 * their name ("tenantId"). Errors for other keys are dropped (they belong to another object).
 */
export function specErrors(errors: FieldError[] | undefined, key: string | undefined): Map<string, string[]> {
  const out = new Map<string, string[]>();
  for (const e of errors ?? []) {
    let p = e.path;
    if (key !== undefined && (p === key || p.startsWith(`${key}.`))) {
      p = p === key ? '' : p.slice(key.length + 1);
    } else if (/^[a-z-]+(\/[a-z-]+)?\/[A-Za-z0-9._-]+\./.test(p)) {
      continue;
    }
    if (p === 'spec') {
      p = '';
    } else if (p.startsWith('spec.')) {
      p = p.slice(5);
    }
    const list = out.get(p) ?? [];
    list.push(e.message);
    out.set(p, list);
  }
  return out;
}

/** Errors at `path` exactly. */
export function at(errors: Map<string, string[]>, path: string): string[] {
  return errors.get(path) ?? [];
}

/** Errors under `prefix` (relative to it), for nested editors such as one plugin's config. */
export function under(errors: Map<string, string[]>, prefix: string): Map<string, string[]> {
  const out = new Map<string, string[]>();
  for (const [p, msgs] of errors) {
    if (p === prefix) {
      out.set('', msgs);
    } else if (p.startsWith(`${prefix}.`)) {
      out.set(p.slice(prefix.length + 1), msgs);
    } else if (p.startsWith(`${prefix}[`)) {
      out.set(p.slice(prefix.length), msgs);
    }
  }
  return out;
}

export function describe(p: Problem): string {
  switch (p.status) {
    case 0:
      return p.detail ?? p.title;
    case 401:
      return 'Your session has expired. Sign in again.';
    case 403:
      return 'Your role does not allow this.';
    case 412:
      return 'Someone else changed this object since you loaded it. Reload it and apply your edit again.';
    case 428:
      return 'This change needs the version you edited (If-Match). Reload the object and try again.';
    default:
      return p.detail ?? p.title;
  }
}
