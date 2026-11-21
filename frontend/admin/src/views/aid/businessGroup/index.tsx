import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Alert, Avatar, Button, Card, Drawer, Empty, Form, Input, InputNumber, Modal,
  Popconfirm, Select, Space, Table, Tag, Typography, message } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { DeleteOutlined, EditOutlined, PlusOutlined, ReloadOutlined, SearchOutlined,
  TeamOutlined } from '@ant-design/icons';
import { listBusinessGroups, listBusinessGroupMembers, removeBusinessGroup,
  removeBusinessGroupMember, saveBusinessGroup, saveBusinessGroupMember,
  type BusinessGroup, type BusinessGroupId, type BusinessGroupMember } from '@/api/aid/businessGroup';
import { listSkills, type AdminSkillSummary } from '@/api/aid/skill';
import { listRuntimeAgents, type RuntimeAgentSummary } from '@/api/aid/legacyRuntimePlanAgent';
import { showReferenceConflict } from '@/utils/apiErrorDetails';
import { useAuth } from '@/hooks/useAuth';
import PageHeader from '@/components/PageHeader';

type GroupValues = Omit<BusinessGroup, 'ownerUserId'>;
type GroupTree = BusinessGroup & { children?: GroupTree[] };

function treeOf(rows: BusinessGroup[]) {
  const nodes = new Map(rows.map((row) => [String(row.id), { ...row, children: [] as GroupTree[] }]));
  const roots: GroupTree[] = [];
  for (const node of nodes.values()) {
    const parent = node.parentId ? nodes.get(String(node.parentId)) : undefined;
    if (parent) parent.children.push(node);
    else roots.push(node);
  }
  const sort = (items: GroupTree[]) => items.sort((a, b) => a.sortOrder - b.sortOrder
      || String(a.id).localeCompare(String(b.id)))
    .forEach((item) => { sort(item.children || []); if (!item.children?.length) delete item.children; });
  sort(roots);
  return roots;
}

async function allGroups(force = false) {
  const result: BusinessGroup[] = [];
  for (let pageNum = 1; pageNum <= 100; pageNum += 1) {
    const response = await listBusinessGroups({ pageNum, pageSize: 100 }, force);
    result.push(...response.data || []);
    if (result.length >= Number(response.total || 0) || (response.data || []).length < 100) break;
  }
  return result;
}

export default function BusinessGroupPage() {
  const { hasPermi } = useAuth();
  const canRead = hasPermi('aid:businessGroup:query');
  const canEdit = hasPermi('aid:businessGroup:edit');
  const [form] = Form.useForm<GroupValues>();
  const [groups, setGroups] = useState<BusinessGroup[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [keyword, setKeyword] = useState('');
  const [editing, setEditing] = useState<BusinessGroup | null>(null);
  const [formOpen, setFormOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [active, setActive] = useState<BusinessGroup>();
  const [members, setMembers] = useState<BusinessGroupMember[]>([]);
  const [memberTotal, setMemberTotal] = useState(0);
  const [memberPage, setMemberPage] = useState(1);
  const [memberSize, setMemberSize] = useState(20);
  const [memberLoading, setMemberLoading] = useState(false);
  const [memberError, setMemberError] = useState('');
  const [memberType, setMemberType] = useState<'SKILL' | 'AGENT'>('SKILL');
  const [memberId, setMemberId] = useState<BusinessGroupId>();
  const [memberSort, setMemberSort] = useState(0);
  const [candidateOptions, setCandidateOptions] = useState<{ label: string; value: BusinessGroupId }[]>([]);
  const [candidateLoading, setCandidateLoading] = useState(false);
  const [memberSaving, setMemberSaving] = useState(false);
  const loadSeq = useRef(0);
  const memberSeq = useRef(0);
  const candidateSeq = useRef(0);
  const candidateTimer = useRef<ReturnType<typeof setTimeout>>();
  const actionRef = useRef(false);

  const load = useCallback(async (force = false) => {
    if (!canRead) return;
    const sequence = ++loadSeq.current;
    setLoading(true);
    setError('');
    try {
      const rows = await allGroups(force);
      if (sequence === loadSeq.current) setGroups(rows);
    } catch (cause: any) {
      if (sequence === loadSeq.current) setError(cause?.message || '业务分组加载失败');
    } finally {
      if (sequence === loadSeq.current) setLoading(false);
    }
  }, [canRead]);

  useEffect(() => { void load(); }, [load]);

  const loadMembers = useCallback(async (groupId: BusinessGroupId, page = 1, size = 20, force = false) => {
    const sequence = ++memberSeq.current;
    setMemberLoading(true);
    setMemberError('');
    try {
      const response = await listBusinessGroupMembers(groupId, page, size, force);
      if (sequence !== memberSeq.current) return;
      setMembers(response.data || []);
      setMemberTotal(Number(response.total || 0));
      setMemberPage(page);
      setMemberSize(size);
    } catch (cause: any) {
      if (sequence === memberSeq.current) setMemberError(cause?.message || '分组成员加载失败');
    } finally {
      if (sequence === memberSeq.current) setMemberLoading(false);
    }
  }, []);

  const searchCandidates = useCallback(async (type: 'SKILL' | 'AGENT', query: string) => {
    const sequence = ++candidateSeq.current;
    setCandidateLoading(true);
    try {
      const response = type === 'SKILL'
        ? await listSkills({ pageNum: 1, pageSize: 100, keyword: query || undefined })
        : await listRuntimeAgents({ pageNum: 1, pageSize: 100, keyword: query || undefined });
      if (sequence !== candidateSeq.current) return;
      setCandidateOptions(type === 'SKILL'
        ? (response.data as AdminSkillSummary[]).filter((item) => item.delFlag !== '1')
          .map((item) => ({ value: item.id, label: `${item.name} · ${item.skillCode}` }))
        : (response.data as RuntimeAgentSummary[]).filter((item) => item.delFlag !== '1')
          .map((item) => ({ value: item.id, label: `${item.name} · ${item.agentCode}` })));
    } catch (cause: any) {
      if (sequence === candidateSeq.current) {
        setCandidateOptions([]);
        message.error(cause?.message || '候选成员加载失败');
      }
    } finally {
      if (sequence === candidateSeq.current) setCandidateLoading(false);
    }
  }, []);

  useEffect(() => { if (active) void searchCandidates(memberType, ''); }, [active, memberType, searchCandidates]);
  useEffect(() => () => { if (candidateTimer.current) clearTimeout(candidateTimer.current); }, []);

  const parentOptions = useMemo(() => {
    const descendants = new Set<string>();
    if (editing) {
      descendants.add(String(editing.id));
      let previous = 0;
      while (previous !== descendants.size) {
        previous = descendants.size;
        for (const row of groups) if (row.parentId && descendants.has(String(row.parentId)))
          descendants.add(String(row.id));
      }
    }
    return groups.filter((row) => !descendants.has(String(row.id)))
      .map((row) => ({ value: row.id, label: `${row.name} · ${row.groupCode}` }));
  }, [editing, groups]);

  const openCreate = () => {
    setEditing(null);
    form.setFieldsValue({ groupCode: '', name: '', description: '', iconUrl: '',
      parentId: undefined, sortOrder: groups.length * 10, visibility: 'PRIVATE', status: '1' });
    setFormOpen(true);
  };
  const openEdit = (row: BusinessGroup) => {
    setEditing(row);
    form.setFieldsValue(row);
    setFormOpen(true);
  };
  const save = async () => {
    if (actionRef.current) return;
    actionRef.current = true;
    setSaving(true);
    try {
      const values = await form.validateFields();
      await saveBusinessGroup({ ...values, id: editing?.id });
      message.success(editing ? '业务分组已更新' : '业务分组已创建');
      setFormOpen(false);
      await load(true);
    } catch (cause: any) {
      if (cause?.errorFields) return;
      if (!showReferenceConflict(cause, '分组仍有有效引用')) message.error(cause?.message || '保存失败');
    } finally { actionRef.current = false; setSaving(false); }
  };
  const remove = async (row: BusinessGroup) => {
    if (actionRef.current) return;
    actionRef.current = true;
    try {
      await removeBusinessGroup(row.id);
      message.success('业务分组已删除');
      await load(true);
    } catch (cause: any) {
      if (!showReferenceConflict(cause, '分组仍有子分组或成员')) message.error(cause?.message || '删除失败');
    } finally { actionRef.current = false; }
  };
  const openMembers = (row: BusinessGroup) => {
    setActive(row);
    setMemberId(undefined);
    void loadMembers(row.id);
  };
  const saveMember = async () => {
    if (!active || !memberId || actionRef.current) return;
    actionRef.current = true;
    setMemberSaving(true);
    try {
      await saveBusinessGroupMember({ groupId: active.id, memberType, memberId, sortOrder: memberSort });
      message.success('成员已加入分组');
      setMemberId(undefined);
      await loadMembers(active.id, memberPage, memberSize, true);
    } catch (cause: any) { message.error(cause?.message || '成员保存失败'); }
    finally { actionRef.current = false; setMemberSaving(false); }
  };
  const changeMemberSort = async (row: BusinessGroupMember, sortOrder: number) => {
    if (!active || actionRef.current) return;
    actionRef.current = true;
    try {
      await saveBusinessGroupMember({ groupId: active.id, memberType: row.memberType,
        memberId: row.memberId, sortOrder });
      await loadMembers(active.id, memberPage, memberSize, true);
    } catch (cause: any) { message.error(cause?.message || '排序保存失败'); }
    finally { actionRef.current = false; }
  };
  const removeMember = async (row: BusinessGroupMember) => {
    if (!active || actionRef.current) return;
    actionRef.current = true;
    try {
      await removeBusinessGroupMember({ groupId: active.id, memberType: row.memberType,
        memberId: row.memberId });
      message.success('成员已移出分组');
      await loadMembers(active.id, memberPage, memberSize, true);
    } catch (cause: any) { message.error(cause?.message || '移出失败'); }
    finally { actionRef.current = false; }
  };

  const filtered = useMemo(() => {
    const q = keyword.trim().toLowerCase();
    if (!q) return treeOf(groups);
    const matched = new Set(groups.filter((row) => `${row.name} ${row.groupCode}`.toLowerCase().includes(q))
      .map((row) => String(row.id)));
    for (const id of [...matched]) {
      let parentId = groups.find((row) => String(row.id) === id)?.parentId;
      while (parentId) {
        matched.add(String(parentId));
        parentId = groups.find((row) => String(row.id) === String(parentId))?.parentId;
      }
    }
    return treeOf(groups.filter((row) => matched.has(String(row.id))));
  }, [groups, keyword]);

  const columns: ColumnsType<GroupTree> = [
    { title: '业务分组', dataIndex: 'name', width: 300, render: (_, row) => <Space>
      <Avatar size="small" src={row.iconUrl} icon={<TeamOutlined />} />
      <Space direction="vertical" size={0}><Typography.Text strong>{row.name}</Typography.Text>
        <Typography.Text type="secondary" copyable>{row.groupCode}</Typography.Text></Space>
    </Space> },
    { title: '说明', dataIndex: 'description', ellipsis: true, render: (value) => value || '--' },
    { title: '排序', dataIndex: 'sortOrder', width: 85 },
    { title: '可见范围', dataIndex: 'visibility', width: 100,
      render: (value) => <Tag color={value === 'PUBLIC' ? 'blue' : undefined}>{value === 'PUBLIC' ? '公开' : '私有'}</Tag> },
    { title: '状态', dataIndex: 'status', width: 90,
      render: (value) => <Tag color={value === '0' ? 'success' : undefined}>{value === '0' ? '启用' : '停用'}</Tag> },
    { title: '操作', width: 250, fixed: 'right', render: (_, row) => <Space size={0}>
      <Button type="link" icon={<TeamOutlined />} onClick={() => openMembers(row)}>成员</Button>
      {canEdit && <><Button type="link" icon={<EditOutlined />} onClick={() => openEdit(row)}>编辑</Button>
        <Popconfirm title="删除此分组？" description="有子分组或成员时服务端会拒绝。"
          onConfirm={() => remove(row)}><Button type="link" danger icon={<DeleteOutlined />}>删除</Button></Popconfirm></>}
    </Space> }
  ];

  if (!canRead) return <Alert type="warning" showIcon message="没有业务分组查询权限" />;
  return <div>
    <PageHeader title="业务分组" desc="按层级组织 Skill 与 Agent；一个能力可以加入多个分组。" />
    <Card bordered={false}>
      <Space wrap style={{ marginBottom: 16 }}>
        <Input allowClear prefix={<SearchOutlined />} placeholder="搜索名称或编码" value={keyword}
          onChange={(event) => setKeyword(event.target.value)} style={{ width: 260 }} />
        <Button icon={<ReloadOutlined />} loading={loading} onClick={() => load(true)}>刷新</Button>
        {canEdit && <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新建分组</Button>}
      </Space>
      {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 16 }} />}
      <Table rowKey="id" dataSource={filtered} columns={columns} loading={loading} pagination={false}
        scroll={{ x: 1000 }} locale={{ emptyText: <Empty description="暂无分组，创建后可绑定 Skill 或 Agent" /> }} />
    </Card>

    <Modal open={formOpen} title={editing ? '编辑业务分组' : '新建业务分组'} width={680}
      onCancel={() => setFormOpen(false)} onOk={save} confirmLoading={saving} okText="保存" destroyOnClose>
      <Form form={form} layout="vertical">
        <Form.Item name="groupCode" label="稳定编码" rules={[{ required: true, message: '请输入编码' },
          { pattern: /^[a-z][a-z0-9._-]{1,63}$/, message: '以小写字母开头，2–64 位' }]}>
          <Input disabled={!!editing} maxLength={64} placeholder="例如 character-design-image" />
        </Form.Item>
        <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入名称' }]}>
          <Input maxLength={100} />
        </Form.Item>
        <Form.Item name="parentId" label="上级分组"><Select allowClear showSearch optionFilterProp="label"
          placeholder="无上级分组" options={parentOptions} /></Form.Item>
        <Form.Item name="description" label="说明"><Input.TextArea maxLength={1000} showCount rows={3} /></Form.Item>
        <Form.Item name="iconUrl" label="图标 URL"><Input maxLength={500} /></Form.Item>
        <Space wrap align="start">
          <Form.Item name="sortOrder" label="排序" rules={[{ required: true }]}>
            <InputNumber min={0} max={1000000} precision={0} /></Form.Item>
          <Form.Item name="visibility" label="可见范围" rules={[{ required: true }]}>
            <Select style={{ width: 140 }} options={[{ value: 'PRIVATE', label: '私有' },
              { value: 'PUBLIC', label: '公开' }]} /></Form.Item>
          <Form.Item name="status" label="启用状态" rules={[{ required: true }]}>
            <Select style={{ width: 140 }} options={[{ value: '0', label: '启用' },
              { value: '1', label: '停用' }]} /></Form.Item>
        </Space>
      </Form>
    </Modal>

    <Drawer open={!!active} title={active ? `${active.name} · 成员` : '分组成员'}
      width="min(960px, 100vw)" onClose={() => setActive(undefined)}
      extra={<Button icon={<ReloadOutlined />} loading={memberLoading}
        onClick={() => active && loadMembers(active.id, memberPage, memberSize, true)}>刷新</Button>}>
      {canEdit && <Card size="small" title="加入 Skill 或 Agent" style={{ marginBottom: 16 }}>
        <Space wrap align="start">
          <Select value={memberType} style={{ width: 110 }} options={[{ value: 'SKILL', label: 'Skill' },
            { value: 'AGENT', label: 'Agent' }]} onChange={(next) => {
              setMemberType(next); setMemberId(undefined); setCandidateOptions([]);
            }} />
          <Select showSearch filterOption={false} loading={candidateLoading} value={memberId}
            aria-label="选择能力" placeholder="输入名称或编码搜索" style={{ width: 300, maxWidth: '100%' }}
            options={candidateOptions} onChange={setMemberId} onSearch={(value) => {
              if (candidateTimer.current) clearTimeout(candidateTimer.current);
              candidateTimer.current = setTimeout(() => void searchCandidates(memberType, value.trim()), 250);
            }} />
          <InputNumber aria-label="成员排序" min={0} max={1000000} precision={0}
            value={memberSort} onChange={(value) => setMemberSort(value ?? 0)} />
          <Button type="primary" disabled={!memberId} loading={memberSaving}
            onClick={saveMember}>加入</Button>
        </Space>
      </Card>}
      {memberError && <Alert type="error" showIcon message={memberError} style={{ marginBottom: 16 }} />}
      <Table rowKey="id" loading={memberLoading} dataSource={members} scroll={{ x: 800 }}
        locale={{ emptyText: <Empty description="此分组尚无成员" /> }}
        pagination={{ current: memberPage, pageSize: memberSize, total: memberTotal,
          showSizeChanger: true, onChange: (page, size) => active && loadMembers(active.id, page, size) }}
        columns={[
          { title: '类型', dataIndex: 'memberType', width: 90, render: (value) => <Tag>{value}</Tag> },
          { title: '成员', render: (_: unknown, row: BusinessGroupMember) => <Space direction="vertical" size={0}>
            <Typography.Text strong>{row.memberName || `${row.memberType} #${row.memberId}`}</Typography.Text>
            <Typography.Text type="secondary">{row.memberCode || `ID ${row.memberId}`}</Typography.Text>
          </Space> },
          { title: '当前版本', dataIndex: 'currentVersionCode', width: 120,
            render: (value?: string) => value || '--' },
          { title: '状态', dataIndex: 'memberStatus', width: 90,
            render: (value?: string) => value === '0' ? <Tag color="success">启用</Tag> : <Tag>停用</Tag> },
          { title: '排序', dataIndex: 'sortOrder', width: 115,
            render: (value: number, row: BusinessGroupMember) => canEdit
              ? <InputNumber key={`${row.id}-${value}`} aria-label={`${row.memberName || row.memberId}排序`} min={0} max={1000000}
                precision={0} defaultValue={value} onBlur={(event) => {
                  const next = Number(event.target.value);
                  if (Number.isInteger(next) && next >= 0 && next !== value) void changeMemberSort(row, next);
                }} /> : value },
          { title: '操作', width: 100, render: (_: unknown, row: BusinessGroupMember) => canEdit
            ? <Popconfirm title="将此成员移出分组？" onConfirm={() => removeMember(row)}>
              <Button type="link" danger>移出</Button></Popconfirm> : null }
        ]} />
    </Drawer>
  </div>;
}
