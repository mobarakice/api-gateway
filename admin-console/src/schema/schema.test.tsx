import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { useState } from 'react';
import { describe, expect, it } from 'vitest';
import type { Json } from '../api/types';
import { shape, type Schema } from './model';
import { SchemaForm } from './SchemaForm';
import { toPath, validate } from './validate';

// The console renders the very schema files the gateway and control plane validate against.
const DIR = join(import.meta.dirname, '../../../config-model/src/main/resources/schemas/plugins');
const schemas: Record<string, Schema> = Object.fromEntries(readdirSync(DIR).filter((f) => f.endsWith('.json'))
  .map((f) => [f.replace(/\.json$/, ''), JSON.parse(readFileSync(join(DIR, f), 'utf8')) as Schema]));

function rateLimit(): Schema {
  const s = schemas['rate-limit'];
  if (!s) {
    throw new Error('rate-limit schema missing');
  }
  return s;
}

function Harness({ schema, errors }: { schema: Schema; errors?: Map<string, string[]> }) {
  const [v, setV] = useState<Json | undefined>(undefined);
  return (
    <>
      <SchemaForm schema={schema} value={v} errors={errors ?? validate(schema, v ?? {})} onChange={setV} />
      <output data-testid="value">{JSON.stringify(v ?? null)}</output>
    </>
  );
}

describe('plugin schemas', () => {
  it('every built-in plugin schema renders as a form (root is an object)', () => {
    expect(Object.keys(schemas).sort()).toEqual(['authz', 'cache', 'cors', 'headers', 'ip-restriction', 'jwt',
      'key-auth', 'rate-limit', 'request-size-limit', 'request-transform']);
    for (const [name, s] of Object.entries(schemas)) {
      expect(shape(s, s).kind, name).toBe('object');
      render(<SchemaForm schema={s} value={undefined} errors={new Map()} onChange={() => undefined} />);
    }
  });

  it('validates like the server and maps errors to field paths', () => {
    const s = rateLimit();
    expect(validate(s, { limit: 10 }).size).toBe(0);
    const e = validate(s, { limit: 0, algorithm: 'nope', bogus: 1, key: ['ip', 'header:'] });
    expect([...e.keys()].sort()).toEqual(['algorithm', 'bogus', 'key[1]', 'limit']);
    expect(e.get('bogus')).toEqual(['unknown field']);
    expect(validate(s, {}).get('limit')).toEqual(['required']);
    expect(toPath('#/global/failMode')).toBe('global.failMode');
    expect(toPath('#/key/0')).toBe('key[0]');
  });

  it('builds a config from typed fields, omitting what is left at defaults', async () => {
    const user = userEvent.setup();
    render(<Harness schema={rateLimit()} />);
    await user.type(screen.getByLabelText(/^limit/), '100');
    await user.selectOptions(screen.getByLabelText(/^algorithm/), '"sliding-window"');
    await user.type(screen.getByLabelText(/^key/), 'ip\nconsumer');
    expect(JSON.parse(screen.getByTestId('value').textContent)).toEqual({ limit: 100,
      algorithm: 'sliding-window', key: ['ip', 'consumer'] });
    await user.clear(screen.getByLabelText(/^limit/));
    expect(screen.getByLabelText(/^limit/)).toHaveAccessibleDescription(/required/);
  });

  it('shows server field errors inline on the named field', () => {
    render(<Harness schema={rateLimit()} errors={new Map([['windowSeconds', ['must be <= 86400']]])} />);
    const input = screen.getByLabelText(/^windowSeconds/);
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(input).toHaveAccessibleDescription(/must be <= 86400/);
  });
});
