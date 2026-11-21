import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Checkbox,
  Descriptions,
  Drawer,
  Empty,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Spin,
  Table,
  Tabs,
  Tag
} from 'antd';
import PageHeader from '@/components/PageHeader';
import { useAuth } from '@/hooks/useAuth';
import { getToken } from '@/utils/auth';
import {
  actCanvasAudit,
  getCanvasAudit,
  listCanvasAudit,
  type CanvasAuditDetail,
  type CanvasAuditQuery,
  type CanvasAuditRow,
  type ReviewAction
} from '@/api/aid/canvasAudit';
import Snapshot from './Snapshot';
import { useAuditResources } from './auditResources';
import InspectData from './InspectData';
import './style.less';

const statuses = [
  { value: 'PENDING', label: '待审核' },
  { value: 'APPROVED', label: '已通过' },
  { value: 'REJECTED', label: '已驳回' },
  { value: 'CANCELLED', label: '已撤销' }
];
const statusLabel = (value: string) => statuses.find((s) => s.value === value)?.label || value;
const statusColor = (value: string) => ({ PENDING: 'processing', APPROVED: 'success', REJECTED: 'error', CANCELLED: 'default' }[value] || 'default');
const describeError = (error: unknown) => (error instanceof Error ? error.message : '请求失败，请重试');
function FixedVersion({ detail, onReady }: { detail: CanvasAuditDetail; onReady?: (ready: boolean) => void }) {
  const [decoded, setDecoded] = useState(false),
    [coverDecoded, setCoverDecoded] = useState(false),
    [mediaError, setMediaError] = useState('');
  const [sceneReady, setSceneReady] = useState(false);
  const resources = useAuditResources(detail.publicationId, detail.canvas);
  const canvas = detail.canvas;
  const cover = resources.urls[String(canvas?.coverResourceId)];
  const video = resources.urls[String(canvas?.videoResourceId)];
  useEffect(() => {
    setDecoded(false);
    setCoverDecoded(false);
    setMediaError('');
    setSceneReady(false);
    onReady?.(false);
  }, [detail, onReady]);
  useEffect(() => {
    onReady?.(resources.ready && decoded && coverDecoded && sceneReady);
  }, [resources.ready, decoded, coverDecoded, sceneReady, onReady]);
  if (!canvas) return <Alert type="error" message="服务端未返回固定画布正文，无法审核" />;
  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Alert
        type="info"
        showIcon
        message="检查的是提交时固定版本"
        description="作者后续编辑不会改变以下内容。展开节点正文、资源依赖、场景与来源对比后作出人工判断。"
      />
      {resources.error && <Alert type="error" message={resources.error} />}
      {mediaError && <Alert type="error" message={mediaError} />}
      <Alert
        type={resources.ready && decoded && coverDecoded && sceneReady ? 'success' : 'info'}
        showIcon
        message="固定版本检查进度"
        description={<Space wrap>
          <Tag color={resources.error ? 'error' : resources.ready ? 'success' : 'processing'}>资源与依赖：{resources.error ? '读取失败' : resources.ready ? '已就绪' : '加载中'}</Tag>
          <Tag color={coverDecoded ? 'success' : 'processing'}>封面：{coverDecoded ? '已解码' : '待解码'}</Tag>
          <Tag color={decoded ? 'success' : 'processing'}>视频：{decoded ? '已解码' : '待解码'}</Tag>
          <Tag color={sceneReady ? 'success' : 'warning'}>完整场景：{sceneReady ? '已还原' : '请查看「完整场景」中的具体提示'}</Tag>
        </Space>}
      />
      {!resources.ready && !resources.error && <Spin tip="正在加载固定资源与完整依赖" />}
      <div className="canvas-audit-primary-media">
        {cover ? (
          <img
            src={cover}
            alt="待审固定封面"
            onLoad={() => setCoverDecoded(true)}
            onError={() => {
              setMediaError('固定封面无法解码，不能通过审核');
              setCoverDecoded(false);
              onReady?.(false);
            }}
          />
        ) : (
          <Empty description="固定封面待加载" />
        )}
        {video ? (
          <video
            key={video}
            src={video}
            controls
            preload="metadata"
            onLoadedMetadata={() => setDecoded(true)}
            onError={() => {
              setMediaError('固定视频无法解码，不能通过审核');
              setDecoded(false);
              onReady?.(false);
            }}
          />
        ) : (
          <Empty description="固定视频待加载" />
        )}
      </div>
      <Snapshot
        publicationId={detail.publicationId}
        canvas={canvas}
        resources={resources}
        onPreviewReady={setSceneReady}
      />
    </Space>
  );
}
function Comparison({ title, value }: { title: string; value?: Record<string, any> }) {
  return (
    <Card title={title} size="small">
      {value ? (
        <>
          <Alert
            type={value.available === false ? 'warning' : 'info'}
            showIcon
            message={
              value.available === false
                ? `不可比较：${value.reason || '缺少可核验的固定版本差异'}`
                : '差异仅供核对，不能自动认定原创、侵权或再次发布授权'
            }
          />
          <InspectData value={value} />
        </>
      ) : (
        <Alert type="warning" showIcon message="不可比较：未提供可核验的固定版本差异" />
      )}
    </Card>
  );
}
export default function CanvasAudit() {
  const { hasPermi } = useAuth();
  const canList = hasPermi('aid:canvasAudit:list'),
    canQuery = hasPermi('aid:canvasAudit:query');
  const canReview = hasPermi('aid:canvasAudit:review'),
    canOffline = hasPermi('aid:canvasAudit:offline');
  const [form] = Form.useForm();
  const [query, setQuery] = useState<CanvasAuditQuery>({ pageNum: 1, pageSize: 20, reviewStatus: 'PENDING' });
  const [rows, setRows] = useState<CanvasAuditRow[]>([]),
    [total, setTotal] = useState(0);
  const [cleanupPending, setCleanupPending] = useState(0);
  const [loading, setLoading] = useState(false),
    [error, setError] = useState('');
  const [open, setOpen] = useState(false),
    [detail, setDetail] = useState<CanvasAuditDetail>();
  const [detailLoading, setDetailLoading] = useState(false),
    [detailError, setDetailError] = useState('');
  const [sourceDetail, setSourceDetail] = useState<CanvasAuditDetail>(),
    [publishedDetail, setPublishedDetail] = useState<CanvasAuditDetail>();
  const [sourceError, setSourceError] = useState(''),
    [comparisonLoading, setComparisonLoading] = useState(false);
  const [reason, setReason] = useState(''),
    [checked, setChecked] = useState(false),
    [ready, setReady] = useState(false);
  const [action, setAction] = useState<ReviewAction>(),
    [writing, setWriting] = useState(false);
  const listEpoch = useRef(0),
    detailEpoch = useRef(0),
    writeLock = useRef(false),
    activeId = useRef<string>();
  const mounted = useRef(true);
  const session = getToken();
  useEffect(() => {
    mounted.current = true;
    const invalidate = () => {
      listEpoch.current++;
      detailEpoch.current++;
    };
    return () => {
      mounted.current = false;
      invalidate();
    };
  }, []);
  const load = useCallback(
    async (force = false) => {
      if (!hasPermi('aid:canvasAudit:list')) return;
      const epoch = ++listEpoch.current;
      const token = getToken();
      setLoading(true);
      setError('');
      try {
        const data = await listCanvasAudit(query, force);
        if (
          mounted.current &&
          epoch === listEpoch.current &&
          token === getToken() &&
          hasPermi('aid:canvasAudit:list')
        ) {
          setRows(data?.items || []);
          setTotal(data?.total || 0);
          setCleanupPending(data?.cleanupPending || 0);
        }
      } catch (e) {
        if (mounted.current && epoch === listEpoch.current) setError(describeError(e));
      } finally {
        if (mounted.current && epoch === listEpoch.current) setLoading(false);
      }
    },
    [query, hasPermi]
  );
  useEffect(() => {
    if (canList) void load();
    else {
      listEpoch.current++;
      setRows([]);
      setTotal(0);
    }
  }, [canList, load, session]);
  const close = () => {
    if (writeLock.current) return;
    detailEpoch.current++;
    activeId.current = undefined;
    setOpen(false);
    setDetail(undefined);
    setSourceDetail(undefined);
    setPublishedDetail(undefined);
    setAction(undefined);
  };
  useEffect(() => {
    if (!canQuery) {
      detailEpoch.current++;
      activeId.current = undefined;
      setOpen(false);
      setDetail(undefined);
    }
  }, [canQuery, session]);
  const showDetail = async (publicationId: string | number, force = false) => {
    if (!hasPermi('aid:canvasAudit:query') || writeLock.current) return;
    const epoch = ++detailEpoch.current;
    const token = getToken();
    activeId.current = String(publicationId);
    setOpen(true);
    setDetail(undefined);
    setSourceDetail(undefined);
    setPublishedDetail(undefined);
    setSourceError('');
    setDetailError('');
    setDetailLoading(true);
    setReason('');
    setChecked(false);
    setReady(false);
    setAction(undefined);
    try {
      const data = await getCanvasAudit(publicationId, force);
      if (mounted.current && epoch === detailEpoch.current && token === getToken() && hasPermi('aid:canvasAudit:query'))
        setDetail(data);
    } catch (e) {
      if (mounted.current && epoch === detailEpoch.current) setDetailError(describeError(e));
    } finally {
      if (mounted.current && epoch === detailEpoch.current) setDetailLoading(false);
    }
  };
  const loadComparisons = async () => {
    if (!detail || comparisonLoading) return;
    const epoch = detailEpoch.current,
      token = getToken();
    setComparisonLoading(true);
    setSourceError('');
    try {
      const [source, published] = await Promise.all([
        detail.sourcePublicationId ? getCanvasAudit(detail.sourcePublicationId) : Promise.resolve(undefined),
        detail.publishedVersion?.publicationId
          ? getCanvasAudit(detail.publishedVersion.publicationId)
          : Promise.resolve(undefined)
      ]);
      if (
        mounted.current &&
        epoch === detailEpoch.current &&
        token === getToken() &&
        hasPermi('aid:canvasAudit:query')
      ) {
        setSourceDetail(source);
        setPublishedDetail(published);
      }
    } catch (e) {
      if (mounted.current && epoch === detailEpoch.current) setSourceError(describeError(e));
    } finally {
      if (mounted.current && epoch === detailEpoch.current) setComparisonLoading(false);
    }
  };
  const submit = async () => {
    if (!detail || !action || writeLock.current) return;
    if (
      !hasPermi(action === 'OFFLINE' ? 'aid:canvasAudit:offline' : 'aid:canvasAudit:review') ||
      !hasPermi('aid:canvasAudit:query')
    )
      return;
    if ((action === 'REJECT' || action === 'OFFLINE') && !reason.trim()) {
      setDetailError('请填写处理原因');
      return;
    }
    if (action === 'APPROVE' && (!ready || !checked)) return;
    const token = getToken(),
      id = detail.publicationId;
    const currentAction = action;
    let resultError = '';
    writeLock.current = true;
    setWriting(true);
    setDetailError('');
    try {
      await actCanvasAudit({
        publicationId: id,
        expectedVersion: detail.stateVersion,
        action: currentAction,
        reason: reason.trim() || undefined
      });
      if (mounted.current && token === getToken()) {
        setAction(undefined);
        setChecked(false);
        setReady(false);
        setDetail(undefined);
      }
    } catch (e) {
      resultError = `${describeError(e)}。请核对刷新后的固定版本，操作不会自动重放。`;
      if (mounted.current && token === getToken()) {
        setAction(undefined);
        setChecked(false);
      }
    } finally {
      writeLock.current = false;
      if (mounted.current) {
        setWriting(false);
        if (token === getToken() && activeId.current === String(id)) {
          void load(true);
          await showDetail(id, true);
          if (resultError && mounted.current) setDetailError(resultError);
        }
      }
    }
  };
  const chooseAction = (value: ReviewAction) => {
    setAction(value);
    setReason('');
  };
  return (
    <div className="canvas-audit-page">
      <PageHeader title="画布审核" desc="按固定发布版本审核完整作品；审核、查询与下架权限分别控制。" />
      {!canList ? (
        <Alert type="warning" message="没有画布审核列表权限" />
      ) : (
        <Card>
          <Form
            form={form}
            layout="inline"
            initialValues={{ reviewStatus: 'PENDING' }}
            onFinish={(values) =>
              setQuery({ ...query, ...values, keyword: values.keyword?.trim() || undefined, pageNum: 1 })
            }
          >
            <Form.Item name="keyword" label="作品关键词">
              <Input allowClear maxLength={100} />
            </Form.Item>
            <Form.Item name="reviewStatus" label="审核状态">
              <Select allowClear style={{ width: 140 }} options={statuses} />
            </Form.Item>
            <Form.Item>
              <Space>
                <Button htmlType="submit" type="primary">
                  查询
                </Button>
                <Button
                  onClick={() => {
                    form.resetFields();
                    setQuery({ pageNum: 1, pageSize: 20, reviewStatus: 'PENDING' });
                  }}
                >
                  重置
                </Button>
                <Button loading={loading} onClick={() => void load(true)}>
                  刷新
                </Button>
              </Space>
            </Form.Item>
          </Form>
          {error && <Alert type="error" showIcon message={error} />}
          {cleanupPending > 0 && (
            <Alert type="warning" showIcon message={`有 ${cleanupPending} 份异常文件待回收，服务端将按既定机制处理`} />
          )}
          <Table
            loading={loading}
            rowKey={(r) => String(r.publicationId)}
            dataSource={rows}
            scroll={{ x: 980 }}
            pagination={{
              current: query.pageNum,
              pageSize: query.pageSize,
              total,
              showSizeChanger: true,
              pageSizeOptions: [10, 20, 50, 100],
              onChange: (pageNum, pageSize) => setQuery({ ...query, pageNum, pageSize })
            }}
            columns={[
              { title: '发布编号', dataIndex: 'publicationId' },
              { title: '作品名称', dataIndex: 'projectName' },
              {
                title: '来源',
                render: (_, r) => (
                  <Tag color={r.isDerivative ? 'orange' : 'default'}>
                    {r.isDerivative ? '复制衍生' : '无复制来源记录'}
                  </Tag>
                )
              },
              { title: '审核状态', render: (_, r) => <Tag color={statusColor(r.reviewStatus)}>{statusLabel(r.reviewStatus)}</Tag> },
              { title: '当前允许复制', render: (_, r) => (r.allowCopy ? '是' : '否') },
              { title: '本次申请复制', render: (_, r) => (r.requestedAllowCopy ? '是' : '否') },
              {
                title: '操作',
                fixed: 'right',
                render: (_, r) =>
                  canQuery ? (
                    <Button onClick={() => void showDetail(r.publicationId)}>查看固定版本</Button>
                  ) : (
                    <span>无查询权限</span>
                  )
              }
            ]}
          />
        </Card>
      )}
      <Drawer
        open={open}
        width="min(1280px, 100vw)"
        title="固定发布版本审核"
        onClose={close}
        maskClosable={!writing}
        closable={!writing}
        destroyOnClose
        extra={
          <Button
            disabled={writing || detailLoading}
            onClick={() => activeId.current && void showDetail(activeId.current, true)}
          >
            重新查询
          </Button>
        }
      >
        {detailError && <Alert type="error" showIcon message={detailError} />}
        {detailLoading && <Spin tip="查询固定版本" />}
        {detail && (
          <>
            <Descriptions
              bordered
              size="small"
              column={{ xs: 1, md: 2 }}
              items={[
                'publicationId',
                'projectId',
                'projectName',
                'projectDesc',
                'stateVersion',
                'sourcePublicationId',
                'rootPublicationId',
                'contentHash',
                'reviewReason'
              ].map((key) => ({
                key,
                label: (
                  {
                    publicationId: '发布编号',
                    projectId: '项目编号',
                    projectName: '名称',
                    projectDesc: '简介',
                    stateVersion: '处理版本',
                    sourcePublicationId: '直接来源固定版本',
                    rootPublicationId: '根来源固定版本',
                    contentHash: '正文校验值',
                    reviewReason: '最近审核说明'
                  } as Record<string, string>
                )[key],
                children: String(detail[key] ?? '未提供')
              }))}
            />
            <Space wrap style={{ margin: '16px 0' }}>
              <Tag color={statusColor(detail.reviewStatus)}>{statusLabel(detail.reviewStatus)}</Tag>
              <Tag color={detail.isPublic === true ? 'green' : detail.isPublic === false ? 'default' : 'warning'}>
                {detail.isPublic === true ? '公开状态：公开' : detail.isPublic === false ? '公开状态：未公开' : '公开状态未提供'}
              </Tag>
              <Tag color={detail.isDerivative ? 'orange' : 'default'}>
                {detail.isDerivative ? '复制衍生作品' : '没有复制来源记录（不代表原创认定）'}
              </Tag>
              <Tag>当前复制权限：{detail.allowCopy ? '允许' : '不允许'}</Tag>
              <Tag>本次申请：{detail.requestedAllowCopy ? '允许复制' : '不允许复制'}</Tag>
            </Space>
            <Tabs
              onChange={(key) => {
                if (key === 'comparison') void loadComparisons();
              }}
              items={[
                {
                  key: 'current',
                  label: '本次固定版本',
                  children: (
                    <FixedVersion
                      key={String(detail.publicationId) + ':' + detail.stateVersion}
                      detail={detail}
                      onReady={setReady}
                    />
                  )
                },
                {
                  key: 'comparison',
                  label: '来源与原发布版本对比',
                  children: (
                    <Space direction="vertical" style={{ width: '100%' }}>
                      <Comparison title="复制来源差异" value={detail.sourceComparison} />
                      <Comparison title="当前原发布版本差异" value={detail.publishedComparison} />
                      <Button loading={comparisonLoading} onClick={() => void loadComparisons()}>
                        查询来源及原发布固定资料
                      </Button>
                      {sourceError && <Alert type="error" message={sourceError} />}
                      <CollapseVersions title="复制时的来源固定版本" detail={sourceDetail} summary={detail.source} />
                      <CollapseVersions
                        title="当前原发布版本"
                        detail={publishedDetail}
                        summary={detail.publishedVersion}
                      />
                    </Space>
                  )
                },
                { key: 'records', label: '审核记录', children: <InspectData value={detail.auditRecords || []} /> }
              ]}
            />
            <Card size="small" title="审核处理" className="canvas-audit-actions">
              <Space direction="vertical" style={{ width: '100%' }}>
                <Checkbox checked={checked} disabled={!ready || writing} onChange={(e) => setChecked(e.target.checked)}>
                  已人工检查当前固定视频、封面、全部节点、完整场景、资源依赖及来源对比
                </Checkbox>
                {!ready && <Alert type="warning" showIcon message="固定版本检查尚未完成"
                  description="请查看上方检查进度及「完整场景」中的具体原因。文件读取失败可重新查询；缺少资源或模型无法还原时，请驳回并说明需要补齐的内容。" />}
                <Space wrap>
                  {canReview && detail.reviewStatus === 'PENDING' && (
                    <>
                      <Button
                        type="primary"
                        disabled={!ready || !checked || writing}
                        onClick={() => chooseAction('APPROVE')}
                      >
                        通过并发布固定版本
                      </Button>
                      <Button danger disabled={writing} onClick={() => chooseAction('REJECT')}>
                        驳回申请
                      </Button>
                    </>
                  )}
                  {canOffline && detail.isPublic === true && detail.publishedVersion?.publicationId != null
                    && String(detail.publishedVersion.publicationId) === String(detail.publicationId) && (
                    <Button danger disabled={writing} onClick={() => chooseAction('OFFLINE')}>
                      下架作品
                    </Button>
                  )}
                </Space>
                <span>通过将切换整份固定版本；驳回保留原公开版本；下架隐藏作品并撤销待审扩权。</span>
              </Space>
            </Card>
          </>
        )}
        {!detailLoading && !detail && !detailError && <Empty description="请选择固定版本" />}
      </Drawer>
      <Modal
        open={!!action}
        title={action === 'APPROVE' ? '确认通过并发布固定版本' : action === 'REJECT' ? '确认驳回申请' : '确认下架作品'}
        confirmLoading={writing}
        okText="确认处理"
        cancelText="取消"
        onCancel={() => {
          if (!writing) setAction(undefined);
        }}
        onOk={() => void submit()}
        okButtonProps={{ danger: action !== 'APPROVE', disabled: action !== 'APPROVE' && !reason.trim() }}
        destroyOnClose
      >
        <p>
          发布编号 {String(detail?.publicationId || '')} · 处理版本 {String(detail?.stateVersion || '')}
        </p>
        <Form layout="vertical">
          <Form.Item
            label={action === 'APPROVE' ? '审核备注（选填）' : '处理原因（必填）'}
            required={action !== 'APPROVE'}
          >
            <Input.TextArea
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              maxLength={1000}
              showCount
              rows={4}
              disabled={writing}
            />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
function CollapseVersions({
  title,
  detail,
  summary
}: {
  title: string;
  detail?: CanvasAuditDetail;
  summary?: Record<string, any>;
}) {
  return (
    <details>
      <summary>{title}</summary>
      {detail ? (
        <>
          <Descriptions
            items={[
              { key: 'name', label: '名称', children: detail.projectName },
              { key: 'id', label: '固定发布编号', children: String(detail.publicationId) }
            ]}
          />
          <FixedVersion detail={detail} />
        </>
      ) : summary && Object.keys(summary).length ? (
        <InspectData value={summary} />
      ) : (
        <Alert type="warning" message="不可比较：没有对应固定版本资料" />
      )}
    </details>
  );
}
