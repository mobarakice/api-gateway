import { describe, expect, it } from 'vitest';
import { can, isRole } from './roles';

describe('roles', () => {
  it('are hierarchical like the Admin API', () => {
    expect(can('viewer', 'viewer')).toBe(true);
    expect(can('viewer', 'editor')).toBe(false);
    expect(can('publisher', 'editor')).toBe(true);
    expect(can('publisher', 'admin')).toBe(false);
    expect(can('admin', 'publisher')).toBe(true);
    expect(can(undefined, 'viewer')).toBe(false);
    expect(isRole('root')).toBe(false);
  });
});
