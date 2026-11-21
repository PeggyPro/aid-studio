import React, { useEffect, useState } from 'react';
import { Alert, Checkbox, Descriptions, Modal, Skeleton, Space, Table, Tag, Typography } from 'antd';
import type { OrchestrationImpact } from '@/api/aid/orchestration';

const { Text, Paragraph } = Typography;

interface Props {
  open: boolean;
  loading?: boolean;
  submitting?: boolean;
  impact?: OrchestrationImpact | null;
  replacementOptions?: { label: string; value: string }[];
  onCancel: () => void;
  onConfirm: (replacementCode?: string) => Promise<void> | void;
}

/** 有效引用存在时只展示影响，不执行自动解绑或替换。 */
export default function RetirementModal({
  open,
  loading,
  submitting,
  impact,
  replacementOptions: _replacementOptions = [],
  onCancel,
  onConfirm
}: Props) {
  const [confirmed, setConfirmed] = useState(false);

  useEffect(() => {
    if (open) {
      setConfirmed(false);
    }
  }, [open, impact?.resourceId]);

  const typeName = impact?.resourceType === 'agent' ? '智能体' : '模型';
  const hasReferences = (impact?.activeReferenceCount || 0) > 0;

  return (
    <Modal
      open={open}
      width={760}
      title={`${typeName}受控下线`}
      okText="确认下线"
      okButtonProps={{ danger: true, disabled: !impact || hasReferences || !confirmed }}
      confirmLoading={submitting}
      onCancel={onCancel}
      onOk={() => onConfirm()}
      destroyOnClose
    >
      {loading || !impact ? <Skeleton active paragraph={{ rows: 6 }} /> : (
        <Space direction="vertical" size={16} style={{ width: '100%' }}>
          <Alert
            type={hasReferences ? 'warning' : 'info'}
            showIcon
            message={hasReferences
              ? `检测到 ${impact.activeReferenceCount} 条有效引用，当前不能下线`
              : '未检测到有效引用，可确认软下线'}
            description={hasReferences ? '请先逐项处理引用；系统不会自动解绑或替换。'
              : '下线前服务端会再次核对引用状态。'}
          />

          <Descriptions size="small" column={2} bordered>
            <Descriptions.Item label={`${typeName}名称`}>{impact.resourceName || '-'}</Descriptions.Item>
            <Descriptions.Item label="稳定编码"><Text code>{impact.resourceCode}</Text></Descriptions.Item>
            {impact.bizCategoryCode && (
              <Descriptions.Item label="业务分类" span={2}><Text code>{impact.bizCategoryCode}</Text></Descriptions.Item>
            )}
          </Descriptions>

          <Table
            rowKey="type"
            size="small"
            pagination={false}
            dataSource={(impact.references || []).filter((item) => item.count > 0)}
            locale={{ emptyText: '没有活动引用' }}
            columns={[
              { title: '引用位置', dataIndex: 'label', width: 180 },
              { title: '数量', dataIndex: 'count', width: 80, render: (count: number) => <Tag color="orange">{count}</Tag> },
              { title: '引用说明', dataIndex: 'action' }
            ]}
          />

          <Alert type="success" showIcon message="历史数据策略" description={impact.historyPolicy} />
          {!hasReferences && <Checkbox checked={confirmed} onChange={(event) => setConfirmed(event.target.checked)}>
            <Paragraph style={{ display: 'inline', margin: 0 }}>
              我已核对以上影响，确认下线。
            </Paragraph>
          </Checkbox>}
        </Space>
      )}
    </Modal>
  );
}
