import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createContext, useContext } from 'react';
import type { AdminClient } from './client';
import type {
  AuditEvent, ChangeSet, ChangeSetSummary, DiffEntry, Gateways, HistoryEntry, HistorySummary, IssuedKey, JsonObject,
  Me, Page, PluginInfo, Published, Staged, StoredObject, Validation,
} from './types';

export const ClientContext = createContext<AdminClient | null>(null);

export function useClient(): AdminClient {
  const c = useContext(ClientContext);
  if (c === null) {
    throw new Error('useClient outside ClientContext');
  }
  return c;
}

export const keys = {
  me: ['me'] as const,
  plugins: ['plugins'] as const,
  schema: (name: string) => ['plugins', name, 'schema'] as const,
  list: (collection: string, cursor?: string) => ['objects', collection, 'list', cursor ?? ''] as const,
  object: (path: string) => ['objects', 'item', path] as const,
  objects: ['objects'] as const,
  changeSets: ['changesets'] as const,
  changeSet: (id: string) => ['changesets', id] as const,
  diff: (id: string) => ['changesets', id, 'diff'] as const,
  history: ['history'] as const,
  entry: (seq: number) => ['history', seq] as const,
  gateways: ['gateways'] as const,
  audit: (before?: string) => ['audit', before ?? ''] as const,
};

export function useMe() {
  const c = useClient();
  return useQuery({ queryKey: keys.me, queryFn: async ({ signal }) => (await c.get<Me>('me', undefined, signal)).data,
    staleTime: 60_000 });
}

export function usePlugins() {
  const c = useClient();
  return useQuery({ queryKey: keys.plugins, staleTime: 300_000,
    queryFn: async ({ signal }) => (await c.get<Page<PluginInfo>>('plugins', undefined, signal)).data.items });
}

export function usePluginSchema(name: string | undefined) {
  const c = useClient();
  return useQuery({
    queryKey: keys.schema(name ?? ''), enabled: name !== undefined && name !== '', staleTime: 300_000,
    queryFn: async ({ signal }) => (await c.get<JsonObject>(`plugins/${encodeURIComponent(name ?? '')}/schema`,
      undefined, signal)).data,
  });
}

export function useList(collection: string, cursor?: string, limit = 100) {
  const c = useClient();
  return useQuery({
    queryKey: keys.list(collection, cursor), placeholderData: keepPreviousData,
    queryFn: async ({ signal }) => (await c.get<Page<StoredObject>>(collection, { limit, cursor }, signal)).data,
  });
}

/** One live object, or null when it does not exist (404). */
export function useObject(path: string | undefined) {
  const c = useClient();
  return useQuery({
    queryKey: keys.object(path ?? ''), enabled: path !== undefined,
    queryFn: async ({ signal }) => {
      try {
        return (await c.get<StoredObject>(path ?? '', undefined, signal)).data;
      } catch (e) {
        if (e instanceof Error && 'status' in e && (e as { status: number }).status === 404) {
          return null;
        }
        throw e;
      }
    },
  });
}

export function useChangeSets() {
  const c = useClient();
  return useQuery({ queryKey: keys.changeSets,
    queryFn: async ({ signal }) => (await c.get<Page<ChangeSetSummary>>('changesets', undefined, signal)).data.items });
}

export function useChangeSet(id: string | undefined) {
  const c = useClient();
  return useQuery({ queryKey: keys.changeSet(id ?? ''), enabled: id !== undefined,
    queryFn: async ({ signal }) => (await c.get<ChangeSet>(`changesets/${id ?? ''}`, undefined, signal)).data });
}

export function useDiff(id: string | undefined) {
  const c = useClient();
  return useQuery({ queryKey: keys.diff(id ?? ''), enabled: id !== undefined,
    queryFn: async ({ signal }) => (await c.get<Page<DiffEntry>>(`changesets/${id ?? ''}/diff`, undefined, signal))
      .data.items });
}

export function useHistory() {
  const c = useClient();
  return useQuery({ queryKey: keys.history,
    queryFn: async ({ signal }) => (await c.get<Page<HistorySummary>>('history', undefined, signal)).data.items });
}

export function useHistoryEntry(seq: number) {
  const c = useClient();
  return useQuery({ queryKey: keys.entry(seq),
    queryFn: async ({ signal }) => (await c.get<HistoryEntry>(`history/${seq}`, undefined, signal)).data });
}

export function useGateways() {
  const c = useClient();
  return useQuery({ queryKey: keys.gateways, refetchInterval: 5_000,
    queryFn: async ({ signal }) => (await c.get<Gateways>('gateways', undefined, signal)).data });
}

export function useAudit(before?: string) {
  const c = useClient();
  return useQuery({ queryKey: keys.audit(before), placeholderData: keepPreviousData,
    queryFn: async ({ signal }) => (await c.get<Page<AuditEvent> & { nextBefore?: string }>('audit',
      { limit: 50, before }, signal)).data });
}

// ------------------------------------------------------------------ mutations

function useInvalidate() {
  const qc = useQueryClient();
  return (changeSetId?: string) => {
    void qc.invalidateQueries({ queryKey: keys.changeSets });
    if (changeSetId !== undefined) {
      void qc.invalidateQueries({ queryKey: keys.changeSet(changeSetId) });
    }
  };
}

export function useCreateChangeSet() {
  const c = useClient();
  const inv = useInvalidate();
  return useMutation({
    mutationFn: async (title: string) => (await c.request<ChangeSet>('POST', 'changesets', { body: { title } })).data,
    onSuccess: () => {
      inv();
    },
  });
}

export interface StageInput {
  path: string;
  changeSet: string;
  ifMatch?: string | undefined;
  body?: { spec: JsonObject; tenantId?: string } | undefined;
}

export function useStage() {
  const c = useClient();
  const inv = useInvalidate();
  return useMutation({
    mutationFn: async (s: StageInput) => (await c.request<Staged>(s.body ? 'PUT' : 'DELETE', s.path, {
      body: s.body, ifMatch: s.ifMatch, query: { changeset: s.changeSet },
    })).data,
    onSuccess: (_d, s) => {
      inv(s.changeSet);
    },
  });
}

export function useIssueKey() {
  const c = useClient();
  const inv = useInvalidate();
  return useMutation({
    mutationFn: async (s: { consumerId: string; changeSet: string; tenantId?: string | undefined }) =>
      (await c.request<IssuedKey>('POST', `consumers/${s.consumerId}/credentials`, {
        body: { type: 'api-key', ...(s.tenantId ? { tenantId: s.tenantId } : {}) }, query: { changeset: s.changeSet },
      })).data,
    onSuccess: (_d, s) => {
      inv(s.changeSet);
    },
  });
}

export function useUnstage(changeSet: string) {
  const c = useClient();
  const inv = useInvalidate();
  return useMutation({
    mutationFn: async (key: string) =>
      (await c.request<ChangeSet>('DELETE', `changesets/${changeSet}/ops`, { query: { key } })).data,
    onSuccess: () => {
      inv(changeSet);
    },
  });
}

export function useDiscard() {
  const c = useClient();
  const inv = useInvalidate();
  return useMutation({
    mutationFn: async (s: { id: string; etag: string }) => {
      await c.request<null>('DELETE', `changesets/${s.id}`, { ifMatch: s.etag });
    },
    onSuccess: () => {
      inv();
    },
  });
}

export function useValidate(changeSet: string) {
  const c = useClient();
  return useMutation({
    mutationFn: async () => (await c.request<Validation>('POST', `changesets/${changeSet}/validate`)).data,
  });
}

function usePublished() {
  const qc = useQueryClient();
  return () => {
    for (const k of [keys.objects, keys.changeSets, keys.history, keys.gateways]) {
      void qc.invalidateQueries({ queryKey: k });
    }
  };
}

export function usePublish() {
  const c = useClient();
  const done = usePublished();
  return useMutation({
    mutationFn: async (s: { id: string; etag: string }) =>
      (await c.request<Published>('POST', `changesets/${s.id}/publish`, { ifMatch: s.etag })).data,
    onSuccess: done,
  });
}

export function useRollback() {
  const c = useClient();
  const done = usePublished();
  return useMutation({
    mutationFn: async (seq: number) => (await c.request<Published>('POST', `history/${seq}/rollback`)).data,
    onSuccess: done,
  });
}
