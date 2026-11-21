import { useEffect, useRef, useState } from 'react';
import { Alert, App, Card, Space, Switch, Typography } from 'antd';
import { useUserStore } from '@/store/useUserStore';
import { getSceneAssistantSetting, updateSceneAssistantSetting, type SceneAssistantSettingView } from '@/api/aid/compute';

export default function SceneAssistantSetting({ refreshKey }: { refreshKey: number }) {
  const { message } = App.useApp();
  const canEdit = useUserStore(state => state.permissions.includes('*:*:*') || state.permissions.includes('aid:compute:edit'));
  const [setting, setSetting] = useState<SceneAssistantSettingView>();
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const writing = useRef(false);

  useEffect(() => {
    let active = true;
    setLoading(true);
    setError('');
    void getSceneAssistantSetting().then(result => {
      if (active && !writing.current) setSetting(result.data);
    }).catch((reason: unknown) => {
      if (active) setError(reason instanceof Error ? reason.message : '读取场景助手配置失败');
    }).finally(() => {
      if (active) setLoading(false);
    });
    return () => { active = false; };
  }, [refreshKey]);

  async function change(enabled: boolean) {
    if (!canEdit || writing.current) return;
    writing.current = true;
    setSaving(true);
    setError('');
    try {
      const result = await updateSceneAssistantSetting(enabled);
      setSetting(result.data);
      message.success(enabled ? '已开启场景助手' : '已关闭场景助手');
    } catch (reason: unknown) {
      setError(reason instanceof Error ? reason.message : '更新场景助手配置失败');
    } finally {
      writing.current = false;
      setSaving(false);
    }
  }

  return (
    <Card size="small" title="预演台场景助手" extra={
      <Switch aria-label="启用大模型生成三维场景" checked={setting?.enabled ?? false}
        loading={loading || saving}
        disabled={!canEdit || !setting || (!setting.enabled && !setting.canEnable)}
        onChange={change} />
    }>
      <Space direction="vertical" size="small">
        <Typography.Text type="secondary">启用后，画布预演台可使用大模型生成三维场景。</Typography.Text>
        {setting?.poolCode && <Typography.Text>计算资源池 {setting.poolCode}</Typography.Text>}
        {setting?.message && <Alert type="warning" showIcon message={setting.message} />}
        {error && <Alert type="error" showIcon message={error} />}
      </Space>
    </Card>
  );
}
