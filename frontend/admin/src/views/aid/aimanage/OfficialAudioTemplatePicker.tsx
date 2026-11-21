import React, { useRef, useState } from 'react';
import { Alert, Button, Modal, Table, Tag } from 'antd';
import { sharedReadRequest } from '@/utils/sharedReadRequest';
import type { ModelCapabilityDefinition } from './modelDefinition';

/** 预览后补充缺失能力，已有参数、价格和默认项保持由管理员维护。 */
export default function OfficialAudioTemplatePicker({ model, current, onChange }: { model?: string; current: ModelCapabilityDefinition[]; onChange: (value: ModelCapabilityDefinition[]) => void }) {
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [items, setItems] = useState<ModelCapabilityDefinition[]>([]);
  const flight = useRef(false);
  const additions = items.filter((candidate) => !current.some((item) => item.code === candidate.code));
  return <><Button disabled={!model} onClick={async () => {
    if (flight.current) return;
    flight.current = true; setOpen(true); setBusy(true); setError(''); setItems([]);
    try { const result = await sharedReadRequest('/aid/officialAudio/template', { model }); setItems(result.data?.capabilities || []); }
    catch { setError('官方音频模板读取失败，请核对真实模型标识'); }
    finally { flight.current = false; setBusy(false); }
  }}>补齐官方音频能力</Button>
    <Modal title="官方音频能力、参数与 SKU" open={open} width={1000} confirmLoading={busy}
      styles={{ body: { maxHeight: '68vh', overflowY: 'auto' } }} okText="补充到表单"
      okButtonProps={{ disabled: busy || !additions.length }} onCancel={() => setOpen(false)} onOk={() => {
        const hasDefault = current.some((item) => item.defaultCapability && item.enabled);
        onChange([...current, ...structuredClone(additions).map((item) => ({ ...item, defaultCapability: !hasDefault && item.defaultCapability }))]);
        setOpen(false);
      }}>
      <Alert showIcon type="info" message="补充缺失能力及全部官方参数，不覆盖已有配置。未公开价格的能力保留停用；已发布的模板价格以人民币（元）填写。MiniMax 接口权限和 Sonilo 实际版本须按账号核验。" style={{ marginBottom: 16 }} />
      {error && <Alert role="alert" type="error" message={error} />}
      <Table size="small" pagination={false} rowKey="code" loading={busy} dataSource={items} columns={[
        { title: '能力', dataIndex: 'label' },
        { title: '参数', render: (_, item) => item.parameters[0]?.properties?.map((field) => field.label || field.name).join('、') },
        { title: '状态', render: (_, item) => <Tag color={item.enabled ? 'blue' : 'default'}>{item.enabled ? '已公开基础计价' : '待核验价格或资源权限'}</Tag> },
        { title: '应用', render: (_, item) => current.some((value) => value.code === item.code) ? '已有，保留' : '新增' }
      ]} />
    </Modal></>;
}
