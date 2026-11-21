import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  Alert,
  Avatar,
  Button,
  Card,
  DatePicker,
  Descriptions,
  Empty,
  Form,
  Input,
  Modal,
  Radio,
  Select,
  Space,
  Spin,
  Table,
  Tag,
  Typography,
  message
} from 'antd';
import type { ColumnsType, TableRowSelection } from 'antd/es/table/interface';
import {
  ClockCircleOutlined,
  EditOutlined,
  EyeOutlined,
  PlusOutlined,
  ReloadOutlined,
  SearchOutlined,
  SendOutlined,
  StopOutlined,
  TeamOutlined,
  UserOutlined
} from '@ant-design/icons';
import dayjs, { type Dayjs } from 'dayjs';

import {
  cancelScheduledNotification,
  getNotificationDetail,
  listNotificationRecipients,
  listNotifications,
  NOTIFICATION_PERMISSIONS,
  type NotificationActionRequest,
  type NotificationContentFormat,
  type NotificationDetail,
  type NotificationId,
  type NotificationListQuery,
  type NotificationRecipient,
  type NotificationSaveRequest,
  type NotificationStatus,
  type NotificationSummary,
  publishNotification,
  saveNotification,
  withdrawNotification
} from '@/api/aid/notification';
import Auth from '@/components/Auth';
import PageHeader from '@/components/PageHeader';
import Pagination from '@/components/Pagination';
import { useAuth } from '@/hooks/useAuth';
import { ApiBusinessError } from '@/utils/apiErrorDetails';
import './style.less';

const { Paragraph, Text, Title } = Typography;

type RecipientMode = 'ALL' | 'SINGLE' | 'USERS';
type DeliveryMode = 'NOW' | 'SCHEDULED';

interface EditorValues {
  title: string;
  summary?: string;
  content: string;
  contentFormat: NotificationContentFormat;
  recipientMode: RecipientMode;
  deliveryMode: DeliveryMode;
  scheduledTime?: Dayjs | null;
}

interface PreviewState {
  open: boolean;
  loading: boolean;
  detail?: NotificationDetail;
  error?: string;
  sourceId?: NotificationId;
}

interface PendingPublish {
  semanticKey: string;
  notification: NotificationDetail;
}

interface FrozenEditorOperation {
  stage: 'SAVE' | 'PUBLISH';
  intent: 'SAVE' | 'PUBLISH';
  semanticKey: string;
  savePayload?: NotificationSaveRequest;
  publishPayload?: NotificationActionRequest;
  notification?: NotificationDetail;
}

interface FrozenRowOperation {
  row: NotificationSummary;
  action: 'publish' | 'cancel' | 'withdraw';
  actionKey: string;
  payload: NotificationActionRequest;
}

const DEFAULT_QUERY: NotificationListQuery = { pageNum: 1, pageSize: 20 };
const DEFAULT_RECIPIENT_QUERY = { pageNum: 1, pageSize: 20, keyword: '' };

const STATUS_META: Record<NotificationStatus, { label: string; color: string }> = {
  DRAFT: { label: '草稿', color: 'default' },
  SCHEDULED: { label: '待定时发布', color: 'blue' },
  PUBLISHED: { label: '已发布', color: 'green' },
  WITHDRAWN: { label: '已撤回', color: 'red' }
};

const STATUS_OPTIONS = (Object.keys(STATUS_META) as NotificationStatus[]).map((value) => ({
  value,
  label: STATUS_META[value].label
}));

const BEIJING_TIME_FORMATTER = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Shanghai',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hourCycle: 'h23'
});

function errorText(error: unknown, fallback = '操作失败，请稍后重试') {
  if (error instanceof Error && error.message) return error.message;
  if (typeof error === 'string' && error) return error;
  return fallback;
}

function displayTime(value?: string | null) {
  if (!value) return '—';
  const parsed = dayjs(value);
  return parsed.isValid() ? parsed.format('YYYY-MM-DD HH:mm:ss') : value;
}

function uniqueIds(ids: NotificationId[]) {
  return Array.from(new Set(ids.filter(Boolean))).sort((left, right) => left.localeCompare(right));
}

function positiveInteger(value: unknown, fallback: number) {
  const parsed = Number(value);
  return Number.isInteger(parsed) && parsed > 0 ? parsed : fallback;
}

function createClientRequestId() {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `notification-${Date.now()}-${Math.random().toString(36).slice(2, 12)}`;
}

function beijingWallClockNow(now = new Date()) {
  const parts = Object.fromEntries(
    BEIJING_TIME_FORMATTER
      .formatToParts(now)
      .filter((part) => part.type !== 'literal')
      .map((part) => [part.type, part.value])
  );
  return dayjs(`${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}:${parts.second}`);
}

function isFutureBeijingWallClock(value?: string | Dayjs | null) {
  const parsed = value ? dayjs(value) : null;
  return Boolean(parsed?.isValid() && parsed.isAfter(beijingWallClockNow()));
}

function isFutureTime(value?: string | null) {
  return isFutureBeijingWallClock(value);
}

function placeholderRecipient(userId: NotificationId): NotificationRecipient {
  return { userId, userName: `用户 ${userId}`, nickName: null, avatar: null, status: '0' };
}

function RecipientPicker({
  open,
  mode,
  selectedIds,
  selectedUsers,
  onCancel,
  onConfirm
}: {
  open: boolean;
  mode: Exclude<RecipientMode, 'ALL'>;
  selectedIds: NotificationId[];
  selectedUsers: Map<NotificationId, NotificationRecipient>;
  onCancel: () => void;
  onConfirm: (ids: NotificationId[], users: Map<NotificationId, NotificationRecipient>) => void;
}) {
  const [searchForm] = Form.useForm();
  const [query, setQuery] = useState(DEFAULT_RECIPIENT_QUERY);
  const [pageMeta, setPageMeta] = useState({
    pageNum: DEFAULT_RECIPIENT_QUERY.pageNum,
    pageSize: DEFAULT_RECIPIENT_QUERY.pageSize
  });
  const [rows, setRows] = useState<NotificationRecipient[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [draftIds, setDraftIds] = useState<NotificationId[]>([]);
  const [draftUsers, setDraftUsers] = useState<Map<NotificationId, NotificationRecipient>>(new Map());
  const loadSequence = useRef(0);

  const loadRecipients = useCallback(async (force = false) => {
    if (!open) return;
    const sequence = ++loadSequence.current;
    setLoading(true);
    setError('');
    try {
      const response = await listNotificationRecipients({
        keyword: query.keyword?.trim() || undefined,
        pageNum: query.pageNum,
        pageSize: query.pageSize
      }, force);
      if (sequence !== loadSequence.current) return;
      setRows(Array.isArray(response.data) ? response.data : []);
      setTotal(Number(response.total || 0));
      setPageMeta({
        pageNum: positiveInteger(response.pageNum, query.pageNum),
        pageSize: positiveInteger(response.pageSize, query.pageSize)
      });
    } catch (error) {
      if (sequence === loadSequence.current) setError(errorText(error, '用户列表加载失败'));
    } finally {
      if (sequence === loadSequence.current) setLoading(false);
    }
  }, [open, query]);

  useEffect(() => {
    if (!open) {
      setQuery(DEFAULT_RECIPIENT_QUERY);
      setPageMeta({
        pageNum: DEFAULT_RECIPIENT_QUERY.pageNum,
        pageSize: DEFAULT_RECIPIENT_QUERY.pageSize
      });
      searchForm.resetFields();
      return;
    }
    const initialIds = mode === 'SINGLE' ? selectedIds.slice(0, 1) : selectedIds;
    setDraftIds(initialIds);
    setDraftUsers(new Map(initialIds.map((id) => [id, selectedUsers.get(id) || placeholderRecipient(id)])));
  }, [mode, open, searchForm, selectedIds, selectedUsers]);

  useEffect(() => {
    loadRecipients();
  }, [loadRecipients]);

  const rowSelection: TableRowSelection<NotificationRecipient> = {
    type: mode === 'SINGLE' ? 'radio' : 'checkbox',
    selectedRowKeys: draftIds,
    preserveSelectedRowKeys: true,
    getCheckboxProps: (row) => ({ disabled: String(row.status) !== '0', name: row.userName }),
    onSelect: (row, selected) => {
      if (mode === 'SINGLE') {
        setDraftIds(selected ? [row.userId] : []);
        setDraftUsers(selected ? new Map([[row.userId, row]]) : new Map());
        return;
      }
      setDraftIds((current) => selected
        ? uniqueIds([...current, row.userId])
        : current.filter((id) => id !== row.userId));
      setDraftUsers((current) => {
        const next = new Map(current);
        if (selected) next.set(row.userId, row);
        else next.delete(row.userId);
        return next;
      });
    },
    onSelectAll: (selected, _selectedRows, changedRows) => {
      const selectable = changedRows.filter((row) => String(row.status) === '0');
      setDraftIds((current) => selected
        ? uniqueIds([...current, ...selectable.map((row) => row.userId)])
        : current.filter((id) => !selectable.some((row) => row.userId === id)));
      setDraftUsers((current) => {
        const next = new Map(current);
        for (const row of selectable) {
          if (selected) next.set(row.userId, row);
          else next.delete(row.userId);
        }
        return next;
      });
    }
  };

  const columns: ColumnsType<NotificationRecipient> = [
    {
      title: '用户',
      dataIndex: 'userName',
      width: 240,
      render: (_value, row) => (
        <Space>
          <Avatar size={34} src={row.avatar || undefined} icon={<UserOutlined />} />
          <div>
            <div className="notification-user-name">{row.nickName || row.userName || '未命名用户'}</div>
            <div className="notification-secondary-text">账号：{row.userName || '—'}</div>
          </div>
        </Space>
      )
    },
    {
      title: '用户 ID',
      dataIndex: 'userId',
      width: 190,
      render: (value: NotificationId) => <Text copyable={{ text: value }}>{value}</Text>
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: (value: string) => String(value) === '0'
        ? <Tag color="green">正常</Tag>
        : <Tag>停用（不可选）</Tag>
    },
    { title: '注册时间', dataIndex: 'createTime', width: 170, render: displayTime }
  ];

  return (
    <Modal
      open={open}
      title={mode === 'SINGLE' ? '选择一个收件人' : '批量选择收件人'}
      width={860}
      maskClosable={false}
      destroyOnClose
      okText={`确认选择（${draftIds.length}）`}
      okButtonProps={{ disabled: mode === 'SINGLE' ? draftIds.length !== 1 : draftIds.length === 0 }}
      onCancel={onCancel}
      onOk={() => onConfirm(draftIds, draftUsers)}
    >
      <Form
        form={searchForm}
        layout="inline"
        className="notification-picker-search"
        onFinish={(values) => setQuery((current) => ({
          ...current,
          keyword: String(values.keyword || '').trim(),
          pageNum: 1
        }))}
      >
        <Form.Item name="keyword" label="用户关键词">
          <Input allowClear placeholder="搜索账号或昵称" maxLength={50} prefix={<SearchOutlined />} />
        </Form.Item>
        <Form.Item>
          <Space>
            <Button type="primary" htmlType="submit">搜索</Button>
            <Button onClick={() => {
              searchForm.resetFields();
              setQuery((current) => ({ ...current, keyword: '', pageNum: 1 }));
            }}>重置</Button>
            <Button icon={<ReloadOutlined />} loading={loading} onClick={() => loadRecipients(true)}>刷新</Button>
          </Space>
        </Form.Item>
      </Form>
      {error && (
        <Alert
          type="error"
          showIcon
          className="notification-inline-alert"
          message="用户列表加载失败"
          description={error}
          action={<Button size="small" onClick={() => loadRecipients(true)}>重试</Button>}
        />
      )}
      <Table
        rowKey="userId"
        size="middle"
        loading={loading}
        dataSource={rows}
        columns={columns}
        rowSelection={rowSelection}
        pagination={false}
        scroll={{ x: 720, y: 360 }}
        locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="未找到匹配的真实 C 端用户" /> }}
      />
      <Pagination
        total={total}
        page={pageMeta.pageNum}
        limit={pageMeta.pageSize}
        pageSizes={[10, 20, 50, 100]}
        onChange={(pageNum, pageSize) => setQuery((current) => ({ ...current, pageNum, pageSize }))}
      />
    </Modal>
  );
}

function NotificationPreview({ state, onCancel, onRetry }: {
  state: PreviewState;
  onCancel: () => void;
  onRetry: () => void;
}) {
  const detail = state.detail;
  const statusMeta = detail ? STATUS_META[detail.status] : null;
  return (
    <Modal open={state.open} title="通知预览" footer={null} width={760} destroyOnClose onCancel={onCancel}>
      <Spin spinning={state.loading} tip="正在读取通知详情…">
        {state.error ? (
          <Alert
            type="error"
            showIcon
            message="通知详情加载失败"
            description={state.error}
            action={<Button size="small" onClick={onRetry}>重试</Button>}
          />
        ) : detail ? (
          <article className="notification-preview">
            <div className="notification-preview__meta">
              {statusMeta && <Tag color={statusMeta.color}>{statusMeta.label}</Tag>}
              <Tag>{detail.contentFormat === 'MARKDOWN' ? 'Markdown' : '纯文本'}</Tag>
              <Text type="secondary">
                {detail.recipientType === 'ALL'
                  ? `全员动态可见（当前 ${detail.recipientCount || 0} 人）`
                  : `指定 ${detail.recipientCount || detail.recipientUserIds?.length || 0} 人`}
              </Text>
            </div>
            <Title level={3}>{detail.title || '未填写标题'}</Title>
            {detail.summary && <Paragraph type="secondary" className="notification-preview__summary">{detail.summary}</Paragraph>}
            <div className="notification-preview__content" aria-label="通知正文预览">{detail.content || '未填写正文'}</div>
            <Descriptions size="small" column={{ xs: 1, sm: 2 }} className="notification-preview__times">
              <Descriptions.Item label="定时时间">{displayTime(detail.scheduledTime)}</Descriptions.Item>
              <Descriptions.Item label="发布时间">{displayTime(detail.publishTime)}</Descriptions.Item>
            </Descriptions>
          </article>
        ) : <div className="notification-preview__placeholder" />}
      </Spin>
    </Modal>
  );
}

export default function NotificationPage() {
  const [modal, modalContextHolder] = Modal.useModal();
  const [queryForm] = Form.useForm();
  const [editorForm] = Form.useForm<EditorValues>();
  const { hasPermi } = useAuth();
  const canSave = hasPermi(NOTIFICATION_PERMISSIONS.save);
  const canPublish = hasPermi(NOTIFICATION_PERMISSIONS.publish);
  const canChooseRecipients = hasPermi(NOTIFICATION_PERMISSIONS.recipients);

  const [query, setQuery] = useState<NotificationListQuery>(DEFAULT_QUERY);
  const [pageMeta, setPageMeta] = useState({
    pageNum: DEFAULT_QUERY.pageNum,
    pageSize: DEFAULT_QUERY.pageSize
  });
  const [rows, setRows] = useState<NotificationSummary[]>([]);
  const [total, setTotal] = useState(0);
  const [listLoading, setListLoading] = useState(false);
  const [listError, setListError] = useState('');
  const [actionError, setActionError] = useState('');
  const [runningActionKey, setRunningActionKey] = useState('');
  const [editorOpen, setEditorOpen] = useState(false);
  const [editorLoading, setEditorLoading] = useState(false);
  const [editorBusy, setEditorBusy] = useState(false);
  const [editorDirty, setEditorDirty] = useState(false);
  const [editorError, setEditorError] = useState('');
  const [editorTargetId, setEditorTargetId] = useState<NotificationId>();
  const [editorDetail, setEditorDetail] = useState<NotificationDetail>();
  const [recipientIds, setRecipientIds] = useState<NotificationId[]>([]);
  const [selectedUsers, setSelectedUsers] = useState<Map<NotificationId, NotificationRecipient>>(new Map());
  const [recipientPickerOpen, setRecipientPickerOpen] = useState(false);
  const [preview, setPreview] = useState<PreviewState>({ open: false, loading: false });

  const listSequence = useRef(0);
  const editorSequence = useRef(0);
  const previewSequence = useRef(0);
  const editorBusyRef = useRef(false);
  const editorIntentRef = useRef(false);
  const runningActionsRef = useRef(new Set<string>());
  const requestIdsRef = useRef(new Map<string, string>());
  const pendingPublishRef = useRef<PendingPublish>();
  const frozenEditorOperationRef = useRef<FrozenEditorOperation>();
  const frozenRowOperationRef = useRef<FrozenRowOperation>();

  const getClientRequestId = useCallback((businessKey: string) => {
    const existing = requestIdsRef.current.get(businessKey);
    if (existing) return existing;
    if (requestIdsRef.current.size > 100) requestIdsRef.current.clear();
    const value = createClientRequestId();
    requestIdsRef.current.set(businessKey, value);
    return value;
  }, []);

  const forgetClientRequestId = useCallback((clientRequestId: string) => {
    for (const [key, value] of requestIdsRef.current) {
      if (value === clientRequestId) requestIdsRef.current.delete(key);
    }
  }, []);

  const loadList = useCallback(async (force = false) => {
    const sequence = ++listSequence.current;
    setListLoading(true);
    setListError('');
    try {
      const response = await listNotifications({
        pageNum: query.pageNum,
        pageSize: query.pageSize,
        status: query.status,
        keyword: query.keyword?.trim() || undefined
      }, force);
      if (sequence !== listSequence.current) return;
      setRows(Array.isArray(response.data) ? response.data : []);
      setTotal(Number(response.total || 0));
      setPageMeta({
        pageNum: positiveInteger(response.pageNum, query.pageNum),
        pageSize: positiveInteger(response.pageSize, query.pageSize)
      });
    } catch (error) {
      if (sequence === listSequence.current) setListError(errorText(error, '通知列表加载失败'));
    } finally {
      if (sequence === listSequence.current) setListLoading(false);
    }
  }, [query]);

  useEffect(() => {
    loadList();
  }, [loadList]);

  const closeEditor = useCallback(() => {
    if (frozenEditorOperationRef.current) return;
    setEditorOpen(false);
    setEditorTargetId(undefined);
    setEditorDetail(undefined);
    setEditorError('');
    setRecipientIds([]);
    setSelectedUsers(new Map());
    pendingPublishRef.current = undefined;
    frozenEditorOperationRef.current = undefined;
    editorIntentRef.current = false;
    editorForm.resetFields();
  }, [editorForm]);

  const requestCloseEditor = () => {
    if (editorBusyRef.current) return;
    if (frozenEditorOperationRef.current) {
      setEditorError('上次写入结果仍未知，请先恢复原操作确认服务端结果。恢复成功或服务端明确拒绝后才能关闭。');
      message.warning('请先恢复结果未知的原操作');
      return;
    }
    if (!editorDirty) {
      closeEditor();
      return;
    }
    modal.confirm({
      title: '放弃未保存修改？',
      content: '关闭后，本次填写的通知内容和收件人选择不会保留。',
      okText: '放弃修改',
      okButtonProps: { danger: true },
      cancelText: '继续编辑',
      onOk: closeEditor
    });
  };

  const openCreate = () => {
    if (frozenRowOperationRef.current) {
      const unresolved = frozenRowOperationRef.current;
      setActionError(`通知“${unresolved.row.title}”的上次操作结果仍未知，请先恢复原操作。`);
      message.warning('请先恢复结果未知的原操作');
      return;
    }
    if (frozenEditorOperationRef.current) {
      setEditorOpen(true);
      setEditorError('上次写入结果仍未知，请先恢复原操作，不能开始新的通知。');
      return;
    }
    ++editorSequence.current;
    editorForm.resetFields();
    editorForm.setFieldsValue({
      title: '',
      summary: '',
      content: '',
      contentFormat: 'MARKDOWN',
      recipientMode: 'ALL',
      deliveryMode: 'NOW',
      scheduledTime: null
    });
    setEditorTargetId(undefined);
    setEditorDetail(undefined);
    setRecipientIds([]);
    setSelectedUsers(new Map());
    setEditorError('');
    setEditorDirty(false);
    setEditorLoading(false);
    pendingPublishRef.current = undefined;
    frozenEditorOperationRef.current = undefined;
    setEditorOpen(true);
  };

  const openEdit = async (row: NotificationSummary) => {
    if (frozenRowOperationRef.current) {
      const unresolved = frozenRowOperationRef.current;
      setActionError(`通知“${unresolved.row.title}”的上次操作结果仍未知，请先恢复原操作。`);
      message.warning('请先恢复结果未知的原操作');
      return;
    }
    if (frozenEditorOperationRef.current) {
      setEditorOpen(true);
      setEditorError('上次写入结果仍未知，请先恢复原操作，不能切换到其他通知。');
      return;
    }
    const sequence = ++editorSequence.current;
    setEditorOpen(true);
    setEditorLoading(true);
    setEditorError('');
    setEditorTargetId(row.id);
    setEditorDetail(undefined);
    setRecipientIds([]);
    setSelectedUsers(new Map());
    setEditorDirty(false);
    pendingPublishRef.current = undefined;
    frozenEditorOperationRef.current = undefined;
    editorForm.resetFields();
    try {
      const response = await getNotificationDetail({ id: row.id });
      if (sequence !== editorSequence.current) return;
      const detail = response.data;
      const ids = uniqueIds(detail.recipientUserIds || []);
      setEditorDetail(detail);
      setRecipientIds(ids);
      setSelectedUsers(new Map(ids.map((id) => [id, placeholderRecipient(id)])));
      editorForm.setFieldsValue({
        title: detail.title,
        summary: detail.summary || '',
        content: detail.content,
        contentFormat: detail.contentFormat || 'MARKDOWN',
        recipientMode: detail.recipientType === 'ALL' ? 'ALL' : ids.length === 1 ? 'SINGLE' : 'USERS',
        deliveryMode: detail.scheduledTime ? 'SCHEDULED' : 'NOW',
        scheduledTime: detail.scheduledTime ? dayjs(detail.scheduledTime) : null
      });
    } catch (error) {
      if (sequence === editorSequence.current) setEditorError(errorText(error, '通知详情加载失败'));
    } finally {
      if (sequence === editorSequence.current) setEditorLoading(false);
    }
  };

  const recipientMode = Form.useWatch('recipientMode', editorForm) || 'ALL';
  const deliveryMode = Form.useWatch('deliveryMode', editorForm) || 'NOW';
  const editorHasFrozenOperation = Boolean(frozenEditorOperationRef.current);
  const rowHasFrozenOperation = Boolean(frozenRowOperationRef.current);
  const hasUnresolvedWrite = editorHasFrozenOperation || rowHasFrozenOperation;
  const editorIsDraft = editorTargetId ? editorDetail?.status === 'DRAFT' : true;
  const editorReady = !editorTargetId || Boolean(editorDetail);

  useEffect(() => {
    if (!hasUnresolvedWrite) return undefined;
    const handleBeforeUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = '';
    };
    window.addEventListener('beforeunload', handleBeforeUnload);
    return () => window.removeEventListener('beforeunload', handleBeforeUnload);
  }, [hasUnresolvedWrite]);

  const removeRecipient = (userId: NotificationId) => {
    setRecipientIds((current) => current.filter((id) => id !== userId));
    setSelectedUsers((current) => {
      const next = new Map(current);
      next.delete(userId);
      return next;
    });
    setEditorDirty(true);
  };

  const semanticKey = (values: EditorValues) => JSON.stringify({
    id: editorDetail?.id || null,
    title: values.title.trim(),
    summary: values.summary?.trim() || '',
    content: values.content,
    contentFormat: values.contentFormat,
    recipientMode: values.recipientMode,
    recipientIds: values.recipientMode === 'ALL' ? [] : uniqueIds(recipientIds),
    deliveryMode: values.deliveryMode,
    scheduledTime: values.deliveryMode === 'SCHEDULED' && values.scheduledTime
      ? values.scheduledTime.format('YYYY-MM-DD HH:mm:ss')
      : null
  });

  const validateEditor = async () => {
    const values = await editorForm.validateFields();
    const ids = uniqueIds(recipientIds);
    if (values.recipientMode === 'SINGLE' && ids.length !== 1) {
      editorForm.setFields([{ name: 'recipientMode', errors: ['请选择且仅选择一个收件人'] }]);
      throw new Error('请选择且仅选择一个收件人');
    }
    if (values.recipientMode === 'USERS' && ids.length === 0) {
      editorForm.setFields([{ name: 'recipientMode', errors: ['请至少选择一个收件人'] }]);
      throw new Error('请至少选择一个收件人');
    }
    if (ids.length > 5000) {
      editorForm.setFields([{ name: 'recipientMode', errors: ['一次最多选择 5000 个收件人'] }]);
      throw new Error('一次最多选择 5000 个收件人');
    }
    return values;
  };

  const executeFrozenEditorOperation = async (initialOperation: FrozenEditorOperation) => {
    if (editorBusyRef.current) return;
    editorBusyRef.current = true;
    setEditorBusy(true);
    setEditorError('');
    try {
      let operation = initialOperation;
      let saved = operation.notification;
      if (operation.stage === 'SAVE') {
        if (!operation.savePayload) throw new Error('缺少待恢复的草稿请求');
        const response = await saveNotification(operation.savePayload);
        saved = response.data;
        forgetClientRequestId(operation.savePayload.clientRequestId);
        setEditorDetail(saved);
        setEditorTargetId(saved.id);
        setEditorDirty(false);
        if (saved.status !== 'DRAFT') {
          pendingPublishRef.current = undefined;
          frozenEditorOperationRef.current = undefined;
          setEditorError(`原保存请求已得到响应，但通知当前状态为“${STATUS_META[saved.status]?.label || saved.status}”。已停止后续发布，请刷新列表核实当前状态。`);
          await loadList(true);
          return;
        }
        if (operation.intent === 'SAVE') {
          pendingPublishRef.current = undefined;
          frozenEditorOperationRef.current = undefined;
          message.success('通知草稿已保存');
          closeEditor();
          await loadList(true);
          return;
        }
        const publishKey = `publish:${saved.id}:${saved.version}`;
        const publishPayload: NotificationActionRequest = {
          id: saved.id,
          expectedVersion: saved.version,
          clientRequestId: getClientRequestId(publishKey)
        };
        operation = {
          stage: 'PUBLISH',
          intent: 'PUBLISH',
          semanticKey: operation.semanticKey,
          publishPayload,
          notification: saved
        };
        pendingPublishRef.current = { semanticKey: operation.semanticKey, notification: saved };
        frozenEditorOperationRef.current = operation;
      }

      if (!operation.publishPayload || !saved) throw new Error('缺少待恢复的发布请求');
      const response = await publishNotification(operation.publishPayload);
      forgetClientRequestId(operation.publishPayload.clientRequestId);
      pendingPublishRef.current = undefined;
      frozenEditorOperationRef.current = undefined;
      if (response.data.status !== 'PUBLISHED' && response.data.status !== 'SCHEDULED') {
        setEditorTargetId(response.data.id);
        setEditorError(`原发布请求已得到响应，但通知当前状态为“${STATUS_META[response.data.status]?.label || response.data.status}”。未按本次操作提示发布成功，请刷新列表核实。`);
        await loadList(true);
        return;
      }
      message.success(response.data.status === 'SCHEDULED' ? '通知已进入定时发布队列' : '通知已立即发布');
      closeEditor();
      await loadList(true);
    } catch (error) {
      if (error instanceof ApiBusinessError) {
        const frozen = frozenEditorOperationRef.current;
        if (frozen?.savePayload) forgetClientRequestId(frozen.savePayload.clientRequestId);
        if (frozen?.publishPayload) forgetClientRequestId(frozen.publishPayload.clientRequestId);
        frozenEditorOperationRef.current = undefined;
        pendingPublishRef.current = undefined;
        setEditorError(error.code === 409
          ? `${error.message}。服务端内容或状态已变化，请关闭后重新打开草稿再操作。`
          : `${error.message}。服务端已明确拒绝本次写入，请修正后重试。`);
      } else {
        setEditorError(`${errorText(error)}。本次写入结果未知，已冻结原请求体和 clientRequestId，请使用“恢复原操作”核实结果。`);
      }
    } finally {
      editorBusyRef.current = false;
      setEditorBusy(false);
    }
  };

  const persistEditor = async (values: EditorValues, publishAfterSave: boolean) => {
    const semantic = semanticKey(values);
    const previousFrozen = frozenEditorOperationRef.current;
    if (previousFrozen) {
      await executeFrozenEditorOperation(previousFrozen);
      return;
    }
    const pendingPublish = publishAfterSave && pendingPublishRef.current?.semanticKey === semantic
      ? pendingPublishRef.current.notification
      : undefined;
    if (pendingPublish) {
      const publishKey = `publish:${pendingPublish.id}:${pendingPublish.version}`;
      const operation: FrozenEditorOperation = {
        stage: 'PUBLISH',
        intent: 'PUBLISH',
        semanticKey: semantic,
        notification: pendingPublish,
        publishPayload: {
          id: pendingPublish.id,
          expectedVersion: pendingPublish.version,
          clientRequestId: getClientRequestId(publishKey)
        }
      };
      frozenEditorOperationRef.current = operation;
      await executeFrozenEditorOperation(operation);
      return;
    }

    const recipientUserIds = values.recipientMode === 'ALL' ? [] : uniqueIds(recipientIds);
    const scheduledTime = values.deliveryMode === 'SCHEDULED' && values.scheduledTime
      ? values.scheduledTime.format('YYYY-MM-DD HH:mm:ss')
      : null;
    const saveKey = `save:${editorDetail?.id || 'new'}:${editorDetail?.version || 'new'}:${semantic}`;
    const savePayload: NotificationSaveRequest = {
      ...(editorDetail?.id ? { id: editorDetail.id, expectedVersion: editorDetail.version } : {}),
      title: values.title.trim(),
      summary: values.summary?.trim() || undefined,
      content: values.content,
      contentFormat: values.contentFormat,
      recipientType: values.recipientMode === 'ALL' ? 'ALL' : 'USERS',
      recipientUserIds,
      scheduledTime,
      clientRequestId: getClientRequestId(saveKey)
    };
    const operation: FrozenEditorOperation = {
      stage: 'SAVE',
      intent: publishAfterSave ? 'PUBLISH' : 'SAVE',
      semanticKey: semantic,
      savePayload
    };
    frozenEditorOperationRef.current = operation;
    await executeFrozenEditorOperation(operation);
  };

  const retryFrozenEditorOperation = () => {
    const operation = frozenEditorOperationRef.current;
    if (!operation) return;
    const values = editorForm.getFieldsValue(true);
    const currentSemantic = semanticKey(values);
    const retry = () => executeFrozenEditorOperation(operation);
    if (currentSemantic === operation.semanticKey) {
      retry();
      return;
    }
    modal.confirm({
      title: '按原请求恢复？',
      content: '表单内容已经变化。恢复会按上次失败时冻结的请求体和同一个幂等键重试，不会提交当前新修改。',
      okText: '恢复原请求',
      cancelText: '返回检查',
      onOk: retry
    });
  };

  const submitEditor = async (publishAfterSave: boolean) => {
    if (editorIntentRef.current || editorBusyRef.current) return;
    if (frozenEditorOperationRef.current) {
      retryFrozenEditorOperation();
      return;
    }
    if (!editorReady) {
      setEditorError('通知详情尚未成功加载，不能保存或发布。请关闭后重新打开。');
      return;
    }
    editorIntentRef.current = true;
    let values: EditorValues;
    try {
      values = await validateEditor();
    } catch {
      editorIntentRef.current = false;
      return;
    }
    if (!publishAfterSave) {
      try {
        await persistEditor(values, false);
      } finally {
        editorIntentRef.current = false;
      }
      return;
    }

    const scheduled = values.deliveryMode === 'SCHEDULED' && values.scheduledTime;
    const isAll = values.recipientMode === 'ALL';
    modal.confirm({
      title: scheduled ? '确认安排定时发布？' : isAll ? '确认立即发送全员通知？' : '确认立即发布通知？',
      content: isAll
        ? scheduled
          ? `通知将在 ${scheduled.format('YYYY-MM-DD HH:mm:ss')} 向届时所有正常 C 端账户开放，并持续对之后注册的正常账户可见。`
          : '发布后，当前及之后注册的所有正常 C 端账户都可看到这条通知。请再次核对标题、正文和发送范围。'
        : scheduled
          ? `通知将在 ${scheduled.format('YYYY-MM-DD HH:mm:ss')} 发送给已选定的 ${recipientIds.length} 个用户。`
          : `通知将立即发送给已选定的 ${recipientIds.length} 个用户。`,
      okText: scheduled ? '确认定时发布' : '确认立即发布',
      cancelText: '返回检查',
      onOk: () => persistEditor(values, true),
      onCancel: () => { editorIntentRef.current = false; },
      afterClose: () => { editorIntentRef.current = false; }
    });
  };

  const previewEditor = () => {
    const values = editorForm.getFieldsValue(true);
    const detail: NotificationDetail = {
      id: editorDetail?.id || '',
      version: editorDetail?.version || '',
      title: values.title || '',
      summary: values.summary || '',
      content: values.content || '',
      contentFormat: values.contentFormat || 'MARKDOWN',
      status: editorDetail?.status || 'DRAFT',
      recipientType: values.recipientMode === 'ALL' ? 'ALL' : 'USERS',
      recipientCount: values.recipientMode === 'ALL' ? editorDetail?.recipientCount || 0 : recipientIds.length,
      recipientUserIds: values.recipientMode === 'ALL' ? [] : recipientIds,
      scheduledTime: values.deliveryMode === 'SCHEDULED' && values.scheduledTime
        ? values.scheduledTime.format('YYYY-MM-DD HH:mm:ss')
        : null,
      publishTime: editorDetail?.publishTime || null
    };
    setPreview({ open: true, loading: false, detail });
  };

  const openPreview = async (row: NotificationSummary, force = false) => {
    const sequence = ++previewSequence.current;
    setPreview({ open: true, loading: true, sourceId: row.id });
    try {
      const response = await getNotificationDetail({ id: row.id }, force);
      if (sequence === previewSequence.current) {
        setPreview({ open: true, loading: false, detail: response.data, sourceId: row.id });
      }
    } catch (error) {
      if (sequence === previewSequence.current) {
        setPreview({ open: true, loading: false, error: errorText(error), sourceId: row.id });
      }
    }
  };

  const performRowAction = async (
    row: NotificationSummary,
    action: 'publish' | 'cancel' | 'withdraw',
    frozen?: FrozenRowOperation
  ) => {
    const unresolved = frozenRowOperationRef.current;
    if (unresolved && !frozen) {
      setActionError(`通知“${unresolved.row.title}”的上次操作结果仍未知，请先恢复原操作。`);
      message.warning('请先恢复结果未知的原操作');
      return;
    }
    const actionKey = frozen?.actionKey || `${action}:${row.id}:${row.version}`;
    if (runningActionsRef.current.size > 0) return;
    runningActionsRef.current.add(actionKey);
    setRunningActionKey(actionKey);
    setActionError('');
    try {
      const payload: NotificationActionRequest = frozen?.payload || {
        id: row.id,
        expectedVersion: row.version,
        clientRequestId: getClientRequestId(actionKey)
      };
      frozenRowOperationRef.current = { row, action, actionKey, payload };
      const response = action === 'publish'
        ? await publishNotification(payload)
        : action === 'cancel'
          ? await cancelScheduledNotification(payload)
          : await withdrawNotification(payload);
      forgetClientRequestId(payload.clientRequestId);
      frozenRowOperationRef.current = undefined;
      const expectedStatuses: Record<typeof action, NotificationStatus[]> = {
        publish: ['PUBLISHED', 'SCHEDULED'],
        cancel: ['DRAFT'],
        withdraw: ['WITHDRAWN']
      };
      if (!expectedStatuses[action].includes(response.data.status)) {
        setActionError(`通知“${row.title}”已返回权威状态“${STATUS_META[response.data.status]?.label || response.data.status}”，与本次操作预期不一致。未提示操作成功，请刷新列表核实。`);
        await loadList(true);
        return;
      }
      message.success(
        action === 'publish'
          ? response.data.status === 'SCHEDULED' ? '通知已进入定时发布队列' : '通知已发布'
          : action === 'cancel' ? '定时发布已取消，通知已恢复为草稿' : '通知已撤回'
      );
      await loadList(true);
    } catch (error) {
      if (error instanceof ApiBusinessError) {
        forgetClientRequestId(frozenRowOperationRef.current?.payload.clientRequestId || '');
        frozenRowOperationRef.current = undefined;
        setActionError(error.code === 409
          ? `通知“${row.title}”状态或版本已变化：${error.message}。请刷新列表后重试。`
          : `通知“${row.title}”操作被服务端明确拒绝：${error.message}。请修正后重试。`);
      } else {
        setActionError(`通知“${row.title}”操作结果未知：${errorText(error)}。已冻结原请求体和 clientRequestId，请恢复原操作核实结果。`);
      }
    } finally {
      runningActionsRef.current.delete(actionKey);
      setRunningActionKey('');
    }
  };

  const confirmRowAction = (row: NotificationSummary, action: 'publish' | 'cancel' | 'withdraw') => {
    const unresolved = frozenRowOperationRef.current;
    if (unresolved) {
      setActionError(`通知“${unresolved.row.title}”的上次操作结果仍未知，请先恢复原操作。`);
      message.warning('请先恢复结果未知的原操作');
      return;
    }
    const scheduled = action === 'publish' && isFutureTime(row.scheduledTime);
    modal.confirm({
      title: action === 'publish'
        ? scheduled ? '确认启用定时发布？' : '确认立即发布？'
        : action === 'cancel' ? '确认取消定时发布？' : '确认撤回通知？',
      content: action === 'publish'
        ? row.recipientType === 'ALL'
          ? scheduled
            ? `通知将在 ${displayTime(row.scheduledTime)} 向届时及之后注册的正常账户开放。`
            : '这条通知将立即向当前及之后注册的所有正常 C 端账户开放。'
          : scheduled
            ? `通知将在 ${displayTime(row.scheduledTime)} 发送给固定选定的 ${row.recipientCount} 个用户。`
            : `通知将立即发送给固定选定的 ${row.recipientCount} 个用户。`
        : action === 'cancel'
          ? '取消后通知恢复为草稿，定时时间会被清除，可重新编辑后发布。'
          : '撤回后用户将不再看到该通知，历史发送与操作审计仍会保留。',
      okText: action === 'publish' ? scheduled ? '确认定时发布' : '确认发布' : action === 'cancel' ? '取消定时' : '确认撤回',
      okButtonProps: action === 'withdraw' ? { danger: true } : undefined,
      cancelText: '返回',
      onOk: () => performRowAction(row, action)
    });
  };

  const columns: ColumnsType<NotificationSummary> = [
    {
      title: '通知',
      dataIndex: 'title',
      width: 300,
      render: (_value, row) => (
        <div className="notification-title-cell">
          <Button type="link" className="notification-title-link" onClick={() => openPreview(row)}>{row.title}</Button>
          <div className="notification-secondary-text">{row.summary || '暂无摘要'}</div>
        </div>
      )
    },
    {
      title: '状态', dataIndex: 'status', width: 120,
      render: (value: NotificationStatus) => <Tag color={STATUS_META[value]?.color}>{STATUS_META[value]?.label || value}</Tag>
    },
    {
      title: '发送范围', dataIndex: 'recipientType', width: 180,
      render: (value, row) => value === 'ALL'
        ? <span><TeamOutlined /> 全员动态可见（当前 {row.recipientCount || 0} 人）</span>
        : <span><UserOutlined /> 指定 {row.recipientCount || 0} 人</span>
    },
    { title: '定时时间', dataIndex: 'scheduledTime', width: 170, render: displayTime },
    { title: '发布时间', dataIndex: 'publishTime', width: 170, render: displayTime },
    { title: '最近更新', dataIndex: 'updateTime', width: 170, render: displayTime },
    {
      title: '操作', key: 'actions', width: 260, fixed: 'right',
      render: (_value, row) => (
        <Space size={0} wrap>
          <Button type="link" size="small" icon={<EyeOutlined />} onClick={() => openPreview(row)}>预览</Button>
          {row.status === 'DRAFT' && (
            <Auth permission={NOTIFICATION_PERMISSIONS.save}>
              <Button type="link" size="small" icon={<EditOutlined />} disabled={hasUnresolvedWrite} onClick={() => openEdit(row)}>编辑</Button>
            </Auth>
          )}
          {row.status === 'DRAFT' && (
            <Auth permission={NOTIFICATION_PERMISSIONS.publish}>
              <Button
                type="link"
                size="small"
                icon={isFutureTime(row.scheduledTime) ? <ClockCircleOutlined /> : <SendOutlined />}
                loading={runningActionKey === `publish:${row.id}:${row.version}`}
                disabled={rowHasFrozenOperation || (Boolean(runningActionKey) && runningActionKey !== `publish:${row.id}:${row.version}`)}
                onClick={() => confirmRowAction(row, 'publish')}
              >
                {isFutureTime(row.scheduledTime) ? '启用定时' : '发布'}
              </Button>
            </Auth>
          )}
          {row.status === 'SCHEDULED' && (
            <Auth permission={NOTIFICATION_PERMISSIONS.cancelSchedule}>
              <Button
                type="link"
                size="small"
                icon={<StopOutlined />}
                loading={runningActionKey === `cancel:${row.id}:${row.version}`}
                disabled={rowHasFrozenOperation || (Boolean(runningActionKey) && runningActionKey !== `cancel:${row.id}:${row.version}`)}
                onClick={() => confirmRowAction(row, 'cancel')}
              >取消定时</Button>
            </Auth>
          )}
          {row.status === 'PUBLISHED' && (
            <Auth permission={NOTIFICATION_PERMISSIONS.withdraw}>
              <Button
                type="link"
                danger
                size="small"
                icon={<StopOutlined />}
                loading={runningActionKey === `withdraw:${row.id}:${row.version}`}
                disabled={rowHasFrozenOperation || (Boolean(runningActionKey) && runningActionKey !== `withdraw:${row.id}:${row.version}`)}
                onClick={() => confirmRowAction(row, 'withdraw')}
              >撤回</Button>
            </Auth>
          )}
        </Space>
      )
    }
  ];

  return (
    <div className="notification-page">
      {modalContextHolder}
      <PageHeader
        title="站内通知"
        desc="创建草稿并向单个、批量选定或全部真实 C 端用户立即或定时发布系统通知"
        extra={(
          <Auth permission={NOTIFICATION_PERMISSIONS.save}>
            <Button type="primary" icon={<PlusOutlined />} disabled={hasUnresolvedWrite} onClick={openCreate}>新建通知</Button>
          </Auth>
        )}
      />

      <Card bordered={false} className="page-card notification-search-card">
        <Form
          form={queryForm}
          layout="inline"
          onFinish={(values) => setQuery((current) => ({
            ...current,
            keyword: String(values.keyword || '').trim() || undefined,
            status: values.status || undefined,
            pageNum: 1
          }))}
        >
          <Form.Item name="keyword" label="关键词">
            <Input allowClear maxLength={50} placeholder="搜索标题或摘要" prefix={<SearchOutlined />} />
          </Form.Item>
          <Form.Item name="status" label="状态">
            <Select allowClear placeholder="全部状态" options={STATUS_OPTIONS} />
          </Form.Item>
          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit">查询</Button>
              <Button onClick={() => {
                queryForm.resetFields();
                setQuery((current) => ({ ...DEFAULT_QUERY, pageSize: current.pageSize }));
              }}>重置</Button>
              <Button icon={<ReloadOutlined />} loading={listLoading} onClick={() => loadList(true)}>刷新</Button>
            </Space>
          </Form.Item>
        </Form>
      </Card>

      <Card bordered={false} className="page-card">
        <div className="notification-toolbar">
          <Text type="secondary">列表顺序由服务端按最新发布优先返回；草稿与定时项同时按最近更新时间排序。</Text>
          <Text type="secondary">共 {total} 条</Text>
        </div>
        {listError && (
          <Alert
            type="error"
            showIcon
            className="notification-inline-alert"
            message="通知列表加载失败"
            description={listError}
            action={<Button size="small" onClick={() => loadList(true)}>重试</Button>}
          />
        )}
        {actionError && (
          <Alert
            type="error"
            showIcon
            className="notification-inline-alert"
            message={actionError}
            description={frozenRowOperationRef.current
              ? '当前页面已冻结失败时的请求体和 clientRequestId，可安全恢复原操作。若要刷新或离开，请先到通知列表核实原操作结果。'
              : undefined}
            action={frozenRowOperationRef.current ? (
              <Space wrap>
                <Button
                  size="small"
                  type="primary"
                  onClick={() => {
                    const frozen = frozenRowOperationRef.current;
                    if (frozen) performRowAction(frozen.row, frozen.action, frozen);
                  }}
                >恢复原操作</Button>
              </Space>
            ) : undefined}
          />
        )}
        <Table
          rowKey="id"
          loading={listLoading}
          dataSource={rows}
          columns={columns}
          pagination={false}
          scroll={{ x: 1320 }}
          locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无符合条件的站内通知" /> }}
        />
        <Pagination
          total={total}
          page={pageMeta.pageNum}
          limit={pageMeta.pageSize}
          pageSizes={[10, 20, 50, 100]}
          onChange={(pageNum, pageSize) => setQuery((current) => ({ ...current, pageNum, pageSize }))}
        />
      </Card>

      <Modal
        open={editorOpen}
        title={editorTargetId ? '编辑通知草稿' : '新建通知草稿'}
        width={960}
        maskClosable={false}
        destroyOnClose
        closable={!editorBusy && !editorHasFrozenOperation}
        onCancel={requestCloseEditor}
        footer={[
          <Button key="cancel" disabled={editorBusy || editorHasFrozenOperation} onClick={requestCloseEditor}>取消</Button>,
          <Button key="preview" icon={<EyeOutlined />} disabled={editorLoading} onClick={previewEditor}>预览</Button>,
          canSave && <Button key="save" loading={editorBusy} disabled={editorLoading || !editorReady || !editorIsDraft || editorHasFrozenOperation} onClick={() => submitEditor(false)}>保存草稿</Button>,
          canSave && canPublish && (
            <Button key="publish" type="primary" icon={<SendOutlined />} loading={editorBusy} disabled={editorLoading || !editorReady || !editorIsDraft || editorHasFrozenOperation} onClick={() => submitEditor(true)}>
              保存并发布
            </Button>
          )
        ].filter(Boolean)}
      >
        <Spin spinning={editorLoading} tip="正在读取草稿…">
          {editorError && (
            <Alert
              type="error"
              showIcon
              className="notification-inline-alert"
              message={editorError}
              description={frozenEditorOperationRef.current
                ? '当前页面已保留失败时的完整请求体和 clientRequestId。可恢复原操作，避免网络结果未知时重复创建或重复发布；刷新或离开前请先核实原操作结果。'
                : undefined}
              action={frozenEditorOperationRef.current ? (
                <Space wrap>
                  <Button size="small" type="primary" onClick={retryFrozenEditorOperation}>恢复原操作</Button>
                </Space>
              ) : undefined}
            />
          )}
          {editorDetail && !editorIsDraft && (
            <Alert
              type="warning"
              showIcon
              className="notification-inline-alert"
              message={`当前通知已变为“${STATUS_META[editorDetail.status]?.label || editorDetail.status}”`}
              description="仅草稿可以编辑。请关闭窗口刷新列表；定时通知需先取消定时后再编辑。"
            />
          )}
          <Form
            form={editorForm}
            layout="vertical"
            requiredMark="optional"
            className="notification-editor-form"
            disabled={editorHasFrozenOperation || !editorReady}
            onValuesChange={() => setEditorDirty(true)}
          >
            <div className="notification-form-grid">
              <Form.Item
                name="title"
                label="通知标题"
                rules={[{ required: true, whitespace: true, max: 100, message: '请输入 1–100 个字符的通知标题' }]}
              >
                <Input maxLength={100} showCount placeholder="简洁说明本次通知主题" autoFocus />
              </Form.Item>
              <Form.Item name="contentFormat" label="正文格式" rules={[{ required: true, message: '请选择正文格式' }]}>
                <Select options={[
                  { label: 'Markdown', value: 'MARKDOWN' },
                  { label: '纯文本', value: 'PLAIN_TEXT' }
                ]} />
              </Form.Item>
            </div>
            <Form.Item name="summary" label="通知摘要" extra="可留空，服务端会从正文生成最多 120 字的纯文本摘要。" rules={[{ max: 500, message: '摘要不能超过 500 个字符' }]}>
              <Input.TextArea rows={2} maxLength={500} showCount placeholder="用于列表快速浏览，可选" />
            </Form.Item>
            <Form.Item
              name="content"
              label="通知正文"
              rules={[{ required: true, whitespace: true, max: 100000, message: '请输入通知正文，最多 100000 个字符' }]}
            >
              <Input.TextArea rows={11} maxLength={100000} showCount placeholder="请输入完整通知正文" />
            </Form.Item>

            <section className="notification-form-section" aria-labelledby="notification-recipient-heading">
              <div className="notification-section-heading" id="notification-recipient-heading">发送范围</div>
              <Form.Item name="recipientMode" label="收件人类型" rules={[{ required: true, message: '请选择收件人类型' }]}>
                <Radio.Group buttonStyle="solid">
                  <Radio.Button value="ALL">全员</Radio.Button>
                  <Radio.Button value="SINGLE" disabled={!canChooseRecipients}>单个用户</Radio.Button>
                  <Radio.Button value="USERS" disabled={!canChooseRecipients}>批量选定用户</Radio.Button>
                </Radio.Group>
              </Form.Item>
              {recipientMode === 'ALL' ? (
                <Alert
                  type="warning"
                  showIcon
                  message="全员通知采用动态受众"
                  description="发布后，当前及以后注册的所有正常、未删除 C 端账户都可见；撤回后统一不可见。列表人数是查询时的当前可见用户数。"
                />
              ) : (
                <div className="notification-recipient-panel">
                  <div className="notification-recipient-panel__toolbar">
                    <div>
                      <div className="notification-user-name">已选择 {recipientIds.length} 人</div>
                      <div className="notification-secondary-text">仅可从真实 C 端用户分页中选择正常账户，不支持手工填写 ID 或 JSON。</div>
                    </div>
                    <Button
                      icon={<TeamOutlined />}
                      disabled={!canChooseRecipients}
                      onClick={() => setRecipientPickerOpen(true)}
                    >{recipientIds.length ? '调整收件人' : '选择收件人'}</Button>
                  </div>
                  <div className="notification-recipient-tags" aria-live="polite">
                    {recipientIds.length === 0 ? (
                      <Text type="secondary">尚未选择收件人</Text>
                    ) : recipientIds.slice(0, 30).map((id) => {
                      const user = selectedUsers.get(id);
                      return <Tag key={id} closable onClose={(event) => { event.preventDefault(); removeRecipient(id); }}>{user?.nickName || user?.userName || id}</Tag>;
                    })}
                    {recipientIds.length > 30 && <Tag>另有 {recipientIds.length - 30} 人</Tag>}
                  </div>
                </div>
              )}
            </section>

            <section className="notification-form-section" aria-labelledby="notification-delivery-heading">
              <div className="notification-section-heading" id="notification-delivery-heading">发布时间</div>
              <div className="notification-form-grid">
                <Form.Item name="deliveryMode" label="发布方式" rules={[{ required: true, message: '请选择发布方式' }]}>
                  <Radio.Group buttonStyle="solid">
                    <Radio.Button value="NOW">立即发布</Radio.Button>
                    <Radio.Button value="SCHEDULED">定时发布</Radio.Button>
                  </Radio.Group>
                </Form.Item>
                {deliveryMode === 'SCHEDULED' && (
                  <Form.Item
                    name="scheduledTime"
                    label="定时发布时间（北京时间 UTC+8）"
                    dependencies={['deliveryMode']}
                    rules={[
                      { required: true, message: '请选择定时发布时间' },
                      {
                        validator: (_rule, value) => !value || isFutureBeijingWallClock(value)
                          ? Promise.resolve()
                          : Promise.reject(new Error('定时发布时间必须晚于当前北京时间'))
                      }
                    ]}
                  >
                    <DatePicker
                      showTime
                      format="YYYY-MM-DD HH:mm:ss"
                      style={{ width: '100%' }}
                      disabledDate={(current) => current && current.isBefore(beijingWallClockNow().startOf('day'))}
                      placeholder="选择未来时间"
                    />
                  </Form.Item>
                )}
              </div>
              <Text type="secondary">保存草稿不会发送；点击“保存并发布”后，服务端根据定时时间进入已发布或待定时发布状态。</Text>
            </section>
          </Form>
        </Spin>
      </Modal>

      <RecipientPicker
        open={recipientPickerOpen}
        mode={recipientMode === 'SINGLE' ? 'SINGLE' : 'USERS'}
        selectedIds={recipientIds}
        selectedUsers={selectedUsers}
        onCancel={() => setRecipientPickerOpen(false)}
        onConfirm={(ids, users) => {
          const normalized = recipientMode === 'SINGLE' ? ids.slice(0, 1) : uniqueIds(ids);
          setRecipientIds(normalized);
          setSelectedUsers(new Map(normalized.map((id) => [id, users.get(id) || placeholderRecipient(id)])));
          setEditorDirty(true);
          editorForm.setFields([{ name: 'recipientMode', errors: [] }]);
          setRecipientPickerOpen(false);
        }}
      />

      <NotificationPreview
        state={preview}
        onCancel={() => setPreview({ open: false, loading: false })}
        onRetry={() => {
          if (preview.sourceId) {
            const row = rows.find((item) => item.id === preview.sourceId);
            if (row) openPreview(row, true);
          }
        }}
      />
    </div>
  );
}
