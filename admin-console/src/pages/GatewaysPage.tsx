import { useGateways } from '../api/hooks';
import { Loading, ProblemAlert } from '../components/ui';

export function GatewaysPage() {
  const g = useGateways();
  const items = g.data?.items ?? [];
  const behind = items.filter((n) => n.behind).length;
  return (
    <section aria-labelledby="page-title">
      <h1 id="page-title">Gateways</h1>
      <p className="muted">Live heartbeats (refreshed every 5 s). A gateway is behind when its applied revision is older
        than the last publish.</p>
      <ProblemAlert error={g.error} />
      {g.isPending ? <Loading what="gateways" /> : (
        <>
          <p role="status">
            {items.length} gateway{items.length === 1 ? '' : 's'} reporting
            {g.data ? `, config revision ${g.data.configRevision}` : ''}
            {behind > 0 ? `, ${behind} behind` : items.length > 0 ? ', all up to date' : ''}.
          </p>
          <table>
            <caption className="visually-hidden">Gateways</caption>
            <thead><tr><th scope="col">Gateway</th><th scope="col">State</th><th scope="col">Applied revision</th>
              <th scope="col">Source</th><th scope="col">Status</th><th scope="col">Version</th></tr></thead>
            <tbody>
              {items.length === 0 && <tr><td colSpan={6} className="muted">No gateway heartbeats.</td></tr>}
              {items.map((n) => (
                <tr key={n.gatewayId}>
                  <th scope="row">{n.gatewayId}</th>
                  <td>{n.state ?? ''}</td>
                  <td>{n.appliedRevision ?? ''}</td>
                  <td>{n.source ?? ''}</td>
                  <td>
                    {n.behind ? <span className="badge badge-warn">behind</span> : <span className="badge badge-ok">up to date</span>}
                    {n.configError && <span className="config-error"> Rejected config: {n.configError}</span>}
                  </td>
                  <td>{n.version ?? ''}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </section>
  );
}
