import { describe, expect, it } from 'vitest';
import { specErrors, under } from './errors';

describe('specErrors', () => {
  it('maps Admin API paths to spec-relative paths and drops other objects', () => {
    const m = specErrors([
      { path: 'routes/a.spec.path', message: 'required' },
      { path: 'routes/a.spec.plugins[0].config.limit', message: 'must be >= 1' },
      { path: 'tenantId', message: 'bad tenant' },
      { path: 'routes/b.spec.path', message: 'other object' },
      { path: 'routes/a', message: 'whole object' },
    ], 'routes/a');
    expect(Object.fromEntries(m)).toEqual({ path: ['required'], 'plugins[0].config.limit': ['must be >= 1'],
      tenantId: ['bad tenant'], '': ['whole object'] });
    expect(Object.fromEntries(under(m, 'plugins[0]'))).toEqual({ 'config.limit': ['must be >= 1'] });
    expect(Object.fromEntries(under(under(m, 'plugins[0]'), 'config'))).toEqual({ limit: ['must be >= 1'] });
  });
});
