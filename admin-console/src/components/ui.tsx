import { useEffect, useRef, type ReactNode } from 'react';
import { describe } from '../api/errors';
import { ApiError } from '../api/client';
import type { Json } from '../api/types';
import { diffLines, pretty } from './diff';

export function ProblemAlert({ error }: { error: unknown }) {
  if (error === null || error === undefined) {
    return null;
  }
  const p = error instanceof ApiError ? error.problem : { title: 'Unexpected error', status: -1,
    detail: error instanceof Error ? error.message : typeof error === 'string' ? error : 'unknown error' };
  return (
    <div className="alert alert-error" role="alert">
      <p><strong>{p.title}</strong>{p.status > 0 ? ` (HTTP ${p.status})` : ''}: {describe(p)}</p>
      {p.conflicts && p.conflicts.length > 0 && (
        <ul>
          {p.conflicts.map((c) => (
            <li key={c.key}>{c.key}: expected revision {c.expectedRevision}, now {c.actualRevision || 'deleted'}</li>
          ))}
        </ul>
      )}
      {p.requestId && <p className="hint">Request ID: <code>{p.requestId}</code></p>}
    </div>
  );
}

/** WCAG error summary: one list of every field error, focused when it appears. */
export function ErrorSummary({ errors }: { errors: Map<string, string[]> }) {
  const ref = useRef<HTMLDivElement>(null);
  const count = [...errors.values()].reduce((n, l) => n + l.length, 0);
  useEffect(() => {
    if (count > 0) {
      ref.current?.focus();
    }
  }, [count]);
  if (count === 0) {
    return null;
  }
  return (
    <div className="alert alert-error" role="alert" tabIndex={-1} ref={ref} aria-labelledby="error-summary-title">
      <p id="error-summary-title"><strong>{count === 1 ? '1 problem' : `${count} problems`} to fix</strong></p>
      <ul>
        {[...errors.entries()].flatMap(([path, msgs]) => msgs.map((m) => (
          <li key={`${path}:${m}`}><code>{path === '' ? '(object)' : path}</code>: {m}</li>
        )))}
      </ul>
    </div>
  );
}

/** Modal built on <dialog>: native focus trap, Esc closes, focus returns to the opener. */
export function Dialog(props: { open: boolean; title: string; onClose: () => void; children: ReactNode;
  wide?: boolean }) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const d = ref.current;
    if (!d) {
      return;
    }
    if (props.open && !d.open) {
      d.showModal();
    } else if (!props.open && d.open) {
      d.close();
    }
  }, [props.open]);
  return (
    <dialog ref={ref} className={props.wide ? 'dialog dialog-wide' : 'dialog'} aria-labelledby="dialog-title"
      onClose={props.onClose} onCancel={props.onClose}>
      {props.open && (
        <>
          <h2 id="dialog-title">{props.title}</h2>
          {props.children}
        </>
      )}
    </dialog>
  );
}

export function JsonDiff({ before, after, label }: { before: Json | undefined; after: Json | undefined;
  label: string }) {
  const lines = diffLines(pretty(before), pretty(after));
  const added = lines.filter((l) => l.op === '+').length;
  const removed = lines.filter((l) => l.op === '-').length;
  return (
    <figure className="diff">
      <figcaption>{label}: {added} added, {removed} removed lines</figcaption>
      <pre aria-label={`Diff of ${label}`}>
        {lines.map((l, i) => {
          const text = `${l.op} ${l.text}\n`;
          return l.op === '+' ? <ins key={i}>{text}</ins> : l.op === '-' ? <del key={i}>{text}</del>
            : <span key={i}>{text}</span>;
        })}
      </pre>
    </figure>
  );
}

export function Loading({ what }: { what: string }) {
  return <p role="status" className="muted">Loading {what}…</p>;
}

export function Time({ at }: { at: string | undefined }) {
  if (!at) {
    return null;
  }
  const d = new Date(at);
  return <time dateTime={at} title={at}>{Number.isNaN(d.getTime()) ? at : d.toLocaleString()}</time>;
}
