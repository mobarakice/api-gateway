import { usePlugins } from '../api/hooks';
import { Loading, ProblemAlert } from '../components/ui';

export function PluginsPage() {
  const plugins = usePlugins();
  return (
    <section aria-labelledby="page-title">
      <h1 id="page-title">Plugin catalog</h1>
      <p className="muted">Plugins compiled into the gateway. Forms are rendered from each plugin&apos;s JSON Schema, the
        same file the gateway and control plane validate against.</p>
      <ProblemAlert error={plugins.error} />
      {plugins.isPending ? <Loading what="plugins" /> : (
        <table>
          <caption className="visually-hidden">Plugins</caption>
          <thead><tr><th scope="col">Plugin</th><th scope="col">Description</th><th scope="col">Phases</th>
            <th scope="col">Priority</th><th scope="col">Version</th></tr></thead>
          <tbody>
            {(plugins.data ?? []).map((p) => (
              <tr key={p.name}>
                <th scope="row">{p.name}{p.authenticates ? <span className="badge"> authenticates</span> : null}</th>
                <td>{p.description || p.title}</td>
                <td>{p.phases.join(', ')}</td>
                <td>{p.priority}</td>
                <td>{p.version}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
