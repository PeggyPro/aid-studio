import React, { useState } from 'react';
import { Button, Card, Space, Typography } from 'antd';
import { LockOutlined, UnlockOutlined } from '@ant-design/icons';
import StructuredValueEditor from './StructuredValueEditor';

interface ContractExampleEditorProps {
  value?: string;
  onChange?: (value: string) => void;
  disabled?: boolean;
  title: string;
}

/** Examples are annotations of the same versioned schema, separate from field editing. */
export default function ContractExampleEditor({ value, onChange, disabled, title }: ContractExampleEditorProps) {
  const [unlocked, setUnlocked] = useState(false);
  let schema: Record<string, unknown>;
  try {
    const parsed: unknown = JSON.parse(value?.trim() || '{}');
    if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object') return null;
    schema = parsed as Record<string, unknown>;
  } catch {
    return null;
  }
  const examples = Array.isArray(schema.examples) ? schema.examples : [];
  const locked = disabled || !unlocked;
  return <Card size="small" title={title} extra={!disabled && <Button
    aria-pressed={unlocked}
    icon={locked ? <LockOutlined /> : <UnlockOutlined />}
    onClick={() => setUnlocked((current) => !current)}>
    {locked ? '解锁编辑' : '锁定样例'}
  </Button>}>
    <Space direction="vertical" style={{ width: '100%' }}>
      <Typography.Text type="secondary">样例用于向模型示范格式，与本次实际输入分开传递。</Typography.Text>
      {locked && examples.length === 0 ? <Typography.Text type="secondary">尚未配置样例</Typography.Text>
        : <StructuredValueEditor arrayOnly value={JSON.stringify(examples)} disabled={locked} onChange={(next) => {
          const items: unknown = JSON.parse(next);
          if (!Array.isArray(items)) return;
          const updated = { ...schema };
          if (items.length) updated.examples = items;
          else delete updated.examples;
          onChange?.(JSON.stringify(updated));
        }} />}
    </Space>
  </Card>;
}
