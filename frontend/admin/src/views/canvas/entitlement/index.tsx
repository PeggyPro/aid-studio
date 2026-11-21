import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  DatePicker,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Switch,
  Table,
  Tabs,
  Tag,
  message
} from 'antd';
import { PlusOutlined, ReloadOutlined, SaveOutlined, StopOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import Auth from '@/components/Auth';
import PageHeader from '@/components/PageHeader';
import Pagination from '@/components/Pagination';
import {
  addEntitlementOverride,
  getDefaultEntitlements,
  listEntitlementOverrides,
  revokeEntitlementOverride,
  updateDefaultEntitlements,
  type CanvasEntitlementItem,
  type CanvasEntitlementOverrideRow,
  type EntitlementOverrideMode
} from '@/api/canvas';
import { useAuth } from '@/hooks/useAuth';
import { displayText, displayTime, readPage } from '../common';
import Identifier from '../Identifier';
import '../style.less';

const modeMeta: Record<EntitlementOverrideMode, { label: string; color: string }> = {
  ADD: { label: '追加', color: 'blue' },
  OVERRIDE: { label: '覆盖', color: 'gold' },
  DENY: { label: '禁止', color: 'red' }
};

function readEntitlementItems(response: any): CanvasEntitlementItem[] {
  const data = response?.data;
  if (Array.isArray(data)) return data;
  return Array.isArray(data?.items) ? data.items : [];
}

export default function CanvasEntitlementPage() {
  const { hasPermi } = useAuth();
  const canEdit = hasPermi('aid:canvas:entitlement:edit');
  const [overrideForm] = Form.useForm();
  const [revokeForm] = Form.useForm();
  const [searchForm] = Form.useForm();
  const [defaults, setDefaults] = useState<CanvasEntitlementItem[]>([]);
  const [defaultLoading, setDefaultLoading] = useState(false);
  const [defaultSaving, setDefaultSaving] = useState(false);
  const [overrideRows, setOverrideRows] = useState<CanvasEntitlementOverrideRow[]>([]);
  const [overrideTotal, setOverrideTotal] = useState(0);
  const [overrideLoading, setOverrideLoading] = useState(false);
  const [overrideSubmitting, setOverrideSubmitting] = useState(false);
  const [overrideOpen, setOverrideOpen] = useState(false);
  const [revokeTarget, setRevokeTarget] = useState<CanvasEntitlementOverrideRow | null>(null);
  const [query, setQuery] = useState<Record<string, unknown>>({ pageNum: 1, pageSize: 20 });
  const defaultLoadSequence = useRef(0);
  const overrideLoadSequence = useRef(0);
  const defaultSavingRef = useRef(false);
  const overrideSubmittingRef = useRef(false);
  const selectedEntitlementCode = Form.useWatch('entitlementCode', overrideForm);

  const loadDefaults = useCallback(async (force = false) => {
    const sequence = ++defaultLoadSequence.current;
    setDefaultLoading(true);
    try {
      const response = await getDefaultEntitlements(force);
      if (sequence !== defaultLoadSequence.current) return;
      setDefaults(readEntitlementItems(response));
    } finally {
      if (sequence === defaultLoadSequence.current) setDefaultLoading(false);
    }
  }, []);

  const loadOverrides = useCallback(async (force = false) => {
    const sequence = ++overrideLoadSequence.current;
    setOverrideLoading(true);
    try {
      const response = await listEntitlementOverrides(query, force);
      if (sequence !== overrideLoadSequence.current) return;
      const page = readPage<CanvasEntitlementOverrideRow>(response);
      setOverrideRows(page.rows);
      setOverrideTotal(page.total);
    } finally {
      if (sequence === overrideLoadSequence.current) setOverrideLoading(false);
    }
  }, [query]);

  useEffect(() => {
    loadDefaults();
  }, [loadDefaults]);

  useEffect(() => {
    loadOverrides();
  }, [loadOverrides]);

  const entitlementOptions = useMemo(
    () => defaults.map((item) => ({ label: `${item.name || item.entitlementCode}（${item.entitlementCode}）`, value: item.entitlementCode })),
    [defaults]
  );
  const selectedEntitlement = useMemo(
    () => defaults.find((item) => item.entitlementCode === selectedEntitlementCode),
    [defaults, selectedEntitlementCode]
  );
  const overrideModeOptions = useMemo(() => {
    const type = String(selectedEntitlement?.valueType || '').toUpperCase();
    const options = [
      { value: 'OVERRIDE', label: modeMeta.OVERRIDE.label },
      { value: 'DENY', label: modeMeta.DENY.label }
    ];
    if (type === 'INTEGER' || type === 'NUMBER') {
      options.unshift({ value: 'ADD', label: modeMeta.ADD.label });
    }
    return options;
  }, [selectedEntitlement]);

  const updateDefault = (code: string, patch: Partial<CanvasEntitlementItem>) => {
    setDefaults((items) => items.map((item) => item.entitlementCode === code ? { ...item, ...patch } : item));
  };

  const saveDefaults = async () => {
    if (defaultSavingRef.current) return;
    const invalid = defaults.find((item) => {
      const type = String(item.valueType).toUpperCase();
      if (type === 'BOOLEAN') return ![true, false, 1, 0, 'true', 'false', '1', '0'].includes(item.value as any);
      if (type === 'INTEGER') return !Number.isInteger(Number(item.value)) || Number(item.value) < 0;
      return item.value === null || item.value === undefined || String(item.value).trim() === '';
    });
    if (invalid) {
      message.error(`请检查“${invalid.name || invalid.entitlementCode}”的默认值`);
      return;
    }
    defaultSavingRef.current = true;
    setDefaultSaving(true);
    try {
      await updateDefaultEntitlements(defaults);
      message.success('默认权益已保存');
      await loadDefaults(true);
    } finally {
      defaultSavingRef.current = false;
      setDefaultSaving(false);
    }
  };

  const openAdd = () => {
    overrideForm.resetFields();
    overrideForm.setFieldsValue({ mode: 'OVERRIDE' });
    setOverrideOpen(true);
  };

  const submitOverride = async () => {
    if (overrideSubmittingRef.current) return;
    overrideSubmittingRef.current = true;
    setOverrideSubmitting(true);
    try {
      const values = await overrideForm.validateFields();
      const payload = {
        ...values,
        startTime: values.startTime ? dayjs(values.startTime).format('YYYY-MM-DD HH:mm:ss') : undefined,
        endTime: values.endTime ? dayjs(values.endTime).format('YYYY-MM-DD HH:mm:ss') : undefined,
        value: values.mode === 'DENY' ? undefined : values.value
      };
      await addEntitlementOverride(payload);
      message.success('用户特殊授权已创建');
      setOverrideOpen(false);
      if (Number(query.pageNum) === 1) await loadOverrides(true);
      else setQuery((current) => ({ ...current, pageNum: 1 }));
    } finally {
      overrideSubmittingRef.current = false;
      setOverrideSubmitting(false);
    }
  };

  const submitRevoke = async () => {
    if (!revokeTarget) return;
    if (overrideSubmittingRef.current) return;
    overrideSubmittingRef.current = true;
    setOverrideSubmitting(true);
    try {
      const values = await revokeForm.validateFields();
      await revokeEntitlementOverride({ id: revokeTarget.id, reason: values.reason });
      message.success('特殊授权已撤销');
      setRevokeTarget(null);
      revokeForm.resetFields();
      await loadOverrides(true);
    } finally {
      overrideSubmittingRef.current = false;
      setOverrideSubmitting(false);
    }
  };

  const defaultColumns = [
    {
      title: '权益', dataIndex: 'name', width: 280,
      render: (_: unknown, row: CanvasEntitlementItem) => (
        <div>
          <div className="canvas-entitlement-name">{row.name || row.entitlementCode}</div>
          <div className="canvas-entitlement-desc">{row.description || '暂无说明'}</div>
        </div>
      )
    },
    {
      title: '权益编码', dataIndex: 'entitlementCode', width: 240,
      render: (value: string) => <Identifier value={value} />
    },
    {
      title: '值类型', dataIndex: 'valueType', width: 110,
      render: (value: string) => <Tag>{displayText(value)}</Tag>
    },
    {
      title: '启用', dataIndex: 'enabled', width: 100,
      render: (value: boolean, row: CanvasEntitlementItem) => (
        <Switch
          checked={Boolean(value)}
          disabled={!canEdit}
          aria-label={`${row.name || row.entitlementCode}启用状态`}
          onChange={(checked) => updateDefault(row.entitlementCode, { enabled: checked })}
        />
      )
    },
    {
      title: '默认值', dataIndex: 'value', width: 260,
      render: (value: unknown, row: CanvasEntitlementItem) => {
        if (String(row.valueType).toUpperCase() === 'BOOLEAN') {
          const checked = value === true || value === 1 || String(value).toLowerCase() === 'true';
          return (
            <Switch
              checked={checked}
              disabled={!canEdit || !row.enabled}
              checkedChildren="允许"
              unCheckedChildren="不允许"
              aria-label={`${row.name || row.entitlementCode}默认值`}
              onChange={(next) => updateDefault(row.entitlementCode, { value: next })}
            />
          );
        }
        if (String(row.valueType).toUpperCase() === 'INTEGER') {
          return (
            <InputNumber
              value={value === null || value === undefined || value === '' ? null : Number(value)}
              min={0}
              precision={0}
              disabled={!canEdit || !row.enabled}
              aria-label={`${row.name || row.entitlementCode}默认值`}
              style={{ width: '100%' }}
              onChange={(next) => updateDefault(row.entitlementCode, { value: next })}
            />
          );
        }
        return (
          <Input
            value={value === null || value === undefined ? '' : String(value)}
            disabled={!canEdit || !row.enabled}
            aria-label={`${row.name || row.entitlementCode}默认值`}
            onChange={(event) => updateDefault(row.entitlementCode, { value: event.target.value })}
          />
        );
      }
    }
  ];

  const overrideColumns = [
    { title: '授权ID', dataIndex: 'id', width: 110, render: (value: unknown) => <Identifier value={value} /> },
    {
      title: '用户', dataIndex: 'userName', width: 170,
      render: (value: unknown, row: CanvasEntitlementOverrideRow) => (
        <div><div>{displayText(value)}</div><Identifier value={row.userId} /></div>
      )
    },
    { title: '权益编码', dataIndex: 'entitlementCode', width: 220, render: (value: unknown) => <Identifier value={value} /> },
    {
      title: '模式', dataIndex: 'mode', width: 90,
      render: (value: EntitlementOverrideMode) => <Tag color={modeMeta[value]?.color}>{modeMeta[value]?.label || value}</Tag>
    },
    { title: '授权值', dataIndex: 'value', width: 120, render: displayText },
    { title: '生效时间', dataIndex: 'startTime', width: 170, render: displayTime },
    { title: '到期时间', dataIndex: 'endTime', width: 170, render: displayTime },
    {
      title: '状态', dataIndex: 'status', width: 100,
      render: (value: unknown) => {
        const status = String(value ?? '');
        const color = status.toUpperCase() === 'ACTIVE' ? 'green' : 'default';
        return <Tag color={color}>{displayText(value)}</Tag>;
      }
    },
    { title: '授权原因', dataIndex: 'reason', width: 220, ellipsis: true },
    { title: '创建时间', dataIndex: 'createTime', width: 170, render: displayTime },
    {
      title: '操作', key: 'actions', fixed: 'right' as const, width: 100,
      render: (_: unknown, row: CanvasEntitlementOverrideRow) => (
        <Auth permission="aid:canvas:entitlement:edit">
          <Button
            type="link"
            danger
            size="small"
            icon={<StopOutlined />}
            disabled={String(row.status).toUpperCase() === 'REVOKED'}
            onClick={() => {
              revokeForm.resetFields();
              setRevokeTarget(row);
            }}
          >
            撤销
          </Button>
        </Auth>
      )
    }
  ];

  const defaultPanel = (
    <Card bordered={false} className="page-card">
      <div className="canvas-toolbar">
        <div className="canvas-toolbar__hint">默认权益适用于未命中特殊授权的用户，具体项目操作仍需通过项目角色校验。</div>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={() => loadDefaults(true)} loading={defaultLoading}>刷新</Button>
          <Auth permission="aid:canvas:entitlement:edit">
            <Button
              type="primary"
              icon={<SaveOutlined />}
              onClick={saveDefaults}
              loading={defaultSaving}
              disabled={defaultLoading || defaults.length === 0}
            >
              保存默认权益
            </Button>
          </Auth>
        </Space>
      </div>
      <Table
        rowKey="entitlementCode"
        loading={defaultLoading}
        dataSource={defaults}
        columns={defaultColumns as any}
        pagination={false}
        scroll={{ x: 'max-content' }}
      />
    </Card>
  );

  const overridePanel = (
    <>
      <Card bordered={false} className="page-card">
        <Form
          form={searchForm}
          layout="inline"
          onFinish={(values) => setQuery((current) => ({ ...current, ...values, pageNum: 1 }))}
        >
          <Form.Item name="userId" label="用户ID"><Input allowClear placeholder="请输入用户ID" /></Form.Item>
          <Form.Item name="entitlementCode" label="权益编码">
            <Select allowClear showSearch options={entitlementOptions} style={{ width: 260 }} placeholder="请选择权益" />
          </Form.Item>
          <Form.Item name="status" label="状态">
            <Select allowClear style={{ width: 130 }} options={[
              { label: '生效中', value: 'ACTIVE' },
              { label: '未生效', value: 'PENDING' },
              { label: '已到期', value: 'EXPIRED' },
              { label: '已撤销', value: 'REVOKED' }
            ]} />
          </Form.Item>
          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit">查询</Button>
              <Button onClick={() => { searchForm.resetFields(); setQuery({ pageNum: 1, pageSize: query.pageSize }); }}>重置</Button>
            </Space>
          </Form.Item>
        </Form>
      </Card>
      <Card bordered={false} className="page-card">
        <div className="canvas-toolbar">
          <div className="canvas-toolbar__hint">特殊授权支持追加、覆盖和禁止；撤销后保留完整审计记录。</div>
          <Space>
            <Button icon={<ReloadOutlined />} onClick={() => loadOverrides(true)} loading={overrideLoading}>刷新</Button>
            <Auth permission="aid:canvas:entitlement:edit">
              <Button
                type="primary"
                icon={<PlusOutlined />}
                onClick={openAdd}
                disabled={defaultLoading || defaults.length === 0}
              >
                新增特殊授权
              </Button>
            </Auth>
          </Space>
        </div>
        <Table
          rowKey="id"
          loading={overrideLoading}
          dataSource={overrideRows}
          columns={overrideColumns as any}
          pagination={false}
          scroll={{ x: 'max-content' }}
        />
        <Pagination
          total={overrideTotal}
          page={Number(query.pageNum)}
          limit={Number(query.pageSize)}
          onChange={(pageNum, pageSize) => setQuery((current) => ({ ...current, pageNum, pageSize }))}
        />
      </Card>
    </>
  );

  return (
    <div className="canvas-admin-page">
      <PageHeader title="自由画布权益" desc="统一维护默认能力和用户例外，权益不会替代项目成员角色与服务端权限校验" />
      <Alert
        className="canvas-readonly-alert"
        type="warning"
        showIcon
        message="特殊授权不能突破系统安全上限，项目角色仍决定用户在具体项目中的可用操作。"
      />
      <Tabs items={[
        { key: 'default', label: '默认权益', children: defaultPanel },
        { key: 'override', label: '用户特殊授权', children: overridePanel }
      ]} />

      <Modal
        title="新增用户特殊授权"
        open={overrideOpen}
        width={680}
        confirmLoading={overrideSubmitting}
        maskClosable={false}
        destroyOnClose
        onCancel={() => setOverrideOpen(false)}
        onOk={submitOverride}
      >
        <Form form={overrideForm} layout="vertical" className="canvas-form-grid canvas-form-grid--two">
          <Form.Item name="userId" label="用户ID" rules={[{ required: true, message: '请输入用户ID' }]}>
            <Input inputMode="numeric" placeholder="请输入需要授权的用户ID" />
          </Form.Item>
          <Form.Item name="entitlementCode" label="权益编码" rules={[{ required: true, message: '请选择权益' }]}>
            <Select
              showSearch
              options={entitlementOptions}
              placeholder="请选择权益"
              onChange={(code) => {
                overrideForm.setFieldValue('value', undefined);
                const definition = defaults.find((item) => item.entitlementCode === code);
                const type = String(definition?.valueType || '').toUpperCase();
                if (!['INTEGER', 'NUMBER'].includes(type) && overrideForm.getFieldValue('mode') === 'ADD') {
                  overrideForm.setFieldValue('mode', 'OVERRIDE');
                }
              }}
            />
          </Form.Item>
          <Form.Item name="mode" label="授权模式" rules={[{ required: true, message: '请选择授权模式' }]}>
            <Select options={overrideModeOptions} />
          </Form.Item>
          <Form.Item noStyle shouldUpdate={(previous, current) => previous.mode !== current.mode || previous.entitlementCode !== current.entitlementCode}>
            {({ getFieldValue }) => {
              if (getFieldValue('mode') === 'DENY') return <div />;
              const valueType = String(selectedEntitlement?.valueType || '').toUpperCase();
              return (
                <Form.Item name="value" label="授权值" rules={[{ required: true, message: '请输入授权值' }]}>
                  {valueType === 'BOOLEAN' ? (
                    <Select options={[{ label: '允许', value: true }, { label: '不允许', value: false }]} />
                  ) : ['INTEGER', 'NUMBER'].includes(valueType) ? (
                    <InputNumber min={0} precision={0} style={{ width: '100%' }} />
                  ) : (
                    <Input placeholder="请输入授权值" />
                  )}
                </Form.Item>
              );
            }}
          </Form.Item>
          <Form.Item
            name="startTime"
            label="生效时间"
            tooltip="留空表示服务端受理后立即生效。"
          >
            <DatePicker showTime style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item
            name="endTime"
            label="到期时间"
            dependencies={['startTime']}
            rules={[
              ({ getFieldValue }) => ({
                validator(_, value) {
                  const start = getFieldValue('startTime');
                  if (!value || !start || dayjs(value).isAfter(dayjs(start))) return Promise.resolve();
                  return Promise.reject(new Error('到期时间必须晚于生效时间'));
                }
              })
            ]}
          >
            <DatePicker showTime style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="reason" label="授权原因" className="canvas-form-span-full" rules={[{ required: true, whitespace: true, max: 500, message: '请输入授权原因' }]}>
            <Input.TextArea rows={3} maxLength={500} showCount placeholder="说明授权依据，便于后续审计" />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="撤销特殊授权"
        open={Boolean(revokeTarget)}
        okText="确认撤销"
        okButtonProps={{ danger: true }}
        confirmLoading={overrideSubmitting}
        maskClosable={false}
        destroyOnClose
        onCancel={() => setRevokeTarget(null)}
        onOk={submitRevoke}
      >
        <Alert
          type="warning"
          showIcon
          message={`将撤销授权 ${revokeTarget?.entitlementCode || ''}，历史记录仍会保留。`}
          style={{ marginBottom: 16 }}
        />
        <Form form={revokeForm} layout="vertical">
          <Form.Item name="reason" label="撤销原因" rules={[{ required: true, whitespace: true, max: 500, message: '请输入撤销原因' }]}>
            <Input.TextArea rows={3} maxLength={500} showCount autoFocus />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
