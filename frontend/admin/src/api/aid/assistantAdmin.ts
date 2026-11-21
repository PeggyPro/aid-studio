import { getToken } from '@/utils/auth';
import { request } from '@/utils/request';
import type { ApiResponse } from '@/types/common';

export type EntityId = string | number;

export const ASSISTANT_ADMIN_PERMISSIONS = Object.freeze({
  runList: 'aid:assistant:run:list',
  runQuery: 'aid:assistant:run:query',
  toolQuery: 'aid:assistant:tool:query',
  budgetList: 'aid:assistant:budget:list',
  budgetQuery: 'aid:assistant:budget:query',
  inputList: 'aid:assistant:input:list',
  inputQuery: 'aid:assistant:input:query'
});

export interface AssistantPage<T> {
  items: T[];
  total: number;
  pageNum: number;
  pageSize: number;
}

export interface PageQuery {
  pageNum: number;
  pageSize: number;
}

export interface RunQuery extends PageQuery {
  runId?: EntityId;
  sessionId?: EntityId;
  userId?: EntityId;
  moduleCode?: string;
  mode?: string;
  status?: string;
  traceId?: string;
  createStartTime?: string;
  createEndTime?: string;
}

export interface AssistantRunSummary {
  id: EntityId;
  sessionId?: EntityId | null;
  turnId?: EntityId | null;
  userId: EntityId;
  moduleCode?: string | null;
  mode?: string | null;
  executorStrategy?: string | null;
  status: string;
  stateRevision: number;
  planRevision: number;
  pauseReason?: string | null;
  resumeStatus?: string | null;
  stopRequested?: boolean;
  nextAction?: string | null;
  nextRetryAt?: string | null;
  createTime?: string | null;
  updateTime?: string | null;
}

export interface AssistantEventSummary {
  id: EntityId;
  seq: number;
  type: string;
  entityId?: string | null;
  entityRevision?: number | null;
  causationId?: string | null;
  createTime?: string | null;
}

export interface InputRequestSummary {
  id: EntityId;
  runId: EntityId;
  userId?: EntityId | null;
  requestKey: string;
  roundNo: number;
  schemaDigest: string;
  contextDigest: string;
  planRevision: number;
  status: string;
  questionCount: number;
  answeredCount: number;
  unansweredCount: number;
  suspendedAt?: string | null;
  expiresAt?: string | null;
  createTime?: string | null;
  updateTime?: string | null;
}

export interface AssistantRunDetail {
  run: AssistantRunSummary;
  recentEvents: AssistantEventSummary[];
  inputRequests: InputRequestSummary[];
}

export type ToolSideEffectLevel = 'PURE' | 'READ' | 'DATABASE_WRITE' | 'BILLABLE';

export interface AssistantToolCatalogItem {
  code: string;
  contractVersion: string;
  schemaDigest: string;
  schemaVersion: number;
  name: string;
  description?: string;
  inputSchemaSummary?: string;
  outputSchemaSummary?: string;
  requiredPermission?: string | null;
  objectScopes: string[];
  sideEffectLevel: ToolSideEffectLevel;
  billable: boolean;
  idempotencyLevel: string;
  cancellable: boolean;
  recoveryPolicy: string;
  requiredCapabilities: string[];
  enabled: boolean;
  disabledReason?: string | null;
  implementationVersion: string;
}

export interface ToolCatalogQuery extends PageQuery {
  keyword?: string;
  sideEffectLevel?: ToolSideEffectLevel;
  billable?: boolean;
  enabled?: boolean;
}

export interface BudgetQuery extends PageQuery {
  authorizationId?: EntityId;
  runId?: EntityId;
  payerUserId?: EntityId;
  status?: string;
}

export interface BudgetAuthorizationSummary {
  id: EntityId;
  runId: EntityId;
  payerUserId: EntityId;
  revision: number;
  approvedTotal: string;
  reservedAmount: string;
  settledAmount: string;
  currencyUnit: string;
  status: string;
  expiresAt?: string | null;
  policyDigest?: string | null;
  createTime?: string | null;
  updateTime?: string | null;
}

export interface BudgetReservationSummary {
  id: EntityId;
  authorizationRevision: number;
  logicalCallKey: string;
  attemptNo: number;
  operationKind: string;
  approvalId?: EntityId | null;
  runtimeStepId?: EntityId | null;
  mediaTaskId?: EntityId | null;
  billingTraceId?: string | null;
  requestDigest?: string | null;
  quoteDigest?: string | null;
  quotedUpperBound: string;
  reservedAmount: string;
  walletFrozenAmount: string;
  settledAmount: string;
  releasedAmount: string;
  walletRefundedAmount: string;
  state: string;
  settlementKey?: string | null;
  createTime?: string | null;
  updateTime?: string | null;
}

export interface BudgetAuthorizationDetail {
  authorization: BudgetAuthorizationSummary;
  reservations: BudgetReservationSummary[];
}

export interface InputQuery extends PageQuery {
  requestId?: EntityId;
  runId?: EntityId;
  userId?: EntityId;
  requestKey?: string;
  status?: string;
  createStartTime?: string;
  createEndTime?: string;
}

export interface InputQuestionTrace {
  questionId: string;
  kind?: string | null;
  required: boolean;
  answerState: string;
  draftRevision: number;
  selectedCount: number;
  freeTextLength: number;
  freeTextPreviewRedacted?: string | null;
  updateTime?: string | null;
}

export interface InputRequestDetail {
  request: InputRequestSummary;
  questions: InputQuestionTrace[];
}

const pending = new Map<string, Promise<ApiResponse<unknown>>>();
const recent = new Map<string, { expiresAt: number; value: ApiResponse<unknown> }>();
let activeToken = getToken();

function ordered(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(ordered);
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value)
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([key, item]) => [key, ordered(item)]));
  return value;
}

function read<T>(path: string, data: unknown, force = false): Promise<ApiResponse<T>> {
  const token = getToken();
  if (token !== activeToken) {
    activeToken = token;
    pending.clear();
    recent.clear();
  }
  const key = `${path}:${JSON.stringify(ordered(data))}`;
  const running = pending.get(key);
  if (running) return running as Promise<ApiResponse<T>>;
  const cached = recent.get(key);
  if (!force && cached && cached.expiresAt > Date.now()) {
    return Promise.resolve(cached.value as ApiResponse<T>);
  }
  const task = request<T>({ url: `/aid/assistant/admin${path}`, method: 'post', data })
    .then((response) => {
      if (token === getToken()) recent.set(key, { expiresAt: Date.now() + 800, value: response });
      return response;
    })
    .finally(() => {
      if (pending.get(key) === task) pending.delete(key);
    });
  pending.set(key, task as Promise<ApiResponse<unknown>>);
  return task;
}

export function listAssistantRuns(data: RunQuery, force = false) {
  return read<AssistantPage<AssistantRunSummary>>('/run/list', data, force);
}

export function getAssistantRunDetail(runId: EntityId, force = false) {
  return read<AssistantRunDetail>('/run/detail', { runId }, force);
}

export function listAssistantTools(data: ToolCatalogQuery, force = false) {
  return read<AssistantPage<AssistantToolCatalogItem>>('/tool/catalog', data, force);
}

export function listAssistantBudgets(data: BudgetQuery, force = false) {
  return read<AssistantPage<BudgetAuthorizationSummary>>('/budget/list', data, force);
}

export function getAssistantBudgetDetail(authorizationId: EntityId, force = false) {
  return read<BudgetAuthorizationDetail>('/budget/detail', { authorizationId }, force);
}

export function listAssistantInputs(data: InputQuery, force = false) {
  return read<AssistantPage<InputRequestSummary>>('/input/list', data, force);
}

export function getAssistantInputDetail(requestId: EntityId, force = false) {
  return read<InputRequestDetail>('/input/detail', { requestId }, force);
}
