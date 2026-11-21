import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Button, Card, Form, Input, Modal, Space, Table } from 'antd';
import { EyeOutlined, ReloadOutlined } from '@ant-design/icons';
import PageHeader from '@/components/PageHeader';
import Pagination from '@/components/Pagination';
import { listCanvasAudits, type CanvasAuditRow } from '@/api/canvas';
import { displayText, displayTime, readPage } from '../common';
import Identifier from '../Identifier';
import '../style.less';

export default function CanvasAuditPage() {
  const [form] = Form.useForm();
  const [query, setQuery] = useState<Record<string, unknown>>({ pageNum: 1, pageSize: 20 });
  const [rows, setRows] = useState<CanvasAuditRow[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [detail, setDetail] = useState<string | null>(null);
  const loadSequence = useRef(0);

  const load = useCallback(async (force = false) => {
    const sequence = ++loadSequence.current;
    setLoading(true);
    try {
      const response = await listCanvasAudits(query, force);
      if (sequence !== loadSequence.current) return;
      const page = readPage<CanvasAuditRow>(response);
      setRows(page.rows);
      setTotal(page.total);
    } finally {
      if (sequence === loadSequence.current) setLoading(false);
    }
  }, [query]);

  useEffect(() => {
    load();
  }, [load]);

  const columns = [
    { title: '记录ID', dataIndex: 'id', width: 110, render: (value: unknown) => <Identifier value={value} /> },
    {
      title: '操作人', dataIndex: 'operatorName', width: 170,
      render: (value: unknown, row: CanvasAuditRow) => (
        <div><div>{displayText(value)}</div><span className="help-text">{displayText(row.operator)}</span></div>
      )
    },
    { title: '动作', dataIndex: 'action', width: 190, render: (value: unknown) => <Identifier value={value} /> },
    { title: '目标类型', dataIndex: 'targetType', width: 120, render: displayText },
    { title: '目标ID', dataIndex: 'targetId', width: 140, render: (value: unknown) => <Identifier value={value} /> },
    { title: '目标用户ID', dataIndex: 'targetUserId', width: 140, render: (value: unknown) => <Identifier value={value} /> },
    {
      title: '操作详情', dataIndex: 'detail', width: 360,
      render: (value: unknown) => {
        const text = displayText(value);
        if (text === '-') return text;
        return (
          <Space size={8}>
            <span className="canvas-audit-detail">{text}</span>
            <Button type="link" size="small" icon={<EyeOutlined />} onClick={() => setDetail(text)}>查看</Button>
          </Space>
        );
      }
    },
    { title: '操作时间', dataIndex: 'createTime', width: 170, render: displayTime }
  ];

  return (
    <div className="canvas-admin-page">
      <PageHeader title="自由画布操作审计" desc="查询协作配置、权益和会话管理等后台操作记录" />
      <Card bordered={false} className="page-card">
        <Form
          form={form}
          layout="inline"
          onFinish={(values) => setQuery((current) => ({ ...current, ...values, pageNum: 1 }))}
        >
          <Form.Item name="operator" label="操作账号"><Input allowClear placeholder="请输入操作账号" /></Form.Item>
          <Form.Item name="targetUserId" label="目标用户ID"><Input allowClear placeholder="请输入用户ID" /></Form.Item>
          <Form.Item name="action" label="动作"><Input allowClear placeholder="请输入动作编码" /></Form.Item>
          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit">查询</Button>
              <Button onClick={() => { form.resetFields(); setQuery({ pageNum: 1, pageSize: query.pageSize }); }}>重置</Button>
            </Space>
          </Form.Item>
        </Form>
      </Card>
      <Card bordered={false} className="page-card">
        <div className="canvas-toolbar">
          <div className="canvas-toolbar__hint">审计记录只读展示，长内容可点击查看完整详情。</div>
          <Button icon={<ReloadOutlined />} onClick={() => load(true)} loading={loading}>刷新</Button>
        </div>
        <Table
          rowKey="id"
          loading={loading}
          dataSource={rows}
          columns={columns as any}
          pagination={false}
          scroll={{ x: 'max-content' }}
        />
        <Pagination
          total={total}
          page={Number(query.pageNum)}
          limit={Number(query.pageSize)}
          onChange={(pageNum, pageSize) => setQuery((current) => ({ ...current, pageNum, pageSize }))}
        />
      </Card>
      <Modal
        title="审计详情"
        open={detail !== null}
        footer={<Button type="primary" onClick={() => setDetail(null)}>关闭</Button>}
        onCancel={() => setDetail(null)}
        destroyOnClose
      >
        <pre className="readonly-preview">{detail}</pre>
      </Modal>
    </div>
  );
}
