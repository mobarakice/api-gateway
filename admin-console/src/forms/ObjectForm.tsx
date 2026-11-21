import { useId, useMemo } from 'react';
import { under } from '../api/errors';
import { useChangeSet, useList, usePlugins, usePluginSchema } from '../api/hooks';
import type { Json, JsonObject } from '../api/types';
import { useActiveChangeSet } from '../components/ActiveChangeSet';
import { Field, JsonInput, LinesInput, MapInput, NumberInput, Select, TextInput, TriBool } from '../components/fields';
import type { FieldDef, KindDef } from '../kinds';
import { isObject, text, withKey } from '../schema/model';
import { SchemaForm } from '../schema/SchemaForm';
import { validate } from '../schema/validate';

type Errors = Map<string, string[]>;

function obj(v: Json | undefined): JsonObject {
  return isObject(v) ? v : {};
}

function strings(v: Json | undefined): string[] | undefined {
  return Array.isArray(v) ? v.map(text) : undefined;
}

function stringMap(v: Json | undefined): Record<string, string> | undefined {
  return isObject(v) ? Object.fromEntries(Object.entries(v).map(([k, x]) => [k, text(x)])) : undefined;
}

/** IDs offered for a reference: live objects plus ones staged in the active change set. */
function RefInput(p: { def: Extract<FieldDef, { type: 'ref' }>; value: string | undefined; errors: string[] | undefined;
  onChange: (v: string | undefined) => void; disabled: boolean }) {
  const live = useList(p.def.collection, undefined, 500);
  const active = useActiveChangeSet();
  const cs = useChangeSet(active.id);
  const listId = useId();
  const ids = useMemo(() => {
    const out = new Set<string>();
    for (const o of live.data?.items ?? []) {
      out.add(o.id);
    }
    for (const op of cs.data?.items ?? []) {
      if (op.action === 'PUT' && op.key.startsWith(`${p.def.collection}/`)) {
        out.add(op.key.slice(p.def.collection.length + 1));
      }
    }
    return [...out].sort();
  }, [live.data, cs.data, p.def.collection]);
  return (
    <Field label={p.def.label} required={p.def.required} hint={p.def.hint ?? `ID of a ${p.def.collection.replace(/s$/, '')}.`}
      errors={p.errors}>
      {(id, d, invalid) => (
        <>
          <input id={id} list={listId} value={p.value ?? ''} aria-describedby={d} aria-invalid={invalid || undefined}
            required={p.def.required} disabled={p.disabled} autoComplete="off" spellCheck={false}
            onChange={(e) => { p.onChange(e.target.value === '' ? undefined : e.target.value); }} />
          <datalist id={listId}>{ids.map((x) => <option key={x} value={x} />)}</datalist>
        </>
      )}
    </Field>
  );
}

function TargetsEditor(p: { value: Json | undefined; errors: Errors; onChange: (v: Json | undefined) => void;
  disabled: boolean }) {
  const rows = Array.isArray(p.value) ? p.value.map(obj) : [];
  const set = (next: JsonObject[]) => {
    p.onChange(next.length === 0 ? undefined : next);
  };
  const edit = (i: number, k: string, v: Json | undefined) => {
    set(rows.map((r, j) => {
      if (j !== i) {
        return r;
      }
      return withKey(r, k, v);
    }));
  };
  return (
    <fieldset className="nested">
      <legend>Targets *</legend>
      {p.errors.get('targets') && <ul className="field-errors">{p.errors.get('targets')?.map((e) => <li key={e}>{e}</li>)}</ul>}
      {rows.map((r, i) => (
        <div className="row-fields" key={i}>
          <TextInput label={`Target ${i + 1} host`} required value={typeof r.host === 'string' ? r.host : undefined}
            errors={p.errors.get(`targets[${i}].host`)} disabled={p.disabled}
            onChange={(v) => { edit(i, 'host', v); }} />
          <NumberInput label={`Target ${i + 1} port`} required integer min={1} max={65535}
            value={typeof r.port === 'number' ? r.port : undefined} errors={p.errors.get(`targets[${i}].port`)}
            disabled={p.disabled} onChange={(v) => { edit(i, 'port', v); }} />
          <NumberInput label={`Target ${i + 1} weight`} integer min={1} placeholder="1"
            value={typeof r.weight === 'number' ? r.weight : undefined} errors={p.errors.get(`targets[${i}].weight`)}
            disabled={p.disabled} onChange={(v) => { edit(i, 'weight', v); }} />
          <button type="button" className="btn btn-small" disabled={p.disabled}
            onClick={() => { set(rows.filter((_r, j) => j !== i)); }}>Remove target {i + 1}</button>
        </div>
      ))}
      <button type="button" className="btn btn-small" disabled={p.disabled}
        onClick={() => { set([...rows, {}]); }}>Add target</button>
    </fieldset>
  );
}

/** A plugin's config, rendered from its schema, with client errors merged over the server's. */
export function PluginConfig(p: { plugin: string | undefined; value: Json | undefined; errors: Errors;
  onChange: (v: Json | undefined) => void; disabled: boolean; label: string }) {
  const schema = usePluginSchema(p.plugin);
  const merged = useMemo(() => {
    const m = new Map(p.errors);
    if (schema.data && p.value !== undefined) {
      for (const [k, v] of validate(schema.data, p.value)) {
        m.set(k, [...new Set([...(m.get(k) ?? []), ...v])]);
      }
    }
    return m;
  }, [schema.data, p.value, p.errors]);
  if (p.plugin === undefined) {
    return <p className="hint">Choose a plugin to configure it.</p>;
  }
  if (schema.isPending) {
    return <p role="status">Loading the {p.plugin} schema…</p>;
  }
  if (schema.isError) {
    return <p className="alert alert-error" role="alert">Could not load the {p.plugin} schema.</p>;
  }
  return (
    <fieldset className="nested plugin-config">
      <legend>{p.label}</legend>
      <SchemaForm schema={schema.data} value={p.value} errors={merged} onChange={p.onChange} disabled={p.disabled}
        label={p.label} />
    </fieldset>
  );
}

function PluginSelect(p: { label: string; value: string | undefined; onChange: (v: string | undefined) => void;
  errors: string[] | undefined; disabled: boolean; required?: boolean }) {
  const plugins = usePlugins();
  return (
    <Select label={p.label} required={p.required ?? true} value={p.value} errors={p.errors} disabled={p.disabled}
      emptyLabel="(choose a plugin)" onChange={p.onChange}
      options={(plugins.data ?? []).map((x) => ({ value: x.name, label: `${x.name} — ${x.title}` }))} />
  );
}

function PluginsEditor(p: { label: string; hint?: string | undefined; value: Json | undefined; errors: Errors;
  onChange: (v: Json | undefined) => void; disabled: boolean }) {
  const refs = Array.isArray(p.value) ? p.value.map(obj) : [];
  const configs = useList('plugin-configs', undefined, 500);
  const set = (next: JsonObject[]) => {
    p.onChange(next.length === 0 ? undefined : next);
  };
  const edit = (i: number, k: string, v: Json | undefined) => {
    set(refs.map((r, j) => {
      if (j !== i) {
        return r;
      }
      return withKey(r, k, v);
    }));
  };
  const move = (i: number, d: -1 | 1) => {
    const next = [...refs];
    const [x] = next.splice(i, 1);
    if (x !== undefined) {
      next.splice(i + d, 0, x);
      set(next);
    }
  };
  return (
    <fieldset className="nested">
      <legend>{p.label}</legend>
      {p.hint && <p className="hint">{p.hint}</p>}
      {p.errors.get('plugins') && <ul className="field-errors">{p.errors.get('plugins')?.map((e) => <li key={e}>{e}</li>)}</ul>}
      {refs.map((r, i) => {
        const name = typeof r.name === 'string' ? r.name : undefined;
        const n = `Plugin ${i + 1}`;
        const shared = (configs.data?.items ?? []).filter((c) => c.spec.plugin === name);
        const errs = under(p.errors, `plugins[${i}]`);
        return (
          <div className="plugin-ref" key={i}>
            <h4>{n}{name ? `: ${name}` : ''}</h4>
            <PluginSelect label={`${n} name`} value={name} errors={errs.get('name')} disabled={p.disabled}
              onChange={(v) => { edit(i, 'name', v); }} />
            <TriBool label={`${n} enabled`} defaultValue value={typeof r.enabled === 'boolean' ? r.enabled : undefined}
              errors={errs.get('enabled')} disabled={p.disabled} onChange={(v) => { edit(i, 'enabled', v); }} />
            <Select label={`${n} shared config`} value={typeof r.configRef === 'string' ? r.configRef : undefined}
              emptyLabel="(none: inline config below)" errors={errs.get('configRef')} disabled={p.disabled}
              options={shared.map((c) => ({ value: c.id, label: c.id }))}
              onChange={(v) => { edit(i, 'configRef', v); }} />
            {r.configRef === undefined && (
              <PluginConfig plugin={name} value={r.config} errors={under(errs, 'config')} disabled={p.disabled}
                label={`${n} configuration`} onChange={(v) => { edit(i, 'config', v); }} />
            )}
            <div className="actions">
              <button type="button" className="btn btn-small" disabled={p.disabled || i === 0}
                onClick={() => { move(i, -1); }}>Move {n} up</button>
              <button type="button" className="btn btn-small" disabled={p.disabled || i === refs.length - 1}
                onClick={() => { move(i, 1); }}>Move {n} down</button>
              <button type="button" className="btn btn-small" disabled={p.disabled}
                onClick={() => { set(refs.filter((_r, j) => j !== i)); }}>Remove {n}</button>
            </div>
          </div>
        );
      })}
      <button type="button" className="btn btn-small" disabled={p.disabled}
        onClick={() => { set([...refs, {}]); }}>Add plugin</button>
    </fieldset>
  );
}

function FieldEditor(p: { def: FieldDef; spec: JsonObject; id: string | undefined; errors: Errors;
  set: (k: string, v: Json | undefined) => void; disabled: boolean }) {
  const { def, spec } = p;
  const v = spec[def.key];
  const errs = p.errors.get(def.key);
  const on = (x: Json | undefined) => {
    p.set(def.key, x);
  };
  switch (def.type) {
    case 'text':
      return <TextInput label={def.label} required={def.required} hint={def.hint} placeholder={def.placeholder}
        value={typeof v === 'string' ? v : undefined} errors={errs} disabled={p.disabled} onChange={on} />;
    case 'number':
      return <NumberInput label={def.label} required={def.required} hint={def.hint} integer={def.integer ?? false}
        value={typeof v === 'number' ? v : undefined} errors={errs} disabled={p.disabled} onChange={on} />;
    case 'bool':
      return <TriBool label={def.label} hint={def.hint} defaultValue={def.defaultValue}
        value={typeof v === 'boolean' ? v : undefined} errors={errs} disabled={p.disabled} onChange={on} />;
    case 'lines':
      return <LinesInput label={def.label} required={def.required} hint={def.hint} value={strings(v)} errors={errs}
        disabled={p.disabled} onChange={on} />;
    case 'map':
      return <MapInput label={def.label} required={def.required} hint={def.hint} value={stringMap(v)} errors={errs}
        disabled={p.disabled} onChange={on} />;
    case 'select':
      return <Select label={def.label} required={def.required} hint={def.hint} emptyLabel={def.defaultLabel}
        options={def.options.map((o) => ({ value: o, label: o }))} value={typeof v === 'string' ? v : undefined}
        errors={errs} disabled={p.disabled} onChange={on} />;
    case 'ref':
      return <RefInput def={def} value={typeof v === 'string' ? v : undefined} errors={errs} disabled={p.disabled}
        onChange={on} />;
    case 'targets':
      return <TargetsEditor value={v} errors={p.errors} disabled={p.disabled} onChange={on} />;
    case 'plugins':
      return <PluginsEditor label={def.label} hint={def.hint} value={v} errors={p.errors} disabled={p.disabled}
        onChange={on} />;
    case 'pluginName':
      return <PluginSelect label={def.label} value={typeof v === 'string' ? v : undefined} errors={errs}
        disabled={p.disabled} onChange={on} />;
    case 'pluginConfig': {
      const plugin = def.pluginField === undefined ? p.id : spec[def.pluginField];
      return <PluginConfig plugin={typeof plugin === 'string' ? plugin : undefined} value={v} label={def.label}
        errors={under(p.errors, def.key)} disabled={p.disabled} onChange={on} />;
    }
  }
}

/**
 * Structured editor for one object's spec. Fields the kind does not model (timeouts, retries,
 * health checks, ...) are edited as JSON so nothing in a stored object is lost or hidden.
 */
export function ObjectForm(p: { kind: KindDef; id: string | undefined; spec: JsonObject;
  onChange: (spec: JsonObject) => void; errors: Errors; disabled: boolean }) {
  const managed = new Set(p.kind.fields.map((f) => f.key));
  const other: JsonObject = Object.fromEntries(Object.entries(p.spec).filter(([k]) => !managed.has(k)));
  const set = (k: string, v: Json | undefined) => {
    p.onChange(withKey(p.spec, k, v));
  };
  const otherErrors: string[] = [];
  for (const [path, msgs] of p.errors) {
    const top = path.split(/[.[]/)[0] ?? '';
    if (path !== '' && !managed.has(top) && path !== 'tenantId') {
      otherErrors.push(...msgs.map((m) => `${path}: ${m}`));
    }
  }
  return (
    <div className="object-form">
      {p.kind.fields.map((def) => (
        <FieldEditor key={def.key} def={def} spec={p.spec} id={p.id} errors={p.errors} set={set} disabled={p.disabled} />
      ))}
      <details className="advanced" open={Object.keys(other).length > 0 || otherErrors.length > 0}>
        <summary>Other fields (JSON)</summary>
        <JsonInput label="Other fields" rows={6} value={Object.keys(other).length === 0 ? undefined : other}
          hint="Any further spec fields, e.g. timeouts, retries, healthCheck, circuitBreaker, pool (see docs/operations/configuration.md)."
          errors={otherErrors.length === 0 ? undefined : otherErrors} disabled={p.disabled}
          onChange={(v) => {
            const next: JsonObject = Object.fromEntries(Object.entries(p.spec).filter(([k]) => managed.has(k)));
            p.onChange({ ...(isObject(v) ? v : {}), ...next });
          }} />
      </details>
    </div>
  );
}
