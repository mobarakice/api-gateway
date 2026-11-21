import { useState } from 'react';
import { useAudit, useMe } from '../api/hooks';
import { Loading, ProblemAlert, Time } from '../components/ui';
import { can } from '../roles';

export function AuditPage() {
  const me = useMe();
  const [pages, setPages] = useState<string[]>([]);
  const audit = useAudit(pages.at(-1));
  if (me.data && !can(me.data.role, 'admin')) {
    return <p className="alert alert-info">The audit log needs the admin role.</p>;
  }
  return (
    <section aria-labelledby="page-title">
      <h1 id="page-title">Audit</h1>
      <p className="muted">The recent window kept in etcd (ADR 0021); the full archive is in the log pipeline.</p>
      <ProblemAlert error={audit.error} />
      {audit.isPending ? <Loading what="audit events" /> : (
        <table>
          <caption className="visually-hidden">Audit events, newest first</caption>
          <thead><tr><th scope="col">Time</th><th scope="col">Action</th><th scope="col">Outcome</th>
            <th scope="col">Who</th><th scope="col">Target</th><th scope="col">Source IP</th></tr></thead>
          <tbody>
            {(audit.data?.items ?? []).map((e) => (
              <tr key={e.id}>
                <td><Time at={e.at} /></td>
                <td>{e.action}{e.seq !== undefined ? ` #${e.seq}` : ''}</td>
                <td>{e.outcome}</td>
                <td>{e.actor?.subject ?? ''}{e.actor?.role ? ` (${e.actor.role})` : ''}</td>
                <td>{e.target ?? ''}{e.changes && e.changes.length > 0 ? ` (${e.changes.length} keys)` : ''}</td>
                <td>{e.sourceIp ?? ''}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <div className="actions">
        <button type="button" className="btn" disabled={pages.length === 0}
          onClick={() => { setPages(pages.slice(0, -1)); }}>Newer</button>
        <button type="button" className="btn" disabled={!audit.data?.nextBefore}
          onClick={() => { if (audit.data?.nextBefore) { setPages([...pages, audit.data.nextBefore]); } }}>Older</button>
      </div>
    </section>
  );
}
