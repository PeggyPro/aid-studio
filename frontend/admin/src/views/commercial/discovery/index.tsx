import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Drawer,
  Empty,
  Form,
  Image,
  Input,
  InputNumber,
  Modal,
  Radio,
  Row,
  Col,
  Select,
  Space,
  Spin,
  Table,
  Tag,
  Timeline,
  Tooltip,
  Typography,
  message
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import {
  CheckCircleOutlined,
  DeleteOutlined,
  EditOutlined,
  EyeOutlined,
  FileImageOutlined,
  PlusOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined,
  SearchOutlined,
  StopOutlined,
  UndoOutlined,
  UserOutlined,
  VideoCameraOutlined
} from '@ant-design/icons';

import {
  addDiscovery,
  DISCOVERY_NAME_MAX_LENGTH,
  canRestoreDiscoveryContent,
  editDiscovery,
  getDiscoveryDetail,
  listDiscovery,
  removeDiscovery,
  reviewDiscovery,
  type DiscoveryContent,
  type DiscoveryDetail,
  type DiscoveryListQuery,
  type DiscoveryMediaType,
  type DiscoveryReviewAction,
  type DiscoveryReviewLog,
  type DiscoveryReviewStatus,
  type DiscoverySavePayload,
  type DiscoveryStatus,
  type DiscoverySourceType
} from '@/api/commercial/discovery';
import PageHeader from '@/components/PageHeader';
import { useAuth } from '@/hooks/useAuth';
import { loadUploadLimits, type UploadLimits } from '@/utils/uploadLimits';
import MediaUploader from './MediaUploader';
import { reconcilePreviewVideoUrl } from './mediaConsistency';
import { readMediaDimensions, type MediaDimensions } from './mediaMetadata';
import './style.less';

const MEDIA_TYPE_OPTIONS: Array<{ label: string; value: DiscoveryMediaType }> = [
  { label: '视频', value: 'video' },
  { label: '图片', value: 'image' }
];

const SOURCE_TYPE_OPTIONS: Array<{ label: string; value: DiscoverySourceType }> = [
  { label: '用户投稿', value: 'USER' },
  { label: '管理员新增', value: 'ADMIN' }
];

const REVIEW_STATUS_OPTIONS: Array<{ label: string; value: DiscoveryReviewStatus }> = [
  { label: '待审核', value: 'PENDING' },
  { label: '已通过', value: 'APPROVED' },
  { label: '已驳回', value: 'REJECTED' },
  { label: '已撤销', value: 'REVOKED' }
];

const REVIEW_META: Record<DiscoveryReviewStatus, { text: string; color: string }> = {
  PENDING: { text: '待审核', color: 'processing' },
  APPROVED: { text: '已通过', color: 'success' },
  REJECTED: { text: '已驳回', color: 'error' },
  REVOKED: { text: '已撤销', color: 'default' }
};

const REVIEW_LOG_ACTION_META: Record<string, { text: string; color: string }> = {
  SUBMIT: { text: '提交审核', color: 'blue' },
  RESUBMIT: { text: '重新提交', color: 'blue' },
  ADMIN_CREATE: { text: '后台新增', color: 'green' },
  APPROVE: { text: '审核通过', color: 'green' },
  REJECT: { text: '审核驳回', color: 'red' },
  REVOKE: { text: '撤销发布', color: 'gray' },
  UPDATE: { text: '编辑内容', color: 'blue' },
  DELETE: { text: '删除内容', color: 'red' }
};

const OPERATOR_TYPE_TEXT: Record<string, string> = {
  ADMIN: '管理员',
  USER: '用户',
  SYSTEM: '系统'
};

const REVIEW_ACTION_META: Record<
  DiscoveryReviewAction,
  {
    title: string;
    okText: string;
    tip: string;
    danger?: boolean;
    reasonRequired?: boolean;
  }
> = {
  APPROVE: {
    title: '通过发现内容',
    okText: '确认通过',
    tip: '通过后内容将进入可展示状态，请先核对媒体和文案。'
  },
  REJECT: {
    title: '驳回发现内容',
    okText: '确认驳回',
    tip: '驳回后内容不会展示，原因将作为本次审核记录保留。',
    danger: true,
    reasonRequired: true
  },
  REVOKE: {
    title: '撤销已通过内容',
    okText: '确认撤销',
    tip: '撤销后内容将立即退出可展示状态，原因必填。',
    danger: true,
    reasonRequired: true
  }
};

interface SearchValues {
  name?: string;
  mediaType?: DiscoveryMediaType;
  sourceType?: DiscoverySourceType;
  reviewStatus?: DiscoveryReviewStatus;
}

interface EditorValues {
  name: string;
  description: string;
  mediaType: DiscoveryMediaType;
  coverUrl: string;
  mediaUrl?: string | null;
  previewVideoUrl?: string | null;
  sortOrder?: number | null;
  status: DiscoveryStatus;
  promptText?: string | null;
}

interface ReviewValues {
  reason?: string;
}

function reviewTag(status: DiscoveryReviewStatus) {
  const meta = REVIEW_META[status];
  return <Tag color={meta?.color || 'default'}>{meta?.text || status}</Tag>;
}

function mediaTag(mediaType: DiscoveryMediaType) {
  return mediaType === 'video' ? (
    <Tag color="geekblue" icon={<VideoCameraOutlined />}>
      视频
    </Tag>
  ) : (
    <Tag color="purple" icon={<FileImageOutlined />}>
      图片
    </Tag>
  );
}

function sourceTag(sourceType: DiscoverySourceType) {
  return sourceType === 'ADMIN' ? (
    <Tag color="blue" icon={<SafetyCertificateOutlined />}>
      管理员新增
    </Tag>
  ) : (
    <Tag color="cyan" icon={<UserOutlined />}>
      用户投稿
    </Tag>
  );
}

function displayStatus(status?: DiscoveryStatus | null) {
  return status === '1' ? <Tag>已停用</Tag> : <Tag color="success">已启用</Tag>;
}

function reviewStatusText(status: DiscoveryReviewStatus | null) {
  return status ? REVIEW_META[status]?.text || status : '--';
}

function reviewLogItem(log: DiscoveryReviewLog, index: number) {
  const action = REVIEW_LOG_ACTION_META[log.action] || { text: log.action, color: 'gray' };
  const operatorType = log.operatorType ? OPERATOR_TYPE_TEXT[log.operatorType] || log.operatorType : '未知类型';
  return {
    key: `${log.createTime || 'unknown'}-${index}`,
    color: action.color,
    children: (
      <div className="discovery-review-log">
        <div className="discovery-review-log__head">
          <Space size={8} wrap>
            <Tag color={action.color}>{action.text}</Tag>
            <span>
              {reviewStatusText(log.beforeStatus)} → {reviewStatusText(log.afterStatus)}
            </span>
          </Space>
          <time>{log.createTime || '--'}</time>
        </div>
        {log.reason && <div className="discovery-review-log__reason">{log.reason}</div>}
        <div className="discovery-review-log__operator">
          操作人：{log.operatorName || '--'} · {operatorType}
        </div>
      </div>
    )
  };
}

function cleanSearch(values: SearchValues): Omit<DiscoveryListQuery, 'pageNum' | 'pageSize'> {
  return {
    name: values.name?.trim() || undefined,
    mediaType: values.mediaType,
    sourceType: values.sourceType,
    reviewStatus: values.reviewStatus
  };
}

export default function DiscoveryPage() {
  const { hasPermi } = useAuth();
  const canList = hasPermi('commercial:discovery:list');
  const canQuery = hasPermi('commercial:discovery:query');
  const canAdd = hasPermi('commercial:discovery:add');
  const canEdit = hasPermi('commercial:discovery:edit');
  const canReview = hasPermi('commercial:discovery:review');
  const canRemove = hasPermi('commercial:discovery:remove');

  const [searchForm] = Form.useForm<SearchValues>();
  const [editorForm] = Form.useForm<EditorValues>();
  const [reviewForm] = Form.useForm<ReviewValues>();

  const [records, setRecords] = useState<DiscoveryContent[]>([]);
  const [total, setTotal] = useState(0);
  const [pageNum, setPageNum] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [search, setSearch] = useState<Omit<DiscoveryListQuery, 'pageNum' | 'pageSize'>>({});
  const [listLoading, setListLoading] = useState(false);
  const [listError, setListError] = useState('');
  const listRequestRef = useRef(0);

  const [uploadLimits, setUploadLimits] = useState<UploadLimits | null>(null);
  const [limitsLoading, setLimitsLoading] = useState(true);
  const [uploadingFields, setUploadingFields] = useState<Set<string>>(new Set());

  const [detailOpen, setDetailOpen] = useState(false);
  const [detailTarget, setDetailTarget] = useState<DiscoveryContent | null>(null);
  const [detail, setDetail] = useState<DiscoveryDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState('');
  const detailRequestRef = useRef(0);

  const [editorOpen, setEditorOpen] = useState(false);
  const [editingRecord, setEditingRecord] = useState<DiscoveryContent | null>(null);
  const [editorLoading, setEditorLoading] = useState(false);
  const [editorError, setEditorError] = useState('');
  const [saving, setSaving] = useState(false);
  const [coverDimensions, setCoverDimensions] = useState<MediaDimensions>();
  const [videoDimensions, setVideoDimensions] = useState<MediaDimensions>();
  const [previewClearedByMediaChange, setPreviewClearedByMediaChange] = useState(false);
  const editorRequestRef = useRef(0);

  const [reviewTarget, setReviewTarget] = useState<DiscoveryContent | null>(null);
  const [reviewAction, setReviewAction] = useState<DiscoveryReviewAction>('APPROVE');
  const [reviewOpen, setReviewOpen] = useState(false);
  const [reviewSubmitting, setReviewSubmitting] = useState(false);

  const mediaType = Form.useWatch('mediaType', editorForm) || 'image';
  const hasUploadInProgress = uploadingFields.size > 0;

  useEffect(() => {
    let active = true;
    loadUploadLimits()
      .then((limits) => {
        if (active) setUploadLimits(limits);
      })
      .finally(() => {
        if (active) setLimitsLoading(false);
      });
    return () => {
      active = false;
    };
  }, []);

  const loadList = useCallback(async () => {
    if (!canList) return;
    const requestId = ++listRequestRef.current;
    setListLoading(true);
    setListError('');
    try {
      const response = await listDiscovery({ ...search, pageNum, pageSize });
      if (requestId !== listRequestRef.current) return;
      const nextRecords = response.data || [];
      const nextTotal = Number(response.total || 0);
      if (nextRecords.length === 0 && nextTotal > 0 && pageNum > 1) {
        setPageNum((current) => Math.max(1, current - 1));
        return;
      }
      setRecords(nextRecords);
      setTotal(nextTotal);
    } catch (error: any) {
      if (requestId !== listRequestRef.current) return;
      setRecords([]);
      setTotal(0);
      setListError(error?.message || '发现内容加载失败');
    } finally {
      if (requestId === listRequestRef.current) setListLoading(false);
    }
  }, [canList, pageNum, pageSize, search]);

  useEffect(() => {
    loadList();
  }, [loadList]);

  const setUploadState = useCallback((field: string, uploading: boolean) => {
    setUploadingFields((previous) => {
      const next = new Set(previous);
      if (uploading) next.add(field);
      else next.delete(field);
      return next;
    });
  }, []);

  const openDetail = useCallback(async (record: DiscoveryContent) => {
    const requestId = ++detailRequestRef.current;
    setDetailOpen(true);
    setDetailTarget(record);
    setDetail(null);
    setDetailError('');
    setDetailLoading(true);
    try {
      const response = await getDiscoveryDetail(record.id);
      if (requestId === detailRequestRef.current) setDetail(response.data);
    } catch (error: any) {
      if (requestId === detailRequestRef.current) setDetailError(error?.message || '详情加载失败');
    } finally {
      if (requestId === detailRequestRef.current) setDetailLoading(false);
    }
  }, []);

  const closeDetail = () => {
    detailRequestRef.current += 1;
    setDetailOpen(false);
    setDetailLoading(false);
  };

  const fillEditor = useCallback(
    (record: DiscoveryDetail) => {
      setPreviewClearedByMediaChange(false);
      editorForm.setFieldsValue({
        name: record.name,
        description: record.description,
        mediaType: record.mediaType,
        coverUrl: record.coverUrl,
        mediaUrl: record.mediaUrl,
        previewVideoUrl: record.previewVideoUrl,
        sortOrder: record.sortOrder ?? 0,
        status: record.status ?? '0',
        promptText: record.promptText
      });
      if (record.mediaType === 'image') {
        setCoverDimensions({ width: record.mediaWidth, height: record.mediaHeight });
        setVideoDimensions(undefined);
      } else {
        setCoverDimensions(undefined);
        setVideoDimensions({ width: record.mediaWidth, height: record.mediaHeight });
      }
    },
    [editorForm]
  );

  const openAdd = () => {
    editorRequestRef.current += 1;
    setEditingRecord(null);
    setEditorError('');
    setCoverDimensions(undefined);
    setVideoDimensions(undefined);
    setPreviewClearedByMediaChange(false);
    setUploadingFields(new Set());
    editorForm.resetFields();
    editorForm.setFieldsValue({ mediaType: 'image', sortOrder: 0, status: '0' });
    setEditorOpen(true);
  };

  const openEdit = async (record: DiscoveryContent) => {
    const requestId = ++editorRequestRef.current;
    setEditingRecord(record);
    setEditorOpen(true);
    setEditorLoading(true);
    setEditorError('');
    setPreviewClearedByMediaChange(false);
    setUploadingFields(new Set());
    editorForm.resetFields();
    try {
      const response = await getDiscoveryDetail(record.id);
      if (requestId !== editorRequestRef.current) return;
      setEditingRecord(response.data);
      fillEditor(response.data);
    } catch (error: any) {
      if (requestId === editorRequestRef.current) setEditorError(error?.message || '编辑数据加载失败');
    } finally {
      if (requestId === editorRequestRef.current) setEditorLoading(false);
    }
  };

  const closeEditor = () => {
    if (hasUploadInProgress) {
      message.warning('文件仍在上传，请等待上传完成后再关闭');
      return;
    }
    editorRequestRef.current += 1;
    setEditorLoading(false);
    setEditorOpen(false);
  };

  const changeMediaType = (nextType: DiscoveryMediaType) => {
    setPreviewClearedByMediaChange(false);
    if (nextType === 'image') {
      editorForm.setFieldValue('mediaUrl', null);
      editorForm.setFieldValue('previewVideoUrl', null);
      setVideoDimensions(undefined);
    } else {
      setVideoDimensions(undefined);
    }
  };

  const handleMainVideoFileChanged = () => {
    const result = reconcilePreviewVideoUrl(editorForm.getFieldValue('previewVideoUrl'), true);
    if (!result.cleared) return;
    editorForm.setFieldValue('previewVideoUrl', result.previewVideoUrl);
    setPreviewClearedByMediaChange(true);
  };

  const saveEditor = async () => {
    if (hasUploadInProgress) {
      message.warning('文件仍在上传，请等待上传完成');
      return;
    }
    let values: EditorValues;
    try {
      values = await editorForm.validateFields();
    } catch {
      return;
    }

    setSaving(true);
    try {
      const dimensionSource = values.mediaType === 'image' ? values.coverUrl : values.mediaUrl;
      let dimensions = values.mediaType === 'image' ? coverDimensions : videoDimensions;
      if (!dimensions && dimensionSource) {
        dimensions = await readMediaDimensions(dimensionSource, values.mediaType);
        if (values.mediaType === 'image') setCoverDimensions(dimensions);
        else setVideoDimensions(dimensions);
      }
      if (!dimensions || dimensions.width <= 0 || dimensions.height <= 0) {
        message.error(`无法读取${values.mediaType === 'image' ? '图片' : '视频'}真实宽高，请重新上传`);
        return;
      }

      const payload: DiscoverySavePayload = {
        name: values.name.trim(),
        description: values.description.trim(),
        mediaType: values.mediaType,
        coverUrl: values.coverUrl,
        mediaUrl: values.mediaType === 'image' ? null : values.mediaUrl || null,
        previewVideoUrl: values.mediaType === 'video' ? values.previewVideoUrl || null : null,
        mediaWidth: dimensions.width,
        mediaHeight: dimensions.height,
        promptText: values.promptText?.trim() || null,
        sortOrder: values.sortOrder ?? 0,
        status: values.status
      };
      if (editingRecord) await editDiscovery({ ...payload, id: editingRecord.id });
      else await addDiscovery(payload);

      message.success(
        editingRecord?.reviewStatus === 'APPROVED' ? '修改已直接生效' : editingRecord ? '修改成功' : '新增成功'
      );
      setEditorOpen(false);
      loadList();
      if (detailOpen && editingRecord && editingRecord.id === detail?.id) openDetail(editingRecord);
    } catch (error: any) {
      if (/无法读取/.test(error?.message || '')) {
        message.error(error.message);
      }
    } finally {
      setSaving(false);
    }
  };

  const openReview = (record: DiscoveryContent, action: DiscoveryReviewAction) => {
    setReviewTarget(record);
    setReviewAction(action);
    reviewForm.resetFields();
    setReviewOpen(true);
  };

  const submitReview = async (values: ReviewValues) => {
    if (!reviewTarget) return;
    const meta = REVIEW_ACTION_META[reviewAction];
    const reason = values.reason?.trim() || '';
    if (meta.reasonRequired && !reason) {
      reviewForm.setFields([{ name: 'reason', errors: ['请输入原因'] }]);
      return;
    }
    setReviewSubmitting(true);
    try {
      await reviewDiscovery({ id: reviewTarget.id, action: reviewAction, reason: reason || null });
      const restoring =
        reviewAction === 'APPROVE' && canRestoreDiscoveryContent(reviewTarget.sourceType, reviewTarget.reviewStatus);
      message.success(
        restoring
          ? '已恢复发布'
          : reviewAction === 'APPROVE'
            ? '已通过'
            : reviewAction === 'REJECT'
              ? '已驳回'
              : '已撤销'
      );
      setReviewOpen(false);
      if (detailOpen && detail?.id === reviewTarget.id) closeDetail();
      loadList();
    } finally {
      setReviewSubmitting(false);
    }
  };

  const confirmRemove = (record: DiscoveryContent) => {
    Modal.confirm({
      title: '删除发现内容',
      content: `确认删除「${record.name}」吗？删除后将无法在管理端恢复。`,
      okText: '确认删除',
      cancelText: '取消',
      okButtonProps: { danger: true },
      centered: true,
      autoFocusButton: 'cancel',
      onOk: async () => {
        await removeDiscovery(record.id);
        message.success('删除成功');
        if (detailOpen && detail?.id === record.id) closeDetail();
        loadList();
      }
    });
  };

  const actionButtons = (record: DiscoveryContent, compact = false, hideDetail = false) => (
    <Space size={compact ? 2 : 8} wrap>
      {canQuery && !hideDetail && (
        <Button type="link" size="small" icon={<EyeOutlined />} onClick={() => openDetail(record)}>
          详情
        </Button>
      )}
      {canEdit && canQuery && (
        <Button type="link" size="small" icon={<EditOutlined />} onClick={() => openEdit(record)}>
          编辑
        </Button>
      )}
      {canReview && record.reviewStatus === 'PENDING' && (
        <>
          <Button type="link" size="small" icon={<CheckCircleOutlined />} onClick={() => openReview(record, 'APPROVE')}>
            通过
          </Button>
          <Button type="link" size="small" danger icon={<StopOutlined />} onClick={() => openReview(record, 'REJECT')}>
            驳回
          </Button>
        </>
      )}
      {canReview && record.reviewStatus === 'APPROVED' && (
        <Button type="link" size="small" danger icon={<UndoOutlined />} onClick={() => openReview(record, 'REVOKE')}>
          撤销
        </Button>
      )}
      {canReview && canRestoreDiscoveryContent(record.sourceType, record.reviewStatus) && (
        <Button type="link" size="small" icon={<CheckCircleOutlined />} onClick={() => openReview(record, 'APPROVE')}>
          恢复发布
        </Button>
      )}
      {canRemove && (
        <Button type="link" size="small" danger icon={<DeleteOutlined />} onClick={() => confirmRemove(record)}>
          删除
        </Button>
      )}
    </Space>
  );

  const columns: ColumnsType<DiscoveryContent> = [
    {
      title: '内容',
      key: 'content',
      width: 310,
      fixed: 'left',
      render: (_value, record) => (
        <div className="discovery-content-cell">
          <button
            type="button"
            className="discovery-content-cell__cover"
            aria-label={`查看 ${record.name} 详情`}
            disabled={!canQuery}
            onClick={() => openDetail(record)}
          >
            {record.coverUrl ? <img src={record.coverUrl} alt="" loading="lazy" /> : <FileImageOutlined />}
            {record.mediaType === 'video' && (
              <span className="discovery-content-cell__play">
                <VideoCameraOutlined />
              </span>
            )}
          </button>
          <div className="discovery-content-cell__copy">
            <Typography.Text strong ellipsis={{ tooltip: record.name }}>
              {record.name}
            </Typography.Text>
            <Typography.Text type="secondary" ellipsis={{ tooltip: record.description }}>
              {record.description}
            </Typography.Text>
          </div>
        </div>
      )
    },
    { title: '来源', dataIndex: 'sourceType', width: 125, render: sourceTag },
    { title: '媒体类型', dataIndex: 'mediaType', width: 100, render: mediaTag },
    {
      title: '投稿人',
      key: 'submitter',
      width: 160,
      render: (_value, record) => (
        <div className="discovery-submitter">
          <span>{record.submitterName || (record.sourceType === 'ADMIN' ? '管理员' : '--')}</span>
          {record.userId != null && <small>ID {record.userId}</small>}
        </div>
      )
    },
    { title: '当前状态', dataIndex: 'reviewStatus', width: 100, render: reviewTag },
    {
      title: '最近原因',
      dataIndex: 'reviewReason',
      width: 210,
      ellipsis: true,
      render: (value?: string | null) =>
        value ? (
          <Tooltip title={value}>
            <span>{value}</span>
          </Tooltip>
        ) : (
          <span className="discovery-muted">--</span>
        )
    },
    {
      title: '审核时间',
      dataIndex: 'reviewTime',
      width: 170,
      render: (value: string | null, record) => (
        <div className="discovery-submitter">
          <span>{value || '--'}</span>
          {record.reviewBy && <small>审核人：{record.reviewBy}</small>}
        </div>
      )
    },
    {
      title: '排序',
      dataIndex: 'sortOrder',
      width: 80,
      align: 'center',
      render: (value?: number | null) => value ?? 0
    },
    {
      title: '操作',
      key: 'actions',
      width: 330,
      fixed: 'right',
      render: (_value, record) => actionButtons(record, true)
    }
  ];

  const isRestoreReview =
    reviewAction === 'APPROVE' &&
    !!reviewTarget &&
    canRestoreDiscoveryContent(reviewTarget.sourceType, reviewTarget.reviewStatus);
  const reviewMeta = isRestoreReview
    ? {
        title: '恢复发布',
        okText: '确认恢复发布',
        tip: '确认恢复这条管理员内容吗？恢复后将重新进入已通过状态并立即发布。',
        danger: false,
        reasonRequired: false
      }
    : REVIEW_ACTION_META[reviewAction];

  if (!canList) {
    return (
      <div className="discovery-page">
        <PageHeader title="发现内容" desc="管理用户投稿与管理员新增的发现页内容" />
        <Card bordered={false}>
          <Empty description="您没有发现内容列表权限" />
        </Card>
      </div>
    );
  }

  return (
    <div className="discovery-page">
      <PageHeader
        title="发现内容"
        desc="独立管理发现页的视频与图片投稿、审核状态和展示内容"
        extra={
          canAdd ? (
            <Button type="primary" icon={<PlusOutlined />} onClick={openAdd}>
              新增内容
            </Button>
          ) : undefined
        }
      />

      <Card bordered={false} className="discovery-search-card">
        <Form
          form={searchForm}
          layout="inline"
          onFinish={(values) => {
            setPageNum(1);
            setSearch(cleanSearch(values));
          }}
        >
          <Form.Item name="name" label="名称">
            <Input allowClear placeholder="输入内容名称" prefix={<SearchOutlined />} />
          </Form.Item>
          <Form.Item name="mediaType" label="媒体类型">
            <Select allowClear placeholder="全部类型" options={MEDIA_TYPE_OPTIONS} />
          </Form.Item>
          <Form.Item name="sourceType" label="来源">
            <Select allowClear placeholder="全部来源" options={SOURCE_TYPE_OPTIONS} />
          </Form.Item>
          <Form.Item name="reviewStatus" label="审核状态">
            <Select allowClear placeholder="全部状态" options={REVIEW_STATUS_OPTIONS} />
          </Form.Item>
          <Form.Item className="discovery-search-card__actions">
            <Space>
              <Button type="primary" htmlType="submit" icon={<SearchOutlined />}>
                查询
              </Button>
              <Button
                icon={<ReloadOutlined />}
                onClick={() => {
                  searchForm.resetFields();
                  setPageNum(1);
                  setSearch({});
                }}
              >
                重置
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Card>

      <Card bordered={false} className="discovery-table-card">
        <div className="discovery-table-card__head">
          <div>
            <strong>内容列表</strong>
            <span>共 {total} 条</span>
          </div>
          <Button icon={<ReloadOutlined />} loading={listLoading} onClick={loadList}>
            刷新
          </Button>
        </div>
        {listError && (
          <Alert
            type="error"
            showIcon
            message="列表加载失败"
            description={listError}
            action={
              <Button size="small" onClick={loadList}>
                重试
              </Button>
            }
            className="discovery-list-alert"
          />
        )}
        <Table<DiscoveryContent>
          rowKey="id"
          loading={listLoading}
          columns={columns}
          dataSource={records}
          scroll={{ x: 1585 }}
          pagination={{
            current: pageNum,
            pageSize,
            total,
            showSizeChanger: true,
            showQuickJumper: total > pageSize * 2,
            showTotal: (value) => `共 ${value} 条`,
            onChange: (nextPage, nextSize) => {
              setPageNum(nextSize !== pageSize ? 1 : nextPage);
              setPageSize(nextSize);
            }
          }}
          locale={{ emptyText: <Empty description={listError ? '暂时无法获取内容' : '暂无符合条件的发现内容'} /> }}
        />
      </Card>

      <Drawer
        title={detail?.name || '发现内容详情'}
        open={detailOpen}
        width="min(780px, 100vw)"
        destroyOnClose
        onClose={closeDetail}
        footer={
          detail ? <div className="discovery-drawer-footer">{actionButtons(detail, false, true)}</div> : undefined
        }
      >
        <Spin spinning={detailLoading}>
          {detailError ? (
            <Alert
              type="error"
              showIcon
              message="详情加载失败"
              description={detailError}
              action={
                detailTarget ? (
                  <Button size="small" onClick={() => openDetail(detailTarget)}>
                    重试
                  </Button>
                ) : undefined
              }
            />
          ) : detail ? (
            <div className="discovery-detail">
              <div className="discovery-detail__media">
                {detail.mediaType === 'video' ? (
                  <video
                    src={detail.previewVideoUrl || detail.mediaUrl || undefined}
                    poster={detail.coverUrl}
                    controls
                    controlsList="nodownload"
                    preload="metadata"
                  />
                ) : (
                  <Image
                    src={detail.coverUrl}
                    alt={detail.name}
                    fallback="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='720' height='405'%3E%3Crect width='100%25' height='100%25' fill='%23f1f5f9'/%3E%3C/svg%3E"
                  />
                )}
              </div>
              {detail.mediaType === 'video' &&
                detail.previewVideoUrl &&
                detail.mediaUrl &&
                detail.previewVideoUrl !== detail.mediaUrl && (
                  <details className="discovery-detail__original">
                    <summary>查看原始视频</summary>
                    <video
                      src={detail.mediaUrl}
                      poster={detail.coverUrl}
                      controls
                      controlsList="nodownload"
                      preload="none"
                    />
                  </details>
                )}
              <div className="discovery-detail__headline">
                <div>
                  <Space wrap>
                    {mediaTag(detail.mediaType)}
                    {sourceTag(detail.sourceType)}
                    {reviewTag(detail.reviewStatus)}
                    {displayStatus(detail.status)}
                  </Space>
                  <h3>{detail.name}</h3>
                </div>
                <span>
                  {detail.mediaWidth} × {detail.mediaHeight}
                  {detail.aspectRatio ? ` · ${detail.aspectRatio}` : ''}
                </span>
              </div>
              <Typography.Paragraph className="discovery-detail__description">
                {detail.description}
              </Typography.Paragraph>
              <Descriptions bordered size="small" column={{ xs: 1, sm: 2 }}>
                <Descriptions.Item label="内容 ID">{detail.id}</Descriptions.Item>
                <Descriptions.Item label="排序">{detail.sortOrder ?? 0}</Descriptions.Item>
                <Descriptions.Item label="投稿人">
                  {detail.submitterName || (detail.sourceType === 'ADMIN' ? '管理员' : '--')}
                  {detail.userId != null ? `（${detail.userId}）` : ''}
                </Descriptions.Item>
                <Descriptions.Item label="审核时间">{detail.reviewTime || '--'}</Descriptions.Item>
                <Descriptions.Item label="最近审核人">{detail.reviewBy || '--'}</Descriptions.Item>
                <Descriptions.Item label="发布时间">{detail.publishTime || '--'}</Descriptions.Item>
                <Descriptions.Item label="创建时间">{detail.createTime || '--'}</Descriptions.Item>
                <Descriptions.Item label="更新时间">{detail.updateTime || '--'}</Descriptions.Item>
                <Descriptions.Item label="比例来源" span={2}>
                  {detail.ratioSource || '--'}
                </Descriptions.Item>
                <Descriptions.Item label="最近原因" span={2}>
                  {detail.reviewReason || '--'}
                </Descriptions.Item>
                {detail.promptText && (
                  <Descriptions.Item label="预留提示词" span={2}>
                    <Typography.Paragraph copyable ellipsis={{ rows: 3, expandable: true, symbol: '展开' }}>
                      {detail.promptText}
                    </Typography.Paragraph>
                  </Descriptions.Item>
                )}
              </Descriptions>
              <section className="discovery-review-history" aria-labelledby="discovery-review-history-title">
                <div className="discovery-review-history__head">
                  <h4 id="discovery-review-history-title">审核记录</h4>
                  <span>{detail.reviewLogs?.length || 0} 条</span>
                </div>
                {detail.reviewLogs?.length ? (
                  <Timeline items={detail.reviewLogs.map(reviewLogItem)} />
                ) : (
                  <div className="discovery-review-history__empty">
                    <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无审核记录" />
                  </div>
                )}
              </section>
            </div>
          ) : !detailLoading ? (
            <Empty description="暂无详情" />
          ) : (
            <div className="discovery-detail-skeleton" />
          )}
        </Spin>
      </Drawer>

      <Modal
        title={editingRecord ? '编辑发现内容' : '新增发现内容'}
        open={editorOpen}
        width="min(820px, calc(100vw - 24px))"
        onCancel={closeEditor}
        onOk={saveEditor}
        okText={editingRecord ? '保存修改' : '确认新增'}
        cancelText="取消"
        confirmLoading={saving}
        okButtonProps={{ disabled: editorLoading || !!editorError || hasUploadInProgress }}
        cancelButtonProps={{ disabled: saving }}
        maskClosable={!hasUploadInProgress && !saving}
        keyboard={!hasUploadInProgress && !saving}
        destroyOnClose
        afterClose={() => {
          editorForm.resetFields();
          setEditingRecord(null);
          setEditorError('');
          setCoverDimensions(undefined);
          setVideoDimensions(undefined);
          setPreviewClearedByMediaChange(false);
          setUploadingFields(new Set());
        }}
      >
        <Spin spinning={editorLoading}>
          {editorError ? (
            <Alert type="error" showIcon message="编辑数据加载失败" description={editorError} />
          ) : (
            <Form form={editorForm} layout="vertical" requiredMark="optional" className="discovery-editor-form">
              {editingRecord?.reviewStatus === 'APPROVED' && (
                <Alert
                  type="info"
                  showIcon
                  message="当前内容已通过审核"
                  description="管理员保存修改后会直接生效，不会重新进入待审核状态。"
                />
              )}
              <Row gutter={16}>
                <Col xs={24} md={16}>
                  <Form.Item
                    name="name"
                    label="内容名称"
                    rules={[
                      { required: true, whitespace: true, message: '请输入内容名称' },
                      {
                        max: DISCOVERY_NAME_MAX_LENGTH,
                        message: `内容名称不能超过 ${DISCOVERY_NAME_MAX_LENGTH} 个字符`
                      }
                    ]}
                  >
                    <Input
                      autoFocus
                      placeholder="请输入便于识别和展示的名称"
                      maxLength={DISCOVERY_NAME_MAX_LENGTH}
                      showCount
                    />
                  </Form.Item>
                </Col>
                <Col xs={24} md={8}>
                  <Form.Item name="mediaType" label="媒体类型" rules={[{ required: true, message: '请选择媒体类型' }]}>
                    <Radio.Group
                      optionType="button"
                      buttonStyle="solid"
                      options={MEDIA_TYPE_OPTIONS}
                      onChange={(event) => changeMediaType(event.target.value)}
                    />
                  </Form.Item>
                </Col>
              </Row>
              <Form.Item
                name="description"
                label="内容描述"
                rules={[
                  { required: true, whitespace: true, message: '请输入内容描述' },
                  { max: 1000, message: '内容描述不能超过 1000 个字符' }
                ]}
              >
                <Input.TextArea rows={4} maxLength={1000} showCount placeholder="说明内容主题、亮点和适用场景" />
              </Form.Item>

              <div className="form-section-title">
                {mediaType === 'image' ? '主图片' : '媒体文件'}
                <span className="form-section-title__desc">限制实时读取自文件存储配置</span>
              </div>
              <Row gutter={16}>
                <Col xs={24} md={mediaType === 'video' ? 12 : 24}>
                  <Form.Item
                    name="coverUrl"
                    label={mediaType === 'image' ? '发现图片' : '视频封面'}
                    rules={[{ required: true, message: mediaType === 'image' ? '请上传发现图片' : '请上传视频封面' }]}
                  >
                    <MediaUploader
                      kind="image"
                      limits={uploadLimits}
                      limitsLoading={limitsLoading}
                      disabled={saving}
                      onMetadata={setCoverDimensions}
                      onUploadingChange={(uploading) => setUploadState('coverUrl', uploading)}
                    />
                  </Form.Item>
                </Col>
                {mediaType === 'video' && (
                  <Col xs={24} md={12}>
                    <Form.Item name="mediaUrl" label="原始视频" rules={[{ required: true, message: '请上传原始视频' }]}>
                      <MediaUploader
                        kind="video"
                        limits={uploadLimits}
                        limitsLoading={limitsLoading}
                        disabled={saving || uploadingFields.has('previewVideoUrl')}
                        onMetadata={setVideoDimensions}
                        onFileChanged={handleMainVideoFileChanged}
                        onUploadingChange={(uploading) => setUploadState('mediaUrl', uploading)}
                      />
                    </Form.Item>
                  </Col>
                )}
              </Row>
              {mediaType === 'video' && (
                <>
                  {previewClearedByMediaChange && (
                    <Alert
                      className="discovery-preview-reset-alert"
                      type="warning"
                      showIcon
                      message="预览视频已清空"
                      description="主视频已替换或移除，旧预览可能与当前内容不一致；如需预览版本，请重新上传。"
                    />
                  )}
                  <Form.Item
                    name="previewVideoUrl"
                    label="预览视频（选填）"
                    extra="可上传更轻量的预览版本；详情优先播放预览视频，未上传时播放原始视频。"
                  >
                    <MediaUploader
                      kind="video"
                      limits={uploadLimits}
                      limitsLoading={limitsLoading}
                      disabled={saving || uploadingFields.has('mediaUrl')}
                      onFileChanged={() => setPreviewClearedByMediaChange(false)}
                      onUploadingChange={(uploading) => setUploadState('previewVideoUrl', uploading)}
                    />
                  </Form.Item>
                </>
              )}

              <details className="discovery-editor-form__advanced">
                <summary>高级设置</summary>
                <Row gutter={16}>
                  <Col xs={24} sm={12}>
                    <Form.Item name="sortOrder" label="排序值" tooltip="数值越小越靠前">
                      <InputNumber min={0} max={999999} precision={0} style={{ width: '100%' }} />
                    </Form.Item>
                  </Col>
                  <Col xs={24} sm={12}>
                    <Form.Item name="status" label="展示开关" rules={[{ required: true, message: '请选择展示状态' }]}>
                      <Select
                        options={[
                          { label: '启用', value: '0' },
                          { label: '停用', value: '1' }
                        ]}
                      />
                    </Form.Item>
                  </Col>
                </Row>
                <Form.Item
                  name="promptText"
                  label="提示词（预留）"
                  extra="用于后续内容理解或生成能力，不在发现页直接展示。"
                >
                  <Input.TextArea rows={3} maxLength={4000} showCount placeholder="选填" />
                </Form.Item>
              </details>
            </Form>
          )}
        </Spin>
      </Modal>

      <Modal
        title={reviewMeta.title}
        open={reviewOpen}
        onCancel={() => !reviewSubmitting && setReviewOpen(false)}
        onOk={() => reviewForm.submit()}
        okText={reviewMeta.okText}
        cancelText="取消"
        confirmLoading={reviewSubmitting}
        okButtonProps={{ danger: reviewMeta.danger }}
        cancelButtonProps={{ disabled: reviewSubmitting }}
        maskClosable={!reviewSubmitting}
        keyboard={!reviewSubmitting}
        centered
        destroyOnClose
      >
        <Alert
          type={reviewMeta.danger ? 'warning' : 'info'}
          showIcon
          message={reviewTarget?.name}
          description={reviewMeta.tip}
          className="discovery-review-alert"
        />
        <Form form={reviewForm} layout="vertical" onFinish={submitReview}>
          <Form.Item
            name="reason"
            label={reviewMeta.reasonRequired ? '原因' : '审核意见（选填）'}
            rules={
              reviewMeta.reasonRequired
                ? [
                    { required: true, whitespace: true, message: '请输入原因' },
                    { max: 500, message: '原因不能超过 500 个字符' }
                  ]
                : [{ max: 500, message: '审核意见不能超过 500 个字符' }]
            }
          >
            <Input.TextArea
              autoFocus
              rows={4}
              maxLength={500}
              showCount
              placeholder={reviewMeta.reasonRequired ? '请输入本次操作原因' : '可填写审核说明'}
              onPressEnter={(event) => {
                if ((event.ctrlKey || event.metaKey) && !reviewSubmitting) reviewForm.submit();
              }}
            />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
