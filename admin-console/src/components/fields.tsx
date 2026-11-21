import { useId, useState, type ReactNode } from 'react';
import type { Json } from '../api/types';

/**
 * Labelled control with hint and errors wired through aria-describedby / aria-invalid (WCAG 2.2:
 * 1.3.1, 3.3.1, 3.3.2). The control is rendered by `children(id, describedBy, invalid)`.
 */
export function Field(props: {
  label: string;
  required?: boolean | undefined;
  hint?: string | undefined;
  errors?: string[] | undefined;
  children: (id: string, describedBy: string | undefined, invalid: boolean) => ReactNode;
}) {
  const id = useId();
  const hintId = `${id}-hint`;
  const errId = `${id}-err`;
  const errors = props.errors ?? [];
  const described = [props.hint ? hintId : undefined, errors.length > 0 ? errId : undefined].filter(Boolean).join(' ');
  return (
    <div className={errors.length > 0 ? 'field field-invalid' : 'field'}>
      <label htmlFor={id}>
        {props.label}
        {props.required && <span className="req" aria-hidden="true"> *</span>}
        {props.required && <span className="visually-hidden"> (required)</span>}
      </label>
      {props.hint && <p className="hint" id={hintId}>{props.hint}</p>}
      {props.children(id, described === '' ? undefined : described, errors.length > 0)}
      {errors.length > 0 && (
        <ul className="field-errors" id={errId}>
          {errors.map((e) => <li key={e}>{e}</li>)}
        </ul>
      )}
    </div>
  );
}

interface Base {
  label: string;
  required?: boolean | undefined;
  hint?: string | undefined;
  errors?: string[] | undefined;
  placeholder?: string | undefined;
  disabled?: boolean | undefined;
}

export function TextInput(p: Base & { value: string | undefined; onChange: (v: string | undefined) => void;
  maxLength?: number | undefined; autoComplete?: string; type?: 'text' | 'url' }) {
  return (
    <Field label={p.label} required={p.required} hint={p.hint} errors={p.errors}>
      {(id, d, invalid) => (
        <input id={id} type={p.type ?? 'text'} value={p.value ?? ''} placeholder={p.placeholder}
          aria-describedby={d} aria-invalid={invalid || undefined} required={p.required} disabled={p.disabled}
          maxLength={p.maxLength} autoComplete={p.autoComplete ?? 'off'} spellCheck={false}
          onChange={(e) => { p.onChange(e.target.value === '' ? undefined : e.target.value); }} />
      )}
    </Field>
  );
}

export function NumberInput(p: Base & { value: number | undefined; onChange: (v: number | undefined) => void;
  integer?: boolean; min?: number | undefined; max?: number | undefined }) {
  const [text, setText] = useState(p.value === undefined ? '' : String(p.value));
  const [seen, setSeen] = useState(p.value);
  // Follow outside changes without clobbering what is being typed ("1." is 1).
  if (seen !== p.value) {
    setSeen(p.value);
    if (!(text === '' ? p.value === undefined : Number(text) === p.value)) {
      setText(p.value === undefined ? '' : String(p.value));
    }
  }
  return (
    <Field label={p.label} required={p.required} hint={p.hint} errors={p.errors}>
      {(id, d, invalid) => (
        <input id={id} type="number" inputMode={p.integer ? 'numeric' : 'decimal'} step={p.integer ? 1 : 'any'}
          min={p.min} max={p.max} value={text} placeholder={p.placeholder} aria-describedby={d}
          aria-invalid={invalid || undefined} required={p.required} disabled={p.disabled}
          onChange={(e) => {
            setText(e.target.value);
            const n = e.target.value === '' ? undefined : Number(e.target.value);
            p.onChange(n === undefined || Number.isNaN(n) ? undefined : n);
          }} />
      )}
    </Field>
  );
}

export function Select(p: Base & { value: string | undefined; onChange: (v: string | undefined) => void;
  options: { value: string; label: string }[]; emptyLabel?: string | undefined }) {
  return (
    <Field label={p.label} required={p.required} hint={p.hint} errors={p.errors}>
      {(id, d, invalid) => (
        <select id={id} value={p.value ?? ''} aria-describedby={d} aria-invalid={invalid || undefined}
          required={p.required} disabled={p.disabled}
          onChange={(e) => { p.onChange(e.target.value === '' ? undefined : e.target.value); }}>
          {(!p.required || p.value === undefined) && <option value="">{p.emptyLabel ?? '(not set)'}</option>}
          {p.options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
        </select>
      )}
    </Field>
  );
}

/** A boolean that can also be "not set" (the server default applies). */
export function TriBool(p: Base & { value: boolean | undefined; onChange: (v: boolean | undefined) => void;
  defaultValue?: boolean | undefined }) {
  const def = p.defaultValue === undefined ? '(not set)' : `(default: ${p.defaultValue ? 'yes' : 'no'})`;
  return (
    <Select label={p.label} required={p.required} hint={p.hint} errors={p.errors} disabled={p.disabled}
      value={p.value === undefined ? undefined : String(p.value)} emptyLabel={def}
      options={[{ value: 'true', label: 'yes' }, { value: 'false', label: 'no' }]}
      onChange={(v) => { p.onChange(v === undefined ? undefined : v === 'true'); }} />
  );
}

/** One value per line. */
export function LinesInput(p: Base & { value: (string | number)[] | undefined;
  onChange: (v: (string | number)[] | undefined) => void; numeric?: boolean }) {
  const [text, setText] = useState((p.value ?? []).join('\n'));
  return (
    <Field label={p.label} required={p.required} hint={p.hint ?? 'One per line.'} errors={p.errors}>
      {(id, d, invalid) => (
        <textarea id={id} rows={Math.min(8, Math.max(2, (p.value?.length ?? 0) + 1))} value={text}
          placeholder={p.placeholder} aria-describedby={d} aria-invalid={invalid || undefined} spellCheck={false}
          disabled={p.disabled}
          onChange={(e) => {
            setText(e.target.value);
            const lines = e.target.value.split('\n').map((l) => l.trim()).filter((l) => l !== '');
            const out = p.numeric ? lines.map(Number) : lines;
            p.onChange(out.length === 0 ? undefined : out);
          }} />
      )}
    </Field>
  );
}

/** `name=value` per line, for string maps such as header matches or consumer attributes. */
export function MapInput(p: Base & { value: Record<string, string> | undefined;
  onChange: (v: Record<string, string> | undefined) => void }) {
  const [text, setText] = useState(Object.entries(p.value ?? {}).map(([k, v]) => `${k}=${v}`).join('\n'));
  const [bad, setBad] = useState<string | undefined>();
  return (
    <Field label={p.label} required={p.required} hint={p.hint ?? 'One name=value per line.'}
      errors={[...(bad ? [bad] : []), ...(p.errors ?? [])]}>
      {(id, d, invalid) => (
        <textarea id={id} rows={3} value={text} aria-describedby={d} aria-invalid={invalid || undefined}
          spellCheck={false} disabled={p.disabled}
          onChange={(e) => {
            setText(e.target.value);
            const out: Record<string, string> = {};
            let err: string | undefined;
            for (const line of e.target.value.split('\n')) {
              if (line.trim() === '') {
                continue;
              }
              const i = line.indexOf('=');
              if (i <= 0) {
                err = `"${line.trim()}" is not name=value`;
                continue;
              }
              out[line.slice(0, i).trim()] = line.slice(i + 1).trim();
            }
            setBad(err);
            if (err === undefined) {
              p.onChange(Object.keys(out).length === 0 ? undefined : out);
            }
          }} />
      )}
    </Field>
  );
}

/** Raw JSON for parts no form can express; invalid JSON is reported and not propagated. */
export function JsonInput(p: Base & { value: Json | undefined; onChange: (v: Json | undefined) => void;
  rows?: number }) {
  const [text, setText] = useState(p.value === undefined ? '' : JSON.stringify(p.value, null, 2));
  const [bad, setBad] = useState<string | undefined>();
  return (
    <Field label={p.label} required={p.required} hint={p.hint ?? 'JSON.'} errors={[...(bad ? [bad] : []),
      ...(p.errors ?? [])]}>
      {(id, d, invalid) => (
        <textarea id={id} className="mono" rows={p.rows ?? 6} value={text} aria-describedby={d}
          aria-invalid={invalid || undefined} spellCheck={false} disabled={p.disabled}
          onChange={(e) => {
            setText(e.target.value);
            if (e.target.value.trim() === '') {
              setBad(undefined);
              p.onChange(undefined);
              return;
            }
            try {
              p.onChange(JSON.parse(e.target.value) as Json);
              setBad(undefined);
            } catch {
              setBad('not valid JSON');
            }
          }} />
      )}
    </Field>
  );
}
