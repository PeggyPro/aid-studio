import React, { useEffect, useRef, useState } from 'react';
import { Alert, Button, Input, Modal, Space, Typography, message } from 'antd';
import { request } from '@/utils/request';
import { getToken } from '@/utils/auth';
import { useAuth } from '@/hooks/useAuth';

type RootState = { configuredRoot: string; activeRoot: string | null; publicRoot: string; privateRoot: string; restartRequired: boolean; configurationDigest: string; migratedFiles: number };
const reads = new Map<string, Promise<any>>();
function readRoot() {
  const key = getToken() || '';
  const running = reads.get(key); if (running) return running;
  const promise = request({ url: '/aidconfig/local-storage', method: 'get' }).finally(() => { if (reads.get(key) === promise) reads.delete(key); });
  reads.set(key, promise); return promise;
}
export default function LocalStorageRootSection() {
  const [state, setState] = useState<RootState | null>(null), [root, setRoot] = useState(''), [loading, setLoading] = useState(false), [error, setError] = useState('');
  const flight = useRef(false), auth = useAuth();
  const load = async () => {
    setError('');
    try { const response = await readRoot(); setState(response.data); setRoot(response.data.configuredRoot || ''); }
    catch { setError('本地存储目录读取失败，请重试。'); }
  };
  useEffect(() => { let current = true; void readRoot().then(response => { if (current) { setState(response.data); setRoot(response.data.configuredRoot || ''); } }).catch(() => { if (current) setError('本地存储目录读取失败，请重试。'); }); return () => { current = false; }; }, []);
  const save = () => {
    if (!state || flight.current || !auth.hasPermi('aidconfig:aidconfig:edit')) return;
    const selectedRoot = root.trim(), digest = state.configurationDigest;
    Modal.confirm({ title: '保存统一本地数据目录？', content: '保存前会校验目录。下次重启时先迁移并逐文件校验，成功后才启用新目录；原文件会保留。请在任务空闲时受控重启服务。', okText: '校验并保存', cancelText: '取消', onOk: async () => {
      if (flight.current) return; flight.current = true; setLoading(true);
      try { const response = await request({ url: '/aidconfig/local-storage', method: 'post', data: { root: selectedRoot, expectedDigest: digest } }); setState(response.data); message.success('目录已保存，重启迁移成功后全局生效。'); }
      finally { flight.current = false; setLoading(false); }
    } });
  };
  return <section aria-label="统一本地数据目录" style={{ padding: 16, marginBottom: 16, border: '1px solid var(--ant-color-border, #ddd)', borderRadius: 8 }}>
    <Typography.Title level={5}>统一本地数据目录</Typography.Title>
    <Typography.Paragraph>支持系统盘和已挂载的数据盘，请填写服务器绝对路径。上传文件、提示词文件、Skill 包、对话归档及本地产物统一使用此目录。公开文件放在 public，私有数据放在 private；云存储桶和程序安装目录分别配置。</Typography.Paragraph>
    {error && <Alert type="error" message={error} action={<Button onClick={() => void load()}>重试</Button>} />}
    {state && <><Typography.Paragraph>当前公开目录：<Typography.Text code>{state.publicRoot}</Typography.Text><br />当前私有目录：<Typography.Text code>{state.privateRoot}</Typography.Text></Typography.Paragraph>
      {state.restartRequired && <Alert type="warning" showIcon message="新目录尚未生效" description="请在任务空闲时重启服务；迁移与校验成功后启用新目录，原文件保留。" />}
      <Space.Compact style={{ width: '100%', marginTop: 12 }}><Input aria-label="统一本地数据目录" value={root} onChange={event => setRoot(event.target.value)} placeholder="例如 /data/aid 或 D:/aid-data" disabled={loading || !auth.hasPermi('aidconfig:aidconfig:edit')} /><Button type="primary" onClick={save} loading={loading} disabled={!root.trim() || !auth.hasPermi('aidconfig:aidconfig:edit')}>校验并保存目录</Button></Space.Compact>
    </>}
  </section>;
}
