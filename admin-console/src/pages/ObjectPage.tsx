import { useMemo, useState, type SyntheticEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { ApiError } from '../api/client';
import { specErrors } from '../api/errors';
import { useChangeSet, useMe, useObject, useStage } from '../api/hooks';
import type { JsonObject, StoredObject } from '../api/types';
import { useActiveChangeSet } from '../components/ActiveChangeSet';
import { TextInput } from '../components/fields';
import { NewChangeSetDialog } from '../components/Layout';
import { Dialog, ErrorSummary, Loading, ProblemAlert, Time } from '../components/ui';
import { ObjectForm } from '../forms/ObjectForm';
import { kindBySlug, type KindDef } from '../kinds';
import { can } from '../roles';
import { isObject } from '../schema/model';
import { Credentials } from './Credentials';
import { NotFound } from './NotFound';

export const ID_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;

function StageBanner({ canWrite, role }: { canWrite: boolean; role: string | undefined }) {
  const active = useActiveChangeSet();
  const cs = useChangeSet(active.id);
  const [open, setOpen] = useState(false);
  if (!canWrite) {
    return <p className="alert alert-info">Read only: the {role ?? 'current'} role cannot change this.</p>;
  }
  if (active.id === undefined || cs.isError) {
    return (
      <div className="alert alert-info">
        <p>Edits are staged in a change set and go live only when it is published.</p>
        <button type="button" className="btn btn-small" onClick={() => { setOpen(true); }}>New change set</button>
        <NewChangeSetDialog open={open} onClose={() => { setOpen(false); }} />
      </div>
    );
  }
  return (
    <p className="alert alert-info">
      Staging into <Link to={`/changesets/${active.id}`}>{cs.data?.title ?? active.id}</Link>.
    </p>
  );
}

interface EditorProps {
  kind: KindDef;
  apiPath: string | undefined;
  key_: string | undefined;
  id: string | undefined;
  isNew: boolean;
  onCreated?: (id: string) => void;
  /** Singletons (settings) may not exist yet; editing then creates them. */
  allowMissing?: boolean;
}

/** Loads the live object and any version staged in the active change set, then shows the form. */
export function ObjectEditor(p: EditorProps) {
  const live = useObject(p.isNew ? undefined : p.apiPath);
  const active = useActiveChangeSet();
  const cs = useChangeSet(active.id);
  if (!p.isNew && (live.isPending || (active.id !== undefined && cs.isPending))) {
    return <Loading what={p.kind.singular} />;
  }
  if (live.isError) {
    return <ProblemAlert error={live.error} />;
  }
  const staged = cs.data?.items.find((o) => o.key === p.key_);
  if (!p.isNew && live.data === null && !staged && !p.allowMissing) {
    return <NotFound what={`${p.kind.singular} ${p.id ?? ''}`} />;
  }
  // Start from the staged version when this change set already edits the object.
  const source: unknown = staged?.action === 'PUT' ? staged.value : live.data;
  const env = isObject(source as JsonObject) ? source as Partial<StoredObject> : undefined;
  const initialSpec = p.isNew ? {} : isObject(env?.spec) ? env.spec : {};
  const initialTenant = typeof env?.tenantId === 'string' ? env.tenantId : undefined;
  return (
    <EditorForm {...p} live={live.data ?? undefined} staged={staged?.action} initialSpec={initialSpec}
      initialTenant={initialTenant} />
  );
}

function EditorForm(p: EditorProps & { live: StoredObject | undefined; staged: 'PUT' | 'DELETE' | undefined;
  initialSpec: JsonObject; initialTenant: string | undefined }) {
  const { kind, live } = p;
  const me = useMe();
  const canWrite = can(me.data?.role, kind.writeRole);
  const active = useActiveChangeSet();
  const stage = useStage();
  const [newId, setNewId] = useState<string | undefined>();
  const [spec, setSpec] = useState<JsonObject>(p.initialSpec);
  const [tenantId, setTenantId] = useState<string | undefined>(p.initialTenant);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [done, setDone] = useState<string | undefined>();
  const [localErrors, setLocalErrors] = useState(new Map<string, string[]>());

  const id = p.isNew ? newId : p.id;
  const key = p.isNew ? (newId ? `${kind.collection}/${newId}` : undefined) : p.key_;
  const serverErrors = useMemo(() => {
    const e = stage.error instanceof ApiError ? stage.error.problem.errors : undefined;
    return specErrors(e, key);
  }, [stage.error, key]);
  const errors = useMemo(() => new Map([...serverErrors, ...localErrors]), [serverErrors, localErrors]);

  const submit = (e: SyntheticEvent) => {
    e.preventDefault();
    const local = new Map<string, string[]>();
    if (p.isNew && (newId === undefined || !ID_PATTERN.test(newId))) {
      local.set('id', ['required: letters, digits, ".", "_" or "-", starting with a letter or digit (max 128)']);
    }
    setLocalErrors(local);
    if (local.size > 0 || active.id === undefined || id === undefined) {
      return;
    }
    stage.mutate({
      path: p.isNew ? `${kind.collection}/${encodeURIComponent(id)}` : p.apiPath ?? '',
      changeSet: active.id, ifMatch: live?.etag,
      body: { spec, ...(tenantId ? { tenantId } : {}) },
    }, {
      onSuccess: () => {
        setDone(`Staged ${kind.singular} ${id}.`);
        if (p.isNew) {
          p.onCreated?.(id);
        }
      },
    });
  };
  const remove = () => {
    if (active.id === undefined || p.apiPath === undefined) {
      return;
    }
    stage.mutate({ path: p.apiPath, changeSet: active.id, ifMatch: live?.etag }, {
      onSuccess: () => {
        setConfirmDelete(false);
        setDone(`Staged deletion of ${kind.singular} ${id ?? ''}.`);
      },
    });
  };
  const disabled = !canWrite || active.id === undefined || stage.isPending;
  const fieldErrors = stage.error instanceof ApiError && (stage.error.problem.errors?.length ?? 0) > 0;
  return (
    <>
      <StageBanner canWrite={canWrite} role={me.data?.role} />
      {p.staged && (
        <p className="alert alert-warn">
          This change set already stages a {p.staged === 'PUT' ? 'change to' : 'deletion of'} this object.
          {p.staged === 'PUT' ? ' The form shows the staged version.' : ''}
        </p>
      )}
      {live && (
        <p className="muted">
          Live revision {live.revision}, updated <Time at={live.updatedAt} />
          {live.updatedBy ? ` by ${live.updatedBy}` : ''}.
        </p>
      )}
      <ErrorSummary errors={errors} />
      {!fieldErrors && <ProblemAlert error={stage.error} />}
      {done && (
        <p className="alert alert-success" role="status">
          {done} <Link to={`/changesets/${active.id ?? ''}`}>Review the change set</Link>.
        </p>
      )}
      <form onSubmit={submit} noValidate>
        {p.isNew && (
          <TextInput label={kind.idIsPlugin ? 'Plugin name (ID)' : 'ID'} required value={newId} errors={errors.get('id')}
            disabled={disabled} maxLength={128} onChange={setNewId}
            hint={kind.idIsPlugin ? "The global plugin's ID is the plugin name, e.g. cors."
              : 'Unique within this collection; it cannot be changed later.'} />
        )}
        {kind.tenant && (
          <TextInput label="Tenant" value={tenantId} errors={errors.get('tenantId')} disabled={disabled}
            hint="Optional. Tenant-scoped objects are isolated from other tenants." onChange={setTenantId} />
        )}
        <ObjectForm kind={kind} id={id} spec={spec} errors={errors} disabled={disabled}
          onChange={(s) => { setSpec(s); setDone(undefined); }} />
        {canWrite && (
          <div className="actions">
            <button type="submit" className="btn btn-primary" disabled={disabled}>Stage {kind.singular}</button>
            {!p.isNew && live && (
              <button type="button" className="btn btn-danger" disabled={disabled}
                onClick={() => { setConfirmDelete(true); }}>Stage deletion</button>
            )}
          </div>
        )}
      </form>
      <Dialog open={confirmDelete} title={`Delete ${kind.singular} ${id ?? ''}?`}
        onClose={() => { setConfirmDelete(false); }}>
        <p>The deletion is staged in your change set; gateways keep the object until it is published.</p>
        <div className="actions">
          <button type="button" className="btn btn-danger" onClick={remove} disabled={stage.isPending}>
            Stage deletion
          </button>
          <button type="button" className="btn" onClick={() => { setConfirmDelete(false); }}>Cancel</button>
        </div>
      </Dialog>
    </>
  );
}

export function ObjectPage() {
  const { slug, id } = useParams();
  const kind = kindBySlug(slug);
  const navigate = useNavigate();
  if (!kind) {
    return <NotFound />;
  }
  const isNew = id === undefined;
  const apiPath = isNew ? undefined : `${kind.collection}/${encodeURIComponent(id)}`;
  return (
    <section aria-labelledby="page-title">
      <p className="crumbs"><Link to={`/objects/${kind.slug}`}>{kind.label}</Link></p>
      <h1 id="page-title">{isNew ? `New ${kind.singular}` : `${kind.singular[0]?.toUpperCase() ?? ''}${kind.singular.slice(1)} ${id}`}</h1>
      <ObjectEditor key={`${kind.slug}/${id ?? ''}`} kind={kind} apiPath={apiPath} isNew={isNew} id={id}
        key_={isNew ? undefined : `${kind.collection}/${id}`}
        onCreated={(newId) => { void navigate(`/objects/${kind.slug}/${encodeURIComponent(newId)}`, { replace: true }); }} />
      {kind.slug === 'consumers' && !isNew && <Credentials consumerId={id} />}
    </section>
  );
}
