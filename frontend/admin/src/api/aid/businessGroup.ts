import { request } from '@/utils/request';

export type BusinessGroupId = string | number;

export interface BusinessGroup {
  id: BusinessGroupId;
  groupCode: string;
  parentId?: BusinessGroupId | null;
  name: string;
  iconUrl?: string;
  description?: string;
  sortOrder: number;
  visibility: 'PUBLIC' | 'PRIVATE';
  status: '0' | '1';
  ownerUserId?: BusinessGroupId;
}

export interface BusinessGroupMember {
  id: BusinessGroupId;
  groupId: BusinessGroupId;
  memberType: 'SKILL' | 'AGENT';
  memberId: BusinessGroupId;
  sortOrder: number;
  memberName?: string;
  memberCode?: string;
  memberStatus?: string;
  currentVersionCode?: string;
}

export interface GroupPageQuery {
  pageNum: number;
  pageSize: number;
  keyword?: string;
  parentCode?: string;
  enabledOnly?: boolean;
}

type GroupSave = Omit<BusinessGroup, 'ownerUserId' | 'id'> & { id?: BusinessGroupId };

const pending = new Map<string, Promise<unknown>>();
const cache = new Map<string, { value: unknown; expires: number }>();
function read<T>(key: string, load: () => Promise<T>, force = false): Promise<T> {
  const running = pending.get(key);
  if (running) return running as Promise<T>;
  const stored = cache.get(key);
  if (!force && stored && stored.expires > Date.now()) return Promise.resolve(stored.value as T);
  const task = load().then((value) => {
    cache.set(key, { value, expires: Date.now() + 800 });
    return value;
  }).finally(() => { if (pending.get(key) === task) pending.delete(key); });
  pending.set(key, task);
  return task;
}

const mutations = new Map<string, Promise<unknown>>();
function mutate<T>(key: string, load: () => Promise<T>): Promise<T> {
  const running = mutations.get(key);
  if (running) return running as Promise<T>;
  const task = load().then(async (value) => {
    await Promise.allSettled([...pending.values()]);
    cache.clear();
    return value;
  })
    .finally(() => { if (mutations.get(key) === task) mutations.delete(key); });
  mutations.set(key, task);
  return task;
}

export function listBusinessGroups(data: GroupPageQuery, force = false) {
  return read(`group:list:${JSON.stringify(data)}`,
    () => request<BusinessGroup[]>({ url: '/aid/business-group/list', method: 'post', data }), force);
}

export function saveBusinessGroup(data: GroupSave) {
  return mutate(`group:save:${JSON.stringify(data)}`,
    () => request<BusinessGroup>({ url: '/aid/business-group/save', method: 'post', data }));
}

export function removeBusinessGroup(id: BusinessGroupId) {
  return mutate(`group:remove:${id}`,
    () => request({ url: '/aid/business-group/remove', method: 'post', data: { id },
      suppressConflictToast: true }));
}

export function listBusinessGroupMembers(groupId: BusinessGroupId, pageNum: number, pageSize: number, force = false) {
  const data = { groupId, pageNum, pageSize };
  return read(`group:members:${JSON.stringify(data)}`,
    () => request<BusinessGroupMember[]>({ url: '/aid/business-group/member/list', method: 'post', data }), force);
}

export function saveBusinessGroupMember(data: Pick<BusinessGroupMember, 'groupId' | 'memberType' | 'memberId' | 'sortOrder'>) {
  return mutate(`group:member:save:${JSON.stringify(data)}`,
    () => request<BusinessGroupMember>({ url: '/aid/business-group/member/save', method: 'post', data }));
}

export function removeBusinessGroupMember(data: Pick<BusinessGroupMember, 'groupId' | 'memberType' | 'memberId'>) {
  return mutate(`group:member:remove:${data.groupId}:${data.memberType}:${data.memberId}`,
    () => request({ url: '/aid/business-group/member/remove', method: 'post', data }));
}
