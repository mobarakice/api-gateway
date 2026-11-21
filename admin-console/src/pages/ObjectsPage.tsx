import { useState } from 'react';
import { Link, useParams } from 'react-router';
import { useList, useMe } from '../api/hooks';
import { ProblemAlert, Loading, Time } from '../components/ui';
import { kindBySlug } from '../kinds';
import { can } from '../roles';
import { NotFound } from './NotFound';

export function ObjectsPage() {
  const { slug } = useParams();
  const kind = kindBySlug(slug);
  const [cursors, setCursors] = useState<string[]>([]);
  const cursor = cursors.at(-1);
  const list = useList(kind?.collection ?? '', cursor);
  const me = useMe();
  if (!kind) {
    return <NotFound />;
  }
  return (
    <section aria-labelledby="page-title">
      <div className="page-head">
        <h1 id="page-title">{kind.label}</h1>
        {can(me.data?.role, kind.writeRole) && (
          <Link className="btn btn-primary" to={`/objects/${kind.slug}/new`}>New {kind.singular}</Link>
        )}
      </div>
      <p className="muted">{kind.intro} Lists show the live (published) state.</p>
      <ProblemAlert error={list.error} />
      {list.isPending ? <Loading what={kind.label.toLowerCase()} /> : (
        <table>
          <caption className="visually-hidden">{kind.label}</caption>
          <thead>
            <tr>
              <th scope="col">ID</th>
              {kind.columns.map((c) => <th scope="col" key={c.label}>{c.label}</th>)}
              {kind.tenant && <th scope="col">Tenant</th>}
              <th scope="col">Updated</th>
            </tr>
          </thead>
          <tbody>
            {(list.data?.items ?? []).length === 0 && (
              <tr><td colSpan={kind.columns.length + 3} className="muted">No {kind.label.toLowerCase()} yet.</td></tr>
            )}
            {(list.data?.items ?? []).map((o) => (
              <tr key={o.key}>
                <th scope="row"><Link to={`/objects/${kind.slug}/${encodeURIComponent(o.id)}`}>{o.id}</Link></th>
                {kind.columns.map((c) => <td key={c.label}>{c.value(o)}</td>)}
                {kind.tenant && <td>{o.tenantId ?? ''}</td>}
                <td><Time at={o.updatedAt} />{o.updatedBy ? ` by ${o.updatedBy}` : ''}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <div className="actions">
        <button type="button" className="btn" disabled={cursors.length === 0}
          onClick={() => { setCursors(cursors.slice(0, -1)); }}>Previous page</button>
        <button type="button" className="btn" disabled={!list.data?.nextCursor}
          onClick={() => { if (list.data?.nextCursor) { setCursors([...cursors, list.data.nextCursor]); } }}>
          Next page
        </button>
      </div>
    </section>
  );
}
