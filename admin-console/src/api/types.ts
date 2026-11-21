import type { Role } from '../roles';

export type Json = null | boolean | number | string | Json[] | { [k: string]: Json };
export type JsonObject = { [k: string]: Json };

export interface FieldError {
  path: string;
  message: string;
}
export interface Conflict {
  key: string;
  expectedRevision: number;
  actualRevision: number;
}
/** RFC 9457 problem details as returned by the Admin API. */
export interface Problem {
  type?: string;
  title: string;
  status: number;
  detail?: string;
  requestId?: string;
  errors?: FieldError[];
  conflicts?: Conflict[];
}

export interface Me {
  subject: string;
  role: Role;
  roles: Role[];
  permissions: Role[];
}

export interface StoredObject {
  key: string;
  revision: number;
  etag: string;
  id: string;
  kind: string;
  schemaVersion: number;
  tenantId?: string;
  updatedAt?: string;
  updatedBy?: string;
  spec: JsonObject;
}
export interface Page<T> {
  revision?: number;
  items: T[];
  nextCursor?: string;
}

export interface PluginInfo {
  name: string;
  version: string;
  priority: number;
  authenticates: boolean;
  title: string;
  description: string;
  phases: string[];
}

export type Action = 'PUT' | 'DELETE';
export interface ChangeSetSummary {
  id: string;
  title: string;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
  ops: number;
  etag: string;
}
export interface StagedOp {
  key: string;
  action: Action;
  baseRevision: number;
  stagedBy: string;
  value: Json;
}
export interface ChangeSet extends ChangeSetSummary {
  items: StagedOp[];
}
export interface Validation {
  valid: boolean;
  errors: FieldError[];
  conflicts: Conflict[];
}
export interface DiffEntry {
  key: string;
  action: Action;
  baseRevision: number;
  liveRevision: number;
  stale: boolean;
  before: Json;
  after: Json;
}
export interface Published {
  seq: number;
  revision: number;
  changeSetId: string;
}
export interface HistorySummary {
  seq: number;
  changeSetId: string;
  title: string;
  createdBy: string;
  publishedBy: string;
  publishedAt: string;
  rollbackOf?: number;
  ops: number;
}
export interface HistoryEntry extends Omit<HistorySummary, 'ops'> {
  ops: { key: string; action: Action; before: Json; after: Json }[];
}
export interface GatewayNode {
  gatewayId: string;
  version?: string;
  state?: string;
  appliedRevision?: number;
  source?: string;
  configError?: string;
  behind: boolean;
  heartbeatRevision: number;
}
export interface Gateways {
  configRevision: number;
  revision: number;
  items: GatewayNode[];
}
export interface AuditEvent {
  id: string;
  at: string;
  action: string;
  outcome: string;
  actor?: { subject?: string; role?: string | null };
  sourceIp?: string;
  requestId?: string;
  target?: string;
  changeSetId?: string;
  seq?: number;
  changes?: { key: string; action: string; beforeHash?: string | null; afterHash?: string | null }[];
  detail?: string;
}
export interface Staged {
  changeSet: { id: string; etag: string; ops: number };
  op: { key: string; action: Action; baseRevision: number };
}
export interface IssuedKey extends Staged {
  id: string;
  keyId: string;
  apiKey: string;
  note: string;
}
