import { request } from '@/utils/request';
import { getToken } from '@/utils/auth';

export type CanvasModelStatus = 'DRAFT' | 'PUBLISHED' | 'DISABLED';
export interface CanvasModelCategory {
  id: string;
  name: string;
  status: 'ACTIVE' | 'DISABLED' | 'DELETED';
  sortOrder: number;
}
export interface CanvasLibraryModel {
  id: string;
  modelId: string;
  versionNo: number;
  name: string;
  categoryId: string;
  categoryName?: string;
  tags?: string;
  status: CanvasModelStatus;
  storagePath: string;
  thumbnailPath: string;
  fileSize: number;
  metadataJson?: string;
  revision: number;
  createTime?: string;
}
export interface CanvasModelQuery {
  pageNum: number;
  pageSize: number;
  keyword?: string;
  categoryId?: string;
}
export type CanvasModelEdit = Pick<CanvasLibraryModel, 'id' | 'revision' | 'name' | 'categoryId' | 'tags' | 'status'>;
export interface CanvasModelPreview {
  id: string;
  fileUrl: string;
  thumbnailUrl?: string;
  metadata?: Record<string, unknown>;
}

const pending = new Map<string, Promise<unknown>>();
let session = getToken(),
  generation = 0;
const blobIds = new WeakMap<Blob, number>();
let blobSequence = 0;
function blobId(blob: Blob) {
  let id = blobIds.get(blob);
  if (!id) {
    id = ++blobSequence;
    blobIds.set(blob, id);
  }
  return id;
}
function call<T>(operation: string, data: object, payload: object = data): Promise<T> {
  const token = getToken();
  if (token !== session) {
    session = token;
    generation++;
  }
  const key = JSON.stringify([
    generation,
    operation,
    Object.fromEntries(Object.entries(data).sort(([left], [right]) => left.localeCompare(right)))
  ]);
  const active = pending.get(key);
  if (active) return active as Promise<T>;
  const task = request<T>({
    url: `/aid/canvas/model/${operation}`,
    method: 'post',
    data: payload,
    errorFeedback: 'caller',
    headers: { repeatSubmit: false, ...(payload instanceof FormData ? { 'Content-Type': undefined } : {}) },
    ...(payload instanceof FormData ? { timeout: 120000 } : {})
  })
    .then((response) => {
      if (token !== getToken()) throw new Error('登录身份已变化，请重新操作');
      return response.data;
    })
    .finally(() => {
      if (pending.get(key) === task) pending.delete(key);
    });
  pending.set(key, task);
  return task;
}
export const listCanvasModels = (query: CanvasModelQuery) => call<CanvasLibraryModel[]>('list', query);
export const listCanvasModelCategories = () => call<CanvasModelCategory[]>('categories', {});
export const getCanvasModel = (id: string) => call<CanvasLibraryModel>('detail', { id });
export const previewCanvasModel = (id: string) => call<CanvasModelPreview>('preview', { id });
export const editCanvasModel = (data: CanvasModelEdit) => call<CanvasLibraryModel>('edit', data);
export const removeCanvasModel = (data: CanvasModelEdit) => call<CanvasLibraryModel>('remove', data);
export const saveCanvasModelCategory = (data: Omit<CanvasModelCategory, 'id'> & { id?: string }) =>
  call<string>('category/save', data);
export function uploadCanvasModel(data: {
  name: string;
  categoryId: string;
  modelId?: string;
  file: File;
  thumbnail: File;
}) {
  const body = new FormData();
  body.set('name', data.name);
  body.set('categoryId', data.categoryId);
  if (data.modelId) body.set('modelId', data.modelId);
  body.set('file', data.file);
  body.set('thumbnail', data.thumbnail);
  return call<CanvasLibraryModel>(
    'upload',
    {
      name: data.name,
      categoryId: data.categoryId,
      modelId: data.modelId,
      file: blobId(data.file),
      thumbnail: blobId(data.thumbnail)
    },
    body
  );
}
