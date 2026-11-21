import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Alert, Button, Card, Descriptions, Drawer, Empty, Form, Input, Select, Space,
  Table, Tabs, Tag, Typography, message
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { EyeOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons';

import {
  ASSISTANT_ADMIN_PERMISSIONS, getAssistantBudgetDetail, getAssistantInputDetail,
  getAssistantRunDetail, listAssistantBudgets, listAssistantInputs, listAssistantRuns,
  listAssistantTools, type AssistantEventSummary, type AssistantRunDetail,
  type AssistantRunSummary, type AssistantToolCatalogItem, type BudgetAuthorizationDetail,
  type BudgetAuthorizationSummary, type BudgetReservationSummary, type EntityId,
  type InputQuestionTrace, type InputRequestDetail, type InputRequestSummary
} from '@/api/aid/assistantAdmin';
import PageHeader from '@/components/PageHeader';
import { useAuth } from '@/hooks/useAuth';
import AgentVersionPanel from './AgentVersionPanel';
import './style.less';

const { Text } = Typography;

function textId(value: EntityId | null | undefined) {
  return value === null || value === undefined || value === '' ? '—' : String(value);
}

function statusColor(status?: string) {
  if (!status) return 'default';
  if (['SUCCEEDED', 'ACTIVE', 'SETTLED', 'SUBMITTED', 'CONSUMED', 'ANSWERED'].includes(status)) return 'success';
  if (['FAILED', 'REVOKED', 'EXPIRED', 'STALE', 'WITHDRAWN'].includes(status)) return 'error';
  if (['RUNNING', 'INFLIGHT', 'RESERVED', 'DRAFTING', 'OPEN'].includes(status)) return 'processing';
  if (['WAITING_APPROVAL', 'WAITING_INPUT', 'PAUSING', 'RECONCILING', 'RESERVING'].includes(status)) return 'warning';
  return 'default';
}

function StatusTag({ value }: { value?: string | null }) {
  return value ? <Tag color={statusColor(value)}>{value}</Tag> : <Text type="secondary">—</Text>;
}

function Denied({ label }: { label: string }) {
  return <Alert type="warning" showIcon message={`没有${label}权限`}
    description="当前账号不会发起该区域的后台查询。请由管理员分配对应的只读权限。" />;
}

function ListError({ error, retry }: { error: string; retry: () => void }) {
  return error ? <Alert type="error" showIcon message="读取失败" description={error}
    action={<Button onClick={retry}>重试</Button>} /> : null;
}

function SearchActions({ loading, onReset }: { loading: boolean; onReset: () => void }) {
  return <Space>
    <Button type="primary" htmlType="submit" icon={<SearchOutlined />} loading={loading}>查询</Button>
    <Button htmlType="button" onClick={onReset}>重置</Button>
  </Space>;
}

function RunPanel({ active }: { active: boolean }) {
  const { hasPermi } = useAuth();
  const canList = hasPermi(ASSISTANT_ADMIN_PERMISSIONS.runList);
  const canQuery = hasPermi(ASSISTANT_ADMIN_PERMISSIONS.runQuery);
  const [form] = Form.useForm();
  const [query, setQuery] = useState<Record<string, unknown>>({});
  const [items, setItems] = useState<AssistantRunSummary[]>([]);
  const [total, setTotal] = useState(0);
  const [pageNum, setPageNum] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [detail, setDetail] = useState<AssistantRunDetail>();
  const [detailLoading, setDetailLoading] = useState(false);
  const sequence = useRef(0);

  const load = useCallback(async (force = false) => {
    if (!canList) return;
    const current = ++sequence.current;
    setLoading(true); setError('');
    try {
      const response = await listAssistantRuns({ pageNum, pageSize, ...query }, force);
      if (current !== sequence.current) return;
      setItems(response.data?.items || []);
      setTotal(response.data?.total || 0);
    } catch (cause: any) {
      if (current === sequence.current) setError(cause?.message || '运行记录读取失败');
    } finally {
      if (current === sequence.current) setLoading(false);
    }
  }, [canList, pageNum, pageSize, query]);

  useEffect(() => { if (active) void load(); }, [active, load]);

  const openDetail = async (id: EntityId) => {
    setDetailLoading(true);
    try {
      const response = await getAssistantRunDetail(id, true);
      setDetail(response.data);
    } catch (cause: any) {
      message.error(cause?.message || '运行详情读取失败');
    } finally { setDetailLoading(false); }
  };

  const columns: ColumnsType<AssistantRunSummary> = [
    { title: 'Run ID', dataIndex: 'id', width: 165, render: textId },
    { title: '会话 / 回合', width: 220, render: (_, row) => <Space direction="vertical" size={0}>
      <Text>会话 {textId(row.sessionId)}</Text><Text type="secondary">回合 {textId(row.turnId)}</Text>
    </Space> },
    { title: '用户', dataIndex: 'userId', width: 150, render: textId },
    { title: '模块', width: 150, render: (_, row) => <Space size={4} wrap>
      {row.moduleCode && <Tag>{row.moduleCode}</Tag>}{row.mode && <Tag>{row.mode}</Tag>}
    </Space> },
    { title: '状态', dataIndex: 'status', width: 170, render: (value) => <StatusTag value={value} /> },
    { title: '版本', width: 110, render: (_, row) => <Text>状态 {row.stateRevision}<br />计划 {row.planRevision}</Text> },
    { title: '下一动作', dataIndex: 'nextAction', width: 190, render: (value) => value || '—' },
    { title: '更新时间', dataIndex: 'updateTime', width: 175, render: (value) => value || '—' },
    { title: '操作', fixed: 'right', width: 86, render: (_, row) => <Button type="link" icon={<EyeOutlined />}
      disabled={!canQuery} loading={detailLoading && detail?.run.id === row.id}
      onClick={() => void openDetail(row.id)}>详情</Button> }
  ];

  if (!canList) return <Denied label="运行审计列表" />;
  return <div className="assistant-admin__panel">
    <Card size="small">
      <Form form={form} layout="inline" onFinish={(values) => { setPageNum(1); setQuery(values); }}>
        <Form.Item name="runId" label="Run ID"><Input allowClear /></Form.Item>
        <Form.Item name="sessionId" label="会话 ID"><Input allowClear /></Form.Item>
        <Form.Item name="userId" label="用户 ID"><Input allowClear /></Form.Item>
        <Form.Item name="moduleCode" label="模块"><Select allowClear style={{ width: 150 }} options={[
          { value: 'UNIVERSAL', label: 'UNIVERSAL' }, { value: 'SCREENWRITER', label: 'SCREENWRITER' }
        ]} /></Form.Item>
        <Form.Item name="status" label="状态"><Input allowClear /></Form.Item>
        <SearchActions loading={loading} onReset={() => { form.resetFields(); setPageNum(1); setQuery({}); }} />
      </Form>
    </Card>
    <ListError error={error} retry={() => void load(true)} />
    <Card size="small" title="持久化运行记录" extra={<Button icon={<ReloadOutlined />} onClick={() => void load(true)}>刷新</Button>}>
      <Table rowKey={(row) => String(row.id)} columns={columns} dataSource={items} loading={loading}
        scroll={{ x: 1400 }} pagination={{ current: pageNum, pageSize, total, showSizeChanger: true,
          showTotal: (count) => `共 ${count} 条`, onChange: (page, size) => { setPageNum(page); setPageSize(size); } }} />
    </Card>
    <Drawer title={`运行详情 · ${textId(detail?.run.id)}`} width="min(920px, 100vw)" open={Boolean(detail)}
      onClose={() => setDetail(undefined)} destroyOnClose loading={detailLoading}>
      {detail && <Space direction="vertical" size={16} className="assistant-admin__drawer-body">
        <Alert type="info" showIcon message="详情读取已写入敏感查询审计；事件 payload、请求正文和模型结果均不返回。" />
        <Descriptions bordered size="small" column={{ xs: 1, sm: 2 }}>
          <Descriptions.Item label="Run ID">{textId(detail.run.id)}</Descriptions.Item>
          <Descriptions.Item label="用户 ID">{textId(detail.run.userId)}</Descriptions.Item>
          <Descriptions.Item label="会话 ID">{textId(detail.run.sessionId)}</Descriptions.Item>
          <Descriptions.Item label="回合 ID">{textId(detail.run.turnId)}</Descriptions.Item>
          <Descriptions.Item label="执行策略">{detail.run.executorStrategy || '—'}</Descriptions.Item>
          <Descriptions.Item label="状态"><StatusTag value={detail.run.status} /></Descriptions.Item>
          <Descriptions.Item label="暂停原因">{detail.run.pauseReason || '—'}</Descriptions.Item>
          <Descriptions.Item label="恢复状态">{detail.run.resumeStatus || '—'}</Descriptions.Item>
          <Descriptions.Item label="停止请求">{detail.run.stopRequested ? '是' : '否'}</Descriptions.Item>
          <Descriptions.Item label="下次重试">{detail.run.nextRetryAt || '—'}</Descriptions.Item>
        </Descriptions>
        <section aria-labelledby="assistant-run-events"><Typography.Title id="assistant-run-events" level={5}>最近事件</Typography.Title>
          <Table size="small" pagination={false} rowKey={(row) => String(row.id)} dataSource={detail.recentEvents}
            columns={eventColumns} scroll={{ x: 700 }} /></section>
        <section aria-labelledby="assistant-run-inputs"><Typography.Title id="assistant-run-inputs" level={5}>输入请求</Typography.Title>
          <Table size="small" pagination={false} rowKey={(row) => String(row.id)} dataSource={detail.inputRequests}
            columns={inputSummaryColumns} scroll={{ x: 850 }} /></section>
      </Space>}
    </Drawer>
  </div>;
}

const eventColumns: ColumnsType<AssistantEventSummary> = [
  { title: '序号', dataIndex: 'seq', width: 90 },
  { title: '类型', dataIndex: 'type', width: 190 },
  { title: '实体', dataIndex: 'entityId', width: 150, render: textId },
  { title: '实体版本', dataIndex: 'entityRevision', width: 100, render: (value) => value ?? '—' },
  { title: '因果标识', dataIndex: 'causationId', width: 180, render: (value) => value || '—' },
  { title: '发生时间', dataIndex: 'createTime', width: 175, render: (value) => value || '—' }
];

const inputSummaryColumns: ColumnsType<InputRequestSummary> = [
  { title: '请求 ID', dataIndex: 'id', width: 150, render: textId },
  { title: '请求键', dataIndex: 'requestKey', width: 160 },
  { title: '轮次', dataIndex: 'roundNo', width: 70 },
  { title: '状态', dataIndex: 'status', width: 130, render: (value) => <StatusTag value={value} /> },
  { title: '问题', width: 150, render: (_, row) => `${row.answeredCount}/${row.questionCount} 已答` },
  { title: '计划版本', dataIndex: 'planRevision', width: 100 },
  { title: '更新时间', dataIndex: 'updateTime', width: 175, render: (value) => value || '—' }
];

function ToolPanel({ active }: { active: boolean }) {
  const { hasPermi } = useAuth();
  const allowed = hasPermi(ASSISTANT_ADMIN_PERMISSIONS.toolQuery);
  const [form] = Form.useForm();
  const [query, setQuery] = useState<Record<string, unknown>>({});
  const [items, setItems] = useState<AssistantToolCatalogItem[]>([]);
  const [total, setTotal] = useState(0);
  const [pageNum, setPageNum] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const sequence = useRef(0);
  const load = useCallback(async (force = false) => {
    if (!allowed) return;
    const current = ++sequence.current; setLoading(true); setError('');
    try {
      const response = await listAssistantTools({ pageNum, pageSize, ...query }, force);
      if (current !== sequence.current) return;
      setItems(response.data?.items || []); setTotal(response.data?.total || 0);
    } catch (cause: any) { if (current === sequence.current) setError(cause?.message || 'Tool 目录读取失败'); }
    finally { if (current === sequence.current) setLoading(false); }
  }, [allowed, pageNum, pageSize, query]);
  useEffect(() => { if (active) void load(); }, [active, load]);
  if (!allowed) return <Denied label="Tool 目录查询" />;
  const columns: ColumnsType<AssistantToolCatalogItem> = [
    { title: 'Tool', width: 250, render: (_, row) => <Space direction="vertical" size={0}><Text strong>{row.name}</Text><Text code>{row.code}</Text></Space> },
    { title: '契约版本', width: 130, render: (_, row) => `${row.contractVersion} / schema v${row.schemaVersion}` },
    { title: '作用级别', dataIndex: 'sideEffectLevel', width: 115, render: (value) => <Tag>{value}</Tag> },
    { title: '权限与范围', width: 220, render: (_, row) => <Space direction="vertical" size={0}><Text>{row.requiredPermission || '无需额外权限'}</Text><Text type="secondary">{row.objectScopes.join('、') || '无对象范围'}</Text></Space> },
    { title: '执行约束', width: 220, render: (_, row) => <Space wrap size={[4, 4]}><Tag>{row.idempotencyLevel}</Tag><Tag>{row.recoveryPolicy}</Tag>{row.cancellable && <Tag>可取消</Tag>}{row.billable && <Tag color="warning">计费</Tag>}</Space> },
    { title: '能力依赖', dataIndex: 'requiredCapabilities', width: 190, render: (values: string[]) => values?.join('、') || '—' },
    { title: '状态', width: 220, render: (_, row) => <Space direction="vertical" size={0}><Tag color={row.enabled ? 'success' : 'default'}>{row.enabled ? '已启用' : '未启用'}</Tag>{!row.enabled && <Text type="secondary">{row.disabledReason || '运行时未开放'}</Text>}</Space> }
  ];
  return <div className="assistant-admin__panel">
    <Alert type="info" showIcon message="目录只展示服务端注册的受控 Tool；此页面不提供脚本上传或执行入口。" />
    <Card size="small"><Form form={form} layout="inline" onFinish={(values) => { setPageNum(1); setQuery(values); }}>
      <Form.Item name="keyword" label="关键字"><Input allowClear /></Form.Item>
      <Form.Item name="sideEffectLevel" label="作用级别"><Select allowClear style={{ width: 180 }} options={['PURE', 'READ', 'DATABASE_WRITE', 'BILLABLE'].map((value) => ({ value, label: value }))} /></Form.Item>
      <Form.Item name="enabled" label="启用状态"><Select allowClear style={{ width: 130 }} options={[{ value: true, label: '已启用' }, { value: false, label: '未启用' }]} /></Form.Item>
      <SearchActions loading={loading} onReset={() => { form.resetFields(); setPageNum(1); setQuery({}); }} />
    </Form></Card>
    <ListError error={error} retry={() => void load(true)} />
    <Card size="small" title="服务端注册目录" extra={<Button icon={<ReloadOutlined />} onClick={() => void load(true)}>刷新</Button>}>
      <Table rowKey="code" columns={columns} dataSource={items} loading={loading} scroll={{ x: 1320 }}
        pagination={{ current: pageNum, pageSize, total, showSizeChanger: true, showTotal: (count) => `共 ${count} 项`, onChange: (page, size) => { setPageNum(page); setPageSize(size); } }} />
    </Card>
  </div>;
}

function BudgetPanel({ active }: { active: boolean }) {
  const { hasPermi } = useAuth();
  const canList = hasPermi(ASSISTANT_ADMIN_PERMISSIONS.budgetList);
  const canQuery = hasPermi(ASSISTANT_ADMIN_PERMISSIONS.budgetQuery);
  const [form] = Form.useForm();
  const [query, setQuery] = useState<Record<string, unknown>>({});
  const [items, setItems] = useState<BudgetAuthorizationSummary[]>([]);
  const [total, setTotal] = useState(0);
  const [pageNum, setPageNum] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [detail, setDetail] = useState<BudgetAuthorizationDetail>();
  const [detailLoading, setDetailLoading] = useState(false);
  const sequence = useRef(0);
  const load = useCallback(async (force = false) => {
    if (!canList) return;
    const current = ++sequence.current; setLoading(true); setError('');
    try {
      const response = await listAssistantBudgets({ pageNum, pageSize, ...query }, force);
      if (current !== sequence.current) return;
      setItems(response.data?.items || []); setTotal(response.data?.total || 0);
    } catch (cause: any) { if (current === sequence.current) setError(cause?.message || '预算授权读取失败'); }
    finally { if (current === sequence.current) setLoading(false); }
  }, [canList, pageNum, pageSize, query]);
  useEffect(() => { if (active) void load(); }, [active, load]);
  const openDetail = async (id: EntityId) => {
    setDetailLoading(true);
    try { const response = await getAssistantBudgetDetail(id, true); setDetail(response.data); }
    catch (cause: any) { message.error(cause?.message || '预算详情读取失败'); }
    finally { setDetailLoading(false); }
  };
  if (!canList) return <Denied label="预算授权列表" />;
  const columns: ColumnsType<BudgetAuthorizationSummary> = [
    { title: '授权 ID', dataIndex: 'id', width: 160, render: textId },
    { title: 'Run ID', dataIndex: 'runId', width: 160, render: textId },
    { title: '付款用户', dataIndex: 'payerUserId', width: 150, render: textId },
    { title: '状态', dataIndex: 'status', width: 120, render: (value) => <StatusTag value={value} /> },
    { title: '批准总额', dataIndex: 'approvedTotal', width: 130 },
    { title: '预留 / 结算', width: 170, render: (_, row) => `${row.reservedAmount} / ${row.settledAmount}` },
    { title: '单位', dataIndex: 'currencyUnit', width: 100 },
    { title: '版本', dataIndex: 'revision', width: 80 },
    { title: '过期时间', dataIndex: 'expiresAt', width: 175, render: (value) => value || '—' },
    { title: '操作', fixed: 'right', width: 86, render: (_, row) => <Button type="link" icon={<EyeOutlined />} disabled={!canQuery} onClick={() => void openDetail(row.id)}>详情</Button> }
  ];
  return <div className="assistant-admin__panel">
    <Alert type="info" showIcon message="金额来自 Billing 权威投影并保持字符串精度；本页不修改账户、授权或预留。" />
    <Card size="small"><Form form={form} layout="inline" onFinish={(values) => { setPageNum(1); setQuery(values); }}>
      <Form.Item name="authorizationId" label="授权 ID"><Input allowClear /></Form.Item>
      <Form.Item name="runId" label="Run ID"><Input allowClear /></Form.Item>
      <Form.Item name="payerUserId" label="付款用户"><Input allowClear /></Form.Item>
      <Form.Item name="status" label="状态"><Input allowClear /></Form.Item>
      <SearchActions loading={loading} onReset={() => { form.resetFields(); setPageNum(1); setQuery({}); }} />
    </Form></Card>
    <ListError error={error} retry={() => void load(true)} />
    <Card size="small" title="预算授权" extra={<Button icon={<ReloadOutlined />} onClick={() => void load(true)}>刷新</Button>}>
      <Table rowKey={(row) => String(row.id)} columns={columns} dataSource={items} loading={loading} scroll={{ x: 1280 }}
        pagination={{ current: pageNum, pageSize, total, showSizeChanger: true, showTotal: (count) => `共 ${count} 条`, onChange: (page, size) => { setPageNum(page); setPageSize(size); } }} />
    </Card>
    <Drawer title={`预算详情 · ${textId(detail?.authorization.id)}`} width="min(960px, 100vw)" open={Boolean(detail)}
      onClose={() => setDetail(undefined)} destroyOnClose loading={detailLoading}>
      {detail && <Space direction="vertical" size={16} className="assistant-admin__drawer-body">
        <Alert type="info" showIcon message="详情读取已写入敏感查询审计；策略正文、请求正文与账本私密信息不返回。" />
        <Descriptions bordered size="small" column={{ xs: 1, sm: 2 }}>
          <Descriptions.Item label="授权 ID">{textId(detail.authorization.id)}</Descriptions.Item>
          <Descriptions.Item label="Run ID">{textId(detail.authorization.runId)}</Descriptions.Item>
          <Descriptions.Item label="付款用户">{textId(detail.authorization.payerUserId)}</Descriptions.Item>
          <Descriptions.Item label="状态"><StatusTag value={detail.authorization.status} /></Descriptions.Item>
          <Descriptions.Item label="批准总额">{detail.authorization.approvedTotal} {detail.authorization.currencyUnit}</Descriptions.Item>
          <Descriptions.Item label="预留金额">{detail.authorization.reservedAmount}</Descriptions.Item>
          <Descriptions.Item label="结算金额">{detail.authorization.settledAmount}</Descriptions.Item>
          <Descriptions.Item label="授权版本">{detail.authorization.revision}</Descriptions.Item>
          <Descriptions.Item label="策略摘要" span={2}><Text code>{detail.authorization.policyDigest || '—'}</Text></Descriptions.Item>
        </Descriptions>
        <Typography.Title level={5}>预留与结算追踪</Typography.Title>
        <Table size="small" rowKey={(row) => String(row.id)} dataSource={detail.reservations}
          columns={reservationColumns} scroll={{ x: 1350 }} pagination={false} />
      </Space>}
    </Drawer>
  </div>;
}

const reservationColumns: ColumnsType<BudgetReservationSummary> = [
  { title: '预留 ID', dataIndex: 'id', width: 150, render: textId },
  { title: '逻辑调用键', dataIndex: 'logicalCallKey', width: 190 },
  { title: '操作 / 尝试', width: 150, render: (_, row) => `${row.operationKind} #${row.attemptNo}` },
  { title: '状态', dataIndex: 'state', width: 130, render: (value) => <StatusTag value={value} /> },
  { title: '报价上限', dataIndex: 'quotedUpperBound', width: 120 },
  { title: '预算预留 / 释放', width: 190, render: (_, row) => `${row.reservedAmount} / ${row.releasedAmount}` },
  { title: '钱包冻结 / 消费 / 退款', width: 230, render: (_, row) => `${row.walletFrozenAmount} / ${row.settledAmount} / ${row.walletRefundedAmount}` },
  { title: '步骤 / 媒体任务', width: 220, render: (_, row) => `${textId(row.runtimeStepId)} / ${textId(row.mediaTaskId)}` },
  { title: '账单追踪', dataIndex: 'billingTraceId', width: 170, render: (value) => value || '—' },
  { title: '结算键', dataIndex: 'settlementKey', width: 180, render: (value) => value || '—' }
];

function InputPanel({ active }: { active: boolean }) {
  const { hasPermi } = useAuth();
  const canList = hasPermi(ASSISTANT_ADMIN_PERMISSIONS.inputList);
  const canQuery = hasPermi(ASSISTANT_ADMIN_PERMISSIONS.inputQuery);
  const [form] = Form.useForm();
  const [query, setQuery] = useState<Record<string, unknown>>({});
  const [items, setItems] = useState<InputRequestSummary[]>([]);
  const [total, setTotal] = useState(0);
  const [pageNum, setPageNum] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [detail, setDetail] = useState<InputRequestDetail>();
  const [detailLoading, setDetailLoading] = useState(false);
  const sequence = useRef(0);
  const load = useCallback(async (force = false) => {
    if (!canList) return;
    const current = ++sequence.current; setLoading(true); setError('');
    try {
      const response = await listAssistantInputs({ pageNum, pageSize, ...query }, force);
      if (current !== sequence.current) return;
      setItems(response.data?.items || []); setTotal(response.data?.total || 0);
    } catch (cause: any) { if (current === sequence.current) setError(cause?.message || '输入追踪读取失败'); }
    finally { if (current === sequence.current) setLoading(false); }
  }, [canList, pageNum, pageSize, query]);
  useEffect(() => { if (active) void load(); }, [active, load]);
  const openDetail = async (id: EntityId) => {
    setDetailLoading(true);
    try { const response = await getAssistantInputDetail(id, true); setDetail(response.data); }
    catch (cause: any) { message.error(cause?.message || '输入详情读取失败'); }
    finally { setDetailLoading(false); }
  };
  if (!canList) return <Denied label="输入追踪列表" />;
  const columns: ColumnsType<InputRequestSummary> = [
    ...inputSummaryColumns.slice(0, 4),
    { title: 'Run ID', dataIndex: 'runId', width: 150, render: textId },
    { title: '用户 ID', dataIndex: 'userId', width: 150, render: textId },
    ...inputSummaryColumns.slice(4),
    { title: '操作', fixed: 'right', width: 86, render: (_, row) => <Button type="link" icon={<EyeOutlined />} disabled={!canQuery} onClick={() => void openDetail(row.id)}>详情</Button> }
  ];
  if (!active && !items.length) return <Empty description="切换到此页后加载" />;
  return <div className="assistant-admin__panel">
    <Alert type="info" showIcon message="只展示问题和草稿状态、revision 与长度；不返回选择答案或自由文本内容。" />
    <Card size="small"><Form form={form} layout="inline" onFinish={(values) => { setPageNum(1); setQuery(values); }}>
      <Form.Item name="requestId" label="请求 ID"><Input allowClear /></Form.Item>
      <Form.Item name="runId" label="Run ID"><Input allowClear /></Form.Item>
      <Form.Item name="userId" label="用户 ID"><Input allowClear /></Form.Item>
      <Form.Item name="requestKey" label="请求键"><Input allowClear /></Form.Item>
      <Form.Item name="status" label="状态"><Input allowClear /></Form.Item>
      <SearchActions loading={loading} onReset={() => { form.resetFields(); setPageNum(1); setQuery({}); }} />
    </Form></Card>
    <ListError error={error} retry={() => void load(true)} />
    <Card size="small" title="结构化输入追踪" extra={<Button icon={<ReloadOutlined />} onClick={() => void load(true)}>刷新</Button>}>
      <Table rowKey={(row) => String(row.id)} columns={columns} dataSource={items} loading={loading} scroll={{ x: 1300 }}
        pagination={{ current: pageNum, pageSize, total, showSizeChanger: true, showTotal: (count) => `共 ${count} 条`, onChange: (page, size) => { setPageNum(page); setPageSize(size); } }} />
    </Card>
    <Drawer title={`输入详情 · ${textId(detail?.request.id)}`} width="min(900px, 100vw)" open={Boolean(detail)}
      onClose={() => setDetail(undefined)} destroyOnClose loading={detailLoading}>
      {detail && <Space direction="vertical" size={16} className="assistant-admin__drawer-body">
        <Alert type="info" showIcon message="详情读取已写入敏感查询审计；原始问题选项、已选答案和自由文本不返回。" />
        <Descriptions bordered size="small" column={{ xs: 1, sm: 2 }}>
          <Descriptions.Item label="请求 ID">{textId(detail.request.id)}</Descriptions.Item>
          <Descriptions.Item label="Run ID">{textId(detail.request.runId)}</Descriptions.Item>
          <Descriptions.Item label="请求键">{detail.request.requestKey}</Descriptions.Item>
          <Descriptions.Item label="状态"><StatusTag value={detail.request.status} /></Descriptions.Item>
          <Descriptions.Item label="Schema 摘要"><Text code>{detail.request.schemaDigest}</Text></Descriptions.Item>
          <Descriptions.Item label="上下文摘要"><Text code>{detail.request.contextDigest}</Text></Descriptions.Item>
          <Descriptions.Item label="轮次">{detail.request.roundNo}</Descriptions.Item>
          <Descriptions.Item label="计划版本">{detail.request.planRevision}</Descriptions.Item>
        </Descriptions>
        <Typography.Title level={5}>问题草稿状态</Typography.Title>
        <Table size="small" rowKey="questionId" dataSource={detail.questions} columns={questionColumns}
          scroll={{ x: 850 }} pagination={false} />
      </Space>}
    </Drawer>
  </div>;
}

const questionColumns: ColumnsType<InputQuestionTrace> = [
  { title: '问题 ID', dataIndex: 'questionId', width: 170 },
  { title: '类型', dataIndex: 'kind', width: 110, render: (value) => value || '—' },
  { title: '必填', dataIndex: 'required', width: 80, render: (value) => value ? '是' : '否' },
  { title: '回答状态', dataIndex: 'answerState', width: 130, render: (value) => <StatusTag value={value} /> },
  { title: '草稿版本', dataIndex: 'draftRevision', width: 100 },
  { title: '选择数', dataIndex: 'selectedCount', width: 90 },
  { title: '文本长度', dataIndex: 'freeTextLength', width: 100 },
  { title: '文本预览', dataIndex: 'freeTextPreviewRedacted', width: 160, render: (value) => value || '已脱敏' }
];

export default function AssistantGovernancePage() {
  const { hasPermi } = useAuth();
  const firstAllowed = useMemo(() => {
    if (hasPermi(ASSISTANT_ADMIN_PERMISSIONS.runList)) return 'runs';
    if (hasPermi('aid:assistant:agent:list')) return 'agents';
    if (hasPermi(ASSISTANT_ADMIN_PERMISSIONS.toolQuery)) return 'tools';
    if (hasPermi(ASSISTANT_ADMIN_PERMISSIONS.budgetList)) return 'budgets';
    if (hasPermi(ASSISTANT_ADMIN_PERMISSIONS.inputList)) return 'inputs';
    return 'runs';
  }, [hasPermi]);
  const [activeKey, setActiveKey] = useState(firstAllowed);
  return <div className="app-container assistant-admin">
    <PageHeader title="助手运行治理"
      desc="配置和发布主 Agent，查看运行审计、工具目录、预算与输入记录；操作入口遵循当前账号权限。" />
    <Tabs activeKey={activeKey} onChange={setActiveKey} destroyInactiveTabPane items={[
      { key: 'agents', label: 'Agent 版本', children: <AgentVersionPanel active={activeKey === 'agents'} /> },
      { key: 'runs', label: '运行审计', children: <RunPanel active={activeKey === 'runs'} /> },
      { key: 'tools', label: 'Tool 目录', children: <ToolPanel active={activeKey === 'tools'} /> },
      { key: 'budgets', label: '预算追踪', children: <BudgetPanel active={activeKey === 'budgets'} /> },
      { key: 'inputs', label: '输入追踪', children: <InputPanel active={activeKey === 'inputs'} /> }
    ]} />
  </div>;
}
