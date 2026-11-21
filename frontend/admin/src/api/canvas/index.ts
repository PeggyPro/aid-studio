import { request } from '@/utils/request';
import { createSingleFlight } from '@/utils/singleFlight';
import { invalidateSharedReads, sharedReadRequest } from '@/utils/sharedReadRequest';
import { getToken } from '@/utils/auth';

export type EntityId = number | string;

export interface CanvasProjectRow {
  projectId: EntityId;
  projectName?: string | null;
  ownerUserId: EntityId;
  ownerName?: string | null;
  status?: number | string | null;
  workspaceId?: EntityId | null;
  workspaceType?: string | null;
  schemaVersion?: number | null;
  revision?: number | null;
  nodeCount?: number | null;
  edgeCount?: number | null;
  groupCount?: number | null;
  memberCount?: number | null;
  onlineCount?: number | null;
  storageBytes?: number | null;
  updateTime?: string | null;
}

export interface CanvasCollaborationConfig {
  collaborationEnabled: boolean;
  maxProjectsPerOwner: number;
  maxNodesPerWorkspace: number;
  maxMembersPerProject: number;
  maxOnlineEditors: number;
  maxConnectionsPerUser: number;
  operationBatchMax: number;
  operationPayloadMaxBytes: number;
  lockLeaseSeconds: number;
  lockRenewSeconds: number;
  textMergeMillis: number;
  inviteDefaultDays: number;
  inviteDefaultUses: number;
}

export type EntitlementValueType = 'BOOLEAN' | 'INTEGER' | 'STRING' | string;

export interface CanvasEntitlementItem {
  entitlementCode: string;
  name?: string | null;
  valueType: EntitlementValueType;
  value?: string | number | boolean | null;
  enabled: boolean;
  description?: string | null;
}

export type EntitlementOverrideMode = 'ADD' | 'OVERRIDE' | 'DENY';

export interface CanvasEntitlementOverrideRow {
  id: EntityId;
  userId: EntityId;
  userName?: string | null;
  entitlementCode: string;
  mode: EntitlementOverrideMode;
  value?: string | number | null;
  startTime?: string | null;
  endTime?: string | null;
  status?: string | null;
  reason?: string | null;
  createBy?: string | null;
  createTime?: string | null;
  revokeBy?: string | null;
  revokeTime?: string | null;
  revokeReason?: string | null;
}

export interface CanvasSessionRow {
  sessionId: string;
  projectId: EntityId;
  projectName?: string | null;
  workspaceId: EntityId;
  userId: EntityId;
  userName?: string | null;
  connectedAt?: string | null;
  lastHeartbeatAt?: string | null;
  clientIp?: string | null;
  status?: string | null;
}

export interface CanvasLockRow {
  workspaceId: EntityId;
  projectId?: EntityId | null;
  projectName?: string | null;
  objectId: string;
  lockType: 'EDIT' | 'TASK';
  userId: EntityId;
  userName?: string | null;
  sessionId?: string | null;
  acquiredAt?: string | null;
  expireAt?: string | null;
  taskId?: EntityId | null;
}

export interface CanvasAuditRow {
  id: EntityId;
  operator?: string | null;
  operatorName?: string | null;
  targetType?: string | null;
  targetId?: EntityId | null;
  targetUserId?: EntityId | null;
  action?: string | null;
  detail?: string | null;
  createTime?: string | null;
}

const API_PREFIX = '/aid/canvas';
const runCanvasMutation = createSingleFlight<string>();
const mutationKey = (businessKey: string) => `${getToken() || 'anonymous'}:${businessKey}`;

export function listCanvasProjects(query: Record<string, unknown>, options?: { force?: boolean }) {
  return sharedReadRequest(`${API_PREFIX}/project/list`, query, options?.force);
}

export function getCanvasProject(projectId: EntityId, force = false) {
  return sharedReadRequest(`${API_PREFIX}/project/${projectId}`, undefined, force);
}

export function getCanvasConfig(force = false) {
  return sharedReadRequest(`${API_PREFIX}/config`, undefined, force);
}

export async function updateCanvasConfig(data: CanvasCollaborationConfig) {
  return runCanvasMutation(mutationKey('config:update'), async () => {
    const result = await request({ url: `${API_PREFIX}/config`, method: 'put', data });
    invalidateSharedReads(API_PREFIX);
    return result;
  });
}

export function getDefaultEntitlements(force = false) {
  return sharedReadRequest(`${API_PREFIX}/entitlement/default`, undefined, force);
}

export async function updateDefaultEntitlements(items: CanvasEntitlementItem[]) {
  return runCanvasMutation(mutationKey('entitlement:default:update'), async () => {
    const result = await request({
      url: `${API_PREFIX}/entitlement/default`,
      method: 'put',
      data: {
        items: items.map((item) => ({
          entitlementCode: item.entitlementCode,
          valueType: item.valueType,
          value: String(item.value),
          enabled: item.enabled
        }))
      }
    });
    invalidateSharedReads(API_PREFIX);
    return result;
  });
}

export function listEntitlementOverrides(query: Record<string, unknown>, force = false) {
  return sharedReadRequest(`${API_PREFIX}/entitlement/override/list`, query, force);
}

export async function addEntitlementOverride(data: Record<string, unknown>) {
  const key = mutationKey(`entitlement:override:add:${String(data.userId)}:${String(data.entitlementCode)}`);
  return runCanvasMutation(key, async () => {
    const result = await request({
      url: `${API_PREFIX}/entitlement/override`,
      method: 'post',
      data
    });
    invalidateSharedReads(API_PREFIX);
    return result;
  });
}

export async function revokeEntitlementOverride(data: { id: EntityId; reason: string }) {
  return runCanvasMutation(mutationKey(`entitlement:override:revoke:${String(data.id)}`), async () => {
    const result = await request({
      url: `${API_PREFIX}/entitlement/override/revoke`,
      method: 'post',
      data
    });
    invalidateSharedReads(API_PREFIX);
    return result;
  });
}

export function listCanvasSessions(query: Record<string, unknown>, force = false) {
  return sharedReadRequest(`${API_PREFIX}/session/list`, query, force);
}

export function listCanvasLocks(query: Record<string, unknown>, force = false) {
  return sharedReadRequest(`${API_PREFIX}/lock/list`, query, force);
}

export async function terminateCanvasSession(data: { sessionId: string; reason: string }) {
  return runCanvasMutation(mutationKey(`session:terminate:${data.sessionId}`), async () => {
    const result = await request({
      url: `${API_PREFIX}/session/terminate`,
      method: 'post',
      data
    });
    invalidateSharedReads(API_PREFIX);
    return result;
  });
}

export function listCanvasAudits(query: Record<string, unknown>, force = false) {
  return sharedReadRequest(`${API_PREFIX}/audit/list`, query, force);
}
