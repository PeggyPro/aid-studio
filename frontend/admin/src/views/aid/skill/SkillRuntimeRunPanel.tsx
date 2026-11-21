import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, Button, Descriptions, Drawer, Empty, Input, Select, Space, Table, Tag,
  Typography, message } from 'antd';
import { ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import { getSkillRuntimeRun, listSkillRuntimeRuns, type SkillId } from '@/api/aid/skill';
import type { RuntimeAgentRun } from '@/api/aid/legacyRuntimePlanAgent';
import { useAuth } from '@/hooks/useAuth';
import StructuredValueEditor from './StructuredValueEditor';
import RuntimeRunMedia from '@/views/aid/runtimeAgent/RuntimeRunMedia';

function displayValue(value: unknown) {
  if (typeof value === 'string') {
    try { JSON.parse(value); return value; } catch { return JSON.stringify(value); }
  }
  return JSON.stringify(value ?? {});
}

function billingStatusTag(value?: string) {
  if (!value) return '--';
  const descriptions: Record<string, string> = {
    INIT: '尚未完成预冻结', FROZEN: '费用已预冻结，等待任务收口',
    SETTLING: '正在结算', REFUNDING: '正在退回预冻结费用',
    SUCCESS: '结算成功', FAILED: '预冻结失败，或任务失败后费用已退回'
  };
  const colors: Record<string, string> = {
    FROZEN: 'gold', SETTLING: 'processing', REFUNDING: 'warning',
    SUCCESS: 'success', FAILED: 'error'
  };
  return <Tag color={colors[value]} title={descriptions[value]}>{value}</Tag>;
}

/** 新通用 Runtime 的 Skill 运行审计，与历史 SkillRun 记录分开。 */
export default function SkillRuntimeRunPanel({ skillId, initialRunId }: {
  skillId?: SkillId;
  initialRunId?: SkillId;
}) {
  const { hasPermi } = useAuth();
  const canQueryRun = hasPermi('aid:skill:run:query');
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
  const [total, setTotal] = useState(0);
  const [rows, setRows] = useState<RuntimeAgentRun[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [userIdInput, setUserIdInput] = useState('');
  const [statusInput, setStatusInput] = useState<string>();
  const [filter, setFilter] = useState<{ userId?: SkillId; status?: string }>({});
  const [detail, setDetail] = useState<RuntimeAgentRun>();
  const [detailLoading, setDetailLoading] = useState<SkillId>();
  const sequence = useRef(0);
  const detailSequence = useRef(0);

  const load = useCallback(async (force = false) => {
    const current = ++sequence.current;
    setLoading(true); setError('');
    try {
      const response = await listSkillRuntimeRuns({ skillId, pageNum: page, pageSize: size,
        ...filter }, { force });
      if (current !== sequence.current) return;
      setRows(response.data || []);
      setTotal(Number(response.total || 0));
    } catch (cause: any) {
      if (current === sequence.current) setError(cause?.message || '通用运行记录加载失败');
    } finally { if (current === sequence.current) setLoading(false); }
  }, [filter, page, size, skillId]);

  useEffect(() => { void load(); }, [load]);

  useEffect(() => {
    detailSequence.current += 1;
    setDetailLoading(undefined);
    setDetail(undefined);
  }, [skillId]);

  const openDetail = useCallback(async (id: SkillId) => {
    if (!canQueryRun) return;
    const current = ++detailSequence.current;
    setDetailLoading(id);
    try {
      const response = await getSkillRuntimeRun(id);
      if (current === detailSequence.current) setDetail(response.data);
    } catch (cause: any) {
      if (current === detailSequence.current) message.error(cause?.message || '运行详情加载失败');
    } finally {
      if (current === detailSequence.current) setDetailLoading(undefined);
    }
  }, [canQueryRun]);

  useEffect(() => {
    if (initialRunId != null && canQueryRun) void openDetail(initialRunId);
  }, [canQueryRun, initialRunId, openDetail]);

  const closeDetail = () => {
    detailSequence.current += 1;
    setDetailLoading(undefined);
    setDetail(undefined);
  };

  return <>
    <Space wrap style={{ marginBottom: 14 }}>
      <Input aria-label="付费用户 ID" placeholder="付费用户 ID" inputMode="numeric"
        value={userIdInput} onChange={(event) => setUserIdInput(event.target.value)} />
      <Select aria-label="运行状态" allowClear placeholder="全部状态" style={{ minWidth: 140 }}
        value={statusInput} onChange={setStatusInput} options={[
          { value: 'CREATED', label: '已创建' }, { value: 'RUNNING', label: '运行中' },
          { value: 'WAITING_INPUT', label: '等待输入' }, { value: 'SUCCEEDED', label: '成功' },
          { value: 'FAILED', label: '失败' }, { value: 'CANCELED', label: '已取消' }
        ]} />
      <Button icon={<SearchOutlined />} onClick={() => {
        if (userIdInput && !/^[1-9][0-9]*$/.test(userIdInput)) {
          message.error('付费用户 ID 必须为正整数数字字符串'); return;
        }
        setPage(1); setFilter({ userId: userIdInput || undefined, status: statusInput });
      }}>查询</Button>
      <Button icon={<ReloadOutlined />} loading={loading} onClick={() => load(true)}>刷新</Button>
    </Space>
    {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 12 }} />}
    <Table rowKey="id" dataSource={rows} loading={loading} scroll={{ x: 960 }}
      locale={{ emptyText: <Empty description="暂无通用 Runtime 运行记录" /> }}
      pagination={{ current: page, pageSize: size, total, showSizeChanger: true,
        onChange: (nextPage, nextSize) => { setPage(nextPage); setSize(nextSize); } }}
      columns={[
        { title: 'Run ID', dataIndex: 'id', width: 100 },
        { title: '付费用户 ID', dataIndex: 'userId', width: 115 },
        { title: '草稿/版本 ID', dataIndex: 'versionId', width: 120 },
        { title: '模型', dataIndex: 'modelCode', width: 180, render: (value?: string) => value || '--' },
        { title: '输出类型', dataIndex: 'outputKind', width: 95 },
        { title: '状态', dataIndex: 'status', width: 110,
          render: (value?: string) => <Tag>{value || '--'}</Tag> },
        { title: '错误', dataIndex: 'errorMessage', ellipsis: true,
          render: (value?: string) => value || '--' },
        { title: '创建时间', dataIndex: 'createTime', width: 170 },
        { title: '操作', width: 90, fixed: 'right', render: (_: unknown, row: RuntimeAgentRun) =>
          canQueryRun && <Button type="link" loading={String(detailLoading) === String(row.id)}
            onClick={() => openDetail(row.id)}>详情</Button> }
      ]} />
    <Drawer open={!!detail} title={detail ? `通用 Run #${detail.id}` : '运行详情'}
      width="min(920px, 100vw)" onClose={closeDetail}>
      {detail && <>
        <Descriptions bordered size="small" column={1} items={[
          { key: 'payer', label: '付费用户 ID', children: detail.userId },
          { key: 'version', label: '草稿/版本 ID', children: detail.versionId },
          { key: 'status', label: '状态', children: detail.status },
          { key: 'stage', label: '阶段', children: detail.stage || '--' },
          { key: 'task', label: '媒体任务 ID', children: detail.mediaTaskId || '--' },
          { key: 'billing', label: '运行计费状态', children: billingStatusTag(detail.billingStatus) },
          { key: 'cost', label: '运行实际费用', children: detail.actualCost ?? '--' },
          { key: 'error', label: '错误', children: detail.errorMessage || '--' },
          { key: 'errorReason', label: '错误原因', children: detail.errorDetails?.reason || '--' },
          { key: 'allowedValues', label: '可选值', children: detail.errorDetails?.allowedValues?.join('、') || '--' }
        ]} />
        <RuntimeRunMedia run={detail} />
        <Typography.Title level={5} style={{ marginTop: 22 }}>执行步骤</Typography.Title>
        <Table size="small" rowKey={(row, index) => `${row.stepKey}-${index}`}
          dataSource={detail.steps || []} pagination={false} scroll={{ x: 980 }} columns={[
            { title: '步骤', dataIndex: 'stepKey' }, { title: '方式', dataIndex: 'stepKind' },
            { title: '状态', dataIndex: 'status' }, { title: 'Tool', dataIndex: 'toolCode' },
            { title: 'Skill 版本', dataIndex: 'skillVersionId' },
            { title: '媒体任务', dataIndex: 'mediaTaskId' },
            { title: '计费状态', dataIndex: 'billingStatus',
              render: (value?: string) => billingStatusTag(value) },
            { title: '实际费用', dataIndex: 'actualCost',
              render: (value?: string | number) => value ?? '--' },
            { title: '错误', dataIndex: 'errorMessage' }
          ]} />
        <Typography.Title level={5} style={{ marginTop: 22 }}>输入</Typography.Title>
        {detail.inputPreview !== undefined ? <StructuredValueEditor disabled value={displayValue(detail.inputPreview)} />
          : <Empty description="服务端未提供输入预览" />}
        <Typography.Title level={5} style={{ marginTop: 22 }}>输出</Typography.Title>
        {detail.resultPreview !== undefined ? <StructuredValueEditor disabled value={displayValue(detail.resultPreview)} />
          : <Empty description="尚无输出" />}
      </>}
    </Drawer>
  </>;
}
