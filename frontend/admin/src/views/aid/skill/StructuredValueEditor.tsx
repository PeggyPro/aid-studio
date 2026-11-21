import React, { useMemo } from 'react';
import { Alert, Button, Card, Collapse, Empty, Input, InputNumber, Select, Space, Switch, Tag, Typography } from 'antd';
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';

type Value = null | boolean | number | string | Value[] | { [key: string]: Value };
type Container = Value[] | { [key: string]: Value };

interface Props {
  value?: string;
  onChange?: (value: string) => void;
  disabled?: boolean;
  objectOnly?: boolean;
  arrayOnly?: boolean;
  emptyText?: string;
}

const types = [
  { value: 'string', label: '文本' }, { value: 'number', label: '数字' },
  { value: 'boolean', label: '布尔值' }, { value: 'object', label: '对象' },
  { value: 'array', label: '列表' }, { value: 'null', label: '空值' }
];

function valueType(value: Value) {
  if (value === null) return 'null';
  if (Array.isArray(value)) return 'array';
  return typeof value;
}

function emptyValue(type: string): Value {
  if (type === 'object') return {};
  if (type === 'array') return [];
  if (type === 'number') return 0;
  if (type === 'boolean') return false;
  if (type === 'null') return null;
  return '';
}

function isContainer(value: Value): value is Container {
  return Array.isArray(value) || (value !== null && typeof value === 'object');
}

function withNode(root: Value, path: (string | number)[], update: (node: Value) => Value): Value {
  if (!path.length) return update(root);
  const [head, ...rest] = path;
  if (Array.isArray(root)) {
    const copy = [...root];
    copy[Number(head)] = withNode(copy[Number(head)], rest, update);
    return copy;
  }
  if (root !== null && typeof root === 'object') {
    return { ...root, [head]: withNode(root[String(head)], rest, update) };
  }
  return root;
}

function Field({ label, value, path, disabled, update, remove, depth = 0, lockType }: {
  label: string;
  value: Value;
  path: (string | number)[];
  disabled?: boolean;
  update: (path: (string | number)[], updater: (value: Value) => Value) => void;
  remove?: () => void;
  depth?: number;
  lockType?: boolean;
}) {
  const type = valueType(value);
  const entryCount = isContainer(value) ? Object.keys(value).length : 0;
  const [newKey, setNewKey] = React.useState('');
  const [addError, setAddError] = React.useState('');
  const updateValue = (next: Value) => update(path, () => next);
  const addEntry = () => {
    if (Array.isArray(value)) {
      update(path, (current) => [...current as Value[], '']);
      return;
    }
    const key = newKey.trim();
    if (!key || Object.prototype.hasOwnProperty.call(value, key)) {
      setAddError(key ? '字段名已存在' : '请输入字段名');
      return;
    }
    update(path, (current) => ({ ...current as Record<string, Value>, [key]: '' }));
    setNewKey('');
    setAddError('');
  };
  const content = <Space direction="vertical" size={10} style={{ width: '100%' }}>
    <Space wrap align="center">
      <Typography.Text strong>{label}</Typography.Text>
      <Select aria-label={`${label}的类型`} size="small" value={type} disabled={disabled || lockType}
        style={{ minWidth: 100 }} options={types}
        onChange={(next) => updateValue(emptyValue(next))} />
      {remove && !disabled && <Button size="small" danger icon={<DeleteOutlined />}
        aria-label={`删除${label}`} onClick={remove}>移除</Button>}
    </Space>
    {type === 'string' && <Input.TextArea aria-label={label} autoSize={{ minRows: 1, maxRows: 8 }}
      value={value as string} disabled={disabled} onChange={(event) => updateValue(event.target.value)} />}
    {type === 'number' && <InputNumber aria-label={label} style={{ width: '100%' }}
      value={value as number} disabled={disabled} onChange={(next) => updateValue(next ?? 0)} />}
    {type === 'boolean' && <Switch aria-label={label} checked={value as boolean} disabled={disabled}
      onChange={updateValue} />}
    {type === 'null' && <Tag>空值</Tag>}
    {isContainer(value) && <div style={{ borderLeft: '2px solid var(--ant-color-border)', paddingLeft: 12 }}>
      {Object.entries(value).map(([key, child], index) => {
        const segment = Array.isArray(value) ? index : key;
        return <Card size="small" key={`${key}-${index}`} style={{ marginBottom: 8 }}>
          <Field label={Array.isArray(value) ? `第 ${index + 1} 项` : key} value={child as Value}
            path={[...path, segment]} disabled={disabled} depth={depth + 1} update={update}
            remove={() => update(path, (current) => {
              if (Array.isArray(current)) return current.filter((_, itemIndex) => itemIndex !== index);
              const copy = { ...current as Record<string, Value> };
              delete copy[key];
              return copy;
            })} />
        </Card>;
      })}
      {entryCount === 0 && <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无字段" />}
      {!disabled && <Space.Compact style={{ width: '100%' }}>
        {!Array.isArray(value) && <Input aria-label={`${label}新字段名`} placeholder="新字段名"
          value={newKey} status={addError ? 'error' : undefined}
          onChange={(event) => { setNewKey(event.target.value); setAddError(''); }}
          onPressEnter={addEntry} />}
        <Button icon={<PlusOutlined />} type="dashed" onClick={addEntry}>
          {Array.isArray(value) ? '添加列表项' : '添加字段'}
        </Button>
      </Space.Compact>}
      {addError && <Typography.Text type="danger">{addError}</Typography.Text>}
    </div>}
  </Space>;
  if (depth > 0 && isContainer(value)) {
    return <Collapse size="small" defaultActiveKey={depth < 2 ? ['content'] : []}
      items={[{ key: 'content', label: <Space><Typography.Text strong>{label}</Typography.Text>
        <Tag>{type === 'array' ? '列表' : '对象'} · {entryCount} 项</Tag></Space>, children: content }]} />;
  }
  return content;
}

/** 任意对象及嵌套字段均用控件编辑，序列化时保留未修改的历史字段。 */
export default function StructuredValueEditor({ value, onChange, disabled, objectOnly, arrayOnly, emptyText }: Props) {
  const parsed = useMemo(() => {
    try {
      const result = JSON.parse(value?.trim() || '{}') as Value;
      if (arrayOnly && !Array.isArray(result)) return { error: '样例必须是列表' };
      if (objectOnly && (result === null || typeof result !== 'object' || Array.isArray(result))) {
        return { error: '根节点必须是对象' };
      }
      return { root: result };
    } catch {
      return { error: '历史配置格式无效，请先由管理员修复原始数据' };
    }
  }, [objectOnly, arrayOnly, value]);
  if (parsed.error || parsed.root === undefined) return <Alert showIcon type="error" message={parsed.error} />;
  const update = (path: (string | number)[], updater: (node: Value) => Value) => {
    onChange?.(JSON.stringify(withNode(parsed.root!, path, updater)));
  };
  return <div>
    {emptyText && <Typography.Paragraph type="secondary">{emptyText}</Typography.Paragraph>}
    <Field label={arrayOnly ? '样例' : '配置'} value={parsed.root} path={[]} disabled={disabled} lockType={objectOnly || arrayOnly} update={update} />
  </div>;
}
