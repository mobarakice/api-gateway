export const ROLES = ['viewer', 'editor', 'publisher', 'admin'] as const;
export type Role = (typeof ROLES)[number];

export function isRole(v: unknown): v is Role {
  return typeof v === 'string' && (ROLES as readonly string[]).includes(v);
}

/** Hierarchical, as enforced by the Admin API: viewer < editor < publisher < admin. */
export function can(have: Role | undefined, need: Role): boolean {
  return have !== undefined && ROLES.indexOf(have) >= ROLES.indexOf(need);
}
