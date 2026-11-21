import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { ApiError } from '../api/client';
import { useChangeSet, useChangeSets, useDiff, useDiscard, useMe, usePublish, useUnstage, useValidate } from '../api/hooks';
import type { DiffEntry, FieldError, Published } from '../api/types';
import { useActiveChangeSet } from '../components/ActiveChangeSet';
import { NewChangeSetDialog } from '../components/Layout';
import { Dialog, JsonDiff, Loading, ProblemAlert, Time } from '../components/ui';
import { linkForKey } from '../kinds';
import { can } from '../roles';
import { NotFound } from './NotFound';

export function ChangeSetsPage() {
  const sets = useChangeSets();
  const me = useMe();
  const active = useActiveChangeSet();
  const [open, setOpen] = useState(false);
  return (
    <section aria-labelledby="page-title">
      <div className="page-head">
        <h1 id="page-title">Change sets</h1>
        {can(me.data?.role, 'editor') && (
          <button type="button" className="btn btn-primary" onClick={() => { setOpen(true); }}>New change set</button>
        )}
      </div>
      <p className="muted">Drafts that have not been published. Publishing applies a change set in one etcd transaction.</p>
      <NewChangeSetDialog open={open} onClose={() => { setOpen(false); }} />
      <ProblemAlert error={sets.error} />
      {sets.isPending ? <Loading what="change sets" /> : (
        <table>
          <caption className="visually-hidden">Change sets</caption>
          <thead><tr><th scope="col">Title</th><th scope="col">Changes</th><th scope="col">Created by</th>
            <th scope="col">Updated</th><th scope="col">Active</th></tr></thead>
          <tbody>
            {(sets.data ?? []).length === 0 && <tr><td colSpan={5} className="muted">No drafts.</td></tr>}
            {(sets.data ?? []).map((s) => (
              <tr key={s.id}>
                <th scope="row"><Link to={`/changesets/${s.id}`}>{s.title}</Link></th>
                <td>{s.ops}</td>
                <td>{s.createdBy}</td>
                <td><Time at={s.updatedAt} /></td>
                <td>{active.id === s.id ? 'yes' : (
                  <button type="button" className="btn btn-small" onClick={() => { active.set(s.id); }}>
                    Stage into {s.title}
                  </button>
                )}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

function ErrorList({ errors }: { errors: FieldError[] }) {
  return (
    <ul className="field-errors">
      {errors.map((e) => {
        const key = /^([a-z-]+(?:\/[a-z-]+)?\/[A-Za-z0-9._-]+?)(?:\.|$)/.exec(e.path)?.[1];
        const link = key ? linkForKey(key) : undefined;
        return <li key={`${e.path}:${e.message}`}>{link ? <Link to={link}><code>{e.path}</code></Link>
          : <code>{e.path}</code>}: {e.message}</li>;
      })}
    </ul>
  );
}

function DiffList({ entries }: { entries: DiffEntry[] }) {
  return (
    <>
      {entries.map((d) => (
        <div key={d.key} className="diff-entry">
          <h3>
            <span className={d.action === 'DELETE' ? 'badge badge-danger' : d.liveRevision === 0 ? 'badge badge-ok' : 'badge'}>
              {d.action === 'DELETE' ? 'delete' : d.liveRevision === 0 ? 'create' : 'update'}
            </span>{' '}
            {linkForKey(d.key) ? <Link to={linkForKey(d.key) ?? '/'}>{d.key}</Link> : d.key}
          </h3>
          {d.stale && (
            <p className="alert alert-warn">Changed by someone else since it was staged (revision {d.baseRevision} →{' '}
              {d.liveRevision || 'deleted'}). Publishing will be refused; re-stage it.</p>
          )}
          <JsonDiff label={d.key} before={d.before} after={d.after} />
        </div>
      ))}
    </>
  );
}

function PublishDialog(p: { id: string; etag: string; open: boolean; onClose: () => void;
  onPublished: (r: Published) => void }) {
  const diff = useDiff(p.open ? p.id : undefined);
  const publish = usePublish();
  const [reviewed, setReviewed] = useState(false);
  const entries = diff.data ?? [];
  const problem = publish.error instanceof ApiError ? publish.error.problem : undefined;
  const close = () => {
    setReviewed(false);
    publish.reset();
    p.onClose();
  };
  return (
    <Dialog open={p.open} wide title="Publish change set" onClose={close}>
      <p>Publishing applies every change below to all gateways in one transaction. Review the diff first.</p>
      {diff.isPending ? <Loading what="diff" /> : <DiffList entries={entries} />}
      <ProblemAlert error={publish.error} />
      {problem?.errors && problem.errors.length > 0 && <ErrorList errors={problem.errors} />}
      <div className="field checkbox">
        <input id="reviewed" type="checkbox" checked={reviewed} onChange={(e) => { setReviewed(e.target.checked); }} />
        <label htmlFor="reviewed">I have reviewed these {entries.length} changes</label>
      </div>
      <div className="actions">
        <button type="button" className="btn btn-primary" disabled={!reviewed || publish.isPending || entries.length === 0}
          onClick={() => { publish.mutate({ id: p.id, etag: p.etag }, { onSuccess: p.onPublished }); }}>
          {publish.isPending ? 'Publishing…' : 'Publish'}
        </button>
        <button type="button" className="btn" onClick={close}>Cancel</button>
      </div>
    </Dialog>
  );
}

export function ChangeSetPage() {
  const { id } = useParams();
  const cs = useChangeSet(id);
  const me = useMe();
  const active = useActiveChangeSet();
  const validate = useValidate(id ?? '');
  const unstage = useUnstage(id ?? '');
  const discard = useDiscard();
  const diff = useDiff(id);
  const navigate = useNavigate();
  const [publishing, setPublishing] = useState(false);
  const [confirmDiscard, setConfirmDiscard] = useState(false);
  const role = me.data?.role;
  if (cs.isPending) {
    return <Loading what="change set" />;
  }
  if (cs.isError || !id) {
    return cs.error instanceof ApiError && cs.error.status === 404
      ? <NotFound what="change set (it may have been published or discarded)" /> : <ProblemAlert error={cs.error} />;
  }
  const s = cs.data;
  return (
    <section aria-labelledby="page-title">
      <p className="crumbs"><Link to="/changesets">Change sets</Link></p>
      <h1 id="page-title">{s.title}</h1>
      <p className="muted">Created by {s.createdBy}, <Time at={s.createdAt} />. {s.items.length} staged changes.
        {active.id === s.id ? ' This is your active change set.' : ''}</p>
      {active.id !== s.id && can(role, 'editor') && (
        <button type="button" className="btn" onClick={() => { active.set(s.id); }}>Stage edits into this change set</button>
      )}
      <h2>Staged changes</h2>
      <table>
        <caption className="visually-hidden">Staged changes</caption>
        <thead><tr><th scope="col">Object</th><th scope="col">Action</th><th scope="col">Staged by</th>
          {can(role, 'editor') && <th scope="col"><span className="visually-hidden">Actions</span></th>}</tr></thead>
        <tbody>
          {s.items.length === 0 && <tr><td colSpan={4} className="muted">Nothing staged yet. Edit objects to stage changes.</td></tr>}
          {s.items.map((o) => (
            <tr key={o.key}>
              <th scope="row">{linkForKey(o.key) ? <Link to={linkForKey(o.key) ?? '/'}>{o.key}</Link> : o.key}</th>
              <td>{o.action === 'DELETE' ? 'delete' : o.baseRevision === 0 ? 'create' : 'update'}</td>
              <td>{o.stagedBy}</td>
              {can(role, 'editor') && (
                <td><button type="button" className="btn btn-small" disabled={unstage.isPending}
                  onClick={() => { unstage.mutate(o.key); }}>Unstage {o.key}</button></td>
              )}
            </tr>
          ))}
        </tbody>
      </table>
      <ProblemAlert error={unstage.error ?? discard.error ?? validate.error} />
      <div className="actions">
        {can(role, 'editor') && (
          <button type="button" className="btn" disabled={validate.isPending || s.items.length === 0}
            onClick={() => { validate.mutate(); }}>Validate</button>
        )}
        {can(role, 'publisher') && (
          <button type="button" className="btn btn-primary" disabled={s.items.length === 0}
            onClick={() => { setPublishing(true); }}>Review and publish…</button>
        )}
        {can(role, 'editor') && (
          <button type="button" className="btn btn-danger" onClick={() => { setConfirmDiscard(true); }}>Discard</button>
        )}
      </div>
      {validate.data && (
        <div className={validate.data.valid ? 'alert alert-success' : 'alert alert-error'} role="status">
          {validate.data.valid ? <p>Valid: the gateway compiler accepts the result.</p> : (
            <>
              <p><strong>Not valid.</strong> Gateways would reject this state:</p>
              <ErrorList errors={validate.data.errors} />
              {validate.data.conflicts.map((c) => (
                <p key={c.key}>{c.key} changed since it was staged (revision {c.expectedRevision} → {c.actualRevision || 'deleted'}).</p>
              ))}
            </>
          )}
        </div>
      )}
      <h2>Diff against the live state</h2>
      {diff.isPending ? <Loading what="diff" /> : <DiffList entries={diff.data ?? []} />}
      <PublishDialog id={s.id} etag={s.etag} open={publishing} onClose={() => { setPublishing(false); }}
        onPublished={(r) => {
          setPublishing(false);
          if (active.id === s.id) {
            active.set(undefined);
          }
          void navigate(`/history/${r.seq}`, { state: { published: true } });
        }} />
      <Dialog open={confirmDiscard} title="Discard this change set?" onClose={() => { setConfirmDiscard(false); }}>
        <p>All {s.items.length} staged changes are dropped. Nothing live changes.</p>
        <div className="actions">
          <button type="button" className="btn btn-danger" disabled={discard.isPending} onClick={() => {
            discard.mutate({ id: s.id, etag: s.etag }, { onSuccess: () => {
              if (active.id === s.id) {
                active.set(undefined);
              }
              void navigate('/changesets');
            } });
          }}>Discard</button>
          <button type="button" className="btn" onClick={() => { setConfirmDiscard(false); }}>Cancel</button>
        </div>
      </Dialog>
    </section>
  );
}
