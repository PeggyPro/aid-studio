/* eslint-disable react-refresh/only-export-components -- modal helpers are invoked imperatively, not mounted for hot refresh */
import React from 'react';
import { Alert, Button, Modal, Space, Table, Tag, Typography } from 'antd';

export interface ApiReference {
  type?: string;
  id?: number | string;
  code?: string;
  name?: string;
  version?: string;
  purpose?: string;
  bindingPurpose?: string;
  modelId?: number | string;
  capabilityCode?: string;
  reason?: string;
  managementPath?: string;
}

export interface ApiErrorDetails {
  errorCode?: string;
  field?: string;
  suppliedValue?: unknown;
  reason?: string;
  allowedValues?: string[];
  catalogPath?: string;
  retryable?: boolean;
  totalReferences?: number;
  references?: ApiReference[];
}

export class ApiBusinessError extends Error {
  constructor(message: string, readonly code: number, readonly details?: ApiErrorDetails) {
    super(message);
    this.name = 'ApiBusinessError';
  }
}

/** 引用拒绝时保留服务端提供的目标与原因；只接受站内相对管理路径。 */
export function showReferenceConflict(error: unknown, title = '当前内容仍被使用') {
  if (!(error instanceof ApiBusinessError) || !error.details
    || !(error.details.references?.length || error.details.totalReferences
      || error.details.errorCode?.endsWith('_IN_USE'))) return false;
  const details = error.details;
  const references = details.references || [];
  Modal.info({
    title,
    width: 900,
    okText: '关闭',
    content: <Space direction="vertical" size={12} style={{ width: '100%' }}>
      <Alert type="warning" showIcon message={error.message}
        description={details.reason || '请先处理下列有效引用，再重试当前操作。'} />
      <Typography.Text>有效引用：{details.totalReferences ?? references.length} 项</Typography.Text>
      <Table size="small" rowKey={(row, index) => `${row.type}-${row.id}-${index}`}
        dataSource={references} pagination={{ pageSize: 5 }} scroll={{ x: 760 }}
        columns={[
          { title: '类型', dataIndex: 'type', width: 100, render: (value?: string) => <Tag>{value || '--'}</Tag> },
          { title: '名称 / 编码', width: 220, render: (_, row: ApiReference) => <Space direction="vertical" size={0}>
            <Typography.Text>{row.name || '--'}</Typography.Text>
            <Typography.Text type="secondary" copyable={!!row.code}>{row.code || row.id || '--'}</Typography.Text>
          </Space> },
          { title: '版本', dataIndex: 'version', width: 95, render: (value?: string) => value || '--' },
          { title: '模型 / 能力', width: 150, render: (_, row: ApiReference) =>
            [row.modelId, row.capabilityCode].filter(Boolean).join(' / ') || '--' },
          { title: '绑定用途', width: 120, render: (_, row: ApiReference) => row.bindingPurpose || row.purpose || '--' },
          { title: '原因', dataIndex: 'reason', width: 190, render: (value?: string) => value || '--' },
          { title: '管理', width: 90, render: (_, row: ApiReference) => {
            const path = row.managementPath;
            return path?.startsWith('/') && !path.startsWith('//') && !path.includes('\\')
              ? <Button type="link" href={path}>打开</Button> : '--';
          } }
        ]} />
    </Space>
  });
  return true;
}

export function ReferenceDetailsModal({ open, title, details, loading, page, pageSize, onPage, onClose }: {
  open: boolean;
  title: string;
  details?: ApiErrorDetails;
  loading?: boolean;
  page: number;
  pageSize: number;
  onPage: (page: number, pageSize: number) => void;
  onClose: () => void;
}) {
  return <Modal open={open} title={title} width={920} footer={<Button onClick={onClose}>关闭</Button>}
    onCancel={onClose}>
    <Alert showIcon type="warning" style={{ marginBottom: 16 }} message="当前仍有有效引用，不能删除"
      description={details?.reason || '请先在对应配置中处理绑定；系统不会自动解绑。'} />
    <Table size="small" rowKey={(row, index) => `${row.type}-${row.id}-${index}`}
      loading={loading} dataSource={details?.references || []} scroll={{ x: 800 }}
      pagination={{ current: page, pageSize, total: details?.totalReferences || 0,
        showSizeChanger: true, showTotal: (total) => `共 ${total} 项有效引用`, onChange: onPage }}
      columns={[
        { title: '类型', dataIndex: 'type', width: 100, render: (value?: string) => <Tag>{value || '--'}</Tag> },
        { title: '名称 / 编码', width: 230, render: (_, row: ApiReference) => <Space direction="vertical" size={0}>
          <Typography.Text>{row.name || '--'}</Typography.Text>
          <Typography.Text type="secondary" copyable={!!row.code}>{row.code || row.id || '--'}</Typography.Text>
        </Space> },
        { title: '版本', dataIndex: 'version', width: 100, render: (value?: string) => value || '--' },
        { title: '模型 / 能力', width: 150, render: (_, row: ApiReference) =>
          [row.modelId, row.capabilityCode].filter(Boolean).join(' / ') || '--' },
        { title: '绑定用途', width: 130, render: (_, row: ApiReference) => row.bindingPurpose || row.purpose || '--' },
        { title: '原因', dataIndex: 'reason', width: 190, render: (value?: string) => value || '--' },
        { title: '管理', width: 90, render: (_, row: ApiReference) => {
          const path = row.managementPath;
          return path?.startsWith('/') && !path.startsWith('//') && !path.includes('\\')
            ? <Button type="link" href={path}>打开</Button> : '--';
        } }
      ]} />
  </Modal>;
}
