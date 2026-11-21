import { useState } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router';
import { useHistory, useHistoryEntry, useMe, useRollback } from '../api/hooks';
import { Dialog, JsonDiff, Loading, ProblemAlert, Time } from '../components/ui';
import { linkForKey } from '../kinds';
import { can } from '../roles';
import { NotFound } from './NotFound';

export function HistoryPage() {
  const h = useHistory();
  return (
    <section aria-labelledby="page-title">
      <h1 id="page-title">History</h1>
      <p className="muted">Published change sets, newest first. Recent entries can be rolled back.</p>
      <ProblemAlert error={h.error} />
      {h.isPending ? <Loading what="history" /> : (
        <table>
          <caption className="visually-hidden">Published change sets</caption>
          <thead><tr><th scope="col">#</th><th scope="col">Title</th><th scope="col">Changes</th>
            <th scope="col">Published by</th><th scope="col">Published</th></tr></thead>
          <tbody>
            {(h.data ?? []).length === 0 && <tr><td colSpan={5} className="muted">Nothing published yet.</td></tr>}
            {(h.data ?? []).map((e) => (
              <tr key={e.seq}>
                <th scope="row"><Link to={`/history/${e.seq}`}>{e.seq}</Link></th>
                <td>{e.title}{e.rollbackOf !== undefined ? ` (rollback of #${e.rollbackOf})` : ''}</td>
                <td>{e.ops}</td>
                <td>{e.publishedBy}</td>
                <td><Time at={e.publishedAt} /></td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

export function HistoryEntryPage() {
  const params = useParams();
  const seq = Number(params.seq);
  const entry = useHistoryEntry(seq);
  const me = useMe();
  const rollback = useRollback();
  const navigate = useNavigate();
  const location = useLocation();
  const [confirm, setConfirm] = useState(false);
  const published = (location.state as { published?: boolean } | null)?.published === true;
  if (!Number.isInteger(seq)) {
    return <NotFound />;
  }
  if (entry.isPending) {
    return <Loading what="history entry" />;
  }
  if (entry.isError) {
    return <ProblemAlert error={entry.error} />;
  }
  const e = entry.data;
  return (
    <section aria-labelledby="page-title">
      <p className="crumbs"><Link to="/history">History</Link></p>
      <h1 id="page-title">#{e.seq} {e.title}</h1>
      {published && <p className="alert alert-success" role="status">Published as #{e.seq}. Gateways apply it within seconds; see Gateways.</p>}
      <p className="muted">Published by {e.publishedBy}, <Time at={e.publishedAt} />. Drafted by {e.createdBy}.
        {e.rollbackOf !== undefined && <> Rolls back <Link to={`/history/${e.rollbackOf}`}>#{e.rollbackOf}</Link>.</>}</p>
      {can(me.data?.role, 'publisher') && (
        <button type="button" className="btn btn-danger" onClick={() => { setConfirm(true); }}>Roll back…</button>
      )}
      <h2>Changes</h2>
      {e.ops.map((o) => (
        <div key={o.key} className="diff-entry">
          <h3>{o.action === 'DELETE' ? 'deleted' : o.before ? 'updated' : 'created'}{' '}
            {linkForKey(o.key) ? <Link to={linkForKey(o.key) ?? '/'}>{o.key}</Link> : o.key}</h3>
          <JsonDiff label={o.key} before={o.before} after={o.after} />
        </div>
      ))}
      <Dialog open={confirm} wide title={`Roll back #${e.seq}?`} onClose={() => { setConfirm(false); rollback.reset(); }}>
        <p>This publishes the inverse of #{e.seq} as a new change: every key returns to its state before #{e.seq}.
          It is refused if a later publish changed the same keys (roll that back first).</p>
        {e.ops.map((o) => <JsonDiff key={o.key} label={o.key} before={o.after} after={o.before} />)}
        <ProblemAlert error={rollback.error} />
        <div className="actions">
          <button type="button" className="btn btn-danger" disabled={rollback.isPending} onClick={() => {
            rollback.mutate(e.seq, { onSuccess: (r) => {
              setConfirm(false);
              void navigate(`/history/${r.seq}`, { state: { published: true } });
            } });
          }}>{rollback.isPending ? 'Rolling back…' : 'Publish rollback'}</button>
          <button type="button" className="btn" onClick={() => { setConfirm(false); rollback.reset(); }}>Cancel</button>
        </div>
      </Dialog>
    </section>
  );
}
