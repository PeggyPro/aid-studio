import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Form,
  Input,
  Modal,
  Space,
  Table,
  Tabs,
  Tag,
  message
} from 'antd';
import { DisconnectOutlined, ReloadOutlined } from '@ant-design/icons';
import Auth from '@/components/Auth';
import PageHeader from '@/components/PageHeader';
import Pagination from '@/components/Pagination';
import {
  listCanvasLocks,
  listCanvasSessions,
  terminateCanvasSession,
  type CanvasLockRow,
  type CanvasSessionRow
} from '@/api/canvas';
import { displayText, displayTime, readPage } from '../common';
import Identifier from '../Identifier';
import StatusTag from '../StatusTag';
import '../style.less';

type TabKey = 'sessions' | 'locks';

export default function CanvasSessionPage() {
  const [searchForm] = Form.useForm();
  const [terminateForm] = Form.useForm();
  const [activeTab, setActiveTab] = useState<TabKey>('sessions');
  const [query, setQuery] = useState<Record<string, unknown>>({ pageNum: 1, pageSize: 20 });
  const [sessions, setSessions] = useState<CanvasSessionRow[]>([]);
  const [locks, setLocks] = useState<CanvasLockRow[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [terminateTarget, setTerminateTarget] = useState<CanvasSessionRow | null>(null);
  const loadSequence = useRef(0);
  const submittingRef = useRef(false);

  const load = useCallback(async (force = false) => {
    const sequence = ++loadSequence.current;
    setLoading(true);
    try {
      const response = activeTab === 'sessions'
        ? await listCanvasSessions(query, force)
        : await listCanvasLocks(query, force);
      if (activeTab === 'sessions') {
        const page = readPage<CanvasSessionRow>(response);
        if (sequence !== loadSequence.current) return;
        setSessions(page.rows);
        setTotal(page.total);
      } else {
        const page = readPage<CanvasLockRow>(response);
        if (sequence !== loadSequence.current) return;
        setLocks(page.rows);
        setTotal(page.total);
      }
    } finally {
      if (sequence === loadSequence.current) setLoading(false);
    }
  }, [activeTab, query]);

  useEffect(() => {
    load();
  }, [load]);

  const submitTerminate = async () => {
    if (!terminateTarget) return;
    if (submittingRef.current) return;
    submittingRef.current = true;
    setSubmitting(true);
    try {
      const values = await terminateForm.validateFields();
      await terminateCanvasSession({ sessionId: terminateTarget.sessionId, reason: values.reason });
      message.success('协作会话已终止');
      setTerminateTarget(null);
      terminateForm.resetFields();
      await load(true);
    } finally {
      submittingRef.current = false;
      setSubmitting(false);
    }
  };

  const sessionColumns = [
    { title: '会话ID', dataIndex: 'sessionId', width: 220, render: (value: unknown) => <Identifier value={value} /> },
    {
      title: '项目', dataIndex: 'projectName', width: 180,
      render: (value: unknown, row: CanvasSessionRow) => (
        <div><div>{displayText(value)}</div><Identifier value={row.projectId} /></div>
      )
    },
    {
      title: '用户', dataIndex: 'userName', width: 160,
      render: (value: unknown, row: CanvasSessionRow) => (
        <div><div>{displayText(value)}</div><Identifier value={row.userId} /></div>
      )
    },
    { title: '工作区ID', dataIndex: 'workspaceId', width: 140, render: (value: unknown) => <Identifier value={value} /> },
    { title: '客户端IP', dataIndex: 'clientIp', width: 140, render: displayText },
    { title: '状态', dataIndex: 'status', width: 100, render: (value: unknown) => <StatusTag status={value} /> },
    { title: '连接时间', dataIndex: 'connectedAt', width: 170, render: displayTime },
    { title: '最后心跳', dataIndex: 'lastHeartbeatAt', width: 170, render: displayTime },
    {
      title: '操作', key: 'actions', fixed: 'right' as const, width: 110,
      render: (_: unknown, row: CanvasSessionRow) => (
        <Auth permission="aid:canvas:session:terminate">
          <Button
            type="link"
            danger
            size="small"
            icon={<DisconnectOutlined />}
            disabled={String(row.status).toUpperCase() !== 'ONLINE'}
            onClick={() => {
              terminateForm.resetFields();
              setTerminateTarget(row);
            }}
          >
            终止会话
          </Button>
        </Auth>
      )
    }
  ];

  const lockColumns = [
    {
      title: '项目', dataIndex: 'projectName', width: 180,
      render: (value: unknown, row: CanvasLockRow) => (
        <div><div>{displayText(value)}</div><Identifier value={row.projectId} /></div>
      )
    },
    { title: '工作区ID', dataIndex: 'workspaceId', width: 140, render: (value: unknown) => <Identifier value={value} /> },
    { title: '对象ID', dataIndex: 'objectId', width: 200, render: (value: unknown) => <Identifier value={value} /> },
    {
      title: '占用类型', dataIndex: 'lockType', width: 110,
      render: (value: CanvasLockRow['lockType']) => (
        <Tag color={value === 'TASK' ? 'purple' : 'blue'}>{value === 'TASK' ? '生成任务' : '编辑租约'}</Tag>
      )
    },
    {
      title: '持有用户', dataIndex: 'userName', width: 160,
      render: (value: unknown, row: CanvasLockRow) => (
        <div><div>{displayText(value)}</div><Identifier value={row.userId} /></div>
      )
    },
    { title: '会话ID', dataIndex: 'sessionId', width: 220, render: (value: unknown) => <Identifier value={value} /> },
    { title: '任务ID', dataIndex: 'taskId', width: 150, render: (value: unknown) => <Identifier value={value} /> },
    { title: '获得时间', dataIndex: 'acquiredAt', width: 170, render: displayTime },
    { title: '租约到期', dataIndex: 'expireAt', width: 170, render: displayTime }
  ];

  const table = activeTab === 'sessions' ? (
    <Table
      rowKey="sessionId"
      loading={loading}
      dataSource={sessions}
      columns={sessionColumns as any}
      pagination={false}
      scroll={{ x: 'max-content' }}
    />
  ) : (
    <Table
      rowKey={(row) => `${row.workspaceId}:${row.objectId}:${row.lockType}`}
      loading={loading}
      dataSource={locks}
      columns={lockColumns as any}
      pagination={false}
      scroll={{ x: 'max-content' }}
    />
  );

  return (
    <div className="canvas-admin-page">
      <PageHeader
        title="自由画布协作会话"
        desc="查看在线连接和节点占用；终止会话只释放该会话的普通编辑租约"
      />
      <Alert
        className="canvas-readonly-alert"
        type="warning"
        showIcon
        message="生成任务占用由权威任务终态管理，后台不提供强制解除任务锁操作。"
      />
      <Card bordered={false} className="page-card">
        <Form
          form={searchForm}
          layout="inline"
          onFinish={(values) => setQuery((current) => ({ ...current, ...values, pageNum: 1 }))}
        >
          <Form.Item name="projectId" label="项目ID"><Input allowClear placeholder="请输入项目ID" /></Form.Item>
          <Form.Item name="userId" label="用户ID"><Input allowClear placeholder="请输入用户ID" /></Form.Item>
          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit">查询</Button>
              <Button onClick={() => { searchForm.resetFields(); setQuery({ pageNum: 1, pageSize: query.pageSize }); }}>重置</Button>
            </Space>
          </Form.Item>
        </Form>
      </Card>
      <Card bordered={false} className="page-card">
        <Tabs
          activeKey={activeTab}
          onChange={(key) => {
            setActiveTab(key as TabKey);
            setQuery((current) => ({ ...current, pageNum: 1 }));
          }}
          tabBarExtraContent={<Button icon={<ReloadOutlined />} onClick={() => load(true)} loading={loading}>刷新</Button>}
          items={[
            { key: 'sessions', label: '在线会话' },
            { key: 'locks', label: '节点占用' }
          ]}
        />
        {table}
        <Pagination
          total={total}
          page={Number(query.pageNum)}
          limit={Number(query.pageSize)}
          onChange={(pageNum, pageSize) => setQuery((current) => ({ ...current, pageNum, pageSize }))}
        />
      </Card>

      <Modal
        title="终止协作会话"
        open={Boolean(terminateTarget)}
        okText="确认终止"
        okButtonProps={{ danger: true }}
        confirmLoading={submitting}
        maskClosable={false}
        destroyOnClose
        onCancel={() => setTerminateTarget(null)}
        onOk={submitTerminate}
      >
        <Alert
          type="warning"
          showIcon
          message="该用户会立即断开协作连接，未提交的本地修改不会由后台代为保存。"
          style={{ marginBottom: 16 }}
        />
        <Form form={terminateForm} layout="vertical">
          <Form.Item label="会话ID"><Identifier value={terminateTarget?.sessionId} /></Form.Item>
          <Form.Item name="reason" label="终止原因" rules={[{ required: true, whitespace: true, max: 500, message: '请输入终止原因' }]}>
            <Input.TextArea rows={3} maxLength={500} showCount autoFocus />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
