import type { Json, JsonObject } from '../api/types';
import { JsonInput, LinesInput, MapInput, NumberInput, Select, TextInput, TriBool } from '../components/fields';
import { childPath, description, isObject, prune, resolve, shape, text, title, withKey, type Schema } from './model';

interface NodeProps {
  schema: Schema;
  root: Schema;
  value: Json | undefined;
  onChange: (v: Json | undefined) => void;
  path: string;
  label: string;
  required: boolean;
  errors: Map<string, string[]>;
  disabled?: boolean | undefined;
}

function hintOf(s: Schema): string | undefined {
  const d = description(s);
  const def = s.default;
  const defText = def === undefined ? undefined : `Default: ${JSON.stringify(def)}.`;
  return [d, defText].filter(Boolean).join(' ') || undefined;
}

function SchemaNode(p: NodeProps) {
  const s = resolve(p.schema, p.root);
  const sh = shape(s, p.root);
  const errs = p.errors.get(p.path);
  const common = { label: title(s, p.label), required: p.required, hint: hintOf(s), errors: errs, disabled: p.disabled };
  switch (sh.kind) {
    case 'enum': {
      const opts = sh.options.map((o) => ({ value: JSON.stringify(o), label: typeof o === 'string' ? o : JSON.stringify(o) }));
      return (
        <Select {...common} options={opts} value={p.value === undefined ? undefined : JSON.stringify(p.value)}
          emptyLabel={s.default === undefined ? '(not set)' : `(default: ${JSON.stringify(s.default)})`}
          onChange={(v) => { p.onChange(v === undefined ? undefined : JSON.parse(v) as Json); }} />
      );
    }
    case 'string':
      return (
        <TextInput {...common} value={typeof p.value === 'string' ? p.value : undefined}
          maxLength={typeof s.maxLength === 'number' ? s.maxLength : undefined}
          placeholder={typeof s.default === 'string' ? s.default : undefined} onChange={p.onChange} />
      );
    case 'number':
      return (
        <NumberInput {...common} integer={sh.integer} value={typeof p.value === 'number' ? p.value : undefined}
          min={typeof s.minimum === 'number' ? s.minimum : undefined}
          max={typeof s.maximum === 'number' ? s.maximum : undefined}
          placeholder={typeof s.default === 'number' ? String(s.default) : undefined} onChange={p.onChange} />
      );
    case 'boolean':
      return (
        <TriBool {...common} value={typeof p.value === 'boolean' ? p.value : undefined}
          defaultValue={typeof s.default === 'boolean' ? s.default : undefined} onChange={p.onChange} />
      );
    case 'list':
      return (
        <LinesInput {...common} numeric={sh.item !== 'string'}
          value={Array.isArray(p.value) ? p.value.filter((x): x is string | number => typeof x !== 'object') : undefined}
          errors={collect(p.errors, p.path)} onChange={(v) => { p.onChange(v); }} />
      );
    case 'stringMap':
      return (
        <MapInput {...common} errors={collect(p.errors, p.path)}
          value={isObject(p.value) ? Object.fromEntries(Object.entries(p.value).map(([k, v]) => [k, text(v)])) : undefined}
          onChange={(v) => { p.onChange(v); }} />
      );
    case 'object':
      return <ObjectNode {...p} schema={s} properties={sh.properties} requiredSet={sh.required} />;
    case 'array':
      return <ArrayNode {...p} schema={s} item={sh.item} />;
    case 'json':
      return <JsonInput {...common} errors={collect(p.errors, p.path)} value={p.value} onChange={p.onChange} />;
  }
}

/** Errors at `path` and below it (for editors that edit a whole subtree as one control). */
function collect(errors: Map<string, string[]>, path: string): string[] | undefined {
  const out: string[] = [];
  for (const [k, v] of errors) {
    if (k === path) {
      out.push(...v);
    } else if (path === '' || k.startsWith(`${path}.`) || k.startsWith(`${path}[`)) {
      const rel = path === '' ? k : k.slice(path.length).replace(/^\./, '');
      out.push(...v.map((m) => `${rel}: ${m}`));
    }
  }
  return out.length === 0 ? undefined : out;
}

function ObjectNode(p: NodeProps & { properties: [string, Schema][]; requiredSet: Set<string> }) {
  const obj: JsonObject = isObject(p.value) ? p.value : {};
  const set = (k: string, v: Json | undefined) => {
    const next = withKey(obj, k, v);
    p.onChange(Object.keys(next).length === 0 ? undefined : next);
  };
  const body = p.properties.map(([k, child]) => (
    <SchemaNode key={k} schema={child} root={p.root} value={obj[k]} path={childPath(p.path, k)} label={k}
      required={p.requiredSet.has(k)} errors={p.errors} disabled={p.disabled} onChange={(v) => { set(k, v); }} />
  ));
  const own = p.errors.get(p.path);
  if (p.path === '') {
    return (
      <div className="schema-form">
        {own && <ul className="field-errors">{own.map((e) => <li key={e}>{e}</li>)}</ul>}
        {body}
      </div>
    );
  }
  return (
    <fieldset className="nested">
      <legend>{title(p.schema, p.label)}{p.required ? ' *' : ''}</legend>
      {description(p.schema) && <p className="hint">{description(p.schema)}</p>}
      {own && <ul className="field-errors">{own.map((e) => <li key={e}>{e}</li>)}</ul>}
      {body}
    </fieldset>
  );
}

function ArrayNode(p: NodeProps & { item: Schema }) {
  const items: Json[] = Array.isArray(p.value) ? p.value : [];
  const set = (next: Json[]) => {
    p.onChange(next.length === 0 ? undefined : next);
  };
  const label = title(p.schema, p.label);
  return (
    <fieldset className="nested">
      <legend>{label}{p.required ? ' *' : ''}</legend>
      {description(p.schema) && <p className="hint">{description(p.schema)}</p>}
      {p.errors.get(p.path) && <ul className="field-errors">{p.errors.get(p.path)?.map((e) => <li key={e}>{e}</li>)}</ul>}
      {items.map((it, i) => (
        // Items have no identity of their own; the index is the identity.
        <div className="array-item" key={i}>
          <SchemaNode schema={p.item} root={p.root} value={it} path={childPath(p.path, i)} label={`${label} ${i + 1}`}
            required errors={p.errors} disabled={p.disabled}
            onChange={(v) => { set(items.map((x, j) => (j === i ? (v ?? {}) : x))); }} />
          <button type="button" className="btn btn-small" disabled={p.disabled}
            onClick={() => { set(items.filter((_x, j) => j !== i)); }}>
            Remove {label} {i + 1}
          </button>
        </div>
      ))}
      <button type="button" className="btn btn-small" disabled={p.disabled} onClick={() => { set([...items, {}]); }}>
        Add {label}
      </button>
    </fieldset>
  );
}

/** Renders a plugin's config form from its JSON Schema. `value` is the config object. */
export function SchemaForm(props: { schema: Schema; value: Json | undefined; onChange: (v: Json | undefined) => void;
  errors: Map<string, string[]>; label?: string; disabled?: boolean }) {
  return (
    <SchemaNode schema={props.schema} root={props.schema} value={props.value} path="" label={props.label ?? 'config'}
      required={false} errors={props.errors} disabled={props.disabled}
      onChange={(v) => { props.onChange(prune(v)); }} />
  );
}
