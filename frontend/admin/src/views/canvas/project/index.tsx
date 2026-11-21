import React from 'react';
import { Alert, Button, Tag } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import CrudPage, { type CrudConfig } from '@/components/CrudPage';
import PageHeader from '@/components/PageHeader';
import { getCanvasProject, listCanvasProjects, type CanvasProjectRow } from '@/api/canvas';
import { displayBytes, displayText } from '../common';
import Identifier from '../Identifier';
import StatusTag from '../StatusTag';
import '../style.less';

const config: CrudConfig<CanvasProjectRow> = {
  title: '自由画布项目',
  rowKey: 'projectId',
  perms: { query: 'aid:canvas:project:query' },
  api: {
    list: listCanvasProjects,
    get: getCanvasProject
  },
  searchFields: [
    { name: 'projectName', label: '项目名称', type: 'input', placeholder: '请输入项目名称' },
    { name: 'ownerUserId', label: '所有者ID', type: 'input', placeholder: '请输入用户ID' },
    {
      name: 'status',
      label: '项目状态',
      type: 'select',
      options: [
        { label: '正常', value: 0 },
        { label: '停用', value: 1 }
      ]
    }
  ],
  columns: [
    { title: '项目ID', dataIndex: 'projectId', width: 130, render: (value) => <Identifier value={value} /> },
    { title: '项目名称', dataIndex: 'projectName', width: 200, ellipsis: true },
    {
      title: '所有者', dataIndex: 'ownerName', width: 180,
      render: (value, row) => (
        <div>
          <div>{displayText(value)}</div>
          <Identifier value={row.ownerUserId} />
        </div>
      )
    },
    { title: '状态', dataIndex: 'status', width: 90, render: (value) => <StatusTag status={value} /> },
    { title: '节点', dataIndex: 'nodeCount', width: 80, align: 'right' },
    { title: '成员', dataIndex: 'memberCount', width: 80, align: 'right' },
    {
      title: '在线', dataIndex: 'onlineCount', width: 90, align: 'right',
      render: (value) => <Tag color={Number(value) > 0 ? 'green' : 'default'}>{Number(value) || 0}</Tag>
    },
    { title: '工作区版本', dataIndex: 'revision', width: 110, align: 'right' },
    { title: '更新时间', dataIndex: 'updateTime', width: 170, dateFormat: true }
  ],
  formFields: [
    { name: 'projectId', label: '项目ID', disabled: true },
    { name: 'projectName', label: '项目名称', disabled: true },
    { name: 'ownerUserId', label: '所有者ID', disabled: true },
    { name: 'ownerName', label: '所有者名称', disabled: true },
    { name: 'status', label: '状态', disabled: true },
    { name: 'workspaceId', label: '工作区ID', disabled: true },
    { name: 'workspaceType', label: '工作区类型', disabled: true },
    { name: 'schemaVersion', label: '结构版本', disabled: true },
    { name: 'revision', label: '工作区版本', disabled: true },
    { name: 'nodeCount', label: '节点数', disabled: true },
    { name: 'edgeCount', label: '连线数', disabled: true },
    { name: 'groupCount', label: '分组数', disabled: true },
    { name: 'memberCount', label: '成员数', disabled: true },
    { name: 'onlineCount', label: '在线人数', disabled: true },
    {
      name: 'storageBytes', label: '存储用量', type: 'custom', span: 24,
      viewRender: (value) => displayBytes(value)
    },
    { name: 'updateTime', label: '更新时间', disabled: true }
  ],
  hideAdd: true,
  hideEdit: true,
  hideDelete: true,
  selectable: false,
  exportable: false,
  viewable: true,
  pageSize: 20,
  toolbarExtra: ({ refresh }) => (
    <Button icon={<ReloadOutlined />} onClick={refresh}>刷新</Button>
  )
};

export default function CanvasProjectPage() {
  return (
    <div className="canvas-admin-page">
      <PageHeader title="自由画布项目" desc="只读查看项目、工作区规模和在线协作情况" />
      <Alert
        className="canvas-readonly-alert"
        type="info"
        showIcon
        message="项目内容和成员关系由用户端权限管理，后台页面不提供直接修改或删除操作。"
      />
      <CrudPage config={config} />
    </div>
  );
}
