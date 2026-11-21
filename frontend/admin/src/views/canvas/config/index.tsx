import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, Button, Card, Form, InputNumber, Space, Spin, Switch, message } from 'antd';
import { ReloadOutlined, SaveOutlined } from '@ant-design/icons';
import Auth from '@/components/Auth';
import PageHeader from '@/components/PageHeader';
import { useAuth } from '@/hooks/useAuth';
import {
  getCanvasConfig,
  updateCanvasConfig,
  type CanvasCollaborationConfig
} from '@/api/canvas';
import { readData } from '../common';
import '../style.less';

const positiveRule = { required: true, type: 'number' as const, min: 1, message: '请输入大于 0 的整数' };

export default function CanvasConfigPage() {
  const { hasPermi } = useAuth();
  const canEdit = hasPermi('aid:canvas:config:edit');
  const [form] = Form.useForm<CanvasCollaborationConfig>();
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const loadSequence = useRef(0);
  const savingRef = useRef(false);

  const load = useCallback(async (force = false) => {
    const sequence = ++loadSequence.current;
    setLoading(true);
    try {
      const response = await getCanvasConfig(force);
      if (sequence !== loadSequence.current) return;
      form.setFieldsValue(readData<CanvasCollaborationConfig>(response));
    } finally {
      if (sequence === loadSequence.current) setLoading(false);
    }
  }, [form]);

  useEffect(() => {
    load();
  }, [load]);

  const submit = async () => {
    if (savingRef.current) return;
    savingRef.current = true;
    setSaving(true);
    try {
      const values = await form.validateFields();
      await updateCanvasConfig(values);
      message.success('协作配置已保存');
      await load(true);
    } finally {
      savingRef.current = false;
      setSaving(false);
    }
  };

  return (
    <div className="canvas-admin-page">
      <PageHeader
        title="自由画布协作配置"
        desc="维护系统默认容量与协作安全边界，配置变更由服务端统一校验并使在线会话及时生效"
        extra={(
          <Space>
            <Button icon={<ReloadOutlined />} onClick={() => load(true)} loading={loading}>刷新</Button>
            <Auth permission="aid:canvas:config:edit">
              <Button type="primary" icon={<SaveOutlined />} onClick={submit} loading={saving}>保存配置</Button>
            </Auth>
          </Space>
        )}
      />

      <Alert
        className="canvas-readonly-alert"
        type="info"
        showIcon
        message="配置是业务默认值，系统资源安全上限和项目权限仍由服务端强制执行。"
      />

      <Spin spinning={loading}>
        <Form form={form} layout="vertical" requiredMark="optional" disabled={!canEdit}>
          <Card bordered={false} className="page-card" title="功能与容量">
            <div className="canvas-form-grid">
              <Form.Item
                name="collaborationEnabled"
                label="启用实时协作"
                valuePropName="checked"
                tooltip="关闭后停止新建协作连接；已有画布内容仍保留。"
              >
                <Switch checkedChildren="启用" unCheckedChildren="停用" />
              </Form.Item>
              <Form.Item name="maxProjectsPerOwner" label="单用户项目上限" rules={[positiveRule]}>
                <InputNumber min={1} precision={0} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item name="maxNodesPerWorkspace" label="单工作区节点上限" rules={[positiveRule]}>
                <InputNumber min={1} precision={0} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item name="maxMembersPerProject" label="单项目成员上限" rules={[positiveRule]}>
                <InputNumber min={1} precision={0} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item
                name="maxOnlineEditors"
                label="单画布在线编辑人数"
                dependencies={['maxMembersPerProject']}
                rules={[
                  positiveRule,
                  ({ getFieldValue }) => ({
                    validator(_, value) {
                      const members = Number(getFieldValue('maxMembersPerProject'));
                      if (!value || !members || Number(value) <= members) return Promise.resolve();
                      return Promise.reject(new Error('在线编辑人数不能超过项目成员上限'));
                    }
                  })
                ]}
              >
                <InputNumber min={1} precision={0} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item name="maxConnectionsPerUser" label="单用户连接上限" rules={[positiveRule]}>
                <InputNumber min={1} precision={0} style={{ width: '100%' }} />
              </Form.Item>
            </div>
          </Card>

          <Card bordered={false} className="page-card" title="增量提交与消息">
            <div className="canvas-form-grid">
              <Form.Item name="operationBatchMax" label="单批操作数上限" rules={[{ ...positiveRule, max: 100, message: '请输入 1 到 100 的整数' }]}>
                <InputNumber min={1} max={100} precision={0} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item
                name="operationPayloadMaxBytes"
                label="单批消息大小上限（字节）"
                rules={[{ required: true, type: 'number', min: 1024, max: 1024 * 1024, message: '请输入 1024 到 1048576 的整数' }]}
                tooltip="限制客户端一次提交的 JSON 消息体大小。"
              >
                <InputNumber min={1024} max={1024 * 1024} precision={0} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item
                name="textMergeMillis"
                label="文本合并间隔（毫秒）"
                rules={[{ required: true, type: 'number', min: 100, message: '请输入不小于 100 的整数' }]}
              >
                <InputNumber min={100} precision={0} style={{ width: '100%' }} />
              </Form.Item>
            </div>
          </Card>

          <Card bordered={false} className="page-card" title="编辑租约与邀请">
            <div className="canvas-form-grid">
              <Form.Item name="lockLeaseSeconds" label="编辑租约时长（秒）" rules={[{ required: true, type: 'number', min: 10, message: '请输入不小于 10 的整数' }]}>
                <InputNumber min={10} precision={0} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item
                name="lockRenewSeconds"
                label="租约续期间隔（秒）"
                dependencies={['lockLeaseSeconds']}
                rules={[
                  { required: true, type: 'number', min: 5, message: '请输入不小于 5 的整数' },
                  ({ getFieldValue }) => ({
                    validator(_, value) {
                      const lease = Number(getFieldValue('lockLeaseSeconds'));
                      if (!value || !lease || Number(value) < lease) return Promise.resolve();
                      return Promise.reject(new Error('续期间隔必须小于租约时长'));
                    }
                  })
                ]}
              >
                <InputNumber min={5} precision={0} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item name="inviteDefaultDays" label="邀请默认有效天数" rules={[{ ...positiveRule, max: 365, message: '请输入 1 到 365 的整数' }]}>
                <InputNumber min={1} max={365} precision={0} style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item name="inviteDefaultUses" label="邀请默认加入次数" rules={[{ ...positiveRule, max: 1000, message: '请输入 1 到 1000 的整数' }]}>
                <InputNumber min={1} max={1000} precision={0} style={{ width: '100%' }} />
              </Form.Item>
            </div>
          </Card>
        </Form>
      </Spin>
    </div>
  );
}
