import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Col,
  DatePicker,
  Descriptions,
  Drawer,
  Form,
  Input,
  InputNumber,
  Modal,
  Result,
  Row,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Tooltip,
  Typography,
  message
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import {
  ClockCircleOutlined,
  EyeOutlined,
  ReloadOutlined,
  SearchOutlined,
  SettingOutlined,
  StopOutlined,
  SyncOutlined,
  WarningOutlined
} from '@ant-design/icons';
import dayjs, { Dayjs } from 'dayjs';

import {
  confirmMediaTaskAnomalyFailure,
  continueWaitingMediaTaskAnomaly,
  EntityId,
  getMediaTaskAnomaly,
  getMediaTaskAnomalyConfig,
  listMediaTaskAnomalies,
  MEDIA_TASK_ANOMALY_PERMISSIONS,
  MediaTaskAnomalyConfig,
  MediaTaskAnomalyListQuery,
  MediaTaskAnomalyRow,
  recheckMediaTaskAnomaly,
  recheckMediaTaskById,
  updateMediaTaskAnomalyConfig
} from '@/api/system/mediaTaskAnomaly';
import Auth from '@/components/Auth';
import { formatWaitingTime, MediaTaskAnomalyPageSummary } from '@/components/MediaTaskAnomaly';
import PageHeader from '@/components/PageHeader';
import { useAuth } from '@/hooks/useAuth';
import { useMediaTaskAnomalySummaryStore } from '@/store/useMediaTaskAnomalySummaryStore';
import './style.less';
import { failureConfirmationFeedback, type MutationFeedback } from './feedback';

const { RangePicker } = DatePicker;
const { Paragraph, Text } = Typography;

const DEFAULT_QUERY: MediaTaskAnomalyListQuery = {
  pageNum: 1,
  pageSize: 20
};

const STATUS_META: Record<string, { text: string; color: string }> = {
  OBSERVING: { text: '观察中', color: 'default' },
  PENDING: { text: '待处理', color: 'orange' },
  WAITING: { text: '继续等待', color: 'blue' },
  COMPENSATION_PENDING: { text: '账务补偿中', color: 'red' },
  CLOSED: { text: '已关闭', color: 'green' }
};

const MEDIA_TYPE_OPTIONS = [
  { label: '图片', value: 'IMAGE' },
  { label: '视频', value: 'VIDEO' },
  { label: '音频', value: 'AUDIO' }
];

interface SearchValues {
  taskId?: EntityId;
  userId?: EntityId;
  mediaType?: string;
  anomalyType?: string;
  anomalyStatus?: string;
  detectedRange?: [Dayjs, Dayjs];
}

interface ContinueWaitValues {
  reason: string;
  nextReviewTime: Dayjs;
}

function text(value?: React.ReactNode) {
  return value === null || value === undefined || value === '' ? '-' : value;
}

function anomalyStatusTag(status?: string | null) {
  if (!status) return '-';
  const meta = STATUS_META[status] || { text: status, color: 'default' };
  return <Tag color={meta.color}>{meta.text}</Tag>;
}

function taskStatusTag(status?: string | null) {
  if (!status) return '-';
  const color = status === 'SUCCEEDED' ? 'green'
    : status === 'FAILED' ? 'red'
      : status === 'PROCESSING' ? 'processing'
        : status === 'QUEUED' ? 'blue' : 'default';
  return <Tag color={color}>{status}</Tag>;
}

function readList(response: any) {
  const data = response?.data || {};
  return {
    items: Array.isArray(data.items) ? data.items as MediaTaskAnomalyRow[] : [],
    total: Math.max(0, Number(data.total) || 0)
  };
}

function isOpenAnomaly(row: MediaTaskAnomalyRow) {
  return row.anomalyStatus === 'PENDING' || row.anomalyStatus === 'WAITING';
}

export default function MediaTaskAnomalyPage() {
  const { hasPermi } = useAuth();
  const canView = hasPermi(MEDIA_TASK_ANOMALY_PERMISSIONS.list);
  const [searchForm] = Form.useForm<SearchValues>();
  const [continueForm] = Form.useForm<ContinueWaitValues>();
  const [failureForm] = Form.useForm<{ reason: string }>();
  const [configForm] = Form.useForm<MediaTaskAnomalyConfig>();
  const [taskRecheckForm] = Form.useForm<{ taskId: string; reason: string }>();
  const [taskRecheckOpen, setTaskRecheckOpen] = useState(false);
  const [taskRecheckSaving, setTaskRecheckSaving] = useState(false);
  const [taskRecheckError, setTaskRecheckError] = useState<string>();
  const taskRecheckGuard = useRef(false);
  const [query, setQuery] = useState<MediaTaskAnomalyListQuery>(DEFAULT_QUERY);
  const [rows, setRows] = useState<MediaTaskAnomalyRow[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [detailId, setDetailId] = useState<EntityId>();
  const [detail, setDetail] = useState<MediaTaskAnomalyRow>();
  const [detailLoading, setDetailLoading] = useState(false);
  const [continueTarget, setContinueTarget] = useState<MediaTaskAnomalyRow>();
  const [failureTarget, setFailureTarget] = useState<MediaTaskAnomalyRow>();
  const [mutationKey, setMutationKey] = useState<string>();
  const mutationGuard = useRef(false);
  const [configOpen, setConfigOpen] = useState(false);
  const [configLoading, setConfigLoading] = useState(false);
  const [configSaving, setConfigSaving] = useState(false);
  const configSavingGuard = useRef(false);
  const listRequestSequence = useRef(0);
  const detailRequestSequence = useRef(0);
  const continueSubmitGuard = useRef(false);
  const failurePrepareGuard = useRef(false);
  const recheckDialogGuard = useRef(false);
  const refreshSummary = useMediaTaskAnomalySummaryStore((state) => state.refresh);

  const loadList = useCallback(async (force = false) => {
    if (!canView) return;
    const sequence = ++listRequestSequence.current;
    setLoading(true);
    try {
      const result = readList(await listMediaTaskAnomalies(query, force));
      if (sequence !== listRequestSequence.current) return;
      setRows(result.items);
      setTotal(result.total);
    } finally {
      if (sequence === listRequestSequence.current) setLoading(false);
    }
  }, [canView, query]);

  useEffect(() => {
    loadList().catch(() => undefined);
  }, [loadList]);

  const loadDetail = useCallback(async (id: EntityId, force = false) => {
    if (!canView) return;
    const sequence = ++detailRequestSequence.current;
    setDetailId(id);
    setDetail(undefined);
    setDetailLoading(true);
    try {
      const response: any = await getMediaTaskAnomaly(id, force);
      if (sequence !== detailRequestSequence.current) return;
      setDetail(response?.data);
    } finally {
      if (sequence === detailRequestSequence.current) setDetailLoading(false);
    }
  }, [canView]);

  const refreshAfterMutation = useCallback(async (id: EntityId) => {
    const tasks: Promise<unknown>[] = [loadList(true), refreshSummary(true)];
    if (detailId === id) tasks.push(loadDetail(id, true));
    await Promise.allSettled(tasks);
  }, [detailId, loadDetail, loadList, refreshSummary]);

  const runMutation = useCallback(async (
    key: string,
    id: EntityId,
    operation: () => Promise<unknown>,
    successMessage: string | ((response: unknown) => MutationFeedback)
  ) => {
    if (mutationGuard.current) return;
    mutationGuard.current = true;
    setMutationKey(key);
    try {
      const response = await operation();
      const feedback: MutationFeedback = typeof successMessage === 'function'
        ? successMessage(response) : { type: 'success', message: successMessage, completed: true };
      message[feedback.type](feedback.message, feedback.type === 'warning' ? 6 : 3);
      await refreshAfterMutation(id);
      return feedback.completed;
    } finally {
      mutationGuard.current = false;
      setMutationKey(undefined);
    }
  }, [refreshAfterMutation]);

  const handleSearch = (values: SearchValues) => {
    const [start, end] = values.detectedRange || [];
    setQuery((current) => ({
      ...current,
      pageNum: 1,
      taskId: values.taskId,
      userId: values.userId,
      mediaType: values.mediaType,
      anomalyType: values.anomalyType?.trim() || undefined,
      anomalyStatus: values.anomalyStatus,
      detectedStartTime: start?.startOf('day').format('YYYY-MM-DD HH:mm:ss'),
      detectedEndTime: end?.endOf('day').format('YYYY-MM-DD HH:mm:ss')
    }));
  };

  const handleReset = () => {
    searchForm.resetFields();
    setQuery((current) => ({ ...DEFAULT_QUERY, pageSize: current.pageSize }));
  };

  const handleTaskRecheck = async () => {
    if (taskRecheckGuard.current || mutationGuard.current) return;
    taskRecheckGuard.current = true;
    try {
      const values = await taskRecheckForm.validateFields();
      setTaskRecheckSaving(true);
      setTaskRecheckError(undefined);
      const response: any = await recheckMediaTaskById(values.taskId.trim(), { reason: values.reason.trim() });
      const data = response?.data || {};
      const result = data.result || {};
      setTaskRecheckOpen(false);
      searchForm.resetFields();
      searchForm.setFieldsValue({ taskId: values.taskId.trim() });
      setQuery({ ...DEFAULT_QUERY, taskId: values.taskId.trim() });
      await refreshSummary(true);
      Modal.info({
        title: `复查结果 · 任务 ${values.taskId.trim()}`,
        content: (
          <Space direction="vertical">
            <Text>{result.terminal ? '任务已到终态' : result.querySuccessful ? '上游仍在处理' : '上游状态暂未确认'}</Text>
            <Text type="secondary">{result.message || result.taskStatus || '请查看任务详情'}</Text>
            {data.anomaly && <Text>已关联异常记录，可在列表查看详情并按证据继续处置。</Text>}
          </Space>
        )
      });
    } catch (error: any) {
      if (!error?.errorFields) setTaskRecheckError('复查未完成，请查看页面错误提示，修正后重试。');
    } finally {
      taskRecheckGuard.current = false;
      setTaskRecheckSaving(false);
    }
  };

  const handleContinueWait = async () => {
    if (!continueTarget || mutationGuard.current || continueSubmitGuard.current) return;
    continueSubmitGuard.current = true;
    try {
      const values = await continueForm.validateFields();
      const target = continueTarget;
      await runMutation(
        `continue:${target.id}`,
        target.id,
        () => continueWaitingMediaTaskAnomaly(target.id, {
          reason: values.reason.trim(),
          nextReviewTime: values.nextReviewTime.format('YYYY-MM-DD HH:mm:ss')
        }),
        '已继续等待，并安排下次复查'
      );
      setContinueTarget(undefined);
      continueForm.resetFields();
    } finally {
      continueSubmitGuard.current = false;
    }
  };

  const prepareConfirmFailure = async () => {
    if (!failureTarget || mutationGuard.current || failurePrepareGuard.current) return;
    failurePrepareGuard.current = true;
    try {
      const values = await failureForm.validateFields();
      const target = failureTarget;
      setFailureTarget(undefined);
      Modal.confirm({
      title: '再次确认任务失败',
      icon: <StopOutlined style={{ color: '#dc2626' }} />,
      width: 520,
      okText: '确认失败并执行终态处理',
      cancelText: '返回',
      okButtonProps: { danger: true },
      content: (
        <div className="media-task-anomaly-confirm">
          <Alert
            type="error"
            showIcon
            message={`确认终止任务 ${target.taskId}？`}
            description="服务端会先复查上游；仍在处理的任务不会被确认失败。可关闭时统一处理原账户账务、资源占用和业务状态，以实际返回结果为准。终态处理不能在此页撤回。"
          />
          <Paragraph><Text type="secondary">处理原因：</Text>{values.reason.trim()}</Paragraph>
        </div>
      ),
      onOk: () => runMutation(
        `failure:${target.id}`,
        target.id,
        () => confirmMediaTaskAnomalyFailure(target.id, { reason: values.reason.trim(), confirmed: true }),
        failureConfirmationFeedback
      ).then((completed) => { if (completed) failureForm.resetFields(); })
      });
    } finally {
      failurePrepareGuard.current = false;
    }
  };

  const openConfig = async () => {
    setConfigOpen(true);
    setConfigLoading(true);
    try {
      const response: any = await getMediaTaskAnomalyConfig();
      configForm.setFieldsValue(response?.data || {});
    } finally {
      setConfigLoading(false);
    }
  };

  const saveConfig = async () => {
    if (configSavingGuard.current) return;
    configSavingGuard.current = true;
    setConfigSaving(true);
    try {
      const values = await configForm.validateFields();
      await updateMediaTaskAnomalyConfig(values);
      message.success('异常治理配置已保存');
      setConfigOpen(false);
      await Promise.allSettled([loadList(true), refreshSummary(true)]);
    } finally {
      configSavingGuard.current = false;
      setConfigSaving(false);
    }
  };

  const openRecheckDialog = useCallback((row: MediaTaskAnomalyRow) => {
    if (recheckDialogGuard.current || mutationGuard.current) return;
    recheckDialogGuard.current = true;
    Modal.confirm({
      title: '重新查询任务状态',
      content: `立即重新查询任务 ${row.taskId} 的权威状态？`,
      okText: '重新查询',
      cancelText: '取消',
      onOk: () => runMutation(
        `recheck:${row.id}`,
        row.id,
        () => recheckMediaTaskAnomaly(row.id),
        '已完成任务状态复查'
      ),
      afterClose: () => {
        recheckDialogGuard.current = false;
      }
    });
  }, [runMutation]);

  const columns = useMemo<ColumnsType<MediaTaskAnomalyRow>>(() => [
    {
      title: '任务',
      dataIndex: 'taskId',
      width: 150,
      fixed: 'left',
      render: (value: EntityId, row) => (
        <Space direction="vertical" size={0}>
          <Text strong>{text(value)}</Text>
          <Text type="secondary" style={{ fontSize: 12 }}>异常 #{row.id}</Text>
        </Space>
      )
    },
    {
      title: '媒体 / 模型',
      key: 'media',
      width: 180,
      render: (_, row) => (
        <Space direction="vertical" size={2}>
          <Space size={4}>
            {row.mediaType && <Tag color="geekblue">{row.mediaType}</Tag>}
            <Text>{text(row.providerCode)}</Text>
          </Space>
          <Text type="secondary" ellipsis style={{ maxWidth: 165, fontSize: 12 }}>
            {text(row.modelCode)}
          </Text>
        </Space>
      )
    },
    { title: '用户ID', dataIndex: 'userId', width: 110, render: text },
    {
      title: '异常类型',
      dataIndex: 'anomalyType',
      width: 150,
      render: (value) => value ? <Tag color="volcano">{value}</Tag> : '-'
    },
    { title: '任务状态', dataIndex: 'taskStatus', width: 120, render: taskStatusTag },
    { title: '处置状态', dataIndex: 'anomalyStatus', width: 120, render: anomalyStatusTag },
    {
      title: '等待时长',
      dataIndex: 'waitingSeconds',
      width: 130,
      render: (value) => <Text type={(Number(value) || 0) > 3600 ? 'danger' : undefined}>{formatWaitingTime(value)}</Text>
    },
    { title: '发现时间', dataIndex: 'detectedAt', width: 170, render: text },
    { title: '下次复查', dataIndex: 'nextReviewTime', width: 170, render: text },
    {
      title: '异常原因',
      dataIndex: 'anomalyReason',
      width: 240,
      ellipsis: true,
      render: (value) => <Tooltip title={value}><span>{text(value)}</span></Tooltip>
    },
    {
      title: '操作',
      key: 'actions',
      width: 300,
      fixed: 'right',
      render: (_, row) => {
        const open = isOpenAnomaly(row);
        return (
          <Space size={0}>
            <Button type="link" size="small" icon={<EyeOutlined />} onClick={() => loadDetail(row.id)}>
              详情
            </Button>
            {open && (
              <Auth permission={MEDIA_TASK_ANOMALY_PERMISSIONS.recheck}>
                <Button
                  type="link"
                  size="small"
                  icon={<SyncOutlined />}
                  loading={mutationKey === `recheck:${row.id}`}
                  disabled={!!mutationKey && mutationKey !== `recheck:${row.id}`}
                  onClick={() => openRecheckDialog(row)}
                >
                  重新查询
                </Button>
              </Auth>
            )}
            {open && (
              <Auth permission={MEDIA_TASK_ANOMALY_PERMISSIONS.continueWait}>
                <Button
                  type="link"
                  size="small"
                  icon={<ClockCircleOutlined />}
                  disabled={!!mutationKey}
                  onClick={() => {
                    continueForm.resetFields();
                    setContinueTarget(row);
                  }}
                >
                  继续等待
                </Button>
              </Auth>
            )}
            {open && (
              <Auth permission={MEDIA_TASK_ANOMALY_PERMISSIONS.confirmFailure}>
                <Button
                  danger
                  type="link"
                  size="small"
                  icon={<StopOutlined />}
                  disabled={!!mutationKey}
                  onClick={() => {
                    failureForm.resetFields();
                    setFailureTarget(row);
                  }}
                >
                  确认失败
                </Button>
              </Auth>
            )}
          </Space>
        );
      }
    }
  ], [continueForm, failureForm, loadDetail, mutationKey, openRecheckDialog]);

  if (!canView) {
    return <Result status="403" title="无权查看异常任务" subTitle="请联系管理员授予异常任务查询权限。" />;
  }

  return (
    <div className="crud-page media-task-anomaly-page">
      <PageHeader
        title={<><WarningOutlined />异常任务</>}
        desc="集中处理长时间无终态或状态不确定的媒体任务，所有终态操作均由服务端统一收口。"
        extra={(
          <Space>
            <Auth permission={MEDIA_TASK_ANOMALY_PERMISSIONS.recheck}>
              <Button icon={<SyncOutlined />} disabled={!!mutationKey} onClick={() => {
                taskRecheckForm.resetFields();
                setTaskRecheckError(undefined);
                setTaskRecheckOpen(true);
              }}>按任务 ID 复查</Button>
            </Auth>
            <Auth permission={MEDIA_TASK_ANOMALY_PERMISSIONS.config}>
              <Button icon={<SettingOutlined />} onClick={openConfig}>异常治理配置</Button>
            </Auth>
            <Button icon={<ReloadOutlined />} loading={loading} onClick={() => loadList(true)}>刷新列表</Button>
          </Space>
        )}
      />

      <MediaTaskAnomalyPageSummary />

      <Card className="page-card" bordered={false}>
        <Form<SearchValues> name="media-task-anomaly-search"
          form={searchForm}
          layout="inline"
          onFinish={handleSearch}
        >
          <Row gutter={[8, 8]} style={{ width: '100%' }}>
            <Col><Form.Item name="taskId" label="任务ID"><Input allowClear placeholder="输入任务ID" style={{ width: 150 }} /></Form.Item></Col>
            <Col><Form.Item name="userId" label="用户ID"><Input allowClear placeholder="输入用户ID" style={{ width: 140 }} /></Form.Item></Col>
            <Col>
              <Form.Item name="mediaType" label="媒体类型">
                <Select allowClear placeholder="全部类型" options={MEDIA_TYPE_OPTIONS} style={{ width: 130 }} />
              </Form.Item>
            </Col>
            <Col><Form.Item name="anomalyType" label="异常类型"><Input allowClear placeholder="输入异常类型" style={{ width: 160 }} /></Form.Item></Col>
            <Col>
              <Form.Item name="anomalyStatus" label="处置状态">
                <Select
                  allowClear
                  placeholder="全部状态"
                  style={{ width: 140 }}
                  options={Object.entries(STATUS_META).map(([value, meta]) => ({ value, label: meta.text }))}
                />
              </Form.Item>
            </Col>
            <Col>
              <Form.Item name="detectedRange" label="发现时间">
                <RangePicker allowClear style={{ width: 250 }} />
              </Form.Item>
            </Col>
            <Col>
              <Space>
                <Button type="primary" htmlType="submit" icon={<SearchOutlined />}>查询</Button>
                <Button icon={<ReloadOutlined />} onClick={handleReset}>重置</Button>
              </Space>
            </Col>
          </Row>
        </Form>
      </Card>

      <Card className="page-card" bordered={false}>
        <Table<MediaTaskAnomalyRow>
          rowKey="id"
          size="middle"
          loading={loading}
          dataSource={rows}
          columns={columns}
          scroll={{ x: 1900 }}
          pagination={{
            current: query.pageNum,
            pageSize: query.pageSize,
            total,
            showSizeChanger: true,
            showQuickJumper: true,
            showTotal: (value) => `共 ${value} 条`,
            onChange: (pageNum, pageSize) => setQuery((current) => ({ ...current, pageNum, pageSize }))
          }}
        />
      </Card>

      <Drawer
        title={`异常任务详情${detail?.taskId ? ` · ${detail.taskId}` : ''}`}
        open={detailId !== undefined}
        width={680}
        loading={detailLoading}
        onClose={() => {
          detailRequestSequence.current++;
          setDetailId(undefined);
          setDetail(undefined);
        }}
      >
        {detail && (
          <div className="media-task-anomaly-detail">
            <Descriptions bordered size="small" column={2}>
              <Descriptions.Item label="异常记录">{text(detail.id)}</Descriptions.Item>
              <Descriptions.Item label="媒体任务">{text(detail.taskId)}</Descriptions.Item>
              <Descriptions.Item label="用户ID">{text(detail.userId)}</Descriptions.Item>
              <Descriptions.Item label="用户名">{text(detail.userName)}</Descriptions.Item>
              <Descriptions.Item label="项目ID">{text(detail.projectId)}</Descriptions.Item>
              <Descriptions.Item label="节点ID">{text(detail.nodeId)}</Descriptions.Item>
              <Descriptions.Item label="业务类型">{text(detail.bizType)}</Descriptions.Item>
              <Descriptions.Item label="媒体类型">{text(detail.mediaType)}</Descriptions.Item>
              <Descriptions.Item label="服务商">{text(detail.providerCode)}</Descriptions.Item>
              <Descriptions.Item label="模型">{text(detail.modelCode)}</Descriptions.Item>
              <Descriptions.Item label="任务状态">{taskStatusTag(detail.taskStatus)}</Descriptions.Item>
              <Descriptions.Item label="处置状态">{anomalyStatusTag(detail.anomalyStatus)}</Descriptions.Item>
              <Descriptions.Item label="等待时长">{formatWaitingTime(detail.waitingSeconds)}</Descriptions.Item>
              <Descriptions.Item label="发现时间">{text(detail.detectedAt)}</Descriptions.Item>
              <Descriptions.Item label="最近复查">{text(detail.lastCheckedAt)}</Descriptions.Item>
              <Descriptions.Item label="下次复查">{text(detail.nextReviewTime)}</Descriptions.Item>
              <Descriptions.Item label="处理人">{text(detail.handler)}</Descriptions.Item>
              <Descriptions.Item label="处理时间">{text(detail.handledAt)}</Descriptions.Item>
              <Descriptions.Item label="上游任务号" span={2}>{text(detail.providerTaskId)}</Descriptions.Item>
              <Descriptions.Item label="上游最近状态">{text(detail.lastProviderStatus)}</Descriptions.Item>
              <Descriptions.Item label="关闭原因">{text(detail.resolutionType)}</Descriptions.Item>
              <Descriptions.Item label="上游受理时间">{text(detail.upstreamAcceptTime)}</Descriptions.Item>
              <Descriptions.Item label="最近进度时间">{text(detail.lastProgressTime)}</Descriptions.Item>
              <Descriptions.Item label="任务终态时间">{text(detail.terminalTime)}</Descriptions.Item>
              <Descriptions.Item label="账务状态">{text(detail.billingStatus)}</Descriptions.Item>
              <Descriptions.Item label="冻结金额">{text(detail.frozenAmount)}</Descriptions.Item>
              <Descriptions.Item label="实际消耗">{text(detail.actualCost)}</Descriptions.Item>
              <Descriptions.Item label="账务流水" span={2}>{text(detail.billingTraceId)}</Descriptions.Item>
              <Descriptions.Item label="补偿状态" span={2}>
                {detail.compensationPending ? <Tag color="red">补偿尚未完成</Tag> : <Tag color="green">无需补偿</Tag>}
              </Descriptions.Item>
            </Descriptions>
            <div className="form-section-title">异常原因</div>
            <Paragraph className="readonly-preview">{text(detail.anomalyReason)}</Paragraph>
            <div className="form-section-title">最近查询结果</div>
            <Paragraph className="readonly-preview">{text(detail.lastCheckMessage)}</Paragraph>
            {detail.handlingReason && (
              <>
                <div className="form-section-title">人工处理原因</div>
                <Paragraph className="readonly-preview">{detail.handlingReason}</Paragraph>
              </>
            )}
            <div className="form-section-title">操作审计</div>
            <Table
              size="small"
              rowKey="id"
              pagination={false}
              dataSource={detail.audits || []}
              columns={[
                { title: '时间', dataIndex: 'createTime', width: 170, render: text },
                { title: '动作', dataIndex: 'action', width: 150, render: text },
                { title: '操作人', dataIndex: 'operatorName', width: 120, render: text },
                { title: '状态变化', key: 'statusChange', width: 180, render: (_, row: any) => `${text(row.beforeStatus)} → ${text(row.afterStatus)}` },
                { title: '原因', dataIndex: 'reason', render: text }
              ]}
              scroll={{ x: 760 }}
            />
          </div>
        )}
      </Drawer>

      <Modal
        title={`继续等待 · 任务 ${continueTarget?.taskId || ''}`}
        open={!!continueTarget}
        okText="确认继续等待"
        cancelText="取消"
        confirmLoading={mutationKey === `continue:${continueTarget?.id}`}
        onOk={handleContinueWait}
        onCancel={() => !mutationKey && setContinueTarget(undefined)}
      >
        <Alert
          type="info"
          showIcon
          message="继续等待不会改变任务终态"
          description="请记录判断依据和下一次复查时间，系统将在指定时间后重新进入待处理队列。"
          style={{ marginBottom: 18 }}
        />
        <Form name="media-task-anomaly-wait" form={continueForm} layout="vertical">
          <Form.Item
            name="reason"
            label="继续等待原因"
            rules={[{ required: true, whitespace: true, message: '请输入继续等待原因' }, { max: 500, message: '原因不能超过500字' }]}
          >
            <Input.TextArea rows={4} maxLength={500} showCount placeholder="例如：上游任务仍在执行，预计稍后返回终态" />
          </Form.Item>
          <Form.Item
            name="nextReviewTime"
            label="下次复查时间"
            rules={[
              { required: true, message: '请选择下次复查时间' },
              { validator: (_, value) => !value || value.isAfter(dayjs()) ? Promise.resolve() : Promise.reject(new Error('复查时间必须晚于当前时间')) }
            ]}
          >
            <DatePicker showTime format="YYYY-MM-DD HH:mm:ss" style={{ width: '100%' }} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={`确认任务失败 · ${failureTarget?.taskId || ''}`}
        open={!!failureTarget}
        okText="下一步"
        cancelText="取消"
        okButtonProps={{ danger: true }}
        onOk={prepareConfirmFailure}
        onCancel={() => !mutationKey && setFailureTarget(undefined)}
      >
        <Alert
          type="warning"
          showIcon
          message="此操作将进入第二次确认"
          description="确认失败必须由服务端统一完成终态、退款和资源释放，本页不直接改写任务或账务数据。"
          style={{ marginBottom: 18 }}
        />
        <Form name="media-task-anomaly-failure" form={failureForm} layout="vertical">
          <Form.Item
            name="reason"
            label="确认失败原因"
            rules={[{ required: true, whitespace: true, message: '请输入确认失败原因' }, { max: 500, message: '原因不能超过500字' }]}
          >
            <Input.TextArea rows={4} maxLength={500} showCount placeholder="填写已核实的失败依据，便于审计追溯" />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="异常治理配置"
        open={configOpen}
        okText="保存配置"
        cancelText="取消"
        confirmLoading={configSaving}
        onOk={saveConfig}
        onCancel={() => !configSaving && setConfigOpen(false)}
      >
        <Alert
          type="info"
          showIcon
          message="配置用于所有媒体任务的异常识别与后台刷新"
          description="修改后服务端立即按新配置执行；已在处理中的任务仍以权威任务状态为准。"
          style={{ marginBottom: 18 }}
        />
        <Form name="media-task-anomaly-config" form={configForm} layout="vertical" disabled={configLoading}>
          <Form.Item name="enabled" label="启用异常治理" valuePropName="checked" rules={[{ required: true }]}>
            <Switch checkedChildren="启用" unCheckedChildren="停用" />
          </Form.Item>
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item
                name="anomalyThresholdSeconds"
                label="异常判定阈值（秒）"
                rules={[{ required: true, message: '请输入异常判定阈值' }]}
              >
                <InputNumber min={60} max={604800} precision={0} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                name="autoRecheckIntervalSeconds"
                label="自动复查间隔（秒）"
                rules={[{ required: true, message: '请输入自动复查间隔' }]}
              >
                <InputNumber min={60} max={86400} precision={0} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item
            name="adminRefreshSeconds"
            label="后台摘要刷新间隔（秒）"
            extra="顶部、首页和异常任务页共用此刷新间隔；页面隐藏时暂停。"
            rules={[{ required: true, message: '请输入后台摘要刷新间隔' }]}
          >
            <InputNumber min={10} max={3600} precision={0} style={{ width: '100%' }} />
          </Form.Item>
        </Form>
      </Modal>
      <Modal
        title="按任务 ID 复查"
        open={taskRecheckOpen}
        okText="开始复查"
        cancelText="取消"
        confirmLoading={taskRecheckSaving}
        onOk={handleTaskRecheck}
        onCancel={() => !taskRecheckSaving && setTaskRecheckOpen(false)}
      >
        <Alert type="info" showIcon message="查询已提交上游的媒体任务"
          description="适用于尚未进入异常列表的卡住任务。先查询上游真实状态；状态不明时进入待处理，确认失败仍需单独审查。"
          style={{ marginBottom: 18 }} />
        {taskRecheckError && <Alert type="error" showIcon message={taskRecheckError} style={{ marginBottom: 18 }} />}
        <Form name="media-task-anomaly-recheck" form={taskRecheckForm} layout="vertical" disabled={taskRecheckSaving}>
          <Form.Item name="taskId" label="任务 ID" rules={[
            { required: true, whitespace: true, message: '请输入任务 ID' },
            { validator: (_, value) => {
              const id = String(value || '').trim();
              return !id || (/^[1-9][0-9]{0,18}$/.test(id) && (id.length < 19 || id <= '9223372036854775807'))
                ? Promise.resolve() : Promise.reject(new Error('请输入有效的任务 ID'));
            } }
          ]}>
            <Input placeholder="输入任务 ID" maxLength={19} />
          </Form.Item>
          <Form.Item name="reason" label="复查原因" rules={[
            { required: true, whitespace: true, message: '请输入复查原因' },
            { validator: (_, value) => !value || String(value).trim().length >= 2 ? Promise.resolve() : Promise.reject(new Error('复查原因至少填写 2 字')) },
            { max: 500, message: '原因不能超过 500 字' }
          ]}>
            <Input.TextArea rows={3} maxLength={500} showCount placeholder="填写任务卡住的现象和已核实的依据" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
